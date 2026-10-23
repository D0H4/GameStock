package com.gamestock.backend.market;

import java.util.*;
import static com.gamestock.backend.market.PriceLimitPolicy.*;

/**
 * Persistent stochastic market sentiment, with exact elapsed-time mean reversion toward durable
 * economic value. Strategies choose their own mix of value, sentiment and executed-price signals.
 * Daily bands constrain executable observations but never redefine economic value.
 */
final class MarketConsensusValue {
    private static final double DAY_MS=86_400_000.0;
    private static final double EDGE_BAND_SHARE=.97;
    record Checkpoint(double value,long at,long randomState) { }
    private static final class State {
        double value; long at, randomState;
        State(double value,long at,long randomState){this.value=value;this.at=at;this.randomState=randomState;}
        long next() {
            long z=(randomState+=0x9e3779b97f4a7c15L);
            z=(z^(z>>>30))*0xbf58476d1ce4e5b9L;z=(z^(z>>>27))*0x94d049bb133111ebL;
            return z^(z>>>31);
        }
        double gaussian() {
            double a=((next()>>>11)+1.0)/9007199254740993.0;
            double b=(next()>>>11)*0x1.0p-53;
            return Math.sqrt(-2*Math.log(a))*Math.cos(2*Math.PI*b);
        }
    }
    private final Map<Long,State> states=new HashMap<>();
    private final long seed;

    MarketConsensusValue(long seed){this.seed=seed;}
    Map<Long,Checkpoint> checkpoint() {
        Map<Long,Checkpoint> result=new HashMap<>();
        states.forEach((id,s)->result.put(id,new Checkpoint(s.value,s.at,s.randomState)));
        return Map.copyOf(result);
    }
    void restore(Map<Long,Checkpoint> saved) {
        states.clear();
        saved.forEach((id,s)->{
            if(!Double.isFinite(s.value())||s.value()<=0)throw new IllegalArgumentException("Invalid consensus checkpoint");
            states.put(id,new State(s.value(),s.at(),s.randomState()));
        });
    }

    /** Daily volatility of the consensus; smaller, deeper companies diffuse more slowly. */
    static double dailyVolatility(String code) {
        return StockMarketProfile.isLargeCap(code) ? .05 : Math.min(.12,.05*StockMarketProfile.of(code).movementWeight());
    }

    /** Minutes for the consensus to close half the gap to a changed news target. */
    static double pullHalfLifeMinutes(String code) {return StockMarketProfile.isLargeCap(code)?60:30;}

    /** Economic value is independent of the calendar's execution limits, for every listing. */
    static double target(BatchOrderBook.Stock symbol,double fundamental,double news) {
        if(!Double.isFinite(fundamental)||fundamental<=0)throw new IllegalArgumentException("Invalid fundamental");
        return Math.max(100,fundamental);
    }

    double observe(BatchOrderBook.Stock symbol,double fundamental,double news,MarketEnvironment environment,long now) {
        double target=target(symbol,fundamental,news);
        // News changes willingness to trade gradually, including when a listing is first observed.
        State state=states.computeIfAbsent(symbol.id,id->new State(Math.max(100,symbol.last),now,seed^id));
        double reference=Math.max(1,symbol.reference),limit=BOT_DAILY_LIMIT_RATE;
        double dt=Math.max(0,(double)now-state.at);state.at=Math.max(now,state.at);
        if(dt>0) {
            double decay=Math.log(2)/(pullHalfLifeMinutes(symbol.code)*60_000.0);
            double pull=-Math.expm1(-dt*decay);
            // Exact OU transition: account for all elapsed time without looping through missed
            // batches, inventing fills, or making variance depend on scheduler cadence.
            double sigma=dailyVolatility(symbol.code)*Math.sqrt(-Math.expm1(-2*decay*dt)/(2*decay*DAY_MS));
            double drift=environment==null?0:MarketEnvironment.clamp(environment.directionBias(),-1,1)
                    *dailyVolatility(symbol.code)*2*pull/(decay*DAY_MS);
            double logValue=Math.log(state.value)+Math.log(target/state.value)*pull+drift+sigma*state.gaussian();
            state.value=MarketEnvironment.clamp(Math.exp(logValue),reference*(1-limit*EDGE_BAND_SHARE),reference*(1+limit*EDGE_BAND_SHARE));
        }
        return state.value;
    }
}
