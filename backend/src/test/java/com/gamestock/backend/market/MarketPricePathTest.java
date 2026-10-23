package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Paid price discovery in an ordinary market; no synthetic minimum-volatility requirement. */
class MarketPricePathTest {
    @Test void fundedPricesCanChangeWithoutTransfersOrSyntheticTicks() {
        var setup=new MarketSimulationHarness.Setup();
        setup.sessionMillis=Long.getLong("path.sessionMillis",1_200_000L);
        // A modest durable improvement, distinct from the out-of-band shock tests.
        Map<String,Double> fundamental=new HashMap<>();
        double hot=Double.parseDouble(System.getProperty("path.fundamental","1.04"));
        for(String code:MarketSimulationHarness.CODES)if(StockMarketProfile.isSpeculative(code))fundamental.put(code,hot);
        setup.fundamental=fundamental;
        var paths=MarketSimulationHarness.run(setup);
        MarketSimulationHarness.print("PATH",paths);
        paths.forEach((code,path)->{
            assertTrue(path.fills()>200,code+" keeps trading: "+path);
            assertTrue(path.longestQuiet()<30_000,code+" never stalls: "+path);
            assertTrue(path.distinct()>=5,code+" has multiple paid price levels: "+path);
            assertTrue(path.high()-path.low()>.3,code+" can discover prices without an imposed random range: "+path);
        });
    }
}
