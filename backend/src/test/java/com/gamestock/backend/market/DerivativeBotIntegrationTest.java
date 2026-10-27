package com.gamestock.backend.market;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static com.gamestock.backend.market.DerivativeModels.OrderRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real cash, ledger, risk checks and competing transactions, in a disposable MySQL schema. */
@EnabledIfSystemProperty(named = "market.test.jdbc", matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DerivativeBotIntegrationTest {
    private static final Instant NOW = Instant.parse("2030-01-01T10:01:00Z");
    private JdbcTemplate root, jdbc;
    private String database;
    private DataSourceTransactionManager manager;
    private DerivativeService target, derivatives;
    private DerivativeMarketImpact impact;
    private DerivativeBotService bots;
    private MarketService market;

    @BeforeAll void createDatabase() throws Exception {
        String url = System.getProperty("market.test.jdbc");
        root = new JdbcTemplate(new DriverManagerDataSource(url, "root", ""));
        database = "derivative_bots_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        int slash = url.indexOf('/', "jdbc:mysql://".length()), query = url.indexOf('?');
        var source = new DriverManagerDataSource(url.substring(0, slash + 1) + database
                + (query < 0 ? "" : url.substring(query)), "root", "");
        jdbc = new JdbcTemplate(source);
        String schema = Files.readString(Path.of("../database/schema.sql"))
                .replaceAll("(?m)^--.*$", "").replaceAll("(?is)CREATE DATABASE[^;]+;", "")
                .replaceAll("(?is)USE gamestock;", "");
        for (String statement : schema.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        manager = new DataSourceTransactionManager(source);
        DerivativeService.ensureTables(jdbc);
        DerivativeBotService.ensureTables(jdbc);
        BotLedgerJournal.ensureTables(jdbc);
        jdbc.update("INSERT INTO games(name,developer,genre) VALUES ('game','test','RPG')");
        jdbc.update("INSERT INTO stocks(game_id,stock_code,current_price,previous_price,total_volume) VALUES (1,'GI',10000,10000,0)");
        jdbc.update("""
                INSERT INTO stock_protection_state(stock_id,day_reference,static_reference,dynamic_reference,last_trade_price)
                VALUES (1,10000,10000,10000,10000)
                """);
    }

    @BeforeEach void resetMarket() {
        jdbc.update("DELETE FROM derivative_trades");
        jdbc.update("DELETE FROM derivative_positions");
        jdbc.update("DELETE FROM derivative_bot_state");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM derivative_index_observations");
        jdbc.update("UPDATE stocks SET current_price=10000,previous_price=10000");
        jdbc.update("UPDATE derivative_reference_prices SET price=10000,observed_at=NULL,computed_at=NULL,revision=revision+1");
        jdbc.update("UPDATE admin_runtime SET bots_paused=FALSE,traders_paused=FALSE,market_paused=FALSE,trader_ramp_seconds=NULL");
        impact = new DerivativeMarketImpact();
        target = new DerivativeService(jdbc, manager);
        target.configureMarketImpact(impact);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        derivatives = (DerivativeService) proxy.getProxy();
        market = mock(MarketService.class);
        when(market.matchingEnabled()).thenReturn(true);
        when(market.ownerAvailable()).thenReturn(true);
        when(market.activeSimulation()).thenReturn(true);
        bots = new DerivativeBotService(jdbc, manager, derivatives, impact, market, new AdminRuntimeControl(jdbc));
        bots.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        bots.initializeBots();
        jdbc.update("UPDATE derivative_bot_state SET next_action_at=?", Timestamp.from(NOW));
        refreshAt(NOW);
    }

    @AfterAll void dropDatabase() { if (root != null && database != null) root.execute("DROP DATABASE " + database); }

    private void refreshAt(Instant now) {
        target.setClock(Clock.fixed(now, ZoneOffset.UTC));
        DerivativeTapeFixture.seed(jdbc, now);
        target.refreshMarks();
    }

    private long user(int ordinal) {
        return jdbc.queryForObject("SELECT user_id FROM derivative_bot_state WHERE ordinal=?", Long.class, ordinal);
    }

    @Test void populationFundsActualFuturesAndOptionsWithoutAnySpotOrdersOrHoldings() {
        assertNotNull(impact.current(NOW));
        for (long user : jdbc.queryForList("SELECT user_id FROM derivative_bot_state ORDER BY ordinal", Long.class))
            bots.act(user, impact.current(NOW));
        assertEquals(48, jdbc.queryForObject("SELECT COUNT(*) FROM derivative_positions", Integer.class));
        assertEquals(24, jdbc.queryForObject("SELECT COUNT(*) FROM derivative_positions WHERE kind='FUTURE'", Integer.class));
        assertEquals(24, jdbc.queryForObject("SELECT COUNT(*) FROM derivative_positions WHERE kind='OPTION'", Integer.class));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(DISTINCT side) FROM derivative_positions", Integer.class));
        assertEquals(48, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE cash<1000000 AND cash>=100000", Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM users u WHERE u.cash<>1000000+
                (SELECT COALESCE(SUM(cash_flow),0) FROM derivative_trades t WHERE t.user_id=u.id)
                """, Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM portfolios", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE password_hash='TRADER'", Integer.class));
        assertEquals(10000, jdbc.queryForObject("SELECT current_price FROM stocks WHERE id=1", Long.class));
    }

    @Test void restartDoesNotRefillCashOrResetSchedulesAndNeverAdoptsAHumanNameCollision() {
        long user = user(0);
        jdbc.update("UPDATE users SET cash=123 WHERE id=?", user);
        jdbc.update("UPDATE derivative_bot_state SET actions=17,next_action_at=? WHERE user_id=?", Timestamp.from(NOW.plusSeconds(500)), user);
        jdbc.update("INSERT INTO users(username,password_hash,nickname,cash) VALUES ('derivative_bot_049','HUMAN','human',456)");
        ReflectionTestUtils.setField(bots, "count", 49);
        bots.initializeBots();
        assertEquals(48, jdbc.queryForObject("SELECT COUNT(*) FROM derivative_bot_state", Integer.class));
        assertEquals(123, jdbc.queryForObject("SELECT cash FROM users WHERE id=?", Long.class, user));
        assertEquals(17, jdbc.queryForObject("SELECT actions FROM derivative_bot_state WHERE user_id=?", Long.class, user));
        assertEquals(Timestamp.from(NOW.plusSeconds(500)), jdbc.queryForObject("SELECT next_action_at FROM derivative_bot_state WHERE user_id=?", Timestamp.class, user));
        assertEquals(456, jdbc.queryForObject("SELECT cash FROM users WHERE username='derivative_bot_049'", Long.class));
    }

    @Test void pauseOwnershipAndFreshnessGatesStopActionsAndTickWorkIsBounded() {
        for (String field : List.of("bots_paused", "traders_paused", "market_paused")) {
            jdbc.update("UPDATE admin_runtime SET " + field + "=TRUE");
            bots.tick();
            bots.act(user(0), impact.current(NOW));
            assertEquals(0, jdbc.queryForObject("SELECT SUM(actions) FROM derivative_bot_state", Long.class));
            jdbc.update("UPDATE admin_runtime SET " + field + "=FALSE");
        }
        when(market.ownerAvailable()).thenReturn(false);
        bots.tick();
        assertEquals(0, jdbc.queryForObject("SELECT SUM(actions) FROM derivative_bot_state", Long.class));
        when(market.ownerAvailable()).thenReturn(true);
        bots.setClock(Clock.fixed(NOW.plusSeconds(12), ZoneOffset.UTC));
        bots.tick();
        bots.act(user(0), impact.current(NOW));
        assertEquals(0, jdbc.queryForObject("SELECT SUM(actions) FROM derivative_bot_state", Long.class));
        bots.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        bots.tick();
        assertEquals(4, jdbc.queryForObject("SELECT SUM(actions) FROM derivative_bot_state", Long.class));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM derivative_positions", Integer.class));
    }

    @Test void simultaneousWorkersCannotSpendOrOpenTheSameDecisionTwice() throws Exception {
        long user = user(0);
        var snapshot = impact.current(NOW);
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Callable<Void> action = () -> { assertTrue(start.await(5, TimeUnit.SECONDS)); bots.act(user, snapshot); return null; };
            Future<Void> a = executor.submit(action), b = executor.submit(action);
            start.countDown();
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
            assertEquals(1, jdbc.queryForObject("SELECT actions FROM derivative_bot_state WHERE user_id=?", Long.class, user));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM derivative_positions", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM derivative_trades", Integer.class));
        } finally { start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void staleReferenceRollsBackCashAndDecisionAndSchedulerBacksOff() {
        jdbc.update("DELETE FROM bot_trade_seconds");
        jdbc.update("UPDATE derivative_reference_prices SET observed_at=NULL");
        bots.tick(); // cached signal is fresh but the authoritative execution price is not
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM derivative_positions", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT SUM(actions) FROM derivative_bot_state", Long.class));
        assertEquals(48_000_000, jdbc.queryForObject("SELECT SUM(cash) FROM users", Long.class));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM derivative_bot_state WHERE next_action_at>?", Integer.class, Timestamp.from(NOW)));
        target.refreshMarks();
        assertNull(impact.current(NOW), "Invalid reference must clear the spot feedback too");
    }

    @Test void persistedShortCrowdingAndForcedCoveringFeedTheSpotEnvironment() {
        long shortUser = user(19), longUser = user(18);
        long shortPosition = derivatives.open(shortUser, new OrderRequest("FUTURE", "SHORT", 7, "FUTIDX", 10, null, null, null))
                .account().positions().get(0).id();
        derivatives.open(longUser, new OrderRequest("FUTURE", "LONG", 1, "FUTIDX", 10, null, null, null));
        new DerivativeIndexHistory(jdbc).observe(1000, NOW.minusSeconds(30));
        jdbc.update("UPDATE stocks SET current_price=10030");
        refreshAt(NOW.plusSeconds(3));
        var squeeze = impact.current(NOW.plusSeconds(3));
        assertTrue(squeeze.shorts() > squeeze.longs());
        assertTrue(squeeze.squeeze() > 0);
        assertTrue(impact.environment("GI", NOW.plusSeconds(3)).directionBias() > 0);
        jdbc.update("UPDATE stocks SET current_price=11000");
        refreshAt(NOW.plusSeconds(6));
        assertEquals("LIQUIDATED", jdbc.queryForObject("SELECT status FROM derivative_positions WHERE id=?", String.class, shortPosition));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM derivative_trades WHERE action='LIQUIDATE'", Integer.class));
        var covered = impact.current(NOW.plusSeconds(6));
        assertEquals(0, covered.shorts());
        assertTrue(covered.closingFlow() > 0);
        assertTrue(covered.direction() > 0);
        refreshAt(NOW.plusSeconds(40));
        assertEquals(0, impact.current(NOW.plusSeconds(40)).closingFlow(), "Old covering must leave the trailing window");
    }
}
