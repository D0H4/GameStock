package com.gamestock.backend.market;

import java.util.*;

/**
 * Each trader is pinned to one symbol, so cash spent there never reaches another symbol's
 * traders. A symbol whose traders run out of both cash and shares stops trading for good.
 * Traders share one simulated investor pool instead: free cash above the pool mean tops up
 * traders who can no longer afford a few ordinary lots. Total cash is conserved exactly and
 * reserved cash on working orders is never touched.
 *
 * The LP refills its inventory with newly issued shares and sells them for trader cash, so
 * without a return path that cash would leave the pool for good. The LP's net gain over its
 * opening budgets comes back as an external contribution, starved traders first.
 */
final class TraderCapitalPool {
    /** Lots a trader should be able to buy before it counts as starved. */
    static final int FLOOR_LOTS=20;
    /** Never raise a starved trader above this share of the pool mean. */
    static final double FLOOR_MEAN_SHARE=.5;

    record Trader(long id,long cash,long price) { }

    /** Cash deltas per trader id; deltas sum to zero and no donor falls below the pool mean. */
    static Map<Long,Long> plan(List<Trader> traders) {return plan(traders,0);}

    /**
     * Deltas that sum to exactly {@code external} (all of it is placed, or none when there are
     * no traders). External cash fills starved traders before any trader donates; what remains
     * is shared evenly so the pool, not the LP, keeps it.
     */
    static Map<Long,Long> plan(List<Trader> traders,long external) {
        external=Math.max(0,external);
        if(traders.isEmpty() || traders.size()<2&&external==0)return Map.of();
        long total=0;for(Trader t:traders)total+=Math.max(0,t.cash());
        double mean=total/(double)traders.size();
        Map<Long,Long> needs=new LinkedHashMap<>();long need=0;
        for(Trader t:traders) {
            long floor=(long)Math.min(mean*FLOOR_MEAN_SHARE,(double)FLOOR_LOTS*BotProfile.MAX_ORDER_SIZE*Math.max(1,t.price()));
            if(t.cash()<floor){needs.put(t.id(),floor-Math.max(0,t.cash()));need+=floor-Math.max(0,t.cash());}
        }
        List<Trader> donors=traders.stream().filter(t->t.cash()>mean).sorted(Comparator.comparingLong(Trader::cash).reversed()).toList();
        long excess=0;for(Trader t:donors)excess+=(long)Math.floor(t.cash()-mean);
        long fromExternal=Math.min(external,need);
        // Keep one won of slack per donor so rounding never pulls a donor below the mean.
        long fromTraders=Math.max(0,Math.min(need-fromExternal,excess-donors.size()));
        long moved=fromExternal+fromTraders;
        Map<Long,Long> deltas=new LinkedHashMap<>();
        // Grant each starved trader the same fraction of its gap; rounding leftovers stay unplaced.
        long granted=0;
        if(moved>0)for(var e:needs.entrySet()) {
            long grant=(long)Math.floor(e.getValue()*(moved/(double)need));
            if(grant>0){deltas.put(e.getKey(),grant);granted+=grant;}
        }
        long externalUsed=Math.min(granted,fromExternal),fromDonors=granted-externalUsed;
        // Take from donors in proportion to their excess, the richest absorbing the rounding remainder.
        if(fromDonors>0) {
            long taken=0;
            for(Trader t:donors) {
                long share=(long)Math.floor((long)Math.floor(t.cash()-mean)*(fromDonors/(double)excess));
                if(share>0){deltas.merge(t.id(),-share,Long::sum);taken+=share;}
            }
            if(fromDonors>taken)deltas.merge(donors.get(0).id(),-(fromDonors-taken),Long::sum);
        }
        // Spread the rest of the external cash evenly; the poorest trader takes the remainder.
        long rest=external-externalUsed;
        if(rest>0) {
            long each=rest/traders.size();
            if(each>0)for(Trader t:traders)deltas.merge(t.id(),each,Long::sum);
            long remainder=rest-each*traders.size();
            if(remainder>0)deltas.merge(traders.stream().min(Comparator.comparingLong(Trader::cash)).orElseThrow().id(),remainder,Long::sum);
        }
        deltas.values().removeIf(v->v==0);
        return deltas;
    }
}
