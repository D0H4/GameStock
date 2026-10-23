package com.gamestock.backend.market;

import com.gamestock.backend.auth.AuthService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.gamestock.backend.market.DerivativeModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real transaction proxies and independent connections: successful responses must actually commit. */
@EnabledIfSystemProperty(named="market.test.jdbc",matches="jdbc:mysql://127\\.0\\.0\\.1:.*")
class DerivativeOrderSafetyIntegrationTest {
    private static final Instant NOW=Instant.parse("2030-01-01T10:00:00Z");
    private JdbcTemplate root,jdbc;
    private DriverManagerDataSource source;
    private DataSourceTransactionManager manager;
    private DerivativeService target,derivatives;
    private String schema;

    @BeforeEach void setup() throws Exception {
        String url=System.getProperty("market.test.jdbc");
        root=new JdbcTemplate(new DriverManagerDataSource(url,"root",""));
        schema="derivative_orders_"+UUID.randomUUID().toString().replace("-","");
        root.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        int slash=url.indexOf('/',"jdbc:mysql://".length()),query=url.indexOf('?');
        source=new DriverManagerDataSource(url.substring(0,slash+1)+schema+(query<0?"":url.substring(query)),"root","");
        jdbc=new JdbcTemplate(source);
        String ddl=Files.readString(Path.of("../database/schema.sql")).replaceAll("(?m)^--.*$","")
                .replaceAll("(?is)CREATE DATABASE[^;]+;","").replaceAll("(?is)USE gamestock;","");
        for(String statement:ddl.split(";"))if(!statement.isBlank())jdbc.execute(statement);
        DerivativeService.ensureTables(jdbc);BotLedgerJournal.ensureTables(jdbc);
        jdbc.update("INSERT INTO games(id,name,developer,genre) VALUES(1,'원신','test','오픈월드'),(2,'트릭컬','test','RPG')");
        jdbc.update("INSERT INTO stocks(id,game_id,stock_code,current_price,previous_price,total_volume) VALUES(1,1,'GI',10000,10000,0),(2,2,'TR',10000,10000,0)");
        jdbc.update("INSERT INTO stock_protection_state(stock_id,day_reference,static_reference,dynamic_reference,last_trade_price) VALUES(1,10000,10000,10000,10000),(2,10000,10000,10000,10000)");
        jdbc.update("INSERT INTO market_protection_state(id,trading_date) VALUES(1,?)",java.sql.Date.valueOf("2030-01-01"));
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname,cash) VALUES(1,'trader','TEST','trader',10000000)");
        manager=new DataSourceTransactionManager(source);
        target=new DerivativeService(jdbc,manager);derivatives=proxied(target);
        marketAt(NOW);
    }

    @AfterEach void cleanup() {if(root!=null&&schema!=null)root.execute("DROP DATABASE "+schema);}

    private DerivativeService proxied(DerivativeService service) {
        ProxyFactory factory=new ProxyFactory(service);
        factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        return (DerivativeService)factory.getProxy();
    }
    private void marketAt(Instant at) {target.setClock(Clock.fixed(at,ZoneOffset.UTC));DerivativeTapeFixture.seed(jdbc,at);}
    private long cash() {return jdbc.queryForObject("SELECT cash FROM users WHERE id=1",Long.class);}
    private OrderRequest etf(String side) {return new OrderRequest("ETF",side,1,"ETFRPG",null,null,null,null);}

    @Test void pendingExpiryCannotBreakAccountReadsOrRollBackOtherProductTrades() {
        Position option=derivatives.open(1,new OrderRequest("OPTION","CALL",1,null,null,"10M",1000.0,null)).account().positions().get(0);
        long etf=derivatives.open(1,etf("BUY")).account().positions().get(0).id();
        marketAt(NOW.plusSeconds(601));
        derivatives.refreshMarks(); // no valid observation existed before expiry
        Account account=assertDoesNotThrow(()->derivatives.account(1));
        Position pending=account.positions().stream().filter(p->p.id()==option.id()).findFirst().orElseThrow();
        assertEquals(option.markPrice(),pending.markPrice());assertEquals(option.value(),pending.value());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_expiry_fixings",Integer.class));
        long before=cash();
        OrderResult closed=derivatives.close(1,etf,1);
        assertEquals(closed.account().cash(),cash());assertTrue(cash()>before);
        assertEquals("CLOSE",jdbc.queryForObject("SELECT status FROM derivative_positions WHERE id=?",String.class,etf));
        assertDoesNotThrow(()->derivatives.open(1,etf("BUY")));
        long paidBefore=cash();
        assertThrows(IllegalStateException.class,()->derivatives.close(1,option.id(),1));
        assertEquals(paidBefore,cash());
        assertEquals("OPEN",jdbc.queryForObject("SELECT status FROM derivative_positions WHERE id=?",String.class,option.id()));

        // Recover a valid historical observation; GET still cannot fix a settlement price.
        new DerivativeIndexHistory(jdbc).observe(1100,NOW.plusSeconds(599));
        assertDoesNotThrow(()->derivatives.account(1));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_expiry_fixings",Integer.class));
        jdbc.update("UPDATE admin_runtime SET market_paused=TRUE WHERE id=1");
        derivatives.refreshMarks();derivatives.refreshMarks();
        assertEquals(paidBefore+100000,cash(),"Deterministic expiry still settles once while user trading is paused");
        assertEquals("EXPIRED",jdbc.queryForObject("SELECT status FROM derivative_positions WHERE id=?",String.class,option.id()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_trades WHERE action='EXPIRE'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT SUM(fee) FROM derivative_trades WHERE action='EXPIRE'",Long.class));
    }

    @Test void pausedMarketRejectsAllUserEntryAndExitRoutesUntilResumed() throws Exception {
        List<OrderRequest> entries=List.of(etf("BUY"),new OrderRequest("FUTURE","LONG",1,null,5,null,null,null),
                new OrderRequest("OPTION","CALL",1,null,null,"10M",1000.0,null));
        List<Long> positions=new ArrayList<>();
        for(var order:entries)positions.add(derivatives.open(1,order).account().positions().get(0).id());
        AuthService auth=mock(AuthService.class);
        when(auth.requireUser("Bearer test")).thenReturn(new AuthService.LoginUser(1,"trader",null,null,0,0,false,"USER"));
        var mvc=MockMvcBuilders.standaloneSetup(new DerivativeController(derivatives,auth)).build();
        jdbc.update("UPDATE admin_runtime SET market_paused=TRUE WHERE id=1");
        long cash=cash();int trades=jdbc.queryForObject("SELECT COUNT(*) FROM derivative_trades",Integer.class);
        for(String body:List.of(
                "{\"kind\":\"ETF\",\"side\":\"BUY\",\"quantity\":1,\"symbol\":\"ETFRPG\"}",
                "{\"kind\":\"ETF\",\"side\":\"SELL\",\"quantity\":1,\"symbol\":\"ETFRPG\"}",
                "{\"kind\":\"ETF\",\"side\":\"BUY\",\"quantity\":1,\"symbol\":\"ETFFPS\"}",
                "{\"kind\":\"FUTURE\",\"side\":\"SHORT\",\"quantity\":1,\"leverage\":5}",
                "{\"kind\":\"OPTION\",\"side\":\"PUT\",\"quantity\":1,\"cycleId\":\"10M\",\"strike\":1000}"))
            mvc.perform(post("/api/derivatives/positions").header("Authorization","Bearer test")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        for(long id:positions)mvc.perform(post("/api/derivatives/positions/"+id+"/close").header("Authorization","Bearer test")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/derivatives/account").header("Authorization","Bearer test")).andExpect(status().isOk());
        assertEquals(cash,cash());assertEquals(trades,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_trades",Integer.class));
        jdbc.update("UPDATE admin_runtime SET market_paused=FALSE WHERE id=1");
        for(long id:positions)assertDoesNotThrow(()->derivatives.close(1,id,1));
        assertDoesNotThrow(()->derivatives.open(1,etf("BUY")));
    }

    @Test void pauseWaitsForAdmittedOrderCommitAndRejectsTheNextOrder() throws Exception {
        CountDownLatch admitted=new CountDownLatch(1),resume=new CountDownLatch(1),pausing=new CountDownLatch(1);
        AtomicBoolean once=new AtomicBoolean();
        JdbcTemplate gated=new JdbcTemplate(source) {
            @Override public <T>T queryForObject(String sql,Class<T> type) {
                T result=super.queryForObject(sql,type);
                if(sql.contains("SELECT market_paused")&&once.compareAndSet(false,true)) {
                    admitted.countDown();await(resume);
                }
                return result;
            }
        };
        var target=new DerivativeService(gated,manager);target.setClock(Clock.fixed(NOW,ZoneOffset.UTC));
        var service=proxied(target);ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            Future<OrderResult> order=workers.submit(()->service.open(1,etf("BUY")));
            assertTrue(admitted.await(5,TimeUnit.SECONDS));
            Future<?> pause=workers.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                pausing.countDown();jdbc.update("UPDATE admin_runtime SET market_paused=TRUE WHERE id=1");
            }));
            assertTrue(pausing.await(5,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->pause.get(200,TimeUnit.MILLISECONDS));
            resume.countDown();order.get(5,TimeUnit.SECONDS);pause.get(5,TimeUnit.SECONDS);
            assertThrows(IllegalArgumentException.class,()->service.open(1,etf("BUY")));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_trades",Integer.class));
        } finally {resume.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test void oldRepeatableReadSnapshotCannotBypassACommittedPause() throws Exception {
        CountDownLatch snapshot=new CountDownLatch(1),resume=new CountDownLatch(1);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> order=worker.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                assertFalse(jdbc.queryForObject("SELECT market_paused FROM admin_runtime WHERE id=1",Boolean.class));
                snapshot.countDown();await(resume);derivatives.open(1,etf("BUY"));
            }));
            assertTrue(snapshot.await(5,TimeUnit.SECONDS));
            jdbc.update("UPDATE admin_runtime SET market_paused=TRUE WHERE id=1");
            resume.countDown();
            ExecutionException error=assertThrows(ExecutionException.class,()->order.get(5,TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class,error.getCause());
            assertEquals(10000000,cash());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_trades",Integer.class));
        } finally {resume.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }

    private static void await(CountDownLatch latch) {
        try {if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Test gate timed out");}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
    }
}
