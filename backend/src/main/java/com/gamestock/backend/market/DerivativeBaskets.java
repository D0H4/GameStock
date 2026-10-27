package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

/** Persisted basket units. Reconstitution exchanges equal values, never resets a holder's NAV. */
final class DerivativeBaskets {
    record Definition(String code, String name, String theme, String description, Set<String> genres, boolean indexWeighted) { }
    static final List<Definition> DEFINITIONS = List.of(
            new Definition("ETFIDX", "슈엔 게임주 지수 ETF", "지수",
                    "상장된 모든 게임 종목을 시가총액 비중으로 담는 지수 추종 ETF입니다.", Set.of(), true),
            new Definition("ETFRPG", "RPG 셀렉트 ETF", "RPG",
                    "RPG·전략 RPG 장르를 같은 비중으로 담는 테마 ETF입니다.", Set.of("RPG", "전략 RPG"), false),
            new Definition("ETFACT", "액션 셀렉트 ETF", "액션",
                    "액션·오픈월드 장르를 같은 비중으로 담는 테마 ETF입니다.", Set.of("액션", "오픈월드"), false),
            new Definition("ETFFPS", "FPS 셀렉트 ETF", "FPS",
                    "FPS 장르의 슈팅 게임을 담는 테마 ETF입니다.", Set.of("FPS"), false),
            new Definition("ETFALT", "얼터너티브 셀렉트 ETF", "얼터너티브",
                    "육성·레이싱·생존 장르를 같은 비중으로 담는 테마 ETF입니다.", Set.of("육성", "레이싱", "생존"), false));

    record Member(long stock, String code, String name, double price, long reference, long rawPrice,
                  Instant observedAt, double units, double weight, long base) {
        double changePercent() { return reference <= 0 ? 0 : (price - reference) * 100.0 / reference; }
        Member execution(boolean higher) {
            return new Member(stock, code, name, higher ? Math.max(price, rawPrice) : Math.min(price, rawPrice),
                    reference, rawPrice, observedAt, units, weight, base);
        }
    }
    record Basket(double cash, double previousAdjustment, boolean initialized, List<Member> members) {
        double price() { return cash + members.stream().mapToDouble(m -> m.units() * m.price()).sum(); }
        double previous() { return cash + previousAdjustment + members.stream().mapToDouble(m -> m.units() * m.reference()).sum(); }
        boolean fresh(Instant now) {
            return initialized && members.stream().allMatch(m -> m.price() > 0 && Double.isFinite(m.price())
                    && m.observedAt() != null && !m.observedAt().isAfter(now)
                    && !m.observedAt().isBefore(now.minusSeconds(DerivativePriceOracle.MAX_AGE_SECONDS)));
        }
        Basket execution(boolean higher) { return new Basket(cash, previousAdjustment, initialized, members.stream().map(m -> m.execution(higher)).toList()); }
    }
    record Values(Map<String, Basket> baskets, long version) {
        Basket basket(String code) { return Objects.requireNonNull(baskets.get(code), "Unknown basket: " + code); }
        double index() { return basket("ETFIDX").price(); }
        Values execution(boolean higher) {
            Map<String, Basket> result = new LinkedHashMap<>();
            baskets.forEach((code, basket) -> result.put(code, basket.execution(higher)));
            return new Values(Map.copyOf(result), version);
        }
    }
    private record Stock(long id, String code, String genre, double price, long reference, long base,
                         boolean listed, Instant observedAt) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    DerivativeBaskets(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }

    static void ensureTables(JdbcTemplate jdbc) {
        AdminSchema.ensureStockState(jdbc);
        jdbc.execute("CREATE TABLE IF NOT EXISTS derivative_basket_lock(id TINYINT PRIMARY KEY,valuation_version BIGINT NOT NULL DEFAULT 0)");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='derivative_basket_lock' AND column_name='valuation_version'", Integer.class) == 0)
            jdbc.execute("ALTER TABLE derivative_basket_lock ADD COLUMN valuation_version BIGINT NOT NULL DEFAULT 0");
        jdbc.update("INSERT IGNORE INTO derivative_basket_lock(id) VALUES(1)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS derivative_baskets (
                  symbol VARCHAR(12) PRIMARY KEY, cash_value DOUBLE NOT NULL DEFAULT 1000,
                  initialized BOOLEAN NOT NULL DEFAULT FALSE, reference_day DATE NULL,
                  previous_adjustment DOUBLE NOT NULL DEFAULT 0)
                """);
        for (String[] column : List.of(new String[]{"reference_day", "DATE NULL"},
                new String[]{"previous_adjustment", "DOUBLE NOT NULL DEFAULT 0"})) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='derivative_baskets' AND column_name=?", Integer.class, column[0]) == 0)
                jdbc.execute("ALTER TABLE derivative_baskets ADD COLUMN " + column[0] + " " + column[1]);
        }
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS derivative_basket_members (
                  symbol VARCHAR(12) NOT NULL, stock_id BIGINT NOT NULL, stock_code VARCHAR(12) NOT NULL,
                  units DOUBLE NOT NULL, weight DOUBLE NOT NULL, base_price BIGINT NOT NULL, last_price DOUBLE NOT NULL,
                  PRIMARY KEY(symbol,stock_id))
                """);
        for (Definition definition : DEFINITIONS)
            jdbc.update("INSERT IGNORE INTO derivative_baskets(symbol) VALUES(?)", definition.code());
    }

    /** One statement gives readers an atomic view of composition, units and prices. */
    Values load() { return load(false); }

    /** Use after writes/CAS checks; an older repeatable-read snapshot may predate a cancellation. */
    Values loadCurrent() { return load(true); }

    /** Called once per scheduled price snapshot while holding lock(), in the same transaction. */
    void advanceValuationVersion() {
        jdbc.update("UPDATE derivative_basket_lock SET valuation_version=valuation_version+1 WHERE id=1");
    }

    private Values load(boolean current) {
        Map<String, List<Member>> members = new LinkedHashMap<>();
        Map<String, Basket> baskets = new LinkedHashMap<>();
        long[] version = {0};
        jdbc.query("""
                SELECT b.symbol,b.cash_value,b.initialized,m.stock_id,m.units,m.weight,m.base_price,v.valuation_version,
                       CASE WHEN b.reference_day=COALESCE(calendar.trading_date,CURRENT_DATE)
                         THEN b.previous_adjustment ELSE 0 END AS previous_adjustment,
                       COALESCE(s.stock_code,m.stock_code) AS code,COALESCE(g.name,m.stock_code) AS name,
                       COALESCE(r.price,m.last_price) AS price,r.observed_at,
                       COALESCE(p.day_reference,s.current_price,m.last_price) AS reference,
                       COALESCE(s.current_price,m.last_price) AS raw_price
                FROM derivative_baskets b JOIN derivative_basket_lock v ON v.id=1
                LEFT JOIN derivative_basket_members m ON m.symbol=b.symbol
                LEFT JOIN stocks s ON s.id=m.stock_id LEFT JOIN games g ON g.id=s.game_id
                LEFT JOIN derivative_reference_prices r ON r.stock_id=m.stock_id
                LEFT JOIN stock_protection_state p ON p.stock_id=m.stock_id
                LEFT JOIN market_protection_state calendar ON calendar.id=1
                ORDER BY b.symbol,m.stock_id
                """ + (current ? " FOR SHARE" : ""), rs -> {
            String code = rs.getString("symbol");
            version[0] = rs.getLong("valuation_version");
            List<Member> list = members.computeIfAbsent(code, ignored -> new ArrayList<>());
            baskets.putIfAbsent(code, new Basket(rs.getDouble("cash_value"), rs.getDouble("previous_adjustment"), rs.getBoolean("initialized"), list));
            if (rs.getObject("stock_id") != null) list.add(new Member(rs.getLong("stock_id"), rs.getString("code"),
                    rs.getString("name"), rs.getDouble("price"), rs.getLong("reference"), rs.getLong("raw_price"),
                    rs.getTimestamp("observed_at") == null ? null : rs.getTimestamp("observed_at").toInstant(),
                    rs.getDouble("units"), rs.getDouble("weight"), rs.getLong("base_price")));
        });
        baskets.replaceAll((code, basket) -> new Basket(basket.cash(), basket.previousAdjustment(), basket.initialized(), List.copyOf(basket.members())));
        return new Values(Map.copyOf(baskets), version[0]);
    }

    /** Joins a catalog mutation's transaction, or owns a short transaction during startup/refresh. */
    void rebalance() { rebalance(Instant.now()); }

    void rebalance(Instant now) {
        transactions.executeWithoutResult(status -> {
            lock();
            var dates = jdbc.queryForList("SELECT trading_date FROM market_protection_state WHERE id=1 FOR SHARE", java.sql.Date.class);
            java.sql.Date day = dates.isEmpty() ? jdbc.queryForObject("SELECT CURRENT_DATE", java.sql.Date.class) : dates.get(0);
            new DerivativeIndexHistory(jdbc).initializeBases();
            new DerivativePriceOracle(jdbc).initialize();
            List<Stock> stocks = jdbc.query("""
                    SELECT s.id,s.stock_code,g.genre,r.price,b.reference_price,COALESCE(p.day_reference,s.current_price) AS day_reference,
                           COALESCE(a.state,'ACTIVE')<>'DELISTED' AS listed,r.observed_at
                    FROM stocks s JOIN games g ON g.id=s.game_id
                    JOIN derivative_index_bases b ON b.stock_id=s.id
                    JOIN derivative_reference_prices r ON r.stock_id=s.id
                    LEFT JOIN stock_protection_state p ON p.stock_id=s.id
                    LEFT JOIN admin_stock_state a ON a.stock_id=s.id ORDER BY s.id FOR SHARE
                    """, (rs,n) -> new Stock(rs.getLong("id"), rs.getString("stock_code"), rs.getString("genre"),
                    rs.getDouble("price"), rs.getLong("day_reference"), rs.getLong("reference_price"), rs.getBoolean("listed"),
                    rs.getTimestamp("observed_at") == null ? null : rs.getTimestamp("observed_at").toInstant()));
            // Current reads are essential when an admin transaction already owns an older
            // repeatable-read snapshot: both sides of the exchange must use the same prices.
            Values before = load(true);
            Set<Long> admitted = new HashSet<>();
            before.baskets().values().forEach(b -> b.members().forEach(m -> admitted.add(m.stock())));
            for (Definition definition : DEFINITIONS) {
                Basket old = before.basket(definition.code());
                // An added listing warms up on its own tape before joining a running
                // basket. Existing members stay admitted when stale or invalidated:
                // freshness must still block trading rather than silently drop risk.
                List<Stock> next = stocks.stream().filter(s -> s.listed()
                        && (!old.initialized() || admitted.contains(s.id())
                            || s.observedAt() != null && !s.observedAt().isAfter(now)
                                && !s.observedAt().isBefore(now.minusSeconds(DerivativePriceOracle.MAX_AGE_SECONDS)))
                        && (definition.genres().isEmpty() || definition.genres().contains(s.genre()))).toList();
                double weightSum = next.stream().mapToDouble(s -> weight(definition, s)).sum();
                boolean unchanged = old.initialized() && old.members().size() == next.size();
                if (unchanged) for (int i=0; i<next.size(); i++) {
                    Stock stock = next.get(i); Member member = old.members().get(i);
                    if (member.stock() != stock.id() || member.base() != stock.base()
                            || Math.abs(member.weight() - weight(definition, stock) / weightSum) > 1e-12) {
                        unchanged = false; break;
                    }
                }
                if (unchanged) continue;
                if (!old.initialized() && next.isEmpty()) {
                    // Legacy empty sector ETFs are cash at 1000. Their first constituent
                    // must not change existing holdings' value either.
                    if (!stocks.isEmpty()) jdbc.update("UPDATE derivative_baskets SET initialized=TRUE WHERE symbol=?", definition.code());
                    continue;
                }
                double raw = next.stream().mapToDouble(s -> weight(definition, s) / weightSum * s.price() / s.base()).sum();
                double value = old.initialized() ? old.price() : 1000 * raw;
                if (!Double.isFinite(value) || value <= 0 || (!next.isEmpty() && (!Double.isFinite(raw) || raw <= 0)))
                    throw new IllegalStateException("Invalid derivative basket prices: " + definition.code());
                jdbc.update("DELETE FROM derivative_basket_members WHERE symbol=?", definition.code());
                if (!next.isEmpty()) jdbc.batchUpdate("""
                        INSERT INTO derivative_basket_members(symbol,stock_id,stock_code,units,weight,base_price,last_price)
                        VALUES(?,?,?,?,?,?,?)
                        """, next.stream().map(s -> new Object[]{definition.code(), s.id(), s.code(),
                        value / raw * weight(definition, s) / weightSum / s.base(),
                        weight(definition, s) / weightSum, s.base(), s.price()}).toList());
                double previous = next.isEmpty() ? value : next.stream().mapToDouble(s ->
                        value / raw * weight(definition, s) / weightSum / s.base() * s.reference()).sum();
                jdbc.update("UPDATE derivative_baskets SET cash_value=?,initialized=TRUE,reference_day=?,previous_adjustment=? WHERE symbol=?",
                        next.isEmpty() ? value : 0, day, old.initialized() ? old.previous() - previous : 0, definition.code());
            }
        });
    }

    void lock() { jdbc.queryForObject("SELECT id FROM derivative_basket_lock WHERE id=1 FOR UPDATE", Integer.class); }

    private static double weight(Definition definition, Stock stock) {
        return definition.indexWeighted() ? StockMarketProfile.of(stock.code()).marketCap() : 1;
    }
}
