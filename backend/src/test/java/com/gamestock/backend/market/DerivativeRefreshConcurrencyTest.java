package com.gamestock.backend.market;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static com.gamestock.backend.market.DerivativeModels.*;

/** Exercise the whole refresh transaction, including its repeatable-read snapshot after CAS. */
@EnabledIfSystemProperty(named="market.test.jdbc",matches="jdbc:mysql://127\\.0\\.0\\.1:.*")
class DerivativeRefreshConcurrencyTest {
    private JdbcTemplate root,jdbc;
    private DriverManagerDataSource source;
    private String schema;
    private long stock,position;
    private static final Instant NOW=Instant.parse("2030-01-01T10:00:00Z");

    @BeforeEach void setup() throws Exception {
        String url=System.getProperty("market.test.jdbc");
        root=new JdbcTemplate(new DriverManagerDataSource(url,"root",""));
        schema="derivative_refresh_"+UUID.randomUUID().toString().replace("-","");
        root.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        int slash=url.indexOf('/',"jdbc:mysql://".length()),query=url.indexOf('?');
        source=new DriverManagerDataSource(url.substring(0,slash+1)+schema+(query<0?"":url.substring(query)),"root","");
        jdbc=new JdbcTemplate(source);
        String ddl=Files.readString(Path.of("../database/schema.sql")).replaceAll("(?m)^--.*$","")
                .replaceAll("(?is)CREATE DATABASE[^;]+;","").replaceAll("(?is)USE gamestock;","");
        for(String statement:ddl.split(";"))if(!statement.isBlank())jdbc.execute(statement);
        DerivativeService.ensureTables(jdbc);BotLedgerJournal.ensureTables(jdbc);
        jdbc.update("INSERT INTO games(id,name,developer,genre) VALUES(1,'원신','test','오픈월드')");
        jdbc.update("INSERT INTO stocks(id,game_id,stock_code,current_price,previous_price,total_volume) VALUES(1,1,'GI',9000,10000,0)");
        stock=1;
        jdbc.update("INSERT INTO stock_protection_state(stock_id,day_reference,static_reference,dynamic_reference,last_trade_price) VALUES(1,10000,10000,10000,9000)");
        jdbc.update("INSERT INTO market_protection_state(id,trading_date) VALUES(1,?)",java.sql.Date.valueOf("2030-01-01"));
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname,cash) VALUES(1,'trader','TEST','trader',1000000)");
        DerivativeTapeFixture.seed(jdbc,NOW.minusSeconds(3));
        var manager=new DataSourceTransactionManager(source);
        var service=new DerivativeService(jdbc,manager);service.setClock(Clock.fixed(NOW.minusSeconds(3),ZoneOffset.UTC));
        position=new TransactionTemplate(manager).execute(status->service.open(1,
                new OrderRequest("ETF","BUY",1,"ETFIDX",null,null,null,null)).account().positions().get(0).id());
        assertEquals(900,jdbc.queryForObject("SELECT mark_price FROM derivative_positions WHERE id=?",Double.class,position));
        jdbc.update("UPDATE stocks SET current_price=10000 WHERE id=1");
        DerivativeTapeFixture.seed(jdbc,NOW);
    }

    @AfterEach void cleanup() {if(root!=null&&schema!=null)root.execute("DROP DATABASE "+schema);}

    @Test void committedInvalidationCannotBecomeAnObservationOrMarkThroughAnOlderSnapshot() throws Exception {
        CountDownLatch read=new CountDownLatch(1),resume=new CountDownLatch(1);
        AtomicBoolean once=new AtomicBoolean();
        JdbcTemplate paused=new JdbcTemplate(source) {
            @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
                List<T> result=super.query(sql,mapper,args);
                if(sql.contains("UNION ALL")&&once.compareAndSet(false,true)) {
                    read.countDown();
                    try {if(!resume.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Refresh was not resumed");}
                    catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                }
                return result;
            }
        };
        var service=new DerivativeService(paused,new DataSourceTransactionManager(source));
        service.setClock(Clock.fixed(NOW,ZoneOffset.UTC));
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> pending=worker.submit(service::refreshMarks);
            assertTrue(read.await(5,TimeUnit.SECONDS));
            new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->{
                jdbc.update("DELETE FROM bot_trade_seconds WHERE stock_id=?",stock);
                new DerivativePriceOracle(jdbc).invalidate(stock,NOW.minusSeconds(10));
            });
            resume.countDown();pending.get(5,TimeUnit.SECONDS);
            assertNull(jdbc.queryForObject("SELECT observed_at FROM derivative_reference_prices WHERE stock_id=?",Timestamp.class,stock));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_index_observations WHERE invalidated=FALSE AND settleable=TRUE",Integer.class));
            assertEquals(900,jdbc.queryForObject("SELECT mark_price FROM derivative_positions WHERE id=?",Double.class,position));
            assertThrows(IllegalArgumentException.class,()->service.close(1,position,1));

            // A subsequent valid tape must recover normally, including the position mark.
            DerivativeTapeFixture.seed(jdbc,NOW.plusSeconds(3));
            service.setClock(Clock.fixed(NOW.plusSeconds(3),ZoneOffset.UTC));service.refreshMarks();
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_index_observations WHERE invalidated=FALSE AND settleable=TRUE",Integer.class));
            assertEquals(1000,jdbc.queryForObject("SELECT mark_price FROM derivative_positions WHERE id=?",Double.class,position));
        } finally {resume.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test void expirySelectionIgnoresCancelledSamplesEvenWhenItsTransactionHasAnOlderSnapshot() throws Exception {
        var history=new DerivativeIndexHistory(jdbc);
        history.observe(1000,NOW.minusSeconds(20));history.observe(1100,NOW.minusSeconds(3));
        CountDownLatch read=new CountDownLatch(1),resume=new CountDownLatch(1);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<Double> fixing=worker.submit(()->new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->{
                assertEquals(1100,jdbc.queryForObject("SELECT index_value FROM derivative_index_observations ORDER BY observed_at DESC LIMIT 1",Double.class));
                read.countDown();
                try {if(!resume.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Fixing was not resumed");}
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                return history.fixing(NOW);
            }));
            assertTrue(read.await(5,TimeUnit.SECONDS));
            new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->
                    new DerivativePriceOracle(jdbc).invalidate(stock,NOW.minusSeconds(10)));
            resume.countDown();assertEquals(1000,fixing.get(5,TimeUnit.SECONDS));
            assertEquals(1000,history.fixing(NOW));
        } finally {resume.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void olderRefreshCannotOverwriteNewerMarksEvenAtTheSameClockTime(boolean sameTime) throws Exception {
        MutableClock clock=new MutableClock(NOW.plusSeconds(35));
        tape(clock.instant(),12000);
        CountDownLatch read=new CountDownLatch(1),resume=new CountDownLatch(1);
        var old=service(pausedRows(read,resume),clock);
        var current=service(jdbc,clock);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> pending=worker.submit(old::refreshMarks);await(read);
            long oldVersion=new DerivativeBaskets(jdbc).load().version();
            if(!sameTime)clock.now=NOW.plusSeconds(70);
            tape(clock.instant(),10000);current.refreshMarks();
            var latest=jdbc.queryForMap("SELECT mark_price,mark_value,mark_version FROM derivative_positions WHERE id=?",position);
            assertEquals(1000L,latest.get("mark_value"));
            assertTrue(((Number)latest.get("mark_version")).longValue()>oldVersion);
            resume.countDown();pending.get(5,TimeUnit.SECONDS);
            assertEquals(latest,jdbc.queryForMap("SELECT mark_price,mark_value,mark_version FROM derivative_positions WHERE id=?",position));
            assertEquals(1000,AccountValuation.derivatives(jdbc,1));
            assertEquals(1000,transaction().execute(status->current.account(1)).positionValue());
        } finally {resume.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test void cachedPricesCannotRevalueNewEntriesOrPartialClosesInTheSameGeneration() throws Exception {
        CountDownLatch read=new CountDownLatch(1),resume=new CountDownLatch(1);
        AtomicBoolean once=new AtomicBoolean();
        JdbcTemplate paused=new JdbcTemplate(source) {
            @Override public <T>T queryForObject(String sql,Class<T> type) {
                // Pause before the position watermark: newly opened positions will be scanned too.
                if(sql.contains("MAX(id)")&&sql.contains("derivative_positions")&&once.compareAndSet(false,true)) {
                    read.countDown();await(resume);
                }
                return super.queryForObject(sql,type);
            }
        };
        MutableClock clock=new MutableClock(NOW);
        var old=service(paused,clock);var current=service(jdbc,clock);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> pending=worker.submit(old::refreshMarks);await(read);
            // Only the raw execution price changes; the scheduled valuation version stays the same.
            jdbc.update("UPDATE stocks SET current_price=12000");
            long opened=transaction().execute(status->current.open(1,new OrderRequest("ETF","BUY",2,"ETFIDX",null,null,null,null))).account().positions().get(0).id();
            long partial=transaction().execute(status->current.open(1,new OrderRequest("ETF","BUY",2,"ETFIDX",null,null,null,null))).account().positions().get(0).id();
            jdbc.update("UPDATE stocks SET current_price=8000");
            transaction().execute(status->current.close(1,partial,1));
            long cash=jdbc.queryForObject("SELECT cash FROM users WHERE id=1",Long.class);
            assertEquals(2400,jdbc.queryForObject("SELECT mark_value FROM derivative_positions WHERE id=?",Long.class,opened));
            assertEquals(800,jdbc.queryForObject("SELECT mark_value FROM derivative_positions WHERE id=?",Long.class,partial));
            resume.countDown();pending.get(5,TimeUnit.SECONDS);
            assertEquals(2400,jdbc.queryForObject("SELECT mark_value FROM derivative_positions WHERE id=?",Long.class,opened));
            assertEquals(800,jdbc.queryForObject("SELECT mark_value FROM derivative_positions WHERE id=?",Long.class,partial));
            assertEquals(cash,jdbc.queryForObject("SELECT cash FROM users WHERE id=1",Long.class));
            clock.now=NOW.plusSeconds(3);tape(clock.instant(),8000);current.refreshMarks();
            assertEquals(1600,jdbc.queryForObject("SELECT mark_value FROM derivative_positions WHERE id=?",Long.class,opened));
            assertEquals(800,jdbc.queryForObject("SELECT mark_value FROM derivative_positions WHERE id=?",Long.class,partial));
        } finally {resume.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test void delayedRefreshCannotPersistAQuoteThatBecameStaleWhileWaiting() throws Exception {
        MutableClock clock=new MutableClock(NOW);
        CountDownLatch read=new CountDownLatch(1),resume=new CountDownLatch(1);
        var delayed=service(pausedRows(read,resume),clock);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> pending=worker.submit(delayed::refreshMarks);await(read);
            clock.now=NOW.plusSeconds(100);
            resume.countDown();pending.get(5,TimeUnit.SECONDS);
            assertEquals(900,jdbc.queryForObject("SELECT mark_value FROM derivative_positions WHERE id=?",Long.class,position));
            tape(clock.instant(),10000);service(jdbc,clock).refreshMarks();
            assertEquals(1000,jdbc.queryForObject("SELECT mark_value FROM derivative_positions WHERE id=?",Long.class,position));
        } finally {resume.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @ParameterizedTest @ValueSource(strings={"recovered","invalidated","distressed"})
    void liquidationUsesThePriceCommittedWhileWaitingForTheAccountLock(String outcome) throws Exception {
        MutableClock clock=new MutableClock(NOW);
        var normal=service(jdbc,clock);
        long future=transaction().execute(status->normal.open(1,new OrderRequest("FUTURE","LONG",1,"FUTIDX",5,null,null,null))).account().positions().get(0).id();
        long cash=jdbc.queryForObject("SELECT cash FROM users WHERE id=1",Long.class);
        clock.now=NOW.plusSeconds(35);tape(clock.instant(),8100); // 10,000 equity, below 20,000 maintenance
        CountDownLatch locking=new CountDownLatch(1);
        JdbcTemplate waiting=new JdbcTemplate(source) {
            @Override public <T>T queryForObject(String sql,Class<T> type,Object... args) {
                if(sql.equals("SELECT id FROM users WHERE id = ? FOR UPDATE"))locking.countDown();
                return super.queryForObject(sql,type,args);
            }
        };
        var delayed=service(waiting,clock);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> pending=transaction().execute(status->{
                jdbc.queryForObject("SELECT id FROM users WHERE id=1 FOR UPDATE",Long.class);
                Future<?> work=worker.submit(delayed::refreshMarks);await(locking);
                assertFalse(work.isDone(),"The account lock must really be held by this connection");
                clock.now=NOW.plusSeconds(70);
                if("invalidated".equals(outcome)) {
                    jdbc.update("DELETE FROM bot_trade_seconds WHERE stock_id=?",stock);
                    new DerivativePriceOracle(jdbc).invalidate(stock,NOW.plusSeconds(25));
                } else tape(clock.instant(),"recovered".equals(outcome)?10000:8150);
                return work;
            });
            pending.get(5,TimeUnit.SECONDS);
            if("distressed".equals(outcome)) {
                assertEquals("LIQUIDATED",jdbc.queryForObject("SELECT status FROM derivative_positions WHERE id=?",String.class,future));
                assertEquals(815,jdbc.queryForObject("SELECT mark_price FROM derivative_positions WHERE id=?",Double.class,future),1e-8);
                assertEquals(cash+15000,jdbc.queryForObject("SELECT cash FROM users WHERE id=1",Long.class));
                normal.refreshMarks();
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_trades WHERE position_id=? AND action='LIQUIDATE'",Integer.class,future));
                assertEquals(cash+15000,jdbc.queryForObject("SELECT cash FROM users WHERE id=1",Long.class));
            } else {
                assertEquals("OPEN",jdbc.queryForObject("SELECT status FROM derivative_positions WHERE id=?",String.class,future));
                assertEquals(cash,jdbc.queryForObject("SELECT cash FROM users WHERE id=1",Long.class));
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_trades WHERE position_id=? AND action='LIQUIDATE'",Integer.class,future));
                if("recovered".equals(outcome))assertEquals(200000,transaction().execute(status->normal.account(1)).positions().stream().filter(p->p.id()==future).findFirst().orElseThrow().value());
            }
        } finally {worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test void legacyValuationColumnsMigrateWithoutResettingMarksOrVersionsOnRestart() {
        jdbc.execute("ALTER TABLE derivative_positions DROP COLUMN mark_version");
        jdbc.execute("ALTER TABLE derivative_basket_lock DROP COLUMN valuation_version");
        DerivativeService.ensureTables(jdbc);
        assertEquals(900,jdbc.queryForObject("SELECT mark_value FROM derivative_positions WHERE id=?",Long.class,position));
        var current=service(jdbc,Clock.fixed(NOW,ZoneOffset.UTC));current.refreshMarks();
        var before=jdbc.queryForMap("SELECT mark_price,mark_value,mark_version FROM derivative_positions WHERE id=?",position);
        long version=new DerivativeBaskets(jdbc).load().version();
        assertTrue(version>0);assertEquals(1000L,before.get("mark_value"));
        DerivativeService.ensureTables(jdbc);
        assertEquals(version,new DerivativeBaskets(jdbc).load().version());
        assertEquals(before,jdbc.queryForMap("SELECT mark_price,mark_value,mark_version FROM derivative_positions WHERE id=?",position));
    }

    private TransactionTemplate transaction() {return new TransactionTemplate(new DataSourceTransactionManager(source));}
    private DerivativeService service(JdbcTemplate db,Clock clock) {
        var service=new DerivativeService(db,new DataSourceTransactionManager(source));service.setClock(clock);return service;
    }
    private void tape(Instant at,long price) {jdbc.update("UPDATE stocks SET current_price=?",price);DerivativeTapeFixture.seed(jdbc,at);}
    private JdbcTemplate pausedRows(CountDownLatch read,CountDownLatch resume) {
        AtomicBoolean once=new AtomicBoolean();
        return new JdbcTemplate(source) {
            @Override public <T>List<T> query(String sql,RowMapper<T> mapper,Object... args) {
                var result=super.query(sql,mapper,args);
                if(sql.contains("AND id>? AND id<=?")&&once.compareAndSet(false,true)){read.countDown();await(resume);}
                return result;
            }
        };
    }
    private static void await(CountDownLatch latch) {
        try {assertTrue(latch.await(10,TimeUnit.SECONDS),"Timed out waiting for the competing transaction");}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
    }
    private static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant now){this.now=now;}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
}
