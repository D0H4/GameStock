package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MarketEconomicClockTest {
    @Test void strategyFamiliesDoNotAllFollowOneStochasticConsensus() {
        var metrics=PriceMetricService.calculate(java.util.List.of(),10000,9990,10010,100,100,1000);
        var profiles=BotProfile.activePopulation(17,6020);
        var value=profiles.stream().filter(p->p.strategy().family()==BotProfile.Family.VALUE).findFirst().orElseThrow();
        var intraday=profiles.stream().filter(p->p.strategy().family()==BotProfile.Family.INTRADAY).findFirst().orElseThrow();
        var momentum=profiles.stream().filter(p->p.strategy().family()==BotProfile.Family.MOMENTUM).findFirst().orElseThrow();
        double valueView=BotStrategyEngine.valuationObservation(value,metrics,11000,10000);
        double intradayView=BotStrategyEngine.valuationObservation(intraday,metrics,11000,10000);
        double momentumView=BotStrategyEngine.valuationObservation(momentum,metrics,11000,10000);
        assertNotEquals(valueView,intradayView);assertNotEquals(momentumView,intradayView);
        assertEquals(intradayView,BotStrategyEngine.valuationObservation(intraday,metrics,11000,12000));
        assertTrue(BotStrategyEngine.valuationObservation(value,metrics,11000,12000)>valueView);
    }

    @Test void midnightAndListingSizeCannotChangeTheSameEconomicTarget() {
        for(String code:new String[]{"GI","TR"}) {
            var before=new BatchOrderBook.Stock(1,code,10000,10000,10000,10000,true);
            var after=new BatchOrderBook.Stock(1,code,10300,10300,10300,10300,true);
            assertEquals(10400,MarketConsensusValue.target(before,10400,.04));
            assertEquals(10400,MarketConsensusValue.target(after,10400,.04));
        }
    }
    @Test void suspensionUsesTheEntireElapsedIntervalAndBackwardClockCannotReplayIt() {
        var stock=new BatchOrderBook.Stock(1,"GI",10000,10000,10000,10000,true);
        var shortStep=new MarketConsensusValue(17);var longStep=new MarketConsensusValue(17);
        var saved=Map.of(1L,new MarketConsensusValue.Checkpoint(10000,0,17));
        shortStep.restore(saved);longStep.restore(saved);
        double minute=shortStep.observe(stock,11500,0,null,60000);
        double halfHour=longStep.observe(stock,11500,0,null,1800000);
        assertTrue(halfHour>minute+100,"A half-hour gap must not be truncated to one minute");
        var checkpoint=longStep.checkpoint();
        assertEquals(halfHour,longStep.observe(stock,11500,0,null,1790000));
        assertEquals(checkpoint,longStep.checkpoint());
    }
}
