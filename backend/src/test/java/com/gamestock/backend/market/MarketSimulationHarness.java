package com.gamestock.backend.market;

import java.util.*;
import static com.gamestock.backend.market.BatchOrderBook.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Production listings, the 6020-trader population and MarketService's four-lane split over one
 * shared in-memory book. Only funded matching moves prices; the harness never assigns a path.
 */
final class MarketSimulationHarness {
    static final String[] CODES={"UMA","BA","GOV","ZZZ","GI","SR","AK","WH","CRK","VR","ES","KRP","OW","PW","TR"};
    static final long REFERENCE=10000,LP=100000;

    /** Per-symbol tape statistics measured after the warm-up minute. */
    record Path(long fills,long longestQuiet,double low,double high,double last,int distinct,
                double boxedShare,double above15Share) {
        @Override public String toString() {
            return String.format("fills=%d quiet=%.1fs range=%+.1f%%..%+.1f%% last=%+.1f%% distinct=%d boxed=%.0f%% above15=%.0f%%",
                    fills,longestQuiet/1000.0,low,high,last,distinct,boxedShare*100,above15Share*100);
        }
    }

    static final class Setup {
        Set<String> broke=Set.of();
        Map<String,Double> startPct=Map.of();
        /** Hidden fundamental as a multiple of the listing reference. */
        Map<String,Double> fundamental=Map.of();
        boolean pooled=true;
        long sessionMillis=600_000,seed=20261022L;
    }

    static Map<String,Path> run(Setup setup) {
        long seed=setup.seed,lp=LP,reference=REFERENCE;
        var profiles=BotProfile.activePopulation(seed,6020);
        var accounts=new HashMap<Long,Account>();var holdings=new HashMap<PositionKey,Holding>();
        var stocks=new HashMap<Long,Stock>();var risks=new HashMap<Long,MarketMakerEngine.RiskBook>();
        var targets=new HashMap<Long,Integer>();
        for(int s=0;s<CODES.length;s++) {
            long id=s+1;String code=CODES[s];
            long price=Math.round(reference*(1+setup.startPct.getOrDefault(code,0.0)/100)/10)*10;
            stocks.put(id,new Stock(id,code,price,reference,reference,reference,true));
            int target=StockMarketProfile.initialLiquidityInventory(code,100);targets.put(id,target);
            holdings.put(new PositionKey(lp,id),new Holding(target,target,price,0));
            stocks.get(id).circulatingShares=target;
            risks.put(id,new MarketMakerEngine.RiskBook(0,target,target,Math.max(1200,target*4),Math.max(1200,target*4)*10000L));
        }
        accounts.put(lp,new Account(lp,6_666_666L*CODES.length,true,true));
        var participants=new ArrayList<BotActivityEngine.Participant>();
        for(int i=0;i<profiles.size();i++) {
            long id=i+1,stock=i%CODES.length+1;String code=CODES[(int)stock-1];
            boolean broke=setup.broke.contains(code),holds=code.equals("AK")&&broke||!broke&&i%2==0;
            accounts.put(id,new Account(id,broke?0:1_000_000,false,true));
            if(holds){holdings.put(new PositionKey(id,stock),new Holding(10,10,reference,0));stocks.get(stock).circulatingShares+=10;}
            participants.add(new BotActivityEngine.Participant(id,profiles.get(i),stock));
        }
        long start=1_761_000_000_000L;
        var book=new BatchOrderBook(accounts,stocks,holdings,List.of(),0,start);
        for(long id:stocks.keySet())book.lpCashBudgets.put(id,LP_OPENING_BUDGET);
        long[] lpReturned={0};
        long initialCash=accounts.values().stream().mapToLong(a->a.cash).sum();
        long initialTraderCash=initialCash-accounts.get(lp).cash;
        var engines=new ArrayList<BotActivityEngine>();
        for(int lane=0;lane<4;lane++) {
            final int l=lane;
            engines.add(new BotActivityEngine(participants.stream().filter(p->(p.stock()-1)%4==l).toList(),seed^(lane*71L),300));
        }
        engines.forEach(BotActivityEngine::resume);
        var patterns=new PatternEngine(seed^11);var regimes=new MarketRegimeEngine(seed^17);
        var history=new HashMap<Long,List<PriceMetricService.Trade>>();
        Map<String,String> genres=new HashMap<>();for(String code:CODES)genres.put(code,"RPG");
        Map<String,long[]> stats=new LinkedHashMap<>();
        Map<String,Set<Long>> distinct=new HashMap<>();
        Map<String,Deque<long[]>> window=new HashMap<>();
        for(String code:CODES){stats.put(code,new long[]{0,0,0,Long.MAX_VALUE,0,0,0,0});distinct.put(code,new HashSet<>());window.put(code,new ArrayDeque<>());}
        int cadence=100;long warmup=60_000;
        for(long elapsed=0;elapsed<setup.sessionMillis;elapsed+=cadence) {
            long now=start+elapsed;
            if(setup.pooled && elapsed%30_000==0)lpReturned[0]+=applyPool(book,accounts,participants,stocks);
            book.beginBatch(now);
            regimes.advance(now,genres);
            Map<Long,BotActivityEngine.Observation> observations=new HashMap<>();
            for(Stock symbol:stocks.values()) {
                book.restoreLiquidityInventory(lp,symbol.id,targets.get(symbol.id),symbol.last);
                var trades=history.computeIfAbsent(symbol.id,k->new ArrayList<>());
                trades.removeIf(t->t.time()<now-600_000);
                double[] depth=book.depth(symbol.id);
                var metrics=PriceMetricService.calculate(trades,symbol.last,depth[0],depth[1],(long)depth[2],(long)depth[3],now);
                var environment=StockMarketProfile.applyTo(symbol.code,regimes.environment(symbol.code,"RPG").combine(patterns.environment(symbol.code,now)));
                double fundamental=reference*setup.fundamental.getOrDefault(symbol.code,1.0);
                observations.put(symbol.id,new BotActivityEngine.Observation(metrics,environment,fundamental,0,StockMarketProfile.liquidityScale(symbol.code,.65)));
            }
            for(var engine:engines)engine.microBatches(book,observations,Map.of(),now,cadence,risks,lp,300);
            Map<Long,long[]> batch=new HashMap<>();
            for(Fill fill:book.fills) {
                long[] b=batch.computeIfAbsent(fill.buy().stock,k->new long[]{0,0,0,Long.MAX_VALUE,0});
                b[0]++;b[1]+=fill.quantity();b[2]+=(long)fill.price()*fill.quantity();
                b[3]=Math.min(b[3],fill.price());b[4]=Math.max(b[4],fill.price());
            }
            for(Stock symbol:stocks.values()) {
                long[] b=batch.get(symbol.id);
                if(b!=null)history.get(symbol.id).add(new PriceMetricService.Trade(now,symbol.last,b[1],b[2],b[4],b[3]));
                if(elapsed<warmup)continue;
                // s: fills, current quiet, longest quiet, low, high, samples, boxed samples, above-15% samples
                long[] s=stats.get(symbol.code);
                if(b==null)s[1]+=cadence;
                else{s[1]=0;s[0]+=b[0];s[3]=Math.min(s[3],b[3]);s[4]=Math.max(s[4],b[4]);distinct.get(symbol.code).add(symbol.last);}
                s[2]=Math.max(s[2],s[1]);
                // Boxed: the last minute's prices span at most two ticks.
                var w=window.get(symbol.code);w.addLast(new long[]{now,symbol.last});
                while(w.peekFirst()[0]<now-60_000)w.removeFirst();
                long lo=Long.MAX_VALUE,hi=0;for(long[] p:w){lo=Math.min(lo,p[1]);hi=Math.max(hi,p[1]);}
                s[5]++;if(hi-lo<=2*PriceLimitPolicy.tickSize(symbol.last))s[6]++;
                if(symbol.last>reference*1.15)s[7]++;
            }
        }
        long reserved=accounts.keySet().stream().flatMap(id->stocks.keySet().stream().flatMap(s->book.working(id,s).stream())).mapToLong(o->o.reservedCash).sum();
        assertEquals(initialCash,accounts.values().stream().mapToLong(a->a.cash).sum()+reserved,"Bot turnover and the pool conserve cash");
        assertTrue(accounts.values().stream().allMatch(a->a.cash>=0));
        long traderCash=participants.stream().mapToLong(p->accounts.get(p.id()).cash+book.working(p.id(),p.stock()).stream().mapToLong(o->o.reservedCash).sum()).sum();
        long lpReserved=stocks.keySet().stream().flatMap(s->book.working(lp,s).stream()).mapToLong(o->o.reservedCash).sum();
        System.out.println("CAPITAL lpReturned="+lpReturned[0]+" traderCash="+traderCash+" of "+initialTraderCash+" lpCash="+(accounts.get(lp).cash+lpReserved));
        Map<String,Path> result=new LinkedHashMap<>();
        for(int s=0;s<CODES.length;s++) {
            String code=CODES[s];long[] v=stats.get(code);
            double low=v[3]==Long.MAX_VALUE?0:(v[3]/(double)reference-1)*100,high=v[4]==0?0:(v[4]/(double)reference-1)*100;
            result.put(code,new Path(v[0],v[2],low,high,(stocks.get(s+1L).last/(double)reference-1)*100,distinct.get(code).size(),
                    v[5]==0?0:v[6]/(double)v[5],v[5]==0?0:v[7]/(double)v[5]));
        }
        return result;
    }

    /**
     * The same plan MarketService applies under the market lock, applied to free cash only. The
     * LP's net gain over its opening budgets returns to the pool, charged to the symbols that earned it.
     */
    static long applyPool(BatchOrderBook book,Map<Long,Account> accounts,List<BotActivityEngine.Participant> participants,Map<Long,Stock> stocks) {
        var traders=participants.stream().map(p->new TraderCapitalPool.Trader(p.id(),accounts.get(p.id()).cash,stocks.get(p.stock()).last)).toList();
        long net=0;Map<Long,Long> gains=new HashMap<>();
        // Like MarketService's trade projection, count cash the LP has parked in resting bids as its own.
        for(var e:book.lpCashBudgets.entrySet()) {
            long reserved=book.working(LP,e.getKey()).stream().mapToLong(o->o.reservedCash).sum();
            long gain=e.getValue()+reserved-LP_OPENING_BUDGET;net+=gain;if(gain>0)gains.put(e.getKey(),gain);
        }
        long external=Math.max(0,Math.min(net,accounts.get(LP).cash/2));
        var deltas=TraderCapitalPool.plan(traders,external);
        deltas.forEach((id,delta)->accounts.get(id).change(delta));
        long returned=deltas.values().stream().mapToLong(Long::longValue).sum();
        if(returned>0) {
            accounts.get(LP).change(-returned);
            long total=gains.values().stream().mapToLong(Long::longValue).sum(),charged=0;long largest=0,top=-1;
            for(var e:gains.entrySet()) {
                long share=(long)Math.floor(returned*(e.getValue()/(double)total));
                book.lpCashBudgets.merge(e.getKey(),-share,Long::sum);charged+=share;
                if(e.getValue()>top){top=e.getValue();largest=e.getKey();}
            }
            book.lpCashBudgets.merge(largest,-(returned-charged),Long::sum);
        }
        return returned;
    }
    static final long LP_OPENING_BUDGET=6_666_666L;

    static void print(String label,Map<String,Path> paths) {
        paths.forEach((code,path)->System.out.println(label+" "+code+" "+path));
    }
}
