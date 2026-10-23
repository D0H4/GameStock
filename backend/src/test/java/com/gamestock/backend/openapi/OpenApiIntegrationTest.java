package com.gamestock.backend.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamestock.backend.market.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.gamestock.backend.openapi.OpenApiModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Only creates/drops a uniquely named disposable database on explicit loopback opt-in. */
@EnabledIfSystemProperty(named = "market.test.jdbc", matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiIntegrationTest {
    private JdbcTemplate root, jdbc;
    private HikariDataSource pool;
    private String database;
    private MarketService market;
    private DerivativeService derivatives;
    private MatchingEngine matching;
    private PersistenceWorker persistence;
    private OpenApiKeyService keys;
    private OpenApiTradingService trading;
    private TransactionTemplate transaction;
    private long buyer, seller;
    private IssuedKey issued;
    private Principal principal;

    @BeforeAll void start() throws Exception {
        String url = System.getProperty("market.test.jdbc");
        root = new JdbcTemplate(new DriverManagerDataSource(url, "root", ""));
        database = "openapi_test_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        var config = new HikariConfig();
        config.setJdbcUrl(url.replaceFirst("/(?=\\?|$)", "/" + database));
        config.setUsername("root"); config.setPassword(""); config.setMaximumPoolSize(10);
        config.setConnectionInitSql("SET time_zone = '+00:00'");
        config.addDataSourceProperty("connectionTimeZone", "UTC");
        config.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
        pool = new HikariDataSource(config); jdbc = new JdbcTemplate(pool);
        String schema = Files.readString(Path.of("../database/schema.sql")).replaceAll("(?m)^--.*$", "")
                .replaceAll("(?is)CREATE DATABASE[^;]+;", "").replaceAll("(?is)USE gamestock;", "");
        for (String statement : schema.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        var metrics = new MarketMetrics(); matching = new MatchingEngine(metrics);
        market = new MarketService(event -> {}, jdbc, new UserFeatureService(jdbc), new TradingProtectionService(jdbc));
        ReflectionTestUtils.setField(market, "configuredSeed", "42");
        market.initializeData();
        ReflectionTestUtils.invokeMethod(market, "configurePipeline", matching, metrics, null);
        var manager = new DataSourceTransactionManager(pool); transaction = new TransactionTemplate(manager);
        derivatives = new DerivativeService(jdbc, manager);
        persistence = new PersistenceWorker(jdbc, new ObjectMapper(), metrics); persistence.initialize();
        keys = new OpenApiKeyService(jdbc);
        trading = new OpenApiTradingService(jdbc, new ObjectMapper(), persistence, keys, market, derivatives);
        jdbc.update("INSERT INTO users(username,password_hash,nickname,cash) VALUES ('api_buyer','TEST','api_buyer',100000000),('api_seller','TEST','api_seller',100000000)");
        buyer = jdbc.queryForObject("SELECT id FROM users WHERE username='api_buyer'", Long.class);
        seller = jdbc.queryForObject("SELECT id FROM users WHERE username='api_seller'", Long.class);
    }

    @BeforeEach void reset() {
        transaction.executeWithoutResult(status -> {
            market.maintainMarket();
            jdbc.update("DELETE FROM settlements"); jdbc.update("DELETE FROM trades"); jdbc.update("DELETE FROM orders");
            jdbc.update("DELETE FROM derivative_trades"); jdbc.update("DELETE FROM derivative_positions");
            jdbc.update("DELETE FROM portfolios WHERE user_id IN (?, ?)", buyer, seller);
            jdbc.update("DELETE FROM open_api_requests"); jdbc.update("DELETE FROM open_api_keys");
            jdbc.update("UPDATE users SET cash=100000000,role='USER',trade_banned_until=NULL WHERE id IN (?,?)", buyer, seller);
            jdbc.update("UPDATE market_book_revisions SET revision=revision+1");
            jdbc.update("INSERT INTO portfolios(user_id,stock_id,quantity,settled_quantity,average_price) SELECT ?,id,1000,1000,current_price FROM stocks WHERE stock_code='UMA'", seller);
        });
        DerivativeTapeFixture.seed(jdbc,Instant.now());
        issued = transaction.execute(status -> keys.create(buyer, new CreateKey("integration", Permission.TRADE, 1)));
        principal = keys.authenticate("Bearer " + issued.token(), true);
    }

    @AfterAll void stop() {
        if (persistence != null) persistence.close();
        if (matching != null) matching.close();
        if (pool != null) pool.close();
        if (root != null && database != null) root.execute("DROP DATABASE " + database);
    }

    private Order stock(Side side, int quantity, Long price) {
        return new Order(Product.STOCK, side, quantity, "UMA", price == null ? OrderType.MARKET : OrderType.LIMIT,
                price, null, null, null, null, null, null);
    }
    private Order etf(Side side, int quantity) {
        return new Order(Product.ETF, side, quantity, "ETFIDX", null, null, null, null, null, null, null, null);
    }
    private long cash() { return jdbc.queryForObject("SELECT cash FROM users WHERE id=?", Long.class, buyer); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Integer.class, buyer); }
    private void sameReceipt(Receipt expected, Receipt actual) {
        assertEquals(expected.requestId(), actual.requestId());
        assertEquals(expected.operation(), actual.operation());
        // On the wire 3 is 3; Jackson's LongNode/IntNode distinction is not part of the API.
        assertEquals(expected.result().toString(), actual.result().toString());
    }
    private void expectStatus(int expected, Runnable action) {
        Throwable failure = assertThrows(RuntimeException.class, action::run);
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        assertInstanceOf(ResponseStatusException.class, failure);
        assertEquals(expected, ((ResponseStatusException) failure).getStatusCode().value());
    }

    @Test void keysAreHashedReadOnlyRevocableAndOwnerScoped() {
        assertNotEquals(issued.token(), jdbc.queryForObject("SELECT token_hash FROM open_api_keys WHERE id=?", String.class, issued.key().id()));
        assertEquals(64, jdbc.queryForObject("SELECT LENGTH(token_hash) FROM open_api_keys WHERE id=?", Integer.class, issued.key().id()));
        assertFalse(keys.list(buyer).toString().contains(issued.token()));
        expectStatus(404, () -> keys.revoke(seller, issued.key().id()));
        var read = transaction.execute(s -> keys.create(buyer, new CreateKey("read", Permission.READ, 1)));
        keys.authenticate("Bearer " + read.token(), false);
        expectStatus(403, () -> keys.authenticate("Bearer " + read.token(), true));
        keys.revoke(buyer, issued.key().id());
        expectStatus(401, () -> keys.authenticate("Bearer " + issued.token(), false));
    }

    @Test void expiredSuspendedAndAdminCredentialsCannotTrade() {
        jdbc.update("UPDATE open_api_keys SET expires_at=CURRENT_TIMESTAMP-INTERVAL 1 SECOND WHERE id=?", issued.key().id());
        expectStatus(401, () -> keys.authenticate("Bearer " + issued.token(), true));
        jdbc.update("UPDATE open_api_keys SET expires_at=CURRENT_TIMESTAMP+INTERVAL 1 DAY WHERE id=?", issued.key().id());
        jdbc.update("UPDATE users SET trade_banned_until=CURRENT_TIMESTAMP+INTERVAL 1 HOUR WHERE id=?", buyer);
        expectStatus(403, () -> keys.authenticate("Bearer " + issued.token(), false));
        jdbc.update("UPDATE users SET trade_banned_until=NULL,role='ADMIN' WHERE id=?", buyer);
        expectStatus(403, () -> keys.authenticate("Bearer " + issued.token(), true));
        keys.authenticate("Bearer " + issued.token(), false);
    }

    @Test void concurrentRetriesProduceOneTradeAndReplaySurvivesServiceRestart() {
        var futures = new ArrayList<CompletableFuture<Receipt>>();
        for (int i = 0; i < 12; i++) futures.add(trading.order(principal, "same-order", etf(Side.BUY, 3)));
        Receipt first = futures.get(0).join();
        futures.forEach(future -> sameReceipt(first, future.join()));
        assertEquals(1, count("derivative_positions")); assertEquals(1, count("derivative_trades"));
        assertEquals(1, count("open_api_requests"));
        long balance = cash();
        var restarted = new OpenApiTradingService(jdbc, new ObjectMapper(), persistence, keys, market, derivatives);
        sameReceipt(first, restarted.order(principal, "same-order", etf(Side.BUY, 3)).join());
        assertEquals(balance, cash());
        expectStatus(409, () -> restarted.order(principal, "same-order", etf(Side.BUY, 4)).join());
        sameReceipt(first, restarted.receipt(buyer, "same-order"));
        expectStatus(404, () -> restarted.receipt(seller, "same-order"));
    }

    @Test void stockBuySellAndCancellationUseTheMatchingEngine() {
        long price = market.stocks().stream().filter(s -> s.code().equals("UMA")).findFirst().orElseThrow().price();
        persistence.submit(UUID.randomUUID().toString(), MarketModels.OrderResult.class, 1,
                () -> market.order(new MarketModels.OrderRequest("UMA", "SELL", 5, "LIMIT", price), seller)).join();
        Receipt buy = trading.order(principal, "stock-buy", stock(Side.BUY, 2, null)).join();
        assertEquals("FILLED", buy.result().path("status").asText());
        assertTrue(buy.result().path("orderId").asLong() > 0);
        assertEquals(2, jdbc.queryForObject("SELECT quantity FROM portfolios WHERE user_id=?", Integer.class, buyer));
        Receipt sell = trading.order(principal, "stock-sell", stock(Side.SELL, 1, price)).join();
        long sellId = sell.result().path("orderId").asLong();
        assertTrue(sellId > 0);
        assertEquals("OPEN", sell.result().path("status").asText());
        var cancel = trading.cancel(principal, "stock-cancel", sellId).join();
        assertEquals("CANCELLED", cancel.result().path("status").asText());
        sameReceipt(cancel, trading.cancel(principal, "stock-cancel", sellId).join());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, sellId));
    }

    @Test void etfBuySellAndPartialCloseReturnSharedAccount() {
        long before = cash();
        var bought = trading.order(principal, "etf-buy", etf(Side.BUY, 5)).join();
        assertEquals(cash() - before, bought.result().path("account").path("trades").get(0).path("cashFlow").asLong());
        Receipt sold = trading.order(principal, "etf-sell", etf(Side.SELL, 2)).join();
        var position = sold.result().path("account").path("positions").get(0);
        assertEquals(3, position.path("quantity").asInt());
        trading.close(principal, "etf-close", position.path("id").asLong(), new Close(3)).join();
        assertTrue(derivatives.account(buyer).positions().isEmpty());
        assertTrue(cash() < 100000000);
    }

    @Test void futuresSupportBothDirectionsAndPartialClosing() {
        for (Side side : Side.values()) {
            var order = new Order(Product.FUTURE, side, 2, "FUTIDX", null, null, 5, null, null, null, null, null);
            Receipt receipt = trading.order(principal, "future-" + side, order).join();
            var position = receipt.result().path("account").path("positions").get(0);
            assertEquals(side == Side.BUY ? "LONG" : "SHORT", position.path("side").asText());
            long id = position.path("id").asLong();
            trading.close(principal, "partial-" + side, id, new Close(1)).join();
            trading.close(principal, "close-" + side, id, new Close(1)).join();
        }
        assertTrue(derivatives.account(buyer).positions().isEmpty());
    }

    @Test void optionsSupportCallPutPurchaseAndOwnedPositionSale() {
        for (OptionType type : OptionType.values()) {
            var contract = derivatives.snapshot().options().stream()
                    .filter(o -> o.cycleId().equals("1H") && o.type().equals(type.name())).findFirst().orElseThrow();
            var order = new Order(Product.OPTION, Side.BUY, 2, null, null, null, null, type, contract.cycleId(),
                    contract.strike(), contract.expiryEpoch(), null);
            var receipt = trading.order(principal, "option-" + type, order).join();
            long id = receipt.result().path("account").path("positions").get(0).path("id").asLong();
            var sell = new Order(Product.OPTION, Side.SELL, 2, null, null, null, null, null, null, null, null, id);
            trading.order(principal, "option-sell-" + type, sell).join();
        }
        assertTrue(derivatives.account(buyer).positions().isEmpty());
        assertEquals(4, count("derivative_trades"));
    }

    @Test void crossAccountAndWrongProductClosuresCannotChangeBalances() {
        var other = transaction.execute(s -> keys.create(seller, new CreateKey("other", Permission.TRADE, 1)));
        var otherPrincipal = keys.authenticate("Bearer " + other.token(), true);
        var bought = trading.order(otherPrincipal, "other-etf", etf(Side.BUY, 1)).join();
        long id = bought.result().path("account").path("positions").get(0).path("id").asLong();
        expectStatus(404, () -> trading.close(principal, "steal", id, new Close(1)).join());
        var wrongKind = new Order(Product.OPTION, Side.SELL, 1, null, null, null, null, null, null, null, null, id);
        expectStatus(404, () -> trading.order(otherPrincipal, "wrong-kind", wrongKind).join());
        assertEquals(100000000, cash()); assertEquals(0, count("open_api_requests"));
    }

    @Test void rejectedAndUnserializableOrdersRollBackBalancesAndReceipts() {
        jdbc.update("UPDATE users SET cash=0 WHERE id=?", buyer);
        assertThrows(CompletionException.class, () -> trading.order(principal, "no-cash", etf(Side.BUY, 1)).join());
        assertEquals(0, cash()); assertEquals(0, count("open_api_requests"));
        jdbc.update("UPDATE users SET cash=100000000 WHERE id=?", buyer);
        ObjectMapper broken = spy(new ObjectMapper());
        doThrow(new IllegalStateException("simulated lost result")).when(broken).valueToTree(any(DerivativeModels.OrderResult.class));
        var failing = new OpenApiTradingService(jdbc, broken, persistence, keys, market, derivatives);
        assertThrows(CompletionException.class, () -> failing.order(principal, "rollback", etf(Side.BUY, 1)).join());
        assertEquals(100000000, cash()); assertEquals(0, count("derivative_positions"));
        assertEquals(0, count("derivative_trades")); assertEquals(0, count("open_api_requests"));
        trading.order(principal, "rollback", etf(Side.BUY, 1)).join();
        assertEquals(1, count("derivative_positions"));
    }

    @Test void revokedKeyIsRecheckedAtExecutionEvenWithPreviouslyAuthenticatedPrincipal() {
        keys.revoke(buyer, issued.key().id());
        expectStatus(401, () -> trading.order(principal, "revoked", etf(Side.BUY, 1)).join());
        assertEquals(100000000, cash()); assertEquals(0, count("open_api_requests"));
    }

    @Test void expiryAndConditionalFieldsAreValidatedWithoutSilentFallback() {
        assertThrows(IllegalArgumentException.class, () -> trading.order(principal, null, etf(Side.BUY, 1)));
        assertThrows(IllegalArgumentException.class, () -> trading.order(principal, "limit-etf",
                new Order(Product.ETF, Side.BUY, 1, "ETFIDX", OrderType.LIMIT, 1000L, null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> trading.order(principal, "future-symbol",
                new Order(Product.FUTURE, Side.BUY, 1, "WRONG", null, null, null, null, null, null, null, null)));
        var stale = new Order(Product.OPTION, Side.BUY, 1, null, null, null, null, OptionType.CALL,
                "10M", 1000.0, Instant.now().minusSeconds(600).getEpochSecond(), null);
        assertThrows(CompletionException.class, () -> trading.order(principal, "stale", stale).join());
        assertEquals(0, count("open_api_requests"));
    }

    @Test void orderLookupAndCursorPaginationReturnAllOwnedOpenOrders() {
        long price = market.stocks().stream().filter(s -> s.code().equals("UMA")).findFirst().orElseThrow().price() - 100;
        for (int i = 1; i <= 8; i++) trading.order(principal, "paged-" + i, stock(Side.BUY, i, price)).join();
        var query = new OpenApiOrderQuery(jdbc);
        var first = query.open(buyer, "UMA", 3, null);
        var second = query.open(buyer, "UMA", 3, first.get(2).id());
        var third = query.open(buyer, "UMA", 3, second.get(2).id());
        assertEquals(3, first.size()); assertEquals(3, second.size()); assertEquals(2, third.size());
        assertTrue(first.get(2).id() > second.get(0).id());
        assertEquals(first.get(0), query.get(buyer, first.get(0).id()));
        expectStatus(404, () -> query.get(seller, first.get(0).id()));
        assertTrue(query.open(seller, null, 100, null).isEmpty());
    }

    @Test void partialOptionCloseKeepsRemainingPortfolioValueInWon() {
        var contract = derivatives.snapshot().options().stream()
                .filter(o -> o.cycleId().equals("SUN") && o.type().equals("CALL") && Math.abs(o.strike()-1000) < 1)
                .findFirst().orElseThrow();
        var order = new Order(Product.OPTION, Side.BUY, 3, null, null, null, null, OptionType.CALL,
                contract.cycleId(), contract.strike(), contract.expiryEpoch(), null);
        long id = trading.order(principal, "partial-option", order).join().result().path("account").path("positions").get(0).path("id").asLong();
        var closed = trading.close(principal, "partial-option-close", id, new Close(1)).join();
        var remaining = closed.result().path("account").path("positions").get(0);
        assertEquals(2, remaining.path("quantity").asInt());
        long stored = jdbc.queryForObject("SELECT mark_value FROM derivative_positions WHERE id=?", Long.class, id);
        assertEquals(remaining.path("value").asLong(), stored, 2);
        assertEquals(stored, market.portfolio(buyer).derivativeAssetValue());
    }

    @Test void etfCanBeSoldAtTheOpenPositionLimit() {
        for (int i = 0; i < 60; i++) trading.order(principal, "limit-" + i, etf(Side.BUY, 1)).join();
        assertThrows(CompletionException.class, () -> trading.order(principal, "limit-extra", etf(Side.BUY, 1)).join());
        trading.order(principal, "limit-sell", etf(Side.SELL, 1)).join();
        assertEquals(59, derivatives.account(buyer).positions().size());
    }

    @Test void realCredentialAndJsonReachEveryTradingEngineThroughTheController() throws Exception {
        var cache = mock(PublicMarketCache.class);
        when(cache.stocks()).thenAnswer(invocation -> market.stocks());
        var limiter = new OpenApiRateLimiter();
        var controller = new OpenApiController(keys, limiter, new OpenApiQuoteService(cache, derivatives), trading,
                market, derivatives, new OpenApiOrderQuery(jdbc));
        List<HttpMessageConverter<?>> converters = new ArrayList<>(List.of(
                new ResourceHttpMessageConverter(), new MappingJackson2HttpMessageConverter(new ObjectMapper())));
        new OpenApiWebConfig(limiter).extendMessageConverters(converters);
        var mvc = MockMvcBuilders.standaloneSetup(controller,
                        new OpenApiKeyController(mock(com.gamestock.backend.auth.AuthService.class), keys, limiter))
                .setMessageConverters(converters.toArray(HttpMessageConverter[]::new))
                .setControllerAdvice(new OpenApiErrors()).build();
        mvc.perform(get("/api/openapi/v1/quotes")).andExpect(status().isOk())
                .andExpect(jsonPath("$.derivatives.future.code").value("FUTIDX"));
        mvc.perform(get("/api/openapi/openapi.json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value("3.0.3"));
        mvc.perform(get("/api/openapi/v1/account")).andExpect(status().isUnauthorized());
        String header = "Bearer " + issued.token();
        long price = market.stocks().stream().filter(s -> s.code().equals("UMA")).findFirst().orElseThrow().price() - 100;
        var option = derivatives.snapshot().options().stream().filter(o -> o.cycleId().equals("SUN")).findFirst().orElseThrow();
        List<Order> requests = List.of(stock(Side.BUY, 1, price), etf(Side.BUY, 1),
                new Order(Product.FUTURE, Side.SELL, 1, "FUTIDX", null, null, 2, null, null, null, null, null),
                new Order(Product.OPTION, Side.BUY, 1, null, null, null, null, OptionType.valueOf(option.type()),
                        option.cycleId(), option.strike(), option.expiryEpoch(), null));
        for (Order request : requests) {
            String requestId = "http-" + request.product();
            var pending = mvc.perform(post("/api/openapi/v1/orders").header("Authorization", header)
                            .header("Idempotency-Key", requestId).contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().writeValueAsString(request)))
                    .andExpect(request().asyncStarted()).andReturn();
            pending.getAsyncResult(5000);
            mvc.perform(asyncDispatch(pending)).andExpect(status().isCreated()).andExpect(jsonPath("$.requestId").value(requestId));
        }
        mvc.perform(post("/api/openapi/v1/orders").header("Authorization", header)
                        .header("Idempotency-Key", "fractional").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"STOCK\",\"side\":\"BUY\",\"symbol\":\"UMA\",\"quantity\":1.5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/openapi/v1/account").header("Authorization", header)).andExpect(status().isOk())
                .andExpect(jsonPath("$.derivatives.positions.length()").value(3));
    }
}
