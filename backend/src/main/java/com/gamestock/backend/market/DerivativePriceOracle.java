package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Executed-price reference shared by every derivative instance, never the last print. */
final class DerivativePriceOracle {
    static final long MAX_AGE_SECONDS = 90;
    static final long WINDOW_SECONDS = 60;
    static final long MAX_LOOKBACK_SECONDS = 600;
    static final long DELAY_SECONDS = 5;
    private static final long MICRO_LOT_SPAN_SECONDS = 180;
    // Leave one second for the sub-second part of the refresh clock. The actual
    // execution timestamp must still pass the unchanged 90-second basket guard.
    private static final long SPARSE_LAST_AGE_SECONDS = MAX_AGE_SECONDS - DELAY_SECONDS - 1;
    record Bucket(long second, long quantity, double notional) { }
    private record Reference(double price, long observedSecond) { }
    private final JdbcTemplate jdbc;
    DerivativePriceOracle(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static void ensureTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS derivative_reference_prices (
                  stock_id BIGINT PRIMARY KEY, price DOUBLE NOT NULL, observed_at TIMESTAMP(3) NULL,
                  computed_at TIMESTAMP(3) NULL, revision BIGINT NOT NULL DEFAULT 0,
                  CONSTRAINT fk_derivative_reference_stock FOREIGN KEY(stock_id) REFERENCES stocks(id))
                """);
        for (String[] column : List.of(new String[]{"computed_at", "TIMESTAMP(3) NULL"},
                new String[]{"revision", "BIGINT NOT NULL DEFAULT 0"})) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='derivative_reference_prices' AND column_name=?", Integer.class, column[0]) == 0)
                jdbc.execute("ALTER TABLE derivative_reference_prices ADD COLUMN " + column[0] + " " + column[1]);
        }
    }

    void initialize() {
        // A bootstrap quote is display-only until enough executed history has been observed.
        List<Object[]> missing = jdbc.query("""
                SELECT b.stock_id,b.reference_price FROM derivative_index_bases b
                LEFT JOIN derivative_reference_prices r ON r.stock_id=b.stock_id WHERE r.stock_id IS NULL
                """, (rs,n) -> new Object[]{rs.getLong(1), rs.getLong(2)});
        if (!missing.isEmpty()) jdbc.batchUpdate("INSERT IGNORE INTO derivative_reference_prices(stock_id,price) VALUES(?,?)", missing);
    }

    void refresh(Instant now) {
        initialize();
        long end = now.getEpochSecond() - DELAY_SECONDS;
        List<Long> stocks = jdbc.queryForList("SELECT id FROM stocks ORDER BY id", Long.class);
        for (long stock : stocks) {
            long revision = jdbc.queryForObject("SELECT revision FROM derivative_reference_prices WHERE stock_id=?", Long.class, stock);
            Optional<Reference> reference = calculate(executions(stock, end, WINDOW_SECONDS), end,
                    WINDOW_SECONDS, 10, false);
            if (reference.isEmpty()) {
                // Small allocation lots need not produce 100 shares in every minute.
                // A longer, independently sampled tape also supports single-share lots;
                // never replace the actual last execution time with the refresh time.
                reference = calculate(executions(stock, end, MAX_LOOKBACK_SECONDS), end,
                        MAX_LOOKBACK_SECONDS, SPARSE_LAST_AGE_SECONDS, true);
            }
            if (reference.isEmpty()) continue;
            Reference observed = reference.get();
            // Sample time is not a computation version: cancellations and window expiry
            // can change the value without a new last trade. CAS also rejects a worker
            // that read the tape before a committed cancellation or another refresh.
            jdbc.update("""
                    UPDATE derivative_reference_prices SET price=?,observed_at=?,computed_at=?,revision=revision+1
                    WHERE stock_id=? AND revision=? AND (computed_at IS NULL OR computed_at<=?)
                    """, observed.price(), Timestamp.from(Instant.ofEpochSecond(observed.observedSecond())),
                    Timestamp.from(now), stock, revision, Timestamp.from(now));
        }
    }

    private List<Bucket> executions(long stock, long end, long window) {
        return jdbc.query("""
                    SELECT second,SUM(quantity) AS quantity,SUM(notional) AS notional FROM (
                      SELECT FLOOR(UNIX_TIMESTAMP(t.created_at)) AS second,t.quantity,t.price*t.quantity AS notional
                      FROM trades t WHERE t.stock_id=? AND t.created_at>=? AND t.created_at<?
                        AND NOT EXISTS(SELECT 1 FROM settlements s WHERE s.trade_id=t.id AND s.status='CANCELLED')
                      UNION ALL
                      SELECT bucket_at AS second,quantity,notional FROM bot_trade_seconds
                      WHERE stock_id=? AND bucket_at>=? AND bucket_at<?
                    ) executions GROUP BY second ORDER BY second
                    """, (rs, n) -> new Bucket(rs.getLong("second"), rs.getLong("quantity"), rs.getDouble("notional")),
                    stock, Timestamp.from(Instant.ofEpochSecond(end - window)), Timestamp.from(Instant.ofEpochSecond(end)),
                    stock, end - window, end);
    }

    /** Called in the settlement reversal transaction; insufficient remaining tape stays untradeable. */
    void invalidate(long stock, Instant executionAt) {
        jdbc.update("UPDATE derivative_reference_prices SET observed_at=NULL,revision=revision+1 WHERE stock_id=?", stock);
        new DerivativeIndexHistory(jdbc).invalidate(executionAt);
    }

    static OptionalDouble reference(List<Bucket> buckets, long end) {
        return price(calculate(buckets, end, WINDOW_SECONDS, 10, false));
    }

    static OptionalDouble sparseReference(List<Bucket> buckets, long end) {
        return price(calculate(buckets, end, MAX_LOOKBACK_SECONDS, SPARSE_LAST_AGE_SECONDS, true));
    }

    private static OptionalDouble price(Optional<Reference> reference) {
        return reference.isPresent() ? OptionalDouble.of(reference.get().price()) : OptionalDouble.empty();
    }

    private static Optional<Reference> calculate(List<Bucket> buckets, long end, long window,
                                                long maxLastAge, boolean shortestWindow) {
        var valid = buckets.stream().filter(b -> b.second() >= end - window && b.second() < end
                && b.quantity() > 0 && Double.isFinite(b.notional()) && b.notional() > 0)
                .sorted(Comparator.comparingLong(Bucket::second)).toList();
        if (valid.size() < 20) return Optional.empty();
        long latest = valid.get(valid.size() - 1).second();
        if (end - latest > maxLastAge) return Optional.empty();
        int first = valid.size();
        long quantity = 0;
        while (first > 0) {
            quantity += valid.get(--first).quantity();
            if (shortestWindow && sufficientEvidence(valid.size() - first,
                    latest - valid.get(first).second(), quantity, true)) break;
        }
        if (!sufficientEvidence(valid.size() - first, latest - valid.get(first).second(),
                quantity, shortestWindow)) return Optional.empty();
        // Each occupied second has one vote. A large or tiny terminal print cannot dominate the window.
        double[] prices = valid.subList(first, valid.size()).stream()
                .mapToDouble(b -> b.notional() / b.quantity()).sorted().toArray();
        return Optional.of(new Reference((prices[(prices.length - 1) / 2] + prices[prices.length / 2]) / 2, latest));
    }

    private static boolean sufficientEvidence(int samples, long span, long quantity, boolean sparse) {
        // Allocation adjustments can be one share. A three-minute tape of at least
        // twenty occupied seconds is persistent price discovery even below 100 shares.
        // The median still gives an isolated large or small print only one vote.
        return samples >= 20 && span >= 45
                && (quantity >= 100 || sparse && span >= MICRO_LOT_SPAN_SECONDS && quantity >= 20);
    }
}
