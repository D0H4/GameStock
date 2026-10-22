package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** A symbol whose traders are broke must recover through the shared pool. */
class TraderCapitalPoolTest {
    /** Live state on 2026-10-22: these symbols' traders had no cash, and only AK's still held shares. */
    private static final Set<String> BROKE=Set.of("GOV","GI","OW","AK");
    private static final Map<String,Double> START=Map.of("GOV",1.08,"GI",1.15,"OW",1.33,"AK",17.5,"CRK",16.5,"VR",16.8,"TR",15.7);

    @Test void planConservesCashAndKeepsDonorsAtOrAboveTheMean() {
        var traders=List.of(new TraderCapitalPool.Trader(1,0,10000),new TraderCapitalPool.Trader(2,0,10000),
                new TraderCapitalPool.Trader(3,3_000_000,10000),new TraderCapitalPool.Trader(4,1_000_000,8000));
        var deltas=TraderCapitalPool.plan(traders);
        assertEquals(0,deltas.values().stream().mapToLong(Long::longValue).sum());
        assertEquals(deltas.get(1L),deltas.get(2L),"Equal gaps receive equal grants");
        assertEquals(500_000L,deltas.get(1L),"A starved trader is lifted to half the pool mean");
        assertTrue(3_000_000+deltas.get(3L)>=1_000_000,"A donor never falls below the pool mean");
        assertFalse(deltas.containsKey(4L),"A trader at the mean neither gives nor receives");
    }

    @Test void externalCashFillsStarvedTradersFirstAndIsPlacedInFull() {
        var traders=List.of(new TraderCapitalPool.Trader(1,0,10000),new TraderCapitalPool.Trader(2,2_000_000,10000));
        var deltas=TraderCapitalPool.plan(traders,600_000);
        assertEquals(600_000,deltas.values().stream().mapToLong(Long::longValue).sum(),"All returned LP cash is placed");
        assertTrue(deltas.get(1L)>=500_000,"The starved trader is filled from the LP before any trader donates");
        assertTrue(deltas.getOrDefault(2L,0L)>=0,"No trader donates while external cash covers the need");
        var funded=TraderCapitalPool.plan(List.of(new TraderCapitalPool.Trader(1,1_000_000,10000),
                new TraderCapitalPool.Trader(2,1_000_000,10000)),1_001);
        assertEquals(Map.of(1L,501L,2L,500L),funded,"Without a need the return is shared evenly");
    }

    @Test void planLeavesAFundedPoolAlone() {
        assertTrue(TraderCapitalPool.plan(List.of(new TraderCapitalPool.Trader(1,900_000,10000),
                new TraderCapitalPool.Trader(2,1_100_000,10000))).isEmpty());
        assertTrue(TraderCapitalPool.plan(List.of(new TraderCapitalPool.Trader(1,0,10000),
                new TraderCapitalPool.Trader(2,0,10000))).isEmpty(),"No donor means nothing to move");
    }

    @Test void brokeSymbolsResumeTradingAndEverySymbolKeepsTrading() {
        var setup=liveState();setup.sessionMillis=Long.getLong("capital.sessionMillis",600_000L);
        var paths=MarketSimulationHarness.run(setup);
        MarketSimulationHarness.print("POOLED",paths);
        paths.forEach((code,path)->{
            assertTrue(path.fills()>200,code+" must keep trading: "+path);
            assertTrue(path.longestQuiet()<30_000,code+" must not stall: "+path);
        });
    }

    @Test void withoutThePoolBrokeLargeCapsStayFrozen() {
        var setup=liveState();setup.pooled=false;setup.sessionMillis=120_000;
        var paths=MarketSimulationHarness.run(setup);
        MarketSimulationHarness.print("ISOLATED",paths);
        for(String code:List.of("GOV","GI","OW"))assertEquals(0,paths.get(code).fills(),"Reproduces the live stall: "+paths.get(code));
    }

    private static MarketSimulationHarness.Setup liveState() {
        var setup=new MarketSimulationHarness.Setup();setup.broke=BROKE;setup.startPct=START;return setup;
    }
}
