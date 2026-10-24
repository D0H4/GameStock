package com.gamestock.backend.market;

import java.util.*;
import static com.gamestock.backend.market.BatchOrderBook.*;
import static com.gamestock.backend.market.PriceLimitPolicy.*;

/** Paces independently funded participant decisions from actual fills, never manufactures a trade. */
final class BotActivityEngine {
    private static final long LIQUIDITY_QUOTE_TTL=10_000L;
    private static final long REVERSE_COOLDOWN_MS=8_000L;
    private static final long SAME_PRICE_REENTRY_COOLDOWN_MS=6_000L;
    private static final long LARGE_CAP_QUOTE_REFRESH_MS=500L;
    private static final long SMALL_CAP_QUOTE_REFRESH_MS=1_200L;
    private static final double FLOOR_ZONE=.15,FLOOR_INVENTORY_ASK_PREMIUM=.012;
    record Observation(PriceMetricService.Metrics metrics,MarketEnvironment environment,double fundamental,double news,double scale) { }
    record Participant(long id,BotProfile profile,long stock) { }
    record Activity(long stock,String code,int fills,int orders,boolean continuous) { }
    private record ParticipantKey(long user,long stock) { }
    private static final class ExecutionMemory {
        String lastFillSide;
        long reverseBlockedUntil;
        long lastOrderAt=Long.MIN_VALUE;
        long lastOrderPrice;
        String lastOrderSide;
    }
    private final List<Participant> participants;
    private final Map<Long,List<Participant>> bySymbol=new LinkedHashMap<>();
    private final Map<Long,List<Participant>> pacedBySymbol=new HashMap<>();
    private final Map<Long,Long> due=new HashMap<>(),acquired=new HashMap<>();
    private final Map<Long,Integer> cursors=new HashMap<>();
    private final Map<Long,Double> credits=new HashMap<>();
    private final BotStrategyEngine strategies;
    private final BotOrderPolicy policy=new BotOrderPolicy();
    private final Random random;
    private final int target;
    private final Map<Long,Long> lastRebalance=new HashMap<>();
    private final Map<Long,Long> nextQuote=new HashMap<>();
    private final Map<ParticipantKey,ExecutionMemory> executionMemory=new HashMap<>();
    private final MarketMakerEngine maker=new MarketMakerEngine();
    private final LiquidityRecoveryPolicy recovery=new LiquidityRecoveryPolicy();
    private final MarketConsensusValue consensus;
    private BatchOrderBook observedBook;
    private int observedFillCursor;

    BotActivityEngine(List<Participant> participants,long seed,int target) {
        this.participants=List.copyOf(participants);this.strategies=new BotStrategyEngine(seed^37);this.random=new Random(seed^71);this.target=target;
        this.consensus=new MarketConsensusValue(seed^113);
        for(Participant p:participants){
            bySymbol.computeIfAbsent(p.stock(),k->new ArrayList<>()).add(p);
            executionMemory.put(new ParticipantKey(p.id(),p.stock()),new ExecutionMemory());
        }
        for(var group:bySymbol.values())Collections.shuffle(group,random);
    }

    Map<Long,MarketConsensusValue.Checkpoint> consensusCheckpoint(){return consensus.checkpoint();}
    void restoreConsensus(Map<Long,MarketConsensusValue.Checkpoint> saved){consensus.restore(saved);}
    private double traderShare=1;

    /** A wake/pulse starts fresh pacing; elapsed idle time never creates a backlog of orders. */
    void resume() {
        credits.clear();due.clear();nextQuote.clear();recovery.reset();executionMemory.values().forEach(memory->{
            memory.lastFillSide=null;memory.reverseBlockedUntil=0;
            memory.lastOrderAt=Long.MIN_VALUE;memory.lastOrderPrice=0;memory.lastOrderSide=null;
        });
        observedBook=null;observedFillCursor=0;
    }

    /**
     * Fraction of participant traders allowed to act (0 = traders paused, LP still quotes).
     * During a resume ramp only a sampled share decides each pass and the fill budget shrinks
     * with it, so hundreds of traders do not all react to the same stale gap at once.
     */
    void traderShare(double share) {traderShare=Math.max(0,Math.min(1,share));}

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
            Observation observed=observations.get(stock);
            if(symbol==null||observed==null)continue;
            if(!symbol.continuous){activity.add(new Activity(stock,symbol.code,0,0,false));continue;}
            double commonValue=consensus.observe(symbol,observed.fundamental(),observed.news(),observed.environment(),now);
            // Consensus is a reservation-value input only; inventory and paid execution VWAP
            // still constrain quotes. It never assigns a trade price or a candle.
            Observation observation=new Observation(observed.metrics(),observed.environment(),
                    StockMarketProfile.isLargeCap(symbol.code)?commonValue:
                            observed.metrics().referenceVwap()*.9+observed.fundamental()*.1,observed.news(),observed.scale());
            var group=pacedBySymbol.computeIfAbsent(stock,key->entry.getValue().stream()
                    .map(p->new Participant(p.id(),p.profile().forMarket(symbol.code),p.stock())).toList());
            int startFills=book.fills.size(),startOrders=book.accepted.size();
            int recent=recentFills.getOrDefault(stock,0);
            var risk=risks.get(stock);
            rememberNewFills(book,now);
            boolean stalled=recovery.stalled(symbol,now);
            if(stalled && !symbol.adminHalted && risk!=null && book.accounts.containsKey(lp)) {
                recovery.trimBotWalls(book,stock);
                if(recovery.absorbWall(book,symbol,risk,lp,maker.fairValue(observation.metrics(),observation.fundamental()),now))
                    nextQuote.remove(stock);
            }
            var marketMetrics=withBook(observation.metrics(),symbol.last,book.depth(stock));
            refreshLiquidity(book,symbol,observation,risk,lp,now,true);
            // LP recovery and refresh fills stay out of `recent`; only the participant restart trade counts.
            int lpFills=book.fills.size()-startFills;
            if(stalled && lpFills==0 && recent==0 && (traderShare>=1 || traderShare>0 && random.nextDouble()<traderShare)
                    && recovery.submitStalledFlow(book,symbol,group.stream().map(Participant::id).toList(),lp,now)) {
                recent+=book.fills.size()-startFills;
                rememberNewFills(book,now);
            }
            if(risk!=null && now-lastRebalance.getOrDefault(stock,0L)>=500) {
                // Gradually distribute an over-target LP position through funded external bids.
                // No transfer, refill, paired counterorder or price change happens outside matching.
                int excess=book.holding(lp,stock).quantity()-risk.targetInventory();
                // Let passive inventory skew absorb ordinary fills. Aggressive rebalancing of
                // tiny deviations otherwise reverses order flow every half second.
                int rebalanceThreshold=StockMarketProfile.isLargeCap(symbol.code)?Math.max(10,risk.targetInventory()/3):10;
                double[] external=book.depth(stock,lp);
                double liquidationFloor=maker.fairValue(marketMetrics,observation.fundamental())*.995;
                if(excess>rebalanceThreshold && external[2]>0 && external[0]>=liquidationFloor) {
                    book.submit(lp,stock,"SELL","LIMIT",Math.min(recent<30?15:5,Math.min(excess,book.available(lp,stock))),Math.round(external[0]),now,500);
                } else if(excess < -rebalanceThreshold && external[3]>0 && external[1]<=maker.fairValue(marketMetrics,observation.fundamental())*1.005
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
            if(traderShare<1)budget=(int)Math.ceil(budget*traderShare);
            int orderBudget=200/bySymbol.size();
            int cursor=cursors.getOrDefault(stock,0),examined=0;
            while(traderShare>0 && examined<group.size()*2 && book.fills.size()-startFills<budget && book.accepted.size()-startOrders<orderBudget && symbol.continuous) {
                Participant participant=group.get(cursor++%group.size());examined++;
                if(due.getOrDefault(participant.id(),0L)>now)continue;
                BotProfile p=participant.profile();
                due.put(participant.id(),now+Math.max(10,(long)(p.decisionInterval()*StockMarketProfile.decisionIntervalWeight(symbol.code)*(.65+random.nextDouble()*.7)))+p.reactionLatency());
                // A skipped trader waits a normal decision interval, so the ramp thins activity instead of queueing it.
                if(traderShare<1 && random.nextDouble()>=traderShare)continue;
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
                double anchor=BotStrategyEngine.valuationObservation(p,m,observed.fundamental(),commonValue,symbol.code);
                double researchNoise=.012*(StockMarketProfile.isLargeCap(symbol.code)?StockMarketProfile.of(symbol.code).movementWeight():1);
                double estimate=strategies.estimate(p,symbol.code,anchor*(1+random.nextGaussian()*researchNoise));
                double news=observation.news()*(1+p.valueError()*8+random.nextGaussian()*.25);
                var decision=strategies.decide(p,m,observation.environment(),position,estimate,news,tickSize(symbol.last),observation.scale(),symbol.code,symbol.reference);
                ExecutionMemory memory=executionMemory.computeIfAbsent(new ParticipantKey(participant.id(),stock),key->new ExecutionMemory());
                if(reverseCooling(memory,decision,p,m,position,now))continue;
                boolean replace=working.size()>1 || working.stream().anyMatch(o->o.remaining>BotProfile.MAX_ORDER_SIZE || now-o.createdAt>=30_000
                        || policy.shouldReplace(p,new BotOrderPolicy.RestingOrder(o.id,o.side,o.price,o.reservedCash,o.createdAt),decision,now,tickSize(o.price),o.side.equals("BUY")?m.bestBid():m.bestAsk(),o.side.equals("BUY")?m.bestAsk():m.bestBid()));
                if(!working.isEmpty()&&!replace)continue;
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
                if(!decision.market() && memory.lastOrderAt!=Long.MIN_VALUE
                        && now>=memory.lastOrderAt && now-memory.lastOrderAt<SAME_PRICE_REENTRY_COOLDOWN_MS
                        && memory.lastOrderSide.equals(decision.side())
                        && BotOrderPolicy.sameTick(memory.lastOrderPrice,price,tickSize(symbol.last)))continue;
                if(replace) {
                    working.forEach(book::cancel);
                    if(book.accepted.size()-startOrders+liquidityRepairReserve(book,symbol,risk,lp,book.nearbyDepth(stock))>=orderBudget)break;
                }
                var submitted=book.submit(participant.id(),stock,decision.side(),decision.market()?"MARKET":"LIMIT",quantity,price,now,Math.min(30_000,Math.max(50,decision.ttlMillis())));
                if(submitted!=null) {
                    memory.lastOrderAt=now;memory.lastOrderPrice=price;memory.lastOrderSide=decision.side();
                    rememberNewFills(book,now);
                }
            }
            cursors.put(stock,cursor%group.size());
            credits.put(stock,Math.max(0,credit-(book.fills.size()-startFills)));
            // Repair consumed liquidity before this batch is persisted and broadcast, even at its pacing limit.
            refreshLiquidity(book,symbol,observation,risk,lp,now,false);
            rememberNewFills(book,now);
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
        boolean scheduledRefresh=scheduled && now>=nextQuote.getOrDefault(stock,0L);
        if(!scheduledRefresh && !asksRecoverable && !bidsRecoverable)return;
        var metrics=withBook(observation.metrics(),symbol.last,depth);
        double[] external=book.depth(stock,lp);
        var band=quoteBand(symbol.code,symbol.reference,observation.news());
        // Near a small cap's floor, dip demand is one-sided and cash is plentiful while shares are not.
        // Selling the last LP lots at the touch would leave 1-share asks and nobody to refill them.
        boolean floorZone=!StockMarketProfile.isLargeCap(symbol.code) && symbol.last<symbol.reference*(1-FLOOR_ZONE);
        var limits=new MarketMakerEngine.QuoteConstraints(band.lowerPrice(),band.upperPrice(),
                external[2]>0?Math.round(external[0]):0,external[3]>0?Math.round(external[1]):0,
                floorZone?FLOOR_INVENTORY_ASK_PREMIUM:0);
        long reservedCash=book.working(lp,stock).stream().mapToLong(o->o.reservedCash).sum();
        long postCancelCash=Math.max(0,account.cash+reservedCash);
        long postCancelBudget=Math.max(0,book.lpCashBudgets.get(stock)+reservedCash);
        var availableRisk=new MarketMakerEngine.RiskBook(Math.max(0,Math.min(postCancelCash,postCancelBudget)),
                book.holding(lp,stock).quantity(),risk.targetInventory(),risk.maxInventory(),risk.riskLimit());
        double anchor=observation.fundamental();
        var quotes=maker.quotes(metrics,observation.environment(),availableRisk,tickSize(symbol.last),observation.scale(),limits,anchor,
                book.liquidityPressure(stock,lp));
        if(scheduledRefresh && !asksRecoverable && !bidsRecoverable
                && sameLpQuotes(book.working(lp,stock),quotes,postCancelBudget,book.holding(lp,stock).settled(),tickSize(symbol.last))) {
            nextQuote.put(stock,now+(StockMarketProfile.isLargeCap(symbol.code)?LARGE_CAP_QUOTE_REFRESH_MS:SMALL_CAP_QUOTE_REFRESH_MS)+random.nextInt(600));
            return;
        }
        book.working(lp,stock).forEach(book::cancel);
        long remainingBudget=postCancelBudget;int remainingInventory=book.holding(lp,stock).settled();
        for(var quote:quotes) {
            long price=quote.side().equals("BUY")?floorToTick(Math.round(quote.price())):ceilToTick(Math.round(quote.price()));
            int quantity;
            if(quote.side().equals("BUY")) {
                quantity=(int)Math.min(quote.quantity(),remainingBudget/Math.max(1,price+BatchOrderBook.fee(price)));
                if(quantity>0)remainingBudget-=price*(long)quantity+BatchOrderBook.fee(price*(long)quantity);
            } else {
                quantity=Math.min(quote.quantity(),remainingInventory);
                remainingInventory-=quantity;
            }
            book.submit(lp,stock,quote.side(),"LIMIT",quantity,price,now,LIQUIDITY_QUOTE_TTL);
        }
        // An early top-up must not postpone the normal refresh or consume participant randomness.
        if(now>=nextQuote.getOrDefault(stock,0L))
            nextQuote.put(stock,now+(StockMarketProfile.isLargeCap(symbol.code)?LARGE_CAP_QUOTE_REFRESH_MS:SMALL_CAP_QUOTE_REFRESH_MS)+random.nextInt(600));
    }

    private boolean sameLpQuotes(List<BatchOrderBook.Order> existing,List<MarketMakerEngine.Quote> quotes,
                                 long budget,int inventory,long tick) {
        Map<String,Integer> expected=new HashMap<>(),actual=new HashMap<>();
        long remainingBudget=budget;int remainingInventory=inventory;
        for(var quote:quotes) {
            long price=quote.side().equals("BUY")?floorToTick(Math.round(quote.price())):ceilToTick(Math.round(quote.price()));
            int quantity;
            if(quote.side().equals("BUY")) {
                quantity=(int)Math.min(quote.quantity(),remainingBudget/Math.max(1,price+BatchOrderBook.fee(price)));
                if(quantity>0)remainingBudget-=price*(long)quantity+BatchOrderBook.fee(price*(long)quantity);
            } else {
                quantity=Math.min(quote.quantity(),remainingInventory);remainingInventory-=quantity;
            }
            if(quantity>0)expected.merge(quoteKey(quote.side(),price,tick),quantity,Integer::sum);
        }
        for(var order:existing)actual.merge(quoteKey(order.side,order.price,tick),order.remaining,Integer::sum);
        return actual.equals(expected);
    }

    private String quoteKey(String side,long price,long tick) {return side+":"+price+":"+Math.max(1,tick);}

    private boolean reverseCooling(ExecutionMemory memory,BotStrategyEngine.Decision decision,
                                   BotProfile profile,PriceMetricService.Metrics metrics,
                                   BotStrategyEngine.Position position,long now) {
        if(memory.reverseBlockedUntil<=now || memory.lastFillSide==null || decision.quantity()<=0
                || decision.side().equals("HOLD") || memory.lastFillSide.equals(decision.side()))return false;
        boolean inventoryReduction="SELL".equals(decision.side())
                && position.quantity()>position.targetQuantity()+BotStrategyEngine.inventoryDeadband(position);
        if(inventoryReduction)return false;
        // Strong protective/trend signals can still reverse; ordinary inventory and noise
        // pressure must wait so a fill does not immediately become the opposite quote.
        return Math.abs(decision.score())<strategies.threshold(profile,metrics)*3;
    }

    private void rememberNewFills(BatchOrderBook book,long now) {
        if(observedBook!=book || observedFillCursor>book.fills.size()) {observedBook=book;observedFillCursor=0;}
        for(int i=observedFillCursor;i<book.fills.size();i++) {
            BatchOrderBook.Fill fill=book.fills.get(i);
            rememberFill(fill.buy().user,fill.buy().stock,"BUY",now);
            rememberFill(fill.sell().user,fill.sell().stock,"SELL",now);
        }
        observedFillCursor=book.fills.size();
    }

    private void rememberFill(long user,long stock,String side,long now) {
        ExecutionMemory memory=executionMemory.get(new ParticipantKey(user,stock));
        if(memory==null)return;
        memory.lastFillSide=side;memory.reverseBlockedUntil=now+REVERSE_COOLDOWN_MS;
    }

    static PriceMetricService.Metrics withBook(PriceMetricService.Metrics m,long last,double[] d) {
        double mid=d[2]>0&&d[3]>0?(d[0]+d[1])/2:last;
        double micro=d[2]>0&&d[3]>0?(d[1]*d[2]+d[0]*d[3])/(d[2]+d[3]):mid;
        // An absent side is not a zero-priced executable quote, even immediately after a trade.
        double bid=d[2]>0?d[0]:previousTickPrice(last),ask=d[3]>0?d[1]:nextTickPrice(last);
        return stabilizeQuietBook(new PriceMetricService.Metrics(last,mid,micro,m.vwap(),m.markPrice(),bid,ask,(long)d[2],(long)d[3],(d[2]-d[3])/Math.max(1,d[2]+d[3]),m.return5s(),m.return20s(),m.emaSlope(),m.recentHigh(),m.recentLow(),m.volumeTrend(),m.acceleration(),m.rsi(),m.zscore(),m.volatility(),m.volume(),m.return60s(),m.return300s(),m.referenceVwap(),m.recentVolume(),m.executionVwap()));
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
        return new PriceMetricService.Metrics(anchor,anchor,anchor,anchor,anchor,bid,ask,m.bidDepth(),m.askDepth(),m.imbalance(),m.return5s(),m.return20s(),m.emaSlope(),m.recentHigh(),m.recentLow(),m.volumeTrend(),m.acceleration(),m.rsi(),m.zscore(),m.volatility(),m.volume(),m.return60s(),m.return300s(),anchor,m.recentVolume(),anchor);
    }
}
