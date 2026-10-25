package com.gamestock.backend.market;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static com.gamestock.backend.market.BatchOrderBook.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-10-19: every small cap ran to ~+19% and stopped trading. The hidden fundamental
 * sat far above the +20% bot band, so each participant SELL was clamped into one wall at
 * exactly reference*1.2, the band fade removed buyers, and the depleted LP only had
 * 1-share asks left. Nobody crossed the spread.
 */
class SmallCapBandFreezeTest {
    @ParameterizedTest @ValueSource(strings={"AK","VR","KRP","PW","CRK","ES","TR"})
    void smallCapNearUpperBandKeepsTradingWithoutForcedPriceReversion(String code) {
        long lp=100000,start=1_000_000,reference=10000,frozen=11900;
        var band=PriceLimitPolicy.botBand(code,reference);
        var accounts=new HashMap<Long,Account>();
        accounts.put(lp,new Account(lp,6_666_666,true,true));
        var holdings=new HashMap<PositionKey,Holding>();
        // The LP sold nearly everything into the run-up: three shares left -> 1-share ask levels.
        holdings.put(new PositionKey(lp,1),new Holding(3,3,frozen,0));
        var participants=new ArrayList<BotActivityEngine.Participant>();
        var profiles=BotProfile.activePopulation(91,6020);
        for(int i=0;i<profiles.size();i+=15) {
            long id=i+1;
            accounts.put(id,new Account(id,1_000_000,false,true));
            if(participants.size()<150)holdings.put(new PositionKey(id,1),new Holding(32,32,10500,0));
            participants.add(new BotActivityEngine.Participant(id,profiles.get(i),1));
        }
        var stocks=new HashMap<Long,Stock>();
        stocks.put(1L,new Stock(1,code,frozen,reference,reference,frozen,true));
        var book=new BatchOrderBook(accounts,stocks,holdings,List.of(),0,start);
        book.lpCashBudgets.put(1L,6_666_666L);
        // Enough circulating supply that the emergency refill does not top the LP back up.
        stocks.get(1L).circulatingShares=holdings.values().stream().mapToLong(Holding::quantity).sum();
        int target=StockMarketProfile.initialLiquidityInventory(code,100);
        var risk=new MarketMakerEngine.RiskBook(0,3,target,Math.max(1200,target*4),Math.max(1200,target*4)*frozen);
        var engine=new BotActivityEngine(participants,91,300);
        var history=new ArrayList<PriceMetricService.Trade>();
        for(int i=0;i<20;i++)history.add(new PriceMetricService.Trade(start-60_000+i*3000,frozen,1));
        // A sustained value shock can remain above the execution band; it must not force a price reset.
        double fundamental=reference*2.5;
        long lateFills=0,participantSellsIntoBids=0,lowest=Long.MAX_VALUE,peak=0,drawdown=0;
        long quiet=0,longestQuiet=0;
        int twoSided=0,boundaryBlocked=0;
        for(long elapsed=0;elapsed<180_000;elapsed+=1000) {
            long now=start+elapsed;
            book.beginBatch(now);
            book.restoreLiquidityInventory(lp,1,target,stocks.get(1L).last);
            var depth=book.depth(1);
            var metrics=PriceMetricService.calculate(history,stocks.get(1L).last,depth[0],depth[1],(long)depth[2],(long)depth[3],now);
            var observation=new BotActivityEngine.Observation(metrics,MarketEnvironment.neutral(),fundamental,0,
                    StockMarketProfile.liquidityScale(code,.65));
            engine.microBatches(book,Map.of(1L,observation),Map.of(),now,1000,Map.of(1L,risk),lp,300);
            quiet=book.fills.isEmpty()?quiet+1000:0;longestQuiet=Math.max(longestQuiet,quiet);
            for(var fill:book.fills) {
                history.add(new PriceMetricService.Trade(now,fill.price(),fill.quantity()));
                lowest=Math.min(lowest,fill.price());
                peak=Math.max(peak,fill.price());drawdown=Math.max(drawdown,peak-fill.price());
                if(elapsed>=120_000)lateFills++;
                if(fill.taker().side.equals("SELL") && fill.taker().user!=lp)participantSellsIntoBids++;
            }
            depth=book.depth(1);
            if(depth[2]>0&&depth[3]>0)twoSided++;
            else if((depth[3]==0 && depth[0]>=band.upperPrice())
                    || (depth[2]==0 && depth[1]<=band.lowerPrice()))boundaryBlocked++;
            assertTrue(book.fills.stream().allMatch(f->band.contains(f.price())));
        }
        long wall=book.participantQuantityAt(1,"SELL",band.upperPrice());
        String context=code+" lateFills="+lateFills+" sellsIntoBids="+participantSellsIntoBids+" lowest="+lowest
                +" drawdown="+drawdown+" last="+stocks.get(1L).last+" wallAtUpper="+wall+" lpInventory="+book.holding(lp,1).quantity()
                +" depth="+Arrays.toString(book.depth(1));
        System.out.println("BAND-FREEZE "+context);
        assertTrue(wall<LiquidityRecoveryPolicy.WALL_QUANTITY,"Participant offers must not pile up at the band ceiling: "+context);
        assertTrue(participantSellsIntoBids>0,"Some participant must cross the spread to sell: "+context);
        assertTrue(lateFills>0 && longestQuiet<30_000,"Paid trading must continue without a prolonged stall: "+context);
        // At the actual bot ceiling a passive ask cannot step above an executable bid.
        // Do not count that legal boundary as a liquidity failure or leave fake 1-share asks.
        assertTrue(twoSided+boundaryBlocked>150,"Both sides must stay quoted away from a binding band: "+context);
        // A 2.5x positive value shock need not revisit a price below the initial print. Verify
        // an actual multi-tick retreat from an executed peak, not a prescribed downward wick.
        assertTrue(drawdown>=2*PriceLimitPolicy.tickSize(frozen),"Paid prices can retreat from a traded peak: "+context);
    }
}
