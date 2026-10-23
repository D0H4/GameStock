package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.OptionalDouble;

/** Persistent index bases and immutable expiry fixings; daily protection references are separate. */
final class DerivativeIndexHistory {
    private final JdbcTemplate jdbc;
    DerivativeIndexHistory(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static void ensureTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS derivative_index_bases (
                  stock_id BIGINT PRIMARY KEY, reference_price BIGINT NOT NULL,
                  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  CONSTRAINT fk_derivative_base_stock FOREIGN KEY(stock_id) REFERENCES stocks(id))
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS derivative_index_observations (
                  observed_at TIMESTAMP(3) PRIMARY KEY, index_value DOUBLE NOT NULL,
                  invalidated BOOLEAN NOT NULL DEFAULT FALSE)
                """);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='derivative_index_observations' AND column_name='invalidated'", Integer.class) == 0)
            jdbc.execute("ALTER TABLE derivative_index_observations ADD COLUMN invalidated BOOLEAN NOT NULL DEFAULT FALSE");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS derivative_expiry_fixings (
                  expiry_at TIMESTAMP(3) PRIMARY KEY, index_value DOUBLE NOT NULL,
                  observed_at TIMESTAMP(3) NOT NULL)
                """);
    }

    void initializeBases() {
        // Preserve the quote on upgrade; freeze this reference once, never at each midnight.
        jdbc.update("""
                INSERT IGNORE INTO derivative_index_bases(stock_id,reference_price)
                SELECT s.id,GREATEST(1,COALESCE(p.day_reference,s.current_price)) FROM stocks s
                LEFT JOIN stock_protection_state p ON p.stock_id=s.id
                WHERE NOT EXISTS (SELECT 1 FROM derivative_index_bases b WHERE b.stock_id=s.id)
                """);
    }

    void observe(double index, Instant at) {
        // Same timestamp is immutable across readers/instances. No public GET writes samples.
        jdbc.update("INSERT IGNORE INTO derivative_index_observations(observed_at,index_value) VALUES(?,?)", Timestamp.from(at), index);
    }

    /** Invalidate potential descendants of a cancelled execution, including held last-good quotes. */
    void invalidate(Instant executionAt) {
        // A reference uses the preceding 60 seconds with a five-second delay and may then
        // be held for 90 seconds. This conservative interval also covers legacy samples
        // without per-constituent provenance. Already fixed expiries remain immutable.
        Instant second = Instant.ofEpochSecond(executionAt.getEpochSecond());
        jdbc.update("""
                UPDATE derivative_index_observations SET invalidated=TRUE
                WHERE observed_at>=? AND observed_at<? AND invalidated=FALSE
                """, Timestamp.from(second), Timestamp.from(second.plusSeconds(
                DerivativePriceOracle.WINDOW_SECONDS + DerivativePriceOracle.DELAY_SECONDS
                        + DerivativePriceOracle.MAX_AGE_SECONDS + 1)));
    }

    double volatility(Instant now) {
        // One actual observation per three-second bucket; requests cannot increase its sample weight.
        var samples = jdbc.query("""
                SELECT UNIX_TIMESTAMP(o.observed_at) AS second,o.index_value
                FROM derivative_index_observations o JOIN (
                  SELECT MAX(observed_at) AS at FROM derivative_index_observations
                  WHERE observed_at>=? AND observed_at<=? AND invalidated=FALSE
                  GROUP BY FLOOR(UNIX_TIMESTAMP(observed_at)/3)
                  ORDER BY at DESC LIMIT 900
                ) buckets ON buckets.at=o.observed_at ORDER BY o.observed_at
                """, (rs, row) -> new double[]{rs.getDouble("second"),rs.getDouble("index_value")},
                Timestamp.from(now.minusSeconds(2700)),Timestamp.from(now));
        return DerivativePricing.realizedVolatility(samples);
    }

    /** Account reads never select or persist a new settlement price. */
    OptionalDouble existingFixing(Instant expiry) {
        var fixed = jdbc.queryForList("SELECT index_value FROM derivative_expiry_fixings WHERE expiry_at=?",
                Double.class, Timestamp.from(expiry));
        return fixed.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(fixed.get(0));
    }

    double fixing(Instant expiry) {
        Timestamp at = Timestamp.from(expiry);
        var existing = existingFixing(expiry);
        if (existing.isPresent()) return existing.getAsDouble();
        jdbc.update("""
                INSERT IGNORE INTO derivative_expiry_fixings(expiry_at,index_value,observed_at)
                SELECT ?,index_value,observed_at FROM derivative_index_observations
                WHERE observed_at<=? AND observed_at>=? AND invalidated=FALSE ORDER BY observed_at DESC LIMIT 1 FOR SHARE
                """, at, at, Timestamp.from(expiry.minusSeconds(DerivativePriceOracle.MAX_AGE_SECONDS)));
        var fixed = jdbc.queryForList("SELECT index_value FROM derivative_expiry_fixings WHERE expiry_at=? FOR SHARE", Double.class, at);
        if (fixed.isEmpty()) throw new IllegalStateException("만기 이전 지수 기록이 없어 정산을 보류합니다: " + expiry);
        return fixed.get(0);
    }

    void retain(Instant now) {
        Instant cutoff=now.minusSeconds(30L*86400);
        var pending=jdbc.queryForList("SELECT MIN(expiry_at) FROM derivative_positions WHERE status='OPEN' AND expiry_at IS NOT NULL",Timestamp.class);
        if(!pending.isEmpty() && pending.get(0)!=null && pending.get(0).toInstant().isBefore(cutoff))cutoff=pending.get(0).toInstant();
        Timestamp keep=jdbc.queryForObject("SELECT MAX(observed_at) FROM derivative_index_observations WHERE observed_at<=? AND invalidated=FALSE",Timestamp.class,Timestamp.from(cutoff));
        jdbc.update("DELETE FROM derivative_index_observations WHERE observed_at<? ORDER BY observed_at LIMIT 5000",keep==null?Timestamp.from(cutoff):keep);
        jdbc.update("""
                DELETE FROM derivative_expiry_fixings WHERE expiry_at<?
                  AND NOT EXISTS (SELECT 1 FROM derivative_positions p WHERE p.status='OPEN' AND p.expiry_at=derivative_expiry_fixings.expiry_at)
                ORDER BY expiry_at LIMIT 5000
                """,Timestamp.from(now.minusSeconds(90L*86400)));
    }
}
