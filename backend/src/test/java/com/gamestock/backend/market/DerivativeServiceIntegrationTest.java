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
import java.sql.Timestamp;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

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
        BotLedgerJournal.ensureTables(jdbc);
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
        new DerivativeIndexHistory(jdbc).initializeBases();
        marketAt(Instant.parse("2030-01-01T10:00:00Z"));
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
        Clock current=(Clock)org.springframework.test.util.ReflectionTestUtils.getField(derivatives,"clock");
        marketAt(current.instant().plusSeconds(1));
    }

    /** A sustained tape fixture, not an isolated last-price assignment. */
    private void marketAt(Instant at) {
        derivatives.setClock(Clock.fixed(at,ZoneOffset.UTC));
        DerivativeTapeFixture.seed(jdbc,at);
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
        Instant beforeExpiry=Instant.parse("2030-01-01T10:08:00Z");
        marketAt(beforeExpiry);
        long before = jdbc.queryForObject("SELECT cash FROM users WHERE id=?", Long.class, user);
        OrderResult opened = inTransaction(() -> derivatives.open(user,
                new OrderRequest("OPTION", "CALL", 5, null, null, "10M", 1000.0, null)));
        Position position = opened.account().positions().get(0);
        assertEquals("OPTION", position.kind());
        assertTrue(opened.account().cash() < before);
        moveIndex(1.10);
        marketAt(beforeExpiry.plusSeconds(119));
        derivatives.refreshMarks();
        derivatives.setClock(Clock.fixed(beforeExpiry.plusSeconds(121),ZoneOffset.UTC));
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

    @Test void optionFlowBucketsOpensAndClosesWithTheLastIndexButSkipsExpiriesAndFutures() {
        Instant now=Instant.parse("2030-01-01T10:00:00Z");
        marketAt(now);
        String trade="INSERT INTO derivative_trades(user_id,position_id,action,kind,symbol,side,quantity,price,cash_flow,fee,created_at) VALUES (?,0,?,?,?,?,?,1,?,?,?)";
        jdbc.update(trade,user,"OPEN","OPTION","10M-C1000","CALL",2,-3009,9,Timestamp.from(now.minusSeconds(100)));
        jdbc.update(trade,user,"OPEN","OPTION","10M-P1000","PUT",1,-1003,3,Timestamp.from(now.minusSeconds(90)));
        jdbc.update(trade,user,"CLOSE","OPTION","10M-C1000","CALL",1,1495,5,Timestamp.from(now.minusSeconds(400)));
        jdbc.update(trade,user,"EXPIRE","OPTION","10M-C1000","CALL",1,800,0,Timestamp.from(now.minusSeconds(50)));
        jdbc.update(trade,user,"OPEN","FUTURE","FUTIDX","LONG",1,-200000,40,Timestamp.from(now.minusSeconds(80)));
        jdbc.update(trade,user,"OPEN","OPTION","10M-C1000","CALL",9,-9999,9,Timestamp.from(now.minusSeconds(7200)));
        jdbc.update("INSERT INTO derivative_index_observations(observed_at,index_value) VALUES (?,999.0),(?,1001.5)",
                Timestamp.from(now.minusSeconds(120)),Timestamp.from(now.minusSeconds(60)));

        OptionFlow flow=derivatives.optionFlow("1h","5m");
        assertEquals(300,flow.intervalSeconds());
        assertEquals(12,flow.buckets().size());
        assertEquals(now.plusSeconds(300).getEpochSecond(),flow.to());
        assertEquals(3,flow.callVolume());
        assertEquals(1,flow.putVolume());
        assertEquals(4500,flow.callPremium());
        assertEquals(1000,flow.putPremium());
        assertEquals((3000-1500)-1000,flow.netPremium());
        OptionFlowBucket late=flow.buckets().stream().filter(b->b.at()==now.minusSeconds(300).getEpochSecond()).findFirst().orElseThrow();
        assertEquals(1001.5,late.index(),1e-9);
        assertEquals(2,late.callVolume());
        assertEquals(2000,late.netPremium());
        OptionFlowBucket early=flow.buckets().stream().filter(b->b.at()==now.minusSeconds(600).getEpochSecond()).findFirst().orElseThrow();
        assertNull(early.index());
        assertEquals(-1500,early.netPremium());
        assertThrows(IllegalArgumentException.class,()->derivatives.optionFlow("1w","1m"));
        assertThrows(IllegalArgumentException.class,()->derivatives.optionFlow("2d","5m"));
    }

    @Test void futureChartBucketsIndexObservationsIntoCandlesAndSkipsInvalidatedSamples() {
        Instant now=Instant.parse("2030-01-01T10:00:00Z");
        marketAt(now);
        jdbc.update("DELETE FROM derivative_index_observations");
        String observe="INSERT INTO derivative_index_observations(observed_at,index_value,invalidated) VALUES (?,?,?)";
        jdbc.update(observe,Timestamp.from(now.minusSeconds(170)),1000.0,false);
        jdbc.update(observe,Timestamp.from(now.minusSeconds(150)),1004.5,false);
        jdbc.update(observe,Timestamp.from(now.minusSeconds(140)),990.0,true);
        jdbc.update(observe,Timestamp.from(now.minusSeconds(130)),998.25,false);
        jdbc.update(observe,Timestamp.from(now.minusSeconds(30)),1002.0,false);
        jdbc.update("INSERT INTO derivative_index_observations(observed_at,index_value,settleable) VALUES (?,1003.0,FALSE)",
                Timestamp.from(now.minusSeconds(10)));
        jdbc.update(observe,Timestamp.from(now.minusSeconds(7200)),900.0,false);

        List<IndexCandle> candles=derivatives.futureChart("1h");
        assertEquals(2,candles.size());
        IndexCandle first=candles.get(0);
        assertEquals(now.minusSeconds(180).toString(),first.recordedAt());
        assertEquals(1000.0,first.openPrice(),1e-9);
        assertEquals(1004.5,first.highPrice(),1e-9);
        assertEquals(998.25,first.lowPrice(),1e-9);
        assertEquals(998.25,first.closePrice(),1e-9);
        assertEquals(3,first.volume());
        assertEquals(now.minusSeconds(60).toString(),candles.get(1).recordedAt());
        assertEquals(1003.0,candles.get(1).closePrice(),1e-9,"chart-only samples are still drawn");
        assertThrows(IllegalArgumentException.class,()->derivatives.futureChart("1y"));
    }

    @Test void missingPreExpiryHistoryDefersSettlementInsteadOfUsingTheCurrentPrice() {
        var history=new DerivativeIndexHistory(jdbc);
        Instant expiry=Instant.parse("2030-01-01T11:00:00Z");
        history.observe(1100,expiry.plusSeconds(1));
        assertThrows(IllegalStateException.class,()->history.fixing(expiry));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_expiry_fixings",Integer.class));
    }

    @Test void historyRetentionPreservesThePreCutoffObservationAndImmutableFixing() {
        var history=new DerivativeIndexHistory(jdbc);
        Instant now=Instant.parse("2030-03-01T11:00:00Z"),cutoff=now.minusSeconds(30L*86400);
        history.observe(900,cutoff.minusSeconds(20));
        history.observe(1000,cutoff.minusSeconds(1));
        history.observe(1100,now);
        assertEquals(1000,history.fixing(cutoff));
        history.retain(now);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_index_observations",Integer.class));
        assertEquals(1000,history.fixing(cutoff));
    }

    @Test void stalePreExpiryHistoryIsNotPromotedToAnExpiryFixing() {
        var history=new DerivativeIndexHistory(jdbc);
        Instant expiry=Instant.parse("2030-01-01T11:00:00Z");
        history.observe(1000,expiry.minusSeconds(91));
        assertThrows(IllegalStateException.class,()->history.fixing(expiry));
        history.observe(1001,expiry.minusSeconds(3));
        assertEquals(1001,history.fixing(expiry));
        history.observe(999,expiry.plusSeconds(1));
        assertEquals(1001,history.fixing(expiry));
    }

    @Test void dailyReferenceRollDoesNotChangeDerivativePricesOrCreateProfit() {
        moveIndex(.9);
        var before=derivatives.snapshot();
        var bought=derivatives.open(user,new OrderRequest("FUTURE","LONG",1,"FUTIDX",10,null,null,null));
        long initialCash=jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,user)+bought.account().positions().get(0).margin();
        jdbc.update("UPDATE stock_protection_state p JOIN stocks s ON s.id=p.stock_id SET p.day_reference=s.current_price");
        assertEquals(before.indexValue(),derivatives.snapshot().indexValue(),1e-9);
        assertEquals(before.etfs().get(0).price(),derivatives.snapshot().etfs().get(0).price(),1e-9);
        long after=derivatives.close(user,bought.account().positions().get(0).id(),1).account().cash();
        assertTrue(after<=initialCash,"Unchanged market cannot create a rollover profit");
    }

    @Test void delayedAndManualExpiryUseTheSameImmutableFixingWithoutAnExpiryFee() {
        Instant openedAt=Instant.parse("2030-01-01T10:59:58Z");
        marketAt(openedAt);
        long a=derivatives.open(user,new OrderRequest("OPTION","CALL",1,null,null,"1H",1000.0,null)).account().positions().get(0).id();
        long b=derivatives.open(user,new OrderRequest("OPTION","CALL",1,null,null,"1H",1000.0,null)).account().positions().get(0).id();
        derivatives.setClock(Clock.fixed(openedAt.plusSeconds(1),ZoneOffset.UTC));
        derivatives.refreshMarks(); // final observed index before expiry = 1000
        long cash=jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,user);
        moveIndex(1.05);
        derivatives.setClock(Clock.fixed(openedAt.plusSeconds(3602),ZoneOffset.UTC));
        derivatives.close(user,a,1);
        derivatives.refreshMarks();
        assertEquals(cash,jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,user));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_positions WHERE id IN (?,?) AND status='EXPIRED'",Integer.class,a,b));
        assertEquals(0,jdbc.queryForObject("SELECT SUM(fee) FROM derivative_trades WHERE action='EXPIRE'",Long.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_expiry_fixings",Integer.class));
    }

    @Test void fiveThousandEtfsCannotStarveLaterExpiriesOrLiquidations() {
        Instant now=Instant.parse("2030-01-01T10:59:58Z");
        marketAt(now);
        derivatives.refreshMarks();
        var rows=new java.util.ArrayList<Object[]>();
        for(int account=0;account<84;account++) {
            jdbc.update("INSERT INTO users(username,password_hash,nickname,cash) VALUES(?,'TEST',?,1000000)","page-"+account,"page-"+account);
            long owner=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
            for(int j=0;j<60 && rows.size()<5000;j++)rows.add(new Object[]{owner});
        }
        jdbc.batchUpdate("INSERT INTO derivative_positions(user_id,kind,symbol,name,side,quantity,entry_price,entry_notional,mark_price,mark_value) VALUES(?,'ETF','ETFIDX','index','LONG',1,1000,1000,1000,1000)",rows);
        long future=derivatives.open(user,new OrderRequest("FUTURE","LONG",1,"FUTIDX",10,null,null,null)).account().positions().get(0).id();
        long option=derivatives.open(user,new OrderRequest("OPTION","CALL",1,null,null,"1H",1000.0,null)).account().positions().get(0).id();
        moveIndex(.8);
        derivatives.setClock(Clock.fixed(now.plusSeconds(3),ZoneOffset.UTC));
        derivatives.refreshMarks();
        assertEquals("LIQUIDATED",jdbc.queryForObject("SELECT status FROM derivative_positions WHERE id=?",String.class,future));
        assertEquals("EXPIRED",jdbc.queryForObject("SELECT status FROM derivative_positions WHERE id=?",String.class,option));
    }

    @Test void persistentVolatilitySurvivesRestartAndGetRequestsCannotSupplySamples() {
        Instant now=Instant.parse("2030-01-01T10:00:00Z");
        var history=new DerivativeIndexHistory(jdbc);
        for(int i=0;i<60;i++)history.observe(1000,now.minusSeconds((59-i)*3L));
        double warm=history.volatility(now);assertEquals(.25,warm,1e-10);
        var original=derivatives.snapshot();
        var restarted=new DerivativeService(jdbc,manager);restarted.setClock(Clock.fixed(now,ZoneOffset.UTC));
        for(int i=0;i<10;i++)assertEquals(original.options(),restarted.snapshot().options());
        assertEquals(60,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_index_observations",Integer.class));
        assertEquals(warm,new DerivativeIndexHistory(jdbc).volatility(now));
    }

    @Test void oneLastPricePrintAndReferenceLagCannotGenerateDerivativeRoundTripProfit() {
        long initial=jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,user);
        jdbc.update("UPDATE stocks SET current_price=11000");
        assertEquals(1000,derivatives.snapshot().indexValue(),1e-8);
        for(String side:List.of("LONG","SHORT")) {
            var opened=derivatives.open(user,new OrderRequest("FUTURE",side,1,"FUTIDX",10,null,null,null));
            derivatives.close(user,opened.account().positions().get(0).id(),1);
        }
        var etf=derivatives.open(user,new OrderRequest("ETF","BUY",1,"ETFIDX",null,null,null,null));
        derivatives.close(user,etf.account().positions().get(0).id(),1);
        jdbc.update("UPDATE stocks SET current_price=10500");
        for(String side:List.of("CALL","PUT")) {
            var option=derivatives.open(user,new OrderRequest("OPTION",side,1,null,null,"10M",1000.0,null));
            derivatives.close(user,option.account().positions().get(0).id(),1);
        }
        derivatives.refreshMarks();
        assertEquals(1000,derivatives.snapshot().indexValue(),1e-8,"The protected quote uses the sustained executed tape");
        assertTrue(jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,user)<initial);
    }

    @Test void staleReferencesCannotOpenCloseOrLiquidatePositionsAndOnlyCreateChartObservations() {
        var opened=derivatives.open(user,new OrderRequest("FUTURE","LONG",1,"FUTIDX",10,null,null,null));
        long position=opened.account().positions().get(0).id();
        derivatives.setClock(Clock.fixed(Instant.parse("2030-01-01T10:05:00Z"),ZoneOffset.UTC));
        jdbc.update("UPDATE stocks SET current_price=100");
        assertThrows(IllegalArgumentException.class,()->derivatives.open(user,new OrderRequest("ETF","BUY",1,"ETFIDX",null,null,null,null)));
        assertThrows(IllegalArgumentException.class,()->derivatives.close(user,position,1));
        derivatives.refreshMarks();
        assertEquals("OPEN",jdbc.queryForObject("SELECT status FROM derivative_positions WHERE id=?",String.class,position));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_index_observations WHERE settleable=TRUE",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_index_observations WHERE settleable=FALSE",Integer.class));
    }

    @Test void sparseRecentConstituentTapeAllowsFuturesAndOptionsWithActualObservationTime() {
        Instant now=Instant.parse("2030-01-01T10:00:00Z");
        long stock=jdbc.queryForObject("SELECT id FROM stocks WHERE stock_code='GI'",Long.class);
        jdbc.update("DELETE FROM bot_trade_seconds WHERE stock_id=?",stock);
        var rows=new java.util.ArrayList<Object[]>();
        for(int i=0;i<20;i++) {
            long second=now.getEpochSecond()-40-(19-i)*20L;
            rows.add(new Object[]{stock,second,second*1000,second*1000,10000,10000,10000,10000,1,10000,1});
        }
        jdbc.batchUpdate("INSERT INTO bot_trade_seconds(stock_id,bucket_at,first_at,last_at,open_price,high_price,low_price,close_price,quantity,notional,fills) VALUES(?,?,?,?,?,?,?,?,?,?,?)",rows);
        new DerivativePriceOracle(jdbc).invalidate(stock,now.minusSeconds(40));
        var future=new OrderRequest("FUTURE","LONG",1,"FUTIDX",5,null,null,null);
        assertThrows(IllegalArgumentException.class,()->derivatives.open(user,future));

        derivatives.refreshMarks();

        assertEquals(Timestamp.from(now.minusSeconds(40)),jdbc.queryForObject(
                "SELECT observed_at FROM derivative_reference_prices WHERE stock_id=?",Timestamp.class,stock));
        assertEquals(10000,referencePrice(stock),1e-8);
        assertDoesNotThrow(()->derivatives.open(user,future));
        assertDoesNotThrow(()->derivatives.open(user,new OrderRequest("OPTION","CALL",1,null,null,"10M",1000.0,null)));
        assertDoesNotThrow(()->derivatives.open(user,new OrderRequest("OPTION","PUT",1,null,null,"10M",1000.0,null)));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_positions WHERE user_id=? AND status='OPEN'",Integer.class,user));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_index_observations WHERE settleable=TRUE",Integer.class));
        for(var position:derivatives.account(user).positions())
            assertDoesNotThrow(()->derivatives.close(user,position.id(),1));
    }

    @Test void cancellationInvalidatesSamplesWhoseSparseReferenceUsesOlderExecutions() {
        Instant now=Instant.parse("2030-01-01T10:00:00Z");
        long stock=jdbc.queryForObject("SELECT id FROM stocks WHERE stock_code='GI'",Long.class);
        var history=new DerivativeIndexHistory(jdbc);
        history.observe(1000,now);
        new DerivativePriceOracle(jdbc).invalidate(stock,now.minusSeconds(400));
        assertTrue(jdbc.queryForObject("SELECT invalidated FROM derivative_index_observations WHERE observed_at=?",
                Boolean.class,Timestamp.from(now)));
        assertThrows(IllegalStateException.class,()->history.fixing(now));
    }

    @Test void chartOnlySamplesNeverBecomeExpiryFixings() {
        var history=new DerivativeIndexHistory(jdbc);
        Instant expiry=Instant.parse("2030-04-01T10:00:00Z");
        history.observe(1000,expiry.minusSeconds(30));
        history.observe(1200,expiry.minusSeconds(3),false);
        assertEquals(1000,history.fixing(expiry));
    }

    @Test void splittingOrdersOrUsingCheapOptionsCannotBypassTheAccountExposureLimit() {
        derivatives.open(user,new OrderRequest("OPTION","CALL",6,null,null,"10M",1000.0,null));
        long before=jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,user);
        assertThrows(IllegalArgumentException.class,()->derivatives.open(user,new OrderRequest("FUTURE","LONG",5,"FUTIDX",10,null,null,null)));
        assertEquals(before,jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,user));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_positions WHERE user_id=?",Integer.class,user));
    }

    @Test void unrelatedStaleStockDoesNotBlockEtfBuysClosesOrMarks() {
        var opened=derivatives.open(user,new OrderRequest("ETF","BUY",10,"ETFRPG",null,null,null,null));
        long position=opened.account().positions().get(0).id();
        Instant now=Instant.parse("2030-01-01T10:00:03Z");
        jdbc.update("UPDATE stocks SET current_price=11000 WHERE stock_code='TR'");
        marketAt(now);
        jdbc.update("DELETE FROM bot_trade_seconds WHERE stock_id=(SELECT id FROM stocks WHERE stock_code='GI')");
        jdbc.update("UPDATE derivative_reference_prices SET observed_at=NULL WHERE stock_id=(SELECT id FROM stocks WHERE stock_code='GI')");
        derivatives.refreshMarks();
        assertEquals(1100,jdbc.queryForObject("SELECT mark_price FROM derivative_positions WHERE id=?",Double.class,position),1e-8);
        assertDoesNotThrow(()->derivatives.open(user,new OrderRequest("ETF","BUY",1,"ETFRPG",null,null,null,null)));
        assertDoesNotThrow(()->derivatives.close(user,position,5));
        assertDoesNotThrow(()->derivatives.open(user,new OrderRequest("ETF","SELL",1,"ETFRPG",null,null,null,null)));
        assertThrows(IllegalArgumentException.class,()->derivatives.open(user,new OrderRequest("FUTURE","LONG",1,"FUTIDX",10,null,null,null)));
        assertThrows(IllegalArgumentException.class,()->derivatives.open(user,new OrderRequest("ETF","BUY",1,"ETFIDX",null,null,null,null)));
        jdbc.update("UPDATE derivative_reference_prices SET observed_at=NULL WHERE stock_id=(SELECT id FROM stocks WHERE stock_code='TR')");
        assertThrows(IllegalArgumentException.class,()->derivatives.close(user,position,1));
        assertThrows(IllegalArgumentException.class,()->derivatives.open(user,new OrderRequest("ETF","BUY",1,"ETFRPG",null,null,null,null)));
    }

    @Test void appreciatedEtfHoldingsCountAtCurrentNavAcrossSplitOrdersAndShortEntries() {
        for (int i=0;i<50;i++) derivatives.open(user,new OrderRequest("ETF","BUY",100,"ETFIDX",null,null,null,null));
        long cash=jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,user);
        // Before the oracle catches up, a SHORT must not use the lower execution index for exposure.
        jdbc.update("UPDATE stocks SET current_price=12000");
        assertThrows(IllegalArgumentException.class,()->derivatives.open(user,new OrderRequest("FUTURE","SHORT",4,"FUTIDX",5,null,null,null)));
        marketAt(Instant.parse("2030-01-01T10:00:03Z"));
        assertEquals(1200,derivatives.snapshot().indexValue(),1e-8);
        assertThrows(IllegalArgumentException.class,()->derivatives.open(user,new OrderRequest("FUTURE","LONG",4,"FUTIDX",5,null,null,null)));
        assertEquals(cash,jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,user));
        assertEquals(50,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_trades WHERE user_id=?",Integer.class,user));
        assertDoesNotThrow(()->derivatives.open(user,new OrderRequest("FUTURE","LONG",3,"FUTIDX",5,null,null,null)));
    }

    @Test void sectorEtfExposureUsesItsOwnNavWhenTheOverallIndexFalls() {
        for (int i=0;i<50;i++) derivatives.open(user,new OrderRequest("ETF","BUY",100,"ETFRPG",null,null,null,null));
        jdbc.update("UPDATE stocks SET current_price=CASE stock_code WHEN 'TR' THEN 12000 ELSE 8000 END");
        marketAt(Instant.parse("2030-01-01T10:00:03Z"));
        double index=derivatives.snapshot().indexValue();
        assertTrue(index<1000);
        int quantity=(int)Math.floor(4_000_000/(index*1000))+1;
        assertTrue(5_000_000+quantity*index*1000<=10_000_000,"Cost-based accounting would have admitted this order");
        assertThrows(IllegalArgumentException.class,()->derivatives.open(user,new OrderRequest("FUTURE","LONG",quantity,"FUTIDX",5,null,null,null)));
        assertEquals(50,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_positions WHERE user_id=?",Integer.class,user));
    }

    @Test void staleHoldingsBlockAdditionalExposureButNotClosingAnotherFreshProduct() {
        derivatives.open(user,new OrderRequest("ETF","BUY",10,"ETFRPG",null,null,null,null));
        long action=derivatives.open(user,new OrderRequest("ETF","BUY",10,"ETFACT",null,null,null,null)).account().positions().get(0).id();
        jdbc.update("UPDATE derivative_reference_prices SET observed_at=NULL WHERE stock_id=(SELECT id FROM stocks WHERE stock_code='TR')");
        long cash=jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,user);
        assertThrows(IllegalArgumentException.class,()->derivatives.open(user,new OrderRequest("ETF","BUY",1,"ETFACT",null,null,null,null)));
        assertEquals(cash,jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,user));
        assertDoesNotThrow(()->derivatives.close(user,action,5));
        assertDoesNotThrow(()->derivatives.open(user,new OrderRequest("ETF","SELL",5,"ETFACT",null,null,null,null)));
    }

    @Test void exposureAtTheLimitIsAllowedButOneMoreUnitIsRejected() {
        derivatives.open(user,new OrderRequest("FUTURE","LONG",10,"FUTIDX",5,null,null,null));
        assertThrows(IllegalArgumentException.class,()->derivatives.open(user,new OrderRequest("ETF","BUY",1,"ETFIDX",null,null,null,null)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_trades WHERE user_id=?",Integer.class,user));
    }

    @Test void anEmptyIndexCannotTradeWhileItsFirstListingsAreWarmingUp() {
        Instant now=Instant.parse("2030-01-01T10:00:00Z");
        jdbc.update("DELETE FROM derivative_basket_members");
        jdbc.update("UPDATE derivative_baskets SET cash_value=1000,initialized=TRUE");
        jdbc.update("UPDATE derivative_reference_prices SET observed_at=NULL");
        new DerivativeBaskets(jdbc).rebalance(now);
        var future=new OrderRequest("FUTURE","LONG",1,"FUTIDX",5,null,null,null);
        assertThrows(IllegalArgumentException.class,()->derivatives.open(user,future));
        marketAt(now.plusSeconds(3));
        assertDoesNotThrow(()->derivatives.open(user,future));
    }

    @Test void newListingsWarmUpWithoutBlockingExistingDerivativesAndRemainMembersWhenInvalidated() {
        moveIndex(1.1);
        Instant now=Instant.parse("2030-01-01T10:00:01Z");
        double before=derivatives.snapshot().indexValue();
        for(String[] listing:List.of(new String[]{"GKM","육성"},new String[]{"KR","레이싱"},
                new String[]{"MC","생존"},new String[]{"KD","레이싱"},new String[]{"MAPLE","RPG"}))
            addListing(listing[0],listing[1]);
        var baskets=new DerivativeBaskets(jdbc);
        baskets.rebalance(now);
        assertEquals(2,baskets.load().basket("ETFIDX").members().size());
        assertEquals(before,baskets.load().index(),1e-8);
        var future=new OrderRequest("FUTURE","LONG",1,"FUTIDX",5,null,null,null);
        long position=derivatives.open(user,future).account().positions().get(0).id();
        assertDoesNotThrow(()->derivatives.close(user,position,1));

        // A stale or future timestamp is not sufficient to admit a new listing.
        jdbc.update("UPDATE derivative_reference_prices SET observed_at=? WHERE stock_id=(SELECT id FROM stocks WHERE stock_code='KD')",
                Timestamp.from(now.minusSeconds(91)));
        jdbc.update("UPDATE derivative_reference_prices SET observed_at=? WHERE stock_id=(SELECT id FROM stocks WHERE stock_code='MAPLE')",
                Timestamp.from(now.plusSeconds(1)));
        baskets.rebalance(now);
        assertEquals(2,baskets.load().basket("ETFIDX").members().size());

        marketAt(now.plusSeconds(3));
        assertEquals(7,baskets.load().basket("ETFIDX").members().size());
        assertEquals(4,baskets.load().basket("ETFALT").members().size());
        assertEquals(2,baskets.load().basket("ETFRPG").members().size());
        assertEquals(before,baskets.load().index(),1e-8,"Admission must preserve the value of existing positions");
        assertDoesNotThrow(()->derivatives.open(user,future));

        long drift=jdbc.queryForObject("SELECT id FROM stocks WHERE stock_code='KD'",Long.class);
        new DerivativePriceOracle(jdbc).invalidate(drift,now);
        baskets.rebalance(now.plusSeconds(3));
        assertEquals(7,baskets.load().basket("ETFIDX").members().size(),"Invalidation must not remove an existing risk constituent");
        assertThrows(IllegalArgumentException.class,()->derivatives.open(user,future));
    }

    @Test void reconstitutionPreservesIndexEtfHoldingsAndRestartPrices() {
        moveIndex(1.1);
        var before=derivatives.snapshot();
        var opened=derivatives.open(user,new OrderRequest("ETF","BUY",10,"ETFRPG",null,null,null,null));
        long position=opened.account().positions().get(0).id();
        long holdingValue=opened.account().positions().get(0).value();
        addListing("NEW","RPG");
        Instant now=Instant.parse("2030-01-01T10:00:04Z");
        marketAt(now);
        var after=derivatives.snapshot();
        assertEquals(before.indexValue(),after.indexValue(),1e-8);
        assertEquals(before.indexPrevClose(),after.indexPrevClose(),1e-8);
        assertEquals(etf(before,"ETFRPG"),etf(after,"ETFRPG"),1e-8);
        for(int i=0;i<before.etfs().size();i++)assertEquals(before.etfs().get(i).prevClose(),after.etfs().get(i).prevClose(),1e-8);
        assertEquals(holdingValue,derivatives.account(user).positions().get(0).value());
        var restarted=new DerivativeService(jdbc,manager);restarted.setClock(Clock.fixed(now,ZoneOffset.UTC));
        for(int i=0;i<3;i++) {
            new DerivativeBaskets(jdbc).rebalance();
            assertEquals(after.indexValue(),restarted.snapshot().indexValue(),1e-8);
            assertEquals(etf(after,"ETFRPG"),etf(restarted.snapshot(),"ETFRPG"),1e-8);
        }
        // Changing the genre transfers a constituent without transferring wealth between funds.
        jdbc.update("UPDATE games g JOIN stocks s ON s.game_id=g.id SET g.genre='액션' WHERE s.stock_code='NEW'");
        new DerivativeBaskets(jdbc).rebalance();
        assertEquals(etf(after,"ETFRPG"),etf(derivatives.snapshot(),"ETFRPG"),1e-8);
        assertEquals(etf(after,"ETFACT"),etf(derivatives.snapshot(),"ETFACT"),1e-8);
        // The last constituent becomes a cash basket at its existing value.
        jdbc.update("INSERT INTO admin_stock_state(stock_id,state,reason) SELECT id,'DELISTED','test' FROM stocks WHERE stock_code='TR'");
        new DerivativeBaskets(jdbc).rebalance();
        assertEquals(etf(after,"ETFRPG"),etf(derivatives.snapshot(),"ETFRPG"),1e-8);
        assertEquals(after.indexValue(),derivatives.snapshot().indexValue(),1e-8);
        assertEquals(before.indexPrevClose(),derivatives.snapshot().indexPrevClose(),1e-8);
        long beforeClose=derivatives.account(user).cash();
        derivatives.close(user,position,10);
        assertEquals(holdingValue-DerivativePricing.fee(holdingValue,DerivativePricing.ETF_FEE_RATE),derivatives.account(user).cash()-beforeClose);
        // The composition-only adjustment expires when the market advances its trading day.
        jdbc.update("UPDATE stock_protection_state p JOIN stocks s ON s.id=p.stock_id SET p.day_reference=s.current_price");
        jdbc.update("UPDATE market_protection_state SET trading_date=DATE_ADD(trading_date,INTERVAL 1 DAY) WHERE id=1");
        var nextDay=derivatives.snapshot();
        assertEquals(nextDay.indexValue(),nextDay.indexPrevClose(),1e-8);
        for(var quote:nextDay.etfs())assertEquals(quote.price(),quote.prevClose(),1e-8);
    }

    @Test void rebasedBasketStillTracksSubsequentPaidPriceMoves() {
        moveIndex(1.1);addListing("NEW","RPG");
        marketAt(Instant.parse("2030-01-01T10:00:04Z"));
        double before=etf(derivatives.snapshot(),"ETFRPG");
        jdbc.update("UPDATE stocks SET current_price=ROUND(current_price*1.1) WHERE stock_code IN ('TR','NEW')");
        marketAt(Instant.parse("2030-01-01T10:00:07Z"));
        assertEquals(before*1.1,etf(derivatives.snapshot(),"ETFRPG"),1e-8);
    }

    @Test void cancellationRepricesUnchangedLastTradeAndInvalidatesInsufficientRemainingTape() {
        Instant now=Instant.parse("2030-01-01T10:00:00Z");
        long stock=jdbc.queryForObject("SELECT id FROM stocks WHERE stock_code='TR'",Long.class);
        jdbc.update("UPDATE bot_trade_seconds SET notional=11000*quantity WHERE stock_id=? AND bucket_at<?",stock,now.getEpochSecond()-35);
        long trade=humanReferenceTrade(stock,now.minusSeconds(10));
        var oracle=new DerivativePriceOracle(jdbc);oracle.refresh(now);
        assertEquals(11000,referencePrice(stock),1e-8);
        var timestamp=jdbc.queryForObject("SELECT observed_at FROM derivative_reference_prices WHERE stock_id=?",java.sql.Timestamp.class,stock);
        jdbc.update("UPDATE settlements SET status='CANCELLED' WHERE trade_id=?",trade);oracle.invalidate(stock,now.minusSeconds(10));
        assertThrows(IllegalArgumentException.class,()->derivatives.open(user,new OrderRequest("ETF","BUY",1,"ETFRPG",null,null,null,null)));
        oracle.refresh(now);
        assertEquals(10500,referencePrice(stock),1e-8);
        assertEquals(timestamp,jdbc.queryForObject("SELECT observed_at FROM derivative_reference_prices WHERE stock_id=?",java.sql.Timestamp.class,stock));
        oracle.refresh(now.plusSeconds(3));
        assertEquals(10000,referencePrice(stock),1e-8,"The rolling window also changes without a new last trade");
        long revision=jdbc.queryForObject("SELECT revision FROM derivative_reference_prices WHERE stock_id=?",Long.class,stock);
        oracle.refresh(now);
        assertEquals(revision,jdbc.queryForObject("SELECT revision FROM derivative_reference_prices WHERE stock_id=?",Long.class,stock),"A delayed worker cannot overwrite the newer computation");
        jdbc.update("DELETE FROM bot_trade_seconds WHERE stock_id=?",stock);oracle.invalidate(stock,now.minusSeconds(10));oracle.refresh(now.plusSeconds(6));
        assertNull(jdbc.queryForObject("SELECT observed_at FROM derivative_reference_prices WHERE stock_id=?",java.sql.Timestamp.class,stock));
    }

    @Test void cancelledPreExpiryObservationDefersSettlementWithoutACorrectedSample() {
        cancelledExpiry(false);
    }

    @Test void cancelledPreExpiryObservationIsReplacedByACorrectedSampleForSettlement() {
        cancelledExpiry(true);
    }

    private void cancelledExpiry(boolean observeCorrection) {
        Instant last=Instant.parse("2030-01-01T10:09:59Z"),expiry=last.plusSeconds(1);
        marketAt(last.minusSeconds(59));
        long position=derivatives.open(user,new OrderRequest("OPTION","CALL",1,null,null,"10M",1000.0,null)).account().positions().get(0).id();
        marketAt(last);
        long stock=jdbc.queryForObject("SELECT id FROM stocks WHERE stock_code='GI'",Long.class);
        jdbc.update("UPDATE bot_trade_seconds SET notional=11000*quantity WHERE stock_id=? AND bucket_at<?",stock,last.getEpochSecond()-35);
        long trade=humanReferenceTrade(stock,last.minusSeconds(10));
        derivatives.refreshMarks();
        double badIndex=derivatives.snapshot().indexValue();
        assertTrue(badIndex>1090);
        jdbc.update("UPDATE settlements SET status='CANCELLED' WHERE trade_id=?",trade);
        var oracle=new DerivativePriceOracle(jdbc);oracle.invalidate(stock,last.minusSeconds(10));
        assertTrue(jdbc.queryForObject("SELECT invalidated FROM derivative_index_observations WHERE observed_at=?",Boolean.class,java.sql.Timestamp.from(last)));
        oracle.refresh(last.plusMillis(500));
        double corrected=derivatives.snapshot().indexValue();
        assertTrue(corrected<badIndex && corrected>1000);
        if(observeCorrection) {
            derivatives.setClock(Clock.fixed(last.plusMillis(500),ZoneOffset.UTC));
            derivatives.refreshMarks();
        }
        long cash=jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,user);
        derivatives.setClock(Clock.fixed(expiry.plusSeconds(1),ZoneOffset.UTC));
        derivatives.refreshMarks();
        if(observeCorrection) {
            assertEquals("EXPIRED",jdbc.queryForObject("SELECT status FROM derivative_positions WHERE id=?",String.class,position));
            assertEquals(corrected,new DerivativeIndexHistory(jdbc).fixing(expiry),1e-8);
            assertEquals(Math.round((corrected-1000)*1000),jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,user)-cash);
        } else {
            assertEquals("OPEN",jdbc.queryForObject("SELECT status FROM derivative_positions WHERE id=?",String.class,position));
            assertEquals(cash,jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,user));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_expiry_fixings",Integer.class));
            assertThrows(IllegalStateException.class,()->derivatives.close(user,position,1));
        }
    }

    @Test void cancellationExcludesHeldObservationsAndVolatilityButPreservesFixedExpiries() {
        Instant execution=Instant.parse("2030-01-01T10:00:00Z");
        var history=new DerivativeIndexHistory(jdbc);
        for(int i=0;i<60;i++)history.observe(1000,execution.minusSeconds((60-i)*3L));
        double originalVolatility=history.volatility(execution);
        history.observe(1100,execution.plusSeconds(60));
        history.observe(1100,execution.plusSeconds(140)); // held last-good reference
        assertEquals(1100,history.fixing(execution.plusSeconds(60)));
        history.invalidate(execution.plusMillis(900));
        assertEquals(originalVolatility,history.volatility(execution.plusSeconds(150)),1e-10);
        assertEquals(1100,history.fixing(execution.plusSeconds(60)),"An already fixed expiry is immutable");
        assertEquals(1000,history.fixing(execution.plusSeconds(61)),"A new fixing can only use an unaffected sample");
        assertThrows(IllegalStateException.class,()->history.fixing(execution.plusSeconds(141)));
    }

    private long humanReferenceTrade(long stock,Instant at) {
        jdbc.update("INSERT INTO users(username,password_hash,nickname,cash) VALUES('oracle-seller','TEST','oracle-seller',1000000)");
        long seller=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        jdbc.update("INSERT INTO orders(user_id,stock_id,side,order_type,price,quantity,remaining_quantity,status) VALUES(?,?,'BUY','LIMIT',12000,10,0,'FILLED')",user,stock);
        long buy=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        jdbc.update("INSERT INTO orders(user_id,stock_id,side,order_type,price,quantity,remaining_quantity,status) VALUES(?,?,'SELL','LIMIT',12000,10,0,'FILLED')",seller,stock);
        long sell=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        jdbc.update("INSERT INTO trades(stock_id,buy_order_id,sell_order_id,buyer_id,seller_id,maker_order_id,taker_order_id,aggressor_side,quantity,price,created_at) VALUES(?,?,?,?,?,?,?,'BUY',10,12000,?)",stock,buy,sell,user,seller,sell,buy,java.sql.Timestamp.from(at));
        long trade=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        jdbc.update("INSERT INTO settlements(trade_id,buyer_id,seller_id,stock_id,quantity,gross_amount,settlement_at,status) VALUES(?,?,?,?,10,120000,?,'SETTLED')",trade,user,seller,stock,java.sql.Timestamp.from(at));
        return trade;
    }

    private double referencePrice(long stock) {return jdbc.queryForObject("SELECT price FROM derivative_reference_prices WHERE stock_id=?",Double.class,stock);}
    @Test void emptySectorEtfKeepsItsCashValueWhenTheFirstConstituentJoins() {
        double before=etf(derivatives.snapshot(),"ETFFPS");
        derivatives.open(user,new OrderRequest("ETF","BUY",10,"ETFFPS",null,null,null,null));
        addListing("FIRSTFPS","FPS");
        jdbc.update("UPDATE stocks SET current_price=11000 WHERE stock_code='FIRSTFPS'");
        marketAt(Instant.parse("2030-01-01T10:00:04Z"));
        assertEquals(before,etf(derivatives.snapshot(),"ETFFPS"),1e-8);
        assertEquals(Math.round(before*10),derivatives.account(user).positions().get(0).value());
    }
    private static double etf(DerivativeSnapshot snapshot,String code) {return snapshot.etfs().stream().filter(e->e.code().equals(code)).findFirst().orElseThrow().price();}
    private void addListing(String code,String genre) {
        jdbc.update("INSERT INTO games(name,developer,genre) VALUES(?,'test',?)",code,genre);
        long game=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        jdbc.update("INSERT INTO stocks(game_id,stock_code,current_price,previous_price,total_volume) VALUES(?,?,10000,10000,0)",game,code);
        long stock=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        jdbc.update("INSERT INTO stock_protection_state(stock_id,day_reference,static_reference,dynamic_reference,last_trade_price) VALUES(?,10000,10000,10000,10000)",stock);
    }

    @Test void invalidEtfSideCannotMoveCashAndCashFlowReconcilesIncludingFees() throws Exception {
        long before=jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,user);
        assertThrows(IllegalArgumentException.class,()->derivatives.open(user,new OrderRequest("ETF","SELL_TYPO",1,"ETFIDX",null,null,null,null)));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->new ObjectMapper().readValue("{\"kind\":\"ETF\",\"side\":\"BUY\",\"quantity\":1.9}",OrderRequest.class));
        assertEquals(before,jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,user));
        var bought=derivatives.open(user,new OrderRequest("ETF","BUY",2,"ETFIDX",null,null,null,null));
        derivatives.close(user,bought.account().positions().get(0).id(),1);
        long actual=jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,user)-before;
        assertEquals(actual,jdbc.queryForObject("SELECT SUM(cash_flow) FROM derivative_trades WHERE user_id=?",Long.class,user));
    }
}
