package com.gamestock.backend.market;

import java.util.*;
import static com.gamestock.backend.market.BatchOrderBook.*;
import static com.gamestock.backend.market.PriceLimitPolicy.*;

/** Paces independently funded participant decisions from actual fills, never manufactures a trade. */
final class BotActivityEngine {
    private static final long LIQUIDITY_QUOTE_TTL=10_000L;
    record Observation(PriceMetricService.Metrics metrics,MarketEnvironment environment,double fundamental,double news,double scale) { }
    record Participant(long id,BotProfile profile,long stock) { }
    record Activity(long stock,String code,int fills,int orders,boolean continuous) { }
    private final List<Participant> participants;
    private final Map<Long,List<Participant>> bySymbol=new LinkedHashMap<>();
    private final Map<Long,Long> due=new HashMap<>(),acquired=new HashMap<>();
    private final Map<Long,Integer> cursors=new HashMap<>();
    private final Map<Long,Double> credits=new HashMap<>();
    private final BotStrategyEngine strategies;
    private final BotOrderPolicy policy=new BotOrderPolicy();
    private final Random random;
    private final int target;
    private final Map<Long,Long> lastRebalance=new HashMap<>();
    private final Map<Long,Long> nextQuote=new HashMap<>();
    private final MarketMakerEngine maker=new MarketMakerEngine();
    private final LiquidityRecoveryPolicy recovery=new LiquidityRecoveryPolicy();

    BotActivityEngine(List<Participant> participants,long seed,int target) {
        this.participants=List.copyOf(participants);this.strategies=new BotStrategyEngine(seed^37);this.random=new Random(seed^71);this.target=target;
        for(Participant p:participants)bySymbol.computeIfAbsent(p.stock(),k->new ArrayList<>()).add(p);
        for(var group:bySymbol.values())Collections.shuffle(group,random);
    }

    /** A wake/pulse starts fresh pacing; elapsed idle time never creates a backlog of orders. */
    void resume() {credits.clear();due.clear();nextQuote.clear();recovery.reset();}

    /** Match at most 200 distinct orders per pass, then persist both passes in one 100 ms transaction.
     * The second pass consumes remaining pacing credit; it does not advance simulated time.
     */
    List<Activity> microBatches(BatchOrderBook book,Map<Long,Observation> observations,Map<Long,Integer> recentFills,long now,long elapsed,
                               Map<Long,MarketMakerEngine.RiskBook> risks,long lp,int targetRate){
        Map<Long,Integer> recent=new HashMap<>(recentFills);Map<Long,Activity> combined=new LinkedHashMap<>();
        for(int pass=0;pass<2;pass++){
            int before=book.accepted.size();
            for(Activity activity:act(book,observations,recent,now,pass==0?elapsed:0,risks,lp,targetRate)){
                recent.merge(activity.stock(),activity.fills(),Integer::sum);
                combined.merge(activity.stock(),activity,(a,b)->new Activity(b.stock(),b.code(),a.fills()+b.fills(),a.orders()+b.orders(),a.continuous()&&b.continuous()));
            }
            if(book.accepted.size()-before<180)break;
        }
        return List.copyOf(combined.values());
    }

    List<Activity> act(BatchOrderBook book,Map<Long,Observation> observations,Map<Long,Integer> recentFills,long now,long elapsed,
                       Map<Long,MarketMakerEngine.RiskBook> risks,long lp) {
        return act(book,observations,recentFills,now,elapsed,risks,lp,target);
    }
    List<Activity> act(BatchOrderBook book,Map<Long,Observation> observations,Map<Long,Integer> recentFills,long now,long elapsed,
                       Map<Long,MarketMakerEngine.RiskBook> risks,long lp,int targetRate) {
        List<Activity> activity=new ArrayList<>();
        for(var entry:bySymbol.entrySet()) {
            long stock=entry.getKey();Stock symbol=book.stocks.get(stock);
            Observation observation=observations.get(stock);
            if(symbol==null||observation==null)continue;
            if(!symbol.continuous){activity.add(new Activity(stock,symbol.code,0,0,false));continue;}
            var group=entry.getValue();int startFills=book.fills.size(),startOrders=book.accepted.size();
            int recent=recentFills.getOrDefault(stock,0);
            var risk=risks.get(stock);
            boolean stalled=recovery.stalled(symbol,now);
            if(stalled && !symbol.adminHalted && risk!=null && book.accounts.containsKey(lp)) {
                recovery.trimBotWalls(book,stock);
                if(recovery.absorbWall(book,symbol,risk,lp,maker.fairValue(observation.metrics(),observation.fundamental()),now))
                    nextQuote.remove(stock);
            }
            var marketMetrics=withBook(observation.metrics(),symbol.last,book.depth(stock));
            refreshLiquidity(book,symbol,observation,risk,lp,now,true);
            if(risk!=null && now-lastRebalance.getOrDefault(stock,0L)>=500) {
                // Gradually distribute an over-target LP position through funded external bids.
                // No transfer, refill, paired counterorder or price change happens outside matching.
                int excess=book.holding(lp,stock).quantity()-risk.targetInventory();
                double[] external=book.depth(stock,lp);
                double liquidationFloor=maker.fairValue(marketMetrics,observation.fundamental())*.995;
                if(excess>10 && external[2]>0 && external[0]>=liquidationFloor) {
                    book.submit(lp,stock,"SELL","LIMIT",Math.min(recent<30?15:5,Math.min(excess,book.available(lp,stock))),Math.round(external[0]),now,500);
                } else if(excess < -10 && external[3]>0 && external[1]<=maker.fairValue(marketMetrics,observation.fundamental())*1.005
                        && book.lpCashBudgets.containsKey(stock)) {
                    long price=Math.round(external[1]);
                    long spendable=Math.max(0,Math.min(book.accounts.get(lp).cash,book.lpCashBudgets.get(stock))
                            -MarketMakerEngine.emergencyCashRequirement(botBand(symbol.code,symbol.reference).upperPrice()));
                    int quantity=(int)Math.min(recent<30?15:5,Math.min(-excess,spendable/Math.max(1,price+BatchOrderBook.fee(price))));
                    book.submit(lp,stock,"BUY","LIMIT",quantity,price,now,500);
                }
                lastRebalance.put(stock,now);
            }
            // A rolling second avoids an end-of-second burst. A bounded catch-up budget absorbs scheduler jitter.
            int symbolTarget=StockMarketProfile.activityTarget(symbol.code,targetRate);
            double credit=Math.min(180,credits.getOrDefault(stock,0.0)+symbolTarget*(elapsed<=0?0:Math.min(500,Math.max(20,elapsed)))/1000.0);
            int budget=Math.max(0,Math.min(150,Math.min((int)credit,symbolTarget+10-recent)));
            if(recent<30 && !StockMarketProfile.isLargeCap(symbol.code))budget=Math.min(90,Math.max(budget,15));
            int orderBudget=200/bySymbol.size();
            int cursor=cursors.getOrDefault(stock,0),examined=0;
            while(examined<group.size()*2 && book.fills.size()-startFills<budget && book.accepted.size()-startOrders<orderBudget && symbol.continuous) {
                Participant participant=group.get(cursor++%group.size());examined++;
                if(due.getOrDefault(participant.id(),0L)>now)continue;
                BotProfile p=participant.profile();
                due.put(participant.id(),now+Math.max(10,(long)(p.decisionInterval()*StockMarketProfile.decisionIntervalWeight(symbol.code)*(.65+random.nextDouble()*.7)))+p.reactionLatency());
                Account account=book.accounts.get(participant.id());
                if(account==null)continue;
                Holding held=book.holding(participant.id(),stock);
                if(held.quantity()==0)acquired.remove(participant.id());
                else acquired.putIfAbsent(participant.id(),now);
                List<Order> working=book.working(participant.id(),stock);
                long refundable=working.stream().mapToLong(o->o.reservedCash).sum();
                double[] depth=book.depth(stock);
                if(book.accepted.size()-startOrders+liquidityRepairReserve(book,symbol,risk,lp,book.nearbyDepth(stock))>=orderBudget)break;
                PriceMetricService.Metrics m=withBook(observation.metrics(),symbol.last,depth);
                long equity=account.cash+refundable+Math.round(held.quantity()*m.markPrice());
                double targetQuantity=strategies.targetQuantity(p,symbol.code,now,equity,1,m.markPrice());
                var position=new BotStrategyEngine.Position(held.quantity(),account.cash+refundable,held.average(),now-acquired.getOrDefault(participant.id(),now),targetQuantity);
                double anchor=StockMarketProfile.valuationAnchor(symbol.code,symbol.reference,observation.fundamental(),observation.news());
                double researchNoise=.012*(StockMarketProfile.isLargeCap(symbol.code)?StockMarketProfile.of(symbol.code).movementWeight():1);
                double estimate=strategies.estimate(p,symbol.code,anchor*(1+random.nextGaussian()*researchNoise));
                double news=observation.news()*(1+p.valueError()*8+random.nextGaussian()*.25);
                var decision=strategies.decide(p,m,observation.environment(),position,estimate,news,tickSize(symbol.last),observation.scale(),symbol.code,symbol.reference);
                boolean replace=working.size()>1 || working.stream().anyMatch(o->o.remaining>BotProfile.MAX_ORDER_SIZE || now-o.createdAt>=30_000
                        || policy.shouldReplace(p,new BotOrderPolicy.RestingOrder(o.id,o.side,o.price,o.reservedCash,o.createdAt),decision,now,tickSize(o.price),o.side.equals("BUY")?m.bestBid():m.bestAsk(),o.side.equals("BUY")?m.bestAsk():m.bestBid()));
                if(!working.isEmpty()&&!replace)continue;
                if(replace) {
                    working.forEach(book::cancel);
                    if(book.accepted.size()-startOrders+liquidityRepairReserve(book,symbol,risk,lp,book.nearbyDepth(stock))>=orderBudget)break;
                }
                int quantity=Math.min(BotProfile.MAX_ORDER_SIZE,decision.quantity());
                if(quantity<=0)continue;
                long price=decision.side().equals("BUY")?floorToTick(Math.round(decision.limitPrice())):ceilToTick(Math.round(decision.limitPrice()));
                var band=quoteBand(symbol.code,symbol.reference,observation.news());
                // Clamping an offer above the ceiling (or a bid below the floor) would trade at a
                // price the participant rejected and stack every such order into one band wall.
                if(!decision.market() && (decision.side().equals("SELL")?price>band.upperPrice():price<band.lowerPrice()))continue;
                price=band.clamp(price);
                if(stalled && !decision.market()) {
                    price=recovery.dispersedPrice(book,stock,decision.side(),price,quantity,band);
                    if(price==0)continue;
                }
                book.submit(participant.id(),stock,decision.side(),decision.market()?"MARKET":"LIMIT",quantity,price,now,Math.min(30_000,Math.max(50,decision.ttlMillis())));
            }
            cursors.put(stock,cursor%group.size());
            credits.put(stock,Math.max(0,credit-(book.fills.size()-startFills)));
            // Repair consumed liquidity before this batch is persisted and broadcast, even at its pacing limit.
            refreshLiquidity(book,symbol,observation,risk,lp,now,false);
            activity.add(new Activity(stock,symbol.code,book.fills.size()-startFills,book.accepted.size()-startOrders,symbol.continuous));
        }
        return activity;
    }

    /** Keep room for replenishment only when one participant order could drain a recoverable side. */
    private int liquidityRepairReserve(BatchOrderBook book,Stock symbol,MarketMakerEngine.RiskBook risk,long lp,double[] depth) {
        Account account=book.accounts.get(lp);
        if(risk==null || account==null || !symbol.continuous || symbol.adminHalted || !book.lpCashBudgets.containsKey(symbol.id))return 0;
        int threshold=MarketMakerEngine.MIN_USABLE_DEPTH;
        if(depth[2]>threshold && depth[3]>threshold)return 0;
        // Include encumbered assets: consuming an LP bid creates sellable inventory,
        // and consuming an LP ask creates cash for new bids within this same batch.
        long reserved=book.working(lp,symbol.id).stream().mapToLong(o->o.reservedCash).sum();
        int inventory=book.holding(lp,symbol.id).quantity();
        boolean resources=inventory>0 || (Math.min(account.cash+reserved,book.lpCashBudgets.get(symbol.id)+reserved)>symbol.last
                && inventory<Math.min(risk.maxInventory(),risk.riskLimit()/Math.max(1,symbol.last)));
        return resources?MarketMakerEngine.MAX_QUOTE_ORDERS:0;
    }

    private void refreshLiquidity(BatchOrderBook book,Stock symbol,Observation observation,
                                  MarketMakerEngine.RiskBook risk,long lp,long now,boolean scheduled) {
        long stock=symbol.id;
        Account account=book.accounts.get(lp);
        if(risk==null || account==null || !symbol.continuous || symbol.adminHalted || !book.lpCashBudgets.containsKey(stock))return;
        double[] depth=book.nearbyDepth(stock);
        // Refill before one ordinary lot drains the near-price book, even if remote walls remain.
        boolean asksRecoverable=depth[3]<=MarketMakerEngine.MIN_USABLE_DEPTH && book.available(lp,stock)>0;
        boolean bidsRecoverable=depth[2]<=MarketMakerEngine.MIN_USABLE_DEPTH && Math.min(account.cash,book.lpCashBudgets.get(stock))>symbol.last
                && book.holding(lp,stock).quantity()<Math.min(risk.maxInventory(),risk.riskLimit()/Math.max(1,symbol.last));
        if(!(scheduled && now>=nextQuote.getOrDefault(stock,0L)) && !asksRecoverable && !bidsRecoverable)return;
        var metrics=withBook(observation.metrics(),symbol.last,depth);
        book.working(lp,stock).forEach(book::cancel);
        double[] external=book.depth(stock,lp);
        var band=quoteBand(symbol.code,symbol.reference,observation.news());
        var limits=new MarketMakerEngine.QuoteConstraints(band.lowerPrice(),band.upperPrice(),
                external[2]>0?Math.round(external[0]):0,external[3]>0?Math.round(external[1]):0);
        var availableRisk=new MarketMakerEngine.RiskBook(Math.max(0,Math.min(account.cash,book.lpCashBudgets.get(stock))),
                book.holding(lp,stock).quantity(),risk.targetInventory(),risk.maxInventory(),risk.riskLimit());
        double anchor=StockMarketProfile.valuationAnchor(symbol.code,symbol.reference,observation.fundamental(),observation.news());
        for(var quote:maker.quotes(metrics,observation.environment(),availableRisk,tickSize(symbol.last),observation.scale(),limits,anchor,
                book.liquidityPressure(stock,lp))) {
            long price=quote.side().equals("BUY")?floorToTick(Math.round(quote.price())):ceilToTick(Math.round(quote.price()));
            int quantity=quote.side().equals("BUY")
                    ?(int)Math.min(quote.quantity(),Math.max(0,book.lpCashBudgets.get(stock))/Math.max(1,price+BatchOrderBook.fee(price)))
                    :Math.min(quote.quantity(),book.available(lp,stock));
            book.submit(lp,stock,quote.side(),"LIMIT",quantity,price,now,LIQUIDITY_QUOTE_TTL);
        }
        // An early top-up must not postpone the normal refresh or consume participant randomness.
        if(now>=nextQuote.getOrDefault(stock,0L))
            nextQuote.put(stock,now+(StockMarketProfile.isLargeCap(symbol.code)?500:1200)+random.nextInt(600));
    }

    static PriceMetricService.Metrics withBook(PriceMetricService.Metrics m,long last,double[] d) {
        double mid=d[2]>0&&d[3]>0?(d[0]+d[1])/2:last;
        double micro=d[2]>0&&d[3]>0?(d[1]*d[2]+d[0]*d[3])/(d[2]+d[3]):mid;
        // An absent side is not a zero-priced executable quote, even immediately after a trade.
        double bid=d[2]>0?d[0]:previousTickPrice(last),ask=d[3]>0?d[1]:nextTickPrice(last);
        return stabilizeQuietBook(new PriceMetricService.Metrics(last,mid,micro,m.vwap(),m.markPrice(),bid,ask,(long)d[2],(long)d[3],(d[2]-d[3])/Math.max(1,d[2]+d[3]),m.return5s(),m.return20s(),m.emaSlope(),m.recentHigh(),m.recentLow(),m.volumeTrend(),m.acceleration(),m.rsi(),m.zscore(),m.volatility(),m.volume(),m.return60s(),m.return300s(),m.referenceVwap(),m.recentVolume()));
    }

    /** Bot decisions must not chase a resting book after executions have gone quiet. */
    static PriceMetricService.Metrics stabilizeQuietBook(PriceMetricService.Metrics m) {
        if (m.recentVolume() > 0) return m;
        long anchor=Math.round(m.lastPrice());
        // Valuation stays anchored, but executable quotes must remain real. An
        // invented inside ask prevents all buying, especially near a daily floor
        // where fitting the LP ladder can widen the spread beyond one percent.
        long bid=m.bidDepth()>0 ? Math.round(m.bestBid()) : previousTickPrice(anchor);
        long ask=m.askDepth()>0 ? Math.round(m.bestAsk()) : nextTickPrice(anchor);
        return new PriceMetricService.Metrics(anchor,anchor,anchor,anchor,anchor,bid,ask,m.bidDepth(),m.askDepth(),m.imbalance(),m.return5s(),m.return20s(),m.emaSlope(),m.recentHigh(),m.recentLow(),m.volumeTrend(),m.acceleration(),m.rsi(),m.zscore(),m.volatility(),m.volume(),m.return60s(),m.return300s(),anchor,m.recentVolume());
    }
}
