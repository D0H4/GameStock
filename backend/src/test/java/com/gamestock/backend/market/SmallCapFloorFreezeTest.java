package com.gamestock.backend.market;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static com.gamestock.backend.market.BatchOrderBook.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Mirror of SmallCapBandFreezeTest: a stale fundamental far below the -20% bot band caps
 * every participant bid under the floor, the floor fade removes sellers, and the LP is the
 * only counterparty left. Small caps must keep trading both ways and recover off the floor.
 */
class SmallCapFloorFreezeTest {
    @ParameterizedTest @ValueSource(strings={"AK","VR","KRP","PW","CRK","ES","TR"})
    void smallCapNearLowerBandKeepsTradingAndRecovers(String code) {
        long lp=100000,start=1_000_000,reference=10000,frozen=8100;
        var band=PriceLimitPolicy.botBand(code,reference);
        var accounts=new HashMap<Long,Account>();
        accounts.put(lp,new Account(lp,6_666_666,true,true));
        var holdings=new HashMap<PositionKey,Holding>();
        var participants=new ArrayList<BotActivityEngine.Participant>();
        var profiles=BotProfile.activePopulation(91,6020);
        for(int i=0;i<profiles.size();i+=15) {
            long id=i+1;
            accounts.put(id,new Account(id,1_000_000,false,true));
            if(participants.size()<150)holdings.put(new PositionKey(id,1),new Holding(32,32,9500,0));
            participants.add(new BotActivityEngine.Participant(id,profiles.get(i),1));
        }
        var stocks=new HashMap<Long,Stock>();
        stocks.put(1L,new Stock(1,code,frozen,reference,reference,frozen,true));
        var book=new BatchOrderBook(accounts,stocks,holdings,List.of(),0,start);
        book.lpCashBudgets.put(1L,6_666_666L);
        stocks.get(1L).circulatingShares=holdings.values().stream().mapToLong(Holding::quantity).sum();
        long shares=stocks.get(1L).circulatingShares;
        int target=StockMarketProfile.initialLiquidityInventory(code,100);
        var risk=new MarketMakerEngine.RiskBook(0,0,target,Math.max(1200,target*4),Math.max(1200,target*4)*frozen);
        var engine=new BotActivityEngine(participants,91,300);
        var history=new ArrayList<PriceMetricService.Trade>();
        for(int i=0;i<20;i++)history.add(new PriceMetricService.Trade(start-60_000+i*3000,frozen,1));
        // News pushed the fundamental to its 0.5x-anchor floor, far below today's -20% band.
        double fundamental=reference*.5;
        long lateFills=0,participantBuysFromAsks=0,highest=0;
        int twoSided=0;
        for(long elapsed=0;elapsed<180_000;elapsed+=1000) {
            long now=start+elapsed;
            book.beginBatch(now);
            book.restoreLiquidityInventory(lp,1,target,stocks.get(1L).last);
            var depth=book.depth(1);
            var metrics=PriceMetricService.calculate(history,stocks.get(1L).last,depth[0],depth[1],(long)depth[2],(long)depth[3],now);
            var observation=new BotActivityEngine.Observation(metrics,MarketEnvironment.neutral(),fundamental,0,
                    StockMarketProfile.liquidityScale(code,.65));
            engine.microBatches(book,Map.of(1L,observation),Map.of(),now,1000,Map.of(1L,risk),lp,300);
            for(var fill:book.fills) {
                history.add(new PriceMetricService.Trade(now,fill.price(),fill.quantity()));
                highest=Math.max(highest,fill.price());
                if(elapsed>=120_000)lateFills++;
                if(fill.taker().side.equals("BUY") && fill.taker().user!=lp)participantBuysFromAsks++;
            }
            depth=book.depth(1);
            if(depth[2]>0&&depth[3]>0)twoSided++;
            assertTrue(book.fills.stream().allMatch(f->band.contains(f.price())));
        }
        long wall=book.participantQuantityAt(1,"BUY",band.lowerPrice());
        String context=code+" lateFills="+lateFills+" buysFromAsks="+participantBuysFromAsks+" highest="+highest
                +" last="+stocks.get(1L).last+" wallAtLower="+wall+" lpInventory="+book.holding(lp,1).quantity()
                +" lpCash="+accounts.get(lp).cash+" depth="+Arrays.toString(book.depth(1));
        System.out.println("FLOOR-FREEZE "+context);
        assertTrue(wall<LiquidityRecoveryPolicy.WALL_QUANTITY,"Participant bids must not pile up at the band floor: "+context);
        assertTrue(participantBuysFromAsks>0,"Some participant must cross the spread to buy: "+context);
        assertTrue(lateFills>0,"An out-of-band adverse value can reduce turnover but must not invent fills: "+context);
        assertTrue(twoSided>150,"Both sides must stay quoted: "+context);
        assertTrue(highest>=reference*.83 && stocks.get(1L).last>frozen,"Price must be able to come off the bot floor: "+context);
        // A human can still trade an ordinary lot both ways, funded by an existing participant.
        long human=100001;
        Account donor=accounts.values().stream().filter(a->!a.liquidityProvider).max(Comparator.comparingLong(a->a.cash)).orElseThrow();
        donor.change(-200000);accounts.put(human,new Account(human,200000,false,false));
        assertEquals("FILLED",book.submit(human,1,"BUY","MARKET",5,0,start+180_000,0).status,"Human buy: "+context);
        assertEquals("FILLED",book.submit(human,1,"SELL","MARKET",5,0,start+180_001,0).status,"Human sell: "+context);
        assertEquals(shares,holdings.values().stream().mapToLong(Holding::quantity).sum(),"No new shares: "+context);
    }
}
