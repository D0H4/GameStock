package com.gamestock.backend.market;

import java.util.*;
import static com.gamestock.backend.market.PriceLimitPolicy.*;
import static com.gamestock.backend.market.MarketModels.*;

/** Which accounts an order can touch, so the caller can lock them in a fixed order before matching. */
final class MarketExecutionLocks {
    private MarketExecutionLocks() { }

    record Resting(long id,long user,String side,long price,int remaining,int reservedQuantity,
                   boolean market,boolean expired) { }

    static SortedSet<Long> accountsFor(OrderRequest request,long user,List<Resting> rows,long reference,long last) {
        SortedSet<Long> result=new TreeSet<>();result.add(user);
        rows.stream().filter(Resting::expired).forEach(o->result.add(o.user()));
        var live=rows.stream().filter(o->!o.expired() && o.remaining()>0).toList();
        long bid=live.stream().filter(o->o.side().equals("BUY")).mapToLong(Resting::price).max().orElse(0);
        long ask=live.stream().filter(o->o.side().equals("SELL")).mapToLong(Resting::price).min().orElse(Long.MAX_VALUE);
        // Legacy crossed/market/unreserved books may cancel a maker and expose a later order.
        // The conservative path locks the entire live book; ordinary continuous books stay narrow.
        if(bid>=ask || live.stream().anyMatch(o->o.market() || o.side().equals("SELL") && o.reservedQuantity()<o.remaining())) {
            live.forEach(o->result.add(o.user()));return result;
        }
        boolean buy="BUY".equalsIgnoreCase(request.side());
        boolean market=request.orderType()==null || request.orderType().isBlank() || "MARKET".equalsIgnoreCase(request.orderType());
        long limit=market?(buy?Long.MAX_VALUE:0):request.price()==null?0:request.price();
        if(!buy) {
            // Rounded fees can make a fully reserved buyer unaffordable after a partial fill.
            // Include later crossing buyers as well in case the matcher cancels such an order.
            live.stream().filter(o->o.side().equals("BUY") && o.price()>=limit).forEach(o->result.add(o.user()));
            return result;
        }
        long remaining=Math.max(0,request.quantity());
        // Stable sort preserves price-time order from the DB, including equal timestamps.
        for(Resting sell:live.stream().filter(o->o.side().equals("SELL") && o.price()<=limit)
                .sorted(Comparator.comparingLong(Resting::price)).toList()) {
            if(remaining<=0) break;
            result.add(sell.user());
            if(sell.user()==user) continue; // STP may cancel either row if a legacy timestamp is ahead.
            // Quotes outside any execution band might be cancelled instead of consumed.
            // Counting only this safe intersection gives a conservative superset of counterparties.
            if(dailyBand(request.stockCode(),reference).contains(sell.price()) && botBand(request.stockCode(),reference).contains(sell.price())
                    && (!market || marketExecutionBand(last).contains(sell.price()))) remaining-=sell.remaining();
        }
        return result;
    }
}
