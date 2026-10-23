package com.gamestock.backend.market;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="market.test.jdbc",matches="jdbc:mysql://127\\.0\\.0\\.1:.*")
class LegacyStockStartupIntegrationTest {
    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;
    private String schema;

    @BeforeEach void setup() throws Exception {
        source=new SingleConnectionDataSource(System.getProperty("market.test.jdbc"),"root","",true);
        jdbc=new JdbcTemplate(source);schema="legacy_startup_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        jdbc.execute("USE "+schema);jdbc.execute("SET time_zone='+00:00'");
        String ddl=Files.readString(Path.of("../database/schema.sql")).replaceAll("(?m)^--.*$","")
                .replaceAll("(?is)CREATE DATABASE[^;]+;","").replaceAll("(?is)USE gamestock;","");
        for(String statement:ddl.split(";"))if(!statement.isBlank())jdbc.execute(statement);
        jdbc.update("INSERT INTO games(id,name,developer,genre) VALUES(1,'보이드 러너','legacy','레이싱')");
        jdbc.update("INSERT INTO stocks(id,game_id,stock_code,current_price,previous_price,total_volume) VALUES(1,1,'VOID',10000,10000,10)");
        jdbc.update("INSERT INTO users(id,username,password_hash,nickname,cash) VALUES(1,'buyer','TEST','buyer',980000),(2,'seller','TEST','seller',1000000)");
        jdbc.update("INSERT INTO portfolios(user_id,stock_id,quantity,settled_quantity,average_price) VALUES(1,1,10,10,10000)");
        jdbc.update("INSERT INTO market_events(stock_id,event_type,title,description,impact) VALUES(1,'NEWS','구형 종목 뉴스','보존해야 하는 뉴스',1)");
        jdbc.update("INSERT INTO stock_price_history(stock_id,price) VALUES(1,10000)");
        jdbc.update("INSERT INTO user_watchlists(user_id,stock_id) VALUES(1,1)");
        jdbc.update("""
                INSERT INTO orders(id,user_id,stock_id,side,order_type,price,quantity,remaining_quantity,status,reserved_cash,reserved_quantity)
                VALUES(1,1,1,'BUY','LIMIT',10000,10,0,'FILLED',0,0),
                      (2,2,1,'SELL','LIMIT',10000,10,0,'FILLED',0,0),
                      (3,1,1,'BUY','LIMIT',10000,2,2,'OPEN',20000,0),
                      (4,1,1,'SELL','LIMIT',10000,2,2,'OPEN',0,2)
                """);
        jdbc.update("INSERT INTO trades(id,stock_id,buy_order_id,sell_order_id,buyer_id,seller_id,maker_order_id,taker_order_id,aggressor_side,quantity,price) VALUES(1,1,1,2,1,2,2,1,'BUY',10,10000)");
        jdbc.update("INSERT INTO settlements(trade_id,buyer_id,seller_id,stock_id,quantity,gross_amount,settlement_at,status) VALUES(1,1,2,1,10,100000,CURRENT_TIMESTAMP,'SETTLED')");
    }

    @AfterEach void cleanup() {
        if(jdbc!=null&&schema!=null)jdbc.execute("DROP DATABASE "+schema);
        if(source!=null)source.destroy();
    }

    private MarketService market(JdbcTemplate db) {
        var protection=new TradingProtectionService(db);protection.configureAdminRuntime(new AdminRuntimeControl(db));
        var market=new MarketService(event->{},db,new UserFeatureService(db),protection);
        market.configureAdminRuntime(new AdminRuntimeControl(db));
        ReflectionTestUtils.setField(market,"configuredSeed","12345");return market;
    }
    private void assertHistoryPreserved() {
        assertEquals("VOID",jdbc.queryForObject("SELECT stock_code FROM stocks WHERE id=1",String.class));
        assertEquals(10,jdbc.queryForObject("SELECT quantity FROM portfolios WHERE user_id=1 AND stock_id=1",Integer.class));
        assertEquals(10,jdbc.queryForObject("SELECT settled_quantity FROM portfolios WHERE user_id=1 AND stock_id=1",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM market_events WHERE stock_id=1",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM stock_price_history WHERE stock_id=1",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM user_watchlists WHERE stock_id=1",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM trades WHERE stock_id=1",Integer.class));
        assertEquals("SETTLED",jdbc.queryForObject("SELECT status FROM settlements WHERE trade_id=1",String.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE id IN (1,2) AND status='FILLED'",Integer.class));
    }

    @Test void upgradeRetiresLegacyListingAndRefundsOnceWithoutDeletingFinancialHistory() {
        var market=market(jdbc);
        assertDoesNotThrow(market::initializeData);assertHistoryPreserved();
        assertEquals("DELISTED",jdbc.queryForObject("SELECT state FROM admin_stock_state WHERE stock_id=1",String.class));
        assertEquals(1000000,jdbc.queryForObject("SELECT cash FROM users WHERE id=1",Long.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE id IN (3,4) AND status='CANCELLED' AND reserved_cash=0 AND reserved_quantity=0 AND remaining_quantity=0",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM derivative_basket_members WHERE stock_id=1",Integer.class));
        assertEquals(1000,new DerivativeBaskets(jdbc).load().index(),1e-8);
        assertDoesNotThrow(market::initializeData);assertHistoryPreserved();
        assertEquals(1000000,jdbc.queryForObject("SELECT cash FROM users WHERE id=1",Long.class));
        jdbc.update("UPDATE admin_stock_state SET state='ACTIVE',reason='관리자 재개' WHERE stock_id=1");
        assertDoesNotThrow(market::initializeData);
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT state FROM admin_stock_state WHERE stock_id=1",String.class));
    }

    @Test void failedRetirementRollsBackRefundAndStateTogetherAndCanBeRetried() {
        AtomicBoolean retiring=new AtomicBoolean();
        JdbcTemplate failing=new JdbcTemplate(source) {
            @Override public int update(String sql,Object... args) {
                int result=super.update(sql,args);
                if(sql.contains("SELECT id,'DELISTED'"))retiring.set(true);
                if(retiring.get()&&sql.startsWith("UPDATE orders SET status='CANCELLED'"))
                    throw new IllegalStateException("Injected failure after refund");
                return result;
            }
        };
        assertThrows(IllegalStateException.class,()->market(failing).initializeData());assertHistoryPreserved();
        assertEquals(980000,jdbc.queryForObject("SELECT cash FROM users WHERE id=1",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM admin_stock_state WHERE stock_id=1",Integer.class));
        assertEquals(20000,jdbc.queryForObject("SELECT reserved_cash FROM orders WHERE id=3",Long.class));
        assertEquals("OPEN",jdbc.queryForObject("SELECT status FROM orders WHERE id=3",String.class));
        assertDoesNotThrow(()->market(jdbc).initializeData());assertHistoryPreserved();
        assertEquals(1000000,jdbc.queryForObject("SELECT cash FROM users WHERE id=1",Long.class));
    }
}
