package com.gamestock.backend.market;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="market.test.jdbc",matches="jdbc:mysql://127\\.0\\.0\\.1:.*")
class DerivativeOracleConcurrencyTest {
    private JdbcTemplate root, jdbc;
    private DriverManagerDataSource source;
    private String schema;
    private static final Instant NOW=Instant.parse("2030-01-01T10:00:00Z");

    @BeforeEach void setup() {
        String url=System.getProperty("market.test.jdbc");
        root=new JdbcTemplate(new DriverManagerDataSource(url,"root",""));
        schema="oracle_review_"+UUID.randomUUID().toString().replace("-","");
        root.execute("CREATE DATABASE "+schema);
        int slash=url.indexOf('/',"jdbc:mysql://".length()),query=url.indexOf('?');
        source=new DriverManagerDataSource(url.substring(0,slash+1)+schema+(query<0?"":url.substring(query)),"root","");
        jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE stocks(id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE derivative_index_bases(stock_id BIGINT PRIMARY KEY,reference_price BIGINT)");
        jdbc.execute("CREATE TABLE derivative_reference_prices(stock_id BIGINT PRIMARY KEY,price DOUBLE NOT NULL,observed_at TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE trades(id BIGINT PRIMARY KEY,stock_id BIGINT,created_at TIMESTAMP(3),price BIGINT,quantity INT)");
        jdbc.execute("CREATE TABLE settlements(trade_id BIGINT,status VARCHAR(12))");
        jdbc.execute("CREATE TABLE bot_trade_seconds(stock_id BIGINT,bucket_at BIGINT,quantity BIGINT,notional BIGINT)");
        jdbc.update("INSERT INTO stocks VALUES(1)");jdbc.update("INSERT INTO derivative_index_bases VALUES(1,10000)");
        jdbc.update("INSERT INTO derivative_reference_prices VALUES(1,10000,?)",Timestamp.from(NOW.minusSeconds(6)));
        DerivativePriceOracle.ensureTables(jdbc);
        jdbc.execute("CREATE TABLE derivative_index_observations(observed_at TIMESTAMP(3) PRIMARY KEY,index_value DOUBLE NOT NULL)");
        DerivativeIndexHistory.ensureTables(jdbc);
        List<Object[]> rows=new ArrayList<>();
        for(long second=NOW.getEpochSecond()-65;second<NOW.getEpochSecond()-5;second++)rows.add(new Object[]{1,second,10,100000});
        jdbc.batchUpdate("INSERT INTO bot_trade_seconds VALUES(?,?,?,?)",rows);
    }

    @AfterEach void cleanup() {if(root!=null&&schema!=null)root.execute("DROP DATABASE "+schema);}

    @Test void legacyReferenceUpgradePreservesPriceAndIsRepeatable() {
        DerivativePriceOracle.ensureTables(jdbc);
        new DerivativeIndexHistory(jdbc).observe(1000,NOW);
        DerivativeIndexHistory.ensureTables(jdbc);
        assertFalse(jdbc.queryForObject("SELECT invalidated FROM derivative_index_observations",Boolean.class));
        assertEquals(1000,new DerivativeIndexHistory(jdbc).fixing(NOW));
        assertEquals(10000,jdbc.queryForObject("SELECT price FROM derivative_reference_prices",Double.class));
        assertEquals(Timestamp.from(NOW.minusSeconds(6)),jdbc.queryForObject("SELECT observed_at FROM derivative_reference_prices",Timestamp.class));
        assertEquals(0,jdbc.queryForObject("SELECT revision FROM derivative_reference_prices",Long.class));
        new DerivativePriceOracle(jdbc).refresh(NOW);
        assertEquals(Timestamp.from(NOW),jdbc.queryForObject("SELECT computed_at FROM derivative_reference_prices",Timestamp.class));
    }

    @Test void invalidationCannotBeUndoneByAnInFlightRefresh() throws Exception {
        CountDownLatch read=new CountDownLatch(1),resume=new CountDownLatch(1);
        JdbcTemplate paused=new JdbcTemplate(source) {
            @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
                List<T> result=super.query(sql,mapper,args);
                if(sql.contains("UNION ALL")) {
                    read.countDown();
                    try {if(!resume.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Refresh was not resumed");}
                    catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                }
                return result;
            }
        };
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> pending=worker.submit(()->new TransactionTemplate(new DataSourceTransactionManager(source))
                    .executeWithoutResult(status->new DerivativePriceOracle(paused).refresh(NOW)));
            assertTrue(read.await(5,TimeUnit.SECONDS));
            // Commit a correction after the worker has already read the old tape.
            jdbc.update("DELETE FROM bot_trade_seconds");
            new DerivativePriceOracle(jdbc).invalidate(1,NOW.minusSeconds(10));
            resume.countDown();pending.get(5,TimeUnit.SECONDS);
            assertNull(jdbc.queryForObject("SELECT observed_at FROM derivative_reference_prices",Timestamp.class));
            assertEquals(1,jdbc.queryForObject("SELECT revision FROM derivative_reference_prices",Long.class));
        } finally {resume.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
