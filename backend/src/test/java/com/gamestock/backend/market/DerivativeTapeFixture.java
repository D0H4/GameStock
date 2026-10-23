package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.ArrayList;

/** A full minute of executed bot buckets for tests whose subject is accounting/API behaviour. */
public final class DerivativeTapeFixture {
    private DerivativeTapeFixture() { }
    public static void seed(JdbcTemplate jdbc, Instant at) {
        new DerivativeIndexHistory(jdbc).initializeBases();
        jdbc.update("DELETE FROM bot_trade_seconds");
        var rows=new ArrayList<Object[]>();
        for(var stock:jdbc.queryForList("SELECT id,current_price FROM stocks")) {
            long id=((Number)stock.get("id")).longValue(),price=((Number)stock.get("current_price")).longValue();
            for(long second=at.getEpochSecond()-65;second<at.getEpochSecond()-5;second++)
                rows.add(new Object[]{id,second,second*1000,second*1000,price,price,price,price,10,price*10,1});
        }
        jdbc.batchUpdate("INSERT INTO bot_trade_seconds(stock_id,bucket_at,first_at,last_at,open_price,high_price,low_price,close_price,quantity,notional,fills) VALUES(?,?,?,?,?,?,?,?,?,?,?)",rows);
        new DerivativePriceOracle(jdbc).refresh(at);
        new DerivativeBaskets(jdbc).rebalance();
    }
}
