package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** A synchronized Java/SQL clock verifies viewer-independent trading and recovery after suspension. */
@EnabledIfSystemProperty(named="market.test.jdbc",matches="jdbc:mysql://127\\.0\\.0\\.1:13367/.*")
class MarketIdleSimulationTest {
    private static final class TestClock extends Clock {
        long now=Instant.parse("2026-10-02T00:00:00Z").toEpochMilli();
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(instant(),zone);}
        public Instant instant(){return Instant.ofEpochMilli(now);}
        public long millis(){return now;}
    }
    private static final class CountedSource extends SingleConnectionDataSource {
        int acquisitions;
        CountedSource(String url){super(url,"root","",true);}
        @Override public Connection getConnection() throws SQLException {acquisitions++;return super.getConnection();}
    }
    private static void sqlClock(JdbcTemplate db,TestClock clock) {
        db.execute("SET timestamp="+String.format(Locale.ROOT,"%.3f",clock.now/1000.0));
    }
    @Test void marketTradesWithoutViewersAndSuspensionRefundsBeforeResuming() throws Exception {
        String schema="idle_test_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getProperty("market.test.jdbc")+"&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        try(var source=new CountedSource(url)) {
            JdbcTemplate db=new JdbcTemplate(source);db.execute("CREATE DATABASE "+schema);db.execute("USE "+schema);
            try {
                String ddl=Files.readString(Path.of("../database/schema.sql")).replaceAll("(?m)^--.*$","")
                        .replaceAll("(?is)CREATE DATABASE[^;]+;","").replaceAll("(?is)USE gamestock;","");
                for(String statement:ddl.split(";"))if(!statement.isBlank())db.execute(statement);
                var clock=new TestClock();long start=clock.now;sqlClock(db,clock);
                var protection=new TradingProtectionService(db);ReflectionTestUtils.setField(protection,"clock",clock);
                var fail=new AtomicBoolean();
                var market=new MarketService(event->{if(fail.get())throw new IllegalStateException("rollback wake");},db,new UserFeatureService(db),protection);
                ReflectionTestUtils.setField(market,"clock",clock);ReflectionTestUtils.setField(market,"configuredSeed","12345");
                ReflectionTestUtils.setField(market,"batchEnabled",true);ReflectionTestUtils.setField(market,"batchParticipants",6020);
                ReflectionTestUtils.setField(market,"compactLedger",true);ReflectionTestUtils.setField(market,"inlineMarketMaker",true);
                ReflectionTestUtils.setField(market,"adaptiveActivity",true);
                market.initializeData();market.maintainScheduledMarket();
                var tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(2);
                long human=db.queryForObject("SELECT id FROM users WHERE username='demo'",Long.class);
                long restingBid=PriceLimitPolicy.dailyBand("UMA",db.queryForObject(
                        "SELECT previous_price FROM stocks WHERE stock_code='UMA'",Long.class)).lowerPrice();
                long cash=totalCash(db),shares=db.queryForObject("SELECT SUM(quantity) FROM portfolios",Long.class);
                long initialIssuance=db.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM market_inventory_issuance",Long.class);
                tx.executeWithoutResult(s->market.order(new MarketModels.OrderRequest("UMA","BUY",2,"LIMIT",restingBid),human));
                long expired=db.queryForObject("SELECT MAX(id) FROM orders WHERE user_id=?",Long.class,human);
                db.update("UPDATE orders SET expires_at=? WHERE id=?",new Timestamp(start+30000),expired);
                int acquisitionsBefore=source.acquisitions,attempts=0;
                for(long elapsed=250;elapsed<=60000;elapsed+=250) {
                    clock.now=start+elapsed;sqlClock(db,clock);market.maintainScheduledMarket();
                    for(int i=0;i<4;i++) {
                        assertTrue(market.botWorkDue("batch:"+i),"No viewer is needed to advance the market");
                        int shard=i;tx.execute(s1->market.participantBatch(shard));attempts++;
                    }
                }
                assertTrue(source.acquisitions>acquisitionsBefore);
                assertEquals("CANCELLED",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,expired));
                long unattendedFills=totalFills(db);assertTrue(unattendedFills>0);
                assertEquals(960,attempts);
                assertEquals(100,market.participantCadenceMillis());assertEquals(300,market.currentTargetPerSymbol());

                // Simulate persisted orders left behind by a visitor: one expires, one remains GTC.
                tx.executeWithoutResult(s->market.order(new MarketModels.OrderRequest("UMA","BUY",2,"LIMIT",restingBid),human));
                long wakeExpired=db.queryForObject("SELECT MAX(id) FROM orders WHERE user_id=?",Long.class,human);
                long refund=db.queryForObject("SELECT reserved_cash FROM orders WHERE id=?",Long.class,wakeExpired);
                db.update("UPDATE orders SET expires_at=? WHERE id=?",new Timestamp(clock.now+1000),wakeExpired);
                tx.executeWithoutResult(s->market.order(new MarketModels.OrderRequest("UMA","BUY",1,"LIMIT",restingBid),human));
                long gtc=db.queryForObject("SELECT MAX(id) FROM orders WHERE user_id=?",Long.class,human);
                long beforeWakeCash=db.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,human);
                clock.now=start+2400000;sqlClock(db,clock);
                assertFalse(market.botWorkDue("batch:0"),"Overdue cleanup blocks participant admission after suspension");

                fail.set(true);assertThrows(IllegalStateException.class,market::recordHumanActivity);
                assertEquals(beforeWakeCash,db.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,human));
                assertFalse(market.botWorkDue("batch:0"),"rollback must not open admission");
                fail.set(false);long began=System.nanoTime();market.recordHumanActivity();double wakeMs=(System.nanoTime()-began)/1e6;
                assertEquals(beforeWakeCash+refund,db.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,human));
                assertEquals("CANCELLED",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,wakeExpired));
                assertEquals("OPEN",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,gtc));
                assertEquals(unattendedFills,totalFills(db),"recovery does maintenance, not historical matching");
                int readyBefore=source.acquisitions;
                for(int i=0;i<20;i++)market.recordHumanActivity();
                assertEquals(readyBefore,source.acquisitions,"subsequent visitors do not repeat wake maintenance");
                assertEquals(100,market.participantCadenceMillis());assertEquals(300,market.currentTargetPerSymbol());
                int firstFills=0;
                for(int i=0;i<4;i++) {
                    int shard=i;assertTrue(market.botWorkDue("batch:"+i));
                    firstFills+=tx.execute(s->market.participantBatch(shard)).stream().mapToInt(BotActivityEngine.Activity::fills).sum();
                }
                assertTrue(firstFills<1000,"wake must not spend idle time as accumulated activity credits");
                long fees=db.queryForObject("SELECT COALESCE(SUM(fees),0) FROM bot_ledger_totals",Long.class)
                        +db.queryForObject("SELECT COALESCE(SUM(buyer_fee+seller_fee),0) FROM trades",Long.class);
                assertEquals(cash,totalCash(db)+fees);
                long issuance=db.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM market_inventory_issuance",Long.class)-initialIssuance;
                assertEquals(shares+issuance,db.queryForObject("SELECT SUM(quantity) FROM portfolios",Long.class));
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM users WHERE cash<0",Integer.class));
                var report=Map.of("unattendedSeconds",60,"unattendedBatchAttempts",attempts,
                        "unattendedFills",unattendedFills,"successfulWakeMaintenanceMs",wakeMs,"firstActiveBatchFills",firstFills,"cashError",0,"shareError",0);
                Path out=Path.of("target/market-activity/unattended-market.json");Files.createDirectories(out.getParent());
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(),report);
                System.out.println("UNATTENDED_MARKET "+report);
            } finally {db.execute("DROP DATABASE "+schema);}
        }
    }
    private static long totalCash(JdbcTemplate db) {
        return db.queryForObject("SELECT (SELECT SUM(cash) FROM users)+(SELECT COALESCE(SUM(reserved_cash),0) FROM orders WHERE status='OPEN')",Long.class);
    }
    private static long totalFills(JdbcTemplate db) {
        return db.queryForObject("SELECT COALESCE(SUM(fills),0) FROM bot_ledger_totals",Long.class)+db.queryForObject("SELECT COUNT(*) FROM trades",Long.class);
    }
}
