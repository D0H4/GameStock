package com.gamestock.backend.market;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="market.test.jdbc",matches="jdbc:mysql://127\\.0\\.0\\.1:13367/.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataRetentionIntegrationTest {
    private static final long HOUR=3_600_000, DAY=24*HOUR;
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private MarketService market;
    private DataRetentionService retention;
    private String database;
    private long stock,botA,botB,human;
    // Hour-aligned so each seeded window falls inside one compaction slice.
    private final long now=System.currentTimeMillis()/HOUR*HOUR+30*60_000;

    @BeforeAll void createDisposableDatabase() throws Exception {
        dataSource=new SingleConnectionDataSource(System.getProperty("market.test.jdbc")+"&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true","root","",true);
        jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("SET time_zone = '+00:00'");
        database="retention_test_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        jdbc.execute("USE "+database);
        String schema=Files.readString(Path.of("../database/schema.sql"));
        schema=schema.replaceAll("(?m)^--.*$","").replaceAll("(?is)CREATE DATABASE[^;]+;","").replaceAll("(?is)USE gamestock;","");
        for(String statement:schema.split(";")) if(!statement.isBlank()) jdbc.execute(statement);
        market=new MarketService(event->{},jdbc,new UserFeatureService(jdbc),new TradingProtectionService(jdbc));
        ReflectionTestUtils.setField(market,"configuredSeed","12345");
        market.initializeData();
        retention=new DataRetentionService(jdbc,market);
        ReflectionTestUtils.setField(retention,"clock",Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC));
        jdbc.update("INSERT INTO users(username,password_hash,nickname,cash) VALUES ('retention_a','TRADER','a',1000000),('retention_b','TRADER','b',1000000),('retention_h','TEST','h',1000000)");
        botA=id("retention_a");botB=id("retention_b");human=id("retention_h");
        stock=jdbc.queryForObject("SELECT id FROM stocks WHERE stock_code='UMA'",Long.class);
        jdbc.update("DELETE FROM stock_price_history WHERE stock_id=?",stock);
    }
    @AfterAll void dispose(){ if(jdbc!=null&&database!=null) jdbc.execute("DROP DATABASE "+database); if(dataSource!=null) dataSource.destroy(); }
    private long id(String username){return jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,username);}
    private void point(long at,long price){jdbc.update("INSERT INTO stock_price_history(stock_id,price,recorded_at) VALUES (?,?,?)",stock,price,new Timestamp(at));}
    private List<Long> prices(long from,long to){
        return jdbc.queryForList("SELECT price FROM stock_price_history WHERE stock_id=? AND recorded_at>=? AND recorded_at<? ORDER BY recorded_at,id",Long.class,stock,new Timestamp(from),new Timestamp(to));
    }
    private long order(long user,String status,long at){
        jdbc.update("INSERT INTO orders(user_id,stock_id,side,order_type,price,quantity,remaining_quantity,status,created_at) VALUES (?,?,'BUY','LIMIT',10000,1,0,?,?)",user,stock,status,new Timestamp(at));
        return jdbc.queryForObject("SELECT MAX(id) FROM orders",Long.class);
    }
    private long trade(long buyer,long seller,long at){
        long buy=order(buyer,"FILLED",at),sell=order(seller,"FILLED",at);
        jdbc.update("INSERT INTO trades(stock_id,buy_order_id,sell_order_id,buyer_id,seller_id,maker_order_id,taker_order_id,aggressor_side,quantity,price,created_at) VALUES (?,?,?,?,?,?,?,'BUY',1,10000,?)",
                stock,buy,sell,buyer,seller,sell,buy,new Timestamp(at));
        long trade=jdbc.queryForObject("SELECT MAX(id) FROM trades",Long.class);
        jdbc.update("INSERT INTO settlements(trade_id,buyer_id,seller_id,stock_id,quantity,gross_amount,settlement_at,status,settled_at) VALUES (?,?,?,?,1,10000,?,'SETTLED',?)",
                trade,buyer,seller,stock,new Timestamp(at),new Timestamp(at));
        return trade;
    }
    private int count(String sql,Object... args){return jdbc.queryForObject(sql,Integer.class,args);}

    @Test void thinsOldPriceHistoryWithoutChangingBucketOhlcAndDropsOnlyBotLedgers() {
        long expired=now-500*DAY, hourly=now/HOUR*HOUR-10*DAY, minutely=now-3*DAY, raw=now-DAY;
        point(expired,9000);
        Random random=new Random(7);
        for(int i=0;i<120;i++)point(hourly+i*20_000,9000+random.nextInt(2000));
        for(int i=0;i<60;i++)point(minutely+i*2_000,9000+random.nextInt(2000));
        for(int i=0;i<20;i++)point(raw+i*1_000,9000+random.nextInt(2000));
        List<Long> hourBefore=prices(hourly,hourly+HOUR),minuteBefore=prices(minutely,minutely+2*60_000),rawBefore=prices(raw,raw+60_000);

        long old=now-40*DAY;
        // Ids follow time, as in production: the scans stop at the first row inside the retention window.
        long projectedBot=trade(botA,botB,old),unprojectedBot=trade(botA,botB,old),humanTrade=trade(human,botB,old);
        long finishedBotOrder=order(botA,"CANCELLED",old),openBotOrder=order(botA,"OPEN",old);
        long recentBot=trade(botA,botB,now-DAY);
        jdbc.update("INSERT INTO lp_cash_projection(stock_id,last_id,cash) VALUES (?,?,0) ON DUPLICATE KEY UPDATE last_id=VALUES(last_id)",stock,projectedBot);
        jdbc.update("UPDATE market_metric_cursors SET last_id=? WHERE stock_id=?",recentBot,stock);
        jdbc.update("INSERT INTO trading_restriction_events(stock_id,kind,reference_price,trigger_price,started_at,ends_at) VALUES (?,'STATIC_VI',1,1,?,?),(?,'STATIC_VI',1,1,?,?)",
                stock,new Timestamp(now-100*DAY),new Timestamp(now-100*DAY),stock,new Timestamp(now-10*DAY),new Timestamp(now-10*DAY));

        for(int pass=0;pass<20&&retention.prune()>0;pass++);

        assertEquals(0,count("SELECT COUNT(*) FROM stock_price_history WHERE stock_id=? AND recorded_at<?",stock,new Timestamp(now-400*DAY)));
        assertOhlc(hourBefore,prices(hourly,hourly+HOUR),4);
        assertOhlc(minuteBefore.subList(0,30),prices(minutely,minutely+60_000),4);
        assertOhlc(minuteBefore.subList(30,60),prices(minutely+60_000,minutely+2*60_000),4);
        assertEquals(rawBefore,prices(raw,raw+60_000),"Points younger than two days stay as recorded");

        assertEquals(0,count("SELECT COUNT(*) FROM trades WHERE id=?",projectedBot));
        assertEquals(0,count("SELECT COUNT(*) FROM settlements WHERE trade_id=?",projectedBot));
        assertEquals(1,count("SELECT COUNT(*) FROM trades WHERE id=?",unprojectedBot),"The LP projection has not consumed it yet");
        assertEquals(1,count("SELECT COUNT(*) FROM trades WHERE id=?",humanTrade),"A person's trade is never purged");
        assertEquals(1,count("SELECT COUNT(*) FROM trades WHERE id=?",recentBot));
        assertEquals(0,count("SELECT COUNT(*) FROM orders WHERE id=?",finishedBotOrder));
        assertEquals(1,count("SELECT COUNT(*) FROM orders WHERE id=?",openBotOrder),"Resting orders stay in the book");
        assertEquals(2,count("SELECT COUNT(*) FROM orders o JOIN trades t ON t.buy_order_id=o.id OR t.sell_order_id=o.id WHERE t.id=?",humanTrade));
        assertEquals(1,count("SELECT COUNT(*) FROM trading_restriction_events WHERE stock_id=? AND kind='STATIC_VI'",stock));
    }

    private static void assertOhlc(List<Long> before,List<Long> after,int maximum){
        assertTrue(after.size()<=maximum,"At most first, last, high and low remain: "+after);
        assertEquals(before.get(0),after.get(0));
        assertEquals(before.get(before.size()-1),after.get(after.size()-1));
        assertEquals(Collections.max(before),Collections.max(after));
        assertEquals(Collections.min(before),Collections.min(after));
    }
}
