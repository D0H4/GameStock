package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.gamestock.backend.market.BatchOrderBook.*;

class BotActivityEngineTest {
    @Test void aRecentlyEmptiedBookKeepsZeroDepthWithoutPassingZeroPricesToStrategies() {
        var metrics=PriceMetricService.calculate(List.of(new PriceMetricService.Trade(1000,10000,10)),10000,9990,10010,4,4,1001);
        var empty=BotActivityEngine.withBook(metrics,10000,new double[4]);
        assertTrue(empty.recentVolume()>0);
        assertEquals(0,empty.bidDepth());assertEquals(0,empty.askDepth());
        assertEquals(9990,empty.bestBid());assertEquals(10010,empty.bestAsk());
        var oneSide=BotActivityEngine.withBook(metrics,10000,new double[]{9980,0,5,0});
        assertEquals(9980,oneSide.bestBid());assertEquals(10010,oneSide.bestAsk());
        assertEquals(5,oneSide.bidDepth());assertEquals(0,oneSide.askDepth());
    }

    @Test void humanCanBuyAndSellWhileTheLiquidityEngineIsRunning() {
        var p=BotProfile.defaults(1).get(2);
        var engine=new BotActivityEngine(List.of(new BotActivityEngine.Participant(1,p,1)),1,300);
        var accounts=new HashMap<Long,Account>();
        accounts.put(1L,new Account(1,0,false,true));
        accounts.put(2L,new Account(2,2_000_000,true,true));
        accounts.put(3L,new Account(3,1_000_000,false,false));
        var stocks=new HashMap<Long,Stock>();stocks.put(1L,new Stock(1,"ES",10000,10000,10000,10000,true));
        var holdings=new HashMap<PositionKey,Holding>();holdings.put(new PositionKey(2,1),new Holding(100,100,10000,0));
        var book=new BatchOrderBook(accounts,stocks,holdings,List.of(),0,1000);
        book.lpCashBudgets.put(1L,2_000_000L);
        engine.act(book,Map.of(1L,observation()),Map.of(),2000,100,
                Map.of(1L,new MarketMakerEngine.RiskBook(2_000_000,100,100,400,4_000_000)),2);
        assertEquals("FILLED",book.submit(3,1,"BUY","MARKET",1,0,2001,0).status);
        assertEquals("FILLED",book.submit(3,1,"SELL","MARKET",1,0,2002,0).status);
        assertEquals(0,book.holding(3,1).quantity());
        assertTrue(book.fills.stream().allMatch(f->f.buyerFee()>0 && f.sellerFee()>0));
    }

    @Test void fastDecisionsRetainStrategicHoldingHorizonsAndSmallOrders() {
        var profiles=BotProfile.activePopulation(91,100);
        for(var p:profiles.subList(20,profiles.size())) {
            assertEquals(2,p.maxOrderSize());
            if(!BotStrategyEngine.hasInventoryDemand(p)) {
                assertTrue(p.holdingTimePreference()>=30_000);
                assertTrue(p.positionLimit()>=8);
                assertTrue(p.decisionInterval()<p.holdingTimePreference());
            }
        }
    }

    @Test void staleOversizedBotOrdersAreRefundedWithoutCancellingAHumanOrder() {
        var p=BotProfile.activePopulation(1,21).get(20);
        var engine=new BotActivityEngine(List.of(new BotActivityEngine.Participant(1,p,1)),1,300);
        var accounts=new HashMap<Long,Account>();
        accounts.put(1L,new Account(1,10_000_000,false,true));
        accounts.put(2L,new Account(2,10_000_000,false,false));
        var stocks=new HashMap<Long,Stock>();stocks.put(1L,new Stock(1,"ES",10000,10000,10000,10000,true));
        var book=new BatchOrderBook(accounts,stocks,new HashMap<>(),List.of(),0,1000);
        var bot=book.submit(1,1,"BUY","LIMIT",100,8000,1000,100000);
        var human=book.submit(2,1,"BUY","LIMIT",100,8000,1000,100000);
        engine.act(book,Map.of(1L,observation()),Map.of(),40_000,100,Map.of(),-1);
        assertEquals("CANCELLED",bot.status);assertEquals("OPEN",human.status);
        assertTrue(book.working(1,1).size()<=1);
        assertTrue(book.working(1,1).stream().allMatch(o->o.remaining<=2));
        assertEquals(10_000_000,book.accounts.get(1L).cash+book.working(1,1).stream().mapToLong(o->o.reservedCash).sum());
    }

    @Test void scaledProfilesAreReproducibleAndAccountsStayUnique() {
        var a=BotProfile.activePopulation(91,6020);var b=BotProfile.activePopulation(91,6020);
        assertEquals(a,b);assertEquals(BotProfile.defaults(91),a.subList(0,20));
        assertEquals(6020,a.stream().map(BotProfile::username).distinct().count());
        assertEquals(6020,a.stream().map(BotProfile::nickname).distinct().count());
        assertEquals(12,a.get(20).positionLimit());assertNotEquals(a.get(20).aggression(),a.get(28).aggression());
        for(int symbol=0;symbol<15;symbol++) {
            Set<BotProfile.Family> families=new HashSet<>();
            for(int i=symbol;i<a.size();i+=15)families.add(a.get(i).strategy().family());
            assertEquals(Set.of(BotProfile.Family.values()),families,"Every symbol needs independent value and flow traders");
        }
    }
    private BotActivityEngine.Observation observation() {
        var metric=PriceMetricService.calculate(List.of(new PriceMetricService.Trade(1000,10000,1)),10000,9990,10010,50,50,1000);
        return new BotActivityEngine.Observation(metric,MarketEnvironment.neutral(),10000,0,.65);
    }
    @Test void activityTargetCannotMintCashSharesTradesOrPrices() {
        var profiles=BotProfile.activePopulation(17,22);
        var engine=new BotActivityEngine(List.of(new BotActivityEngine.Participant(1,profiles.get(20),1),new BotActivityEngine.Participant(2,profiles.get(21),1)),17,300);
        var accounts=new HashMap<Long,Account>();accounts.put(1L,new Account(1,0,false));accounts.put(2L,new Account(2,0,false));
        var stocks=new HashMap<Long,Stock>();stocks.put(1L,new Stock(1,"A",10000,10000,10000,10000,true));
        var book=new BatchOrderBook(accounts,stocks,new HashMap<>(),List.of(),0,1000);
        for(int i=0;i<100;i++)engine.act(book,Map.of(1L,observation()),Map.of(),1000+i*50,50,Map.of(),-1);
        assertTrue(book.fills.isEmpty());assertTrue(book.prices.isEmpty());assertTrue(book.holdings.isEmpty());
        assertEquals(10000,stocks.get(1L).last);assertEquals(0,accounts.values().stream().mapToLong(a->a.cash).sum());
    }
    @Test void lpRebalanceSellsOnlyExistingSharesAgainstFundedBids() {
        var profile=BotProfile.defaults(1).get(2);
        var engine=new BotActivityEngine(List.of(new BotActivityEngine.Participant(1,profile,1)),1,300);
        var accounts=new HashMap<Long,Account>();accounts.put(1L,new Account(1,1000000,false));accounts.put(2L,new Account(2,0,true));
        var stocks=new HashMap<Long,Stock>();stocks.put(1L,new Stock(1,"A",10000,10000,10000,10000,true));
        var holdings=new HashMap<PositionKey,Holding>();holdings.put(new PositionKey(2,1),new Holding(400,400,10000,0));
        var book=new BatchOrderBook(accounts,stocks,holdings,List.of(),0,1000);
        book.submit(1,1,"BUY","LIMIT",20,10000,1000,10000);
        engine.act(book,Map.of(1L,observation()),Map.of(1L,100),2000,50,Map.of(1L,new MarketMakerEngine.RiskBook(0,400,100,1200,12000000)),2);
        assertEquals(395,book.holding(2,1).quantity());assertEquals(5,book.holding(1,1).quantity());
        assertEquals(49950,book.accounts.get(2L).cash);assertEquals(1,book.fills.size());
    }
}
