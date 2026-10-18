package com.gamestock.backend.market;

import java.util.*;
import static com.gamestock.backend.market.BatchOrderBook.*;
import static com.gamestock.backend.market.PriceLimitPolicy.*;

/** A stalled book is relieved through refundable bot cancellations and bounded, funded fills. */
final class LiquidityRecoveryPolicy {
    static final long STALL_MILLIS=60_000;
    static final int MAX_BOT_LEVEL_QUANTITY=100;
    static final int WALL_QUANTITY=200;
    private static final long RECOVERY_INTERVAL=1_000;
    private static final int MAX_DISPERSION_TICKS=8;
    private static final class State {
        long price,since,lastRecovery=Long.MIN_VALUE;
        State(long price,long now){this.price=price;this.since=now;}
    }
    private final Map<Long,State> states=new HashMap<>();

    void reset(){states.clear();}

    boolean stalled(Stock stock,long now) {
        State state=states.computeIfAbsent(stock.id,id->new State(stock.last,now));
        if(state.price!=stock.last || now<state.since) {
            state.price=stock.last;state.since=now;state.lastRecovery=Long.MIN_VALUE;
        }
        return now-state.since>=STALL_MILLIS;
    }

    /** Preserve older bot priority; release the excess instead of deleting any human order. */
    void trimBotWalls(BatchOrderBook book,long stock) {
        for(String side:List.of("BUY","SELL")) {
            Map<Long,Long> quantities=new HashMap<>();
            for(Order order:book.resting(stock,side)) {
                Account owner=book.accounts.get(order.user);
                if(order.market() || !owner.bot || owner.liquidityProvider)continue;
                long total=quantities.getOrDefault(order.price,0L)+order.remaining;
                if(total>MAX_BOT_LEVEL_QUANTITY)book.cancel(order);
                else quantities.put(order.price,total);
            }
        }
    }

    /** Passive outward dispersion cannot chase the opposite book or escape the price band. */
    long dispersedPrice(BatchOrderBook book,long stock,String side,long price,int quantity,PriceBand band) {
        for(int step=0;step<=MAX_DISPERSION_TICKS && band.contains(price);step++) {
            if(book.participantQuantityAt(stock,side,price)+quantity<=MAX_BOT_LEVEL_QUANTITY)return price;
            price=side.equals("BUY")?previousTickPrice(price):nextTickPrice(price);
        }
        return 0;
    }

    /** At most ten shares per second, inside fair value +/-0.5%, with normal matching/protection. */
    boolean absorbWall(BatchOrderBook book,Stock stock,MarketMakerEngine.RiskBook risk,long lp,
                    double fairValue,long now) {
        State state=states.get(stock.id);
        if(state==null || !stalled(stock,now) || !stock.continuous || stock.adminHalted)return false;
        if(state.lastRecovery!=Long.MIN_VALUE && now-state.lastRecovery<RECOVERY_INTERVAL)return false;
        if(!book.lpCashBudgets.containsKey(stock.id))return false;
        boolean withdrawn=false;
        var sides=book.liquidityPressure(stock.id,lp)>0?List.of("BUY","SELL"):List.of("SELL","BUY");
        for(String wallSide:sides) {
            List<Order> external=book.resting(stock.id,wallSide).stream().filter(o->o.user!=lp && !o.market()).toList();
            if(external.isEmpty())continue;
            long price=external.get(0).price;
            long quantityAtPrice=external.stream().filter(o->o.price==price).mapToLong(o->o.remaining).sum();
            if(quantityAtPrice<WALL_QUANTITY || !botBand(stock.code,stock.reference).contains(price))continue;
            if(wallSide.equals("SELL")?price>fairValue*1.005:price<fairValue*.995)continue;
            book.working(lp,stock.id).forEach(book::cancel);
            withdrawn=true;
            int quantity=BotProfile.MAX_ORDER_SIZE;
            if(wallSide.equals("SELL")) {
                long reserve=MarketMakerEngine.emergencyCashRequirement(botBand(stock.code,stock.reference).upperPrice());
                long cash=Math.max(0,Math.min(book.accounts.get(lp).cash,book.lpCashBudgets.get(stock.id))-reserve);
                long capacity=Math.max(0,Math.min(risk.maxInventory(),risk.riskLimit()/Math.max(1,price))-book.holding(lp,stock.id).quantity());
                quantity=(int)Math.min(quantity,Math.min(capacity,cash/Math.max(1,price+BatchOrderBook.fee(price))));
            } else {
                // Retain the emergency ask ladder while releasing excess inventory to a real bidder.
                quantity=Math.min(quantity,Math.max(0,book.available(lp,stock.id)-MarketMakerEngine.emergencyInventoryRequirement()));
            }
            if(quantity<=0)continue;
            book.submit(lp,stock.id,wallSide.equals("SELL")?"BUY":"SELL","LIMIT",quantity,price,now,500);
            state.lastRecovery=now;return true;
        }
        if(withdrawn)state.lastRecovery=now;
        return withdrawn;
    }
}
