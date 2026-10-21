package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Price discovery across every listing: no symbol may sit in a fixed box or pin under the band. */
class MarketPricePathTest {
    @Test void pricesKeepMovingWithoutABoxOrACeiling() {
        var setup=new MarketSimulationHarness.Setup();
        setup.sessionMillis=Long.getLong("path.sessionMillis",1_200_000L);
        // Live news had pushed every small cap's hidden fundamental well past the +20% bot band.
        Map<String,Double> fundamental=new HashMap<>();
        double hot=Double.parseDouble(System.getProperty("path.fundamental","2.0"));
        for(String code:MarketSimulationHarness.CODES)if(StockMarketProfile.isSpeculative(code))fundamental.put(code,hot);
        setup.fundamental=fundamental;
        var paths=MarketSimulationHarness.run(setup);
        MarketSimulationHarness.print("PATH",paths);
        paths.forEach((code,path)->{
            assertTrue(path.fills()>200,code+" keeps trading: "+path);
            assertTrue(path.longestQuiet()<30_000,code+" never stalls: "+path);
            if(StockMarketProfile.isLargeCap(code)) {
                // Before the moving consensus, every large cap traded about eleven prices in a +/-0.6% box.
                assertTrue(path.distinct()>=14,code+" discovers new prices instead of a fixed box: "+path);
            } else {
                // A strong fundamental may hold a small cap high, but it must keep trading new prices
                // in both directions rather than sit in a two-tick box under the band.
                assertTrue(path.distinct()>=40,code+" keeps discovering prices: "+path);
                assertTrue(path.boxedShare()<.1,code+" is not parked in a box: "+path);
                assertTrue(path.high()-path.low()>2,code+" moves more than a couple of points: "+path);
            }
        });
    }
}
