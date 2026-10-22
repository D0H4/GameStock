package com.gamestock.backend.market;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamestock.backend.auth.AuthService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static com.gamestock.backend.market.DerivativeModels.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Disposable MySQL integration test for ETF/futures/options settlement.
 * Opt-in: -Dmarket.test.jdbc=jdbc:mysql://127.0.0.1:3306/
 */
@EnabledIfSystemProperty(named = "market.test.jdbc", matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DerivativeServiceIntegrationTest {
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private DerivativeService derivatives;
    private DataSourceTransactionManager manager;
    private TransactionStatus transaction;
    private TransactionTemplate template;
    private String database;
    private long user;

    @BeforeAll
    void createDisposableDatabase() throws Exception {
        dataSource = new SingleConnectionDataSource(System.getProperty("market.test.jdbc"), "root", "", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("SET time_zone = '+00:00'");
        database = "derivative_test_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        jdbc.execute("USE " + database);
        String schema = Files.readString(Path.of("../database/schema.sql"));
        schema = schema.replaceAll("(?m)^--.*$", "")
                .replaceAll("(?is)CREATE DATABASE[^;]+;", "")
                .replaceAll("(?is)USE gamestock;", "");
        for (String statement : schema.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        manager = new DataSourceTransactionManager(dataSource);
        template = new TransactionTemplate(manager);
        derivatives = new DerivativeService(jdbc, manager);
        derivatives.ensureTables();
        jdbc.update("INSERT INTO users(username,password_hash,nickname,cash) VALUES ('trader','TEST','trader',100000000)");
        user = jdbc.queryForObject("SELECT id FROM users WHERE username='trader'", Long.class);
        for (String[] stock : List.of(new String[]{"GI", "원신", "오픈월드"}, new String[]{"TR", "트릭컬", "RPG"})) {
            jdbc.update("INSERT INTO games(name,developer,genre) VALUES (?, 'test', ?)", stock[1], stock[2]);
            long game = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("INSERT INTO stocks(game_id,stock_code,current_price,previous_price,total_volume) VALUES (?,?,10000,10000,0)",
                    game, stock[0]);
            long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("""
                    INSERT INTO stock_protection_state(stock_id,day_reference,static_reference,dynamic_reference,last_trade_price)
                    VALUES (?,10000,10000,10000,10000)
                    """, id);
        }
        jdbc.update("INSERT INTO market_protection_state(id,trading_date) VALUES (1,CURRENT_DATE)");
    }

    @BeforeEach
    void begin() {
        transaction = manager.getTransaction(new DefaultTransactionDefinition());
    }

    @AfterEach
    void rollback() {
        if (transaction != null) manager.rollback(transaction);
    }

    @AfterAll
    void dispose() {
        if (jdbc != null && database != null) jdbc.execute("DROP DATABASE " + database);
        if (dataSource != null) dataSource.destroy();
    }

    private <T> T inTransaction(Supplier<T> action) {
        return template.execute(status -> action.get());
    }

    private void moveIndex(double multiplier) {
        jdbc.update("UPDATE stocks SET current_price = ROUND(current_price * ?)", multiplier);
    }

    @Test
    @Order(1)
    void snapshotExposesCategoryEtfsFuturesAndThreeOptionCycles() {
        DerivativeSnapshot snapshot = derivatives.snapshot();
        assertEquals(1000, snapshot.indexValue(), 0.001);
        assertEquals(5, snapshot.etfs().size());
        assertEquals("ETFIDX", snapshot.etfs().get(0).code());
        assertEquals(1, snapshot.etfs().get(0).constituents().size() >= 1 ? 1 : 0);
        assertEquals("FUTIDX", snapshot.future().code());
        assertEquals(3, snapshot.cycles().size());
        assertEquals(18 * 3, snapshot.options().size());
        assertEquals(DerivativePricing.CONTRACT_MULTIPLIER, snapshot.future().multiplier());
        assertTrue(snapshot.options().stream().allMatch(contract -> contract.premium() >= 10));
        assertTrue(snapshot.cycles().stream().allMatch(cycle -> cycle.expiryEpoch() * 1000 > System.currentTimeMillis()));
    }

    @Test
    @Order(2)
    void etfBuyAndSellRoundTripsAtNav() {
        long before = jdbc.queryForObject("SELECT cash FROM users WHERE id=?", Long.class, user);
        OrderResult bought = inTransaction(() -> derivatives.open(user,
                new OrderRequest("ETF", "BUY", 10, "ETFRPG", null, null, null, null)));
        assertEquals(1, bought.account().positions().size());
        assertTrue(bought.account().cash() < before);
        long positionId = bought.account().positions().get(0).id();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM derivative_trades WHERE user_id=?", Integer.class, user));

        OrderResult sold = inTransaction(() -> derivatives.close(user, positionId, 10));
        assertEquals(0, sold.account().positions().size());
        assertEquals(before, sold.account().cash(), 2_000);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM derivative_trades WHERE user_id=?", Integer.class, user));
    }

    @Test
    @Order(3)
    void leveragedFutureLosesOnlyFeesWhenIndexIsUnchanged() {
        long before = jdbc.queryForObject("SELECT cash FROM users WHERE id=?", Long.class, user);
        OrderResult opened = inTransaction(() -> derivatives.open(user,
                new OrderRequest("FUTURE", "LONG", 2, "FUTIDX", 10, null, null, null)));
        Position position = opened.account().positions().get(0);
        assertEquals("FUTURE", position.kind());
        assertEquals(10, position.leverage());
        assertTrue(position.margin() > 0);

        OrderResult closed = inTransaction(() -> derivatives.close(user, position.id(), 2));
        assertTrue(closed.account().cash() <= before);
        assertTrue(closed.account().cash() >= before - 2_000);
        assertEquals(0, closed.account().positions().size());
    }

    @Test
    @Order(4)
    void optionExpiryPaysIntrinsicValue() {
        long before = jdbc.queryForObject("SELECT cash FROM users WHERE id=?", Long.class, user);
        OrderResult opened = inTransaction(() -> derivatives.open(user,
                new OrderRequest("OPTION", "CALL", 5, null, null, "10M", 1000.0, null)));
        Position position = opened.account().positions().get(0);
        assertEquals("OPTION", position.kind());
        assertTrue(opened.account().cash() < before);
        moveIndex(1.10);
        jdbc.update("UPDATE derivative_positions SET expiry_at = DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id = ?", position.id());
        derivatives.refreshMarks();
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM derivative_positions WHERE id=?", String.class, position.id()));
        long after = jdbc.queryForObject("SELECT cash FROM users WHERE id=?", Long.class, user);
        assertTrue(after > before, "내재가치 100포인트 × 1,000원 × 5계약이 입금되어야 한다");
    }

    @Test
    @Order(6)
    void restApiAcceptsTheFrontendJsonAndReturnsTheUpdatedAccount() throws Exception {
        AuthService auth = mock(AuthService.class);
        when(auth.requireUser("Bearer test")).thenReturn(
                new AuthService.LoginUser(user, "trader", null, null, 0, 0, false, "USER"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DerivativeController(derivatives, auth)).build();

        String body = mvc.perform(post("/api/derivatives/positions")
                        .header("Authorization", "Bearer test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"ETF\",\"side\":\"BUY\",\"quantity\":1,\"symbol\":\"ETFIDX\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.account.positions[0].kind").value("ETF"))
                .andReturn().getResponse().getContentAsString();
        long id = new ObjectMapper().readTree(body).path("account").path("positions").get(0).path("id").asLong();

        mvc.perform(post("/api/derivatives/positions/" + id + "/close")
                        .header("Authorization", "Bearer test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.positions.length()").value(0));

        mvc.perform(get("/api/derivatives"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.etfs.length()").value(5))
                .andExpect(jsonPath("$.cycles.length()").value(3))
                .andExpect(jsonPath("$.options.length()").value(54));
    }

    @Test
    @Order(5)
    void leveragedFutureIsLiquidatedWhenEquityFallsBelowMaintenance() {
        OrderResult opened = inTransaction(() -> derivatives.open(user,
                new OrderRequest("FUTURE", "LONG", 10, "FUTIDX", 10, null, null, null)));
        Position position = opened.account().positions().get(0);
        moveIndex(0.70);
        derivatives.refreshMarks();
        assertEquals("LIQUIDATED", jdbc.queryForObject("SELECT status FROM derivative_positions WHERE id=?", String.class, position.id()));
        long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id=?", Long.class, user);
        assertTrue(cash >= 0, "강제청산 뒤 현금이 음수가 되면 안 된다");
    }
}
