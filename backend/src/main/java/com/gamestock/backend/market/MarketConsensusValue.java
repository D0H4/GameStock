package com.gamestock.backend.market;

import java.util.*;
import static com.gamestock.backend.market.PriceLimitPolicy.*;

/**
 * The value traders and the LP quote around. A fixed anchor (yesterday's close, or a news
 * fundamental clipped to the band) left large caps in a box and parked small caps just under
 * the band, so the consensus instead diffuses like a price: a random walk scaled to each
 * listing's size, drifting with the market regime and pulled over tens of minutes, never
 * instantly, toward the news target. It starts at that target and stays inside the bot band.
 */
final class MarketConsensusValue {
    private static final double DAY_MS=86_400_000.0;
    private static final long MAX_STEP_MS=60_000;
    /** Share of the bot band the news pull aims for; the walk itself may still reach the edge. */
    private static final double TARGET_BAND_SHARE=.8,EDGE_BAND_SHARE=.97,SMALL_CAP_DAILY_SHARE=.5,SMALL_CAP_FULL_GAP=.10;
    private static final class State {double value;long at;State(double value,long at){this.value=value;this.at=at;}}
    private final Map<Long,State> states=new HashMap<>();
    private final Random random;

    MarketConsensusValue(long seed){random=new Random(seed);}

    /** Daily volatility of the consensus; smaller, deeper companies diffuse more slowly. */
    static double dailyVolatility(String code) {
        return StockMarketProfile.isLargeCap(code) ? .05 : Math.min(.12,.05*StockMarketProfile.of(code).movementWeight());
    }

    /** Minutes for the consensus to close half the gap to a changed news target. */
    static double pullHalfLifeMinutes(String code) {return StockMarketProfile.isLargeCap(code)?60:30;}

    /**
     * Large caps re-rate on news around the day reference. A small cap digests its news
     * fundamental over several sessions: a gap of up to ten points is priced today, beyond that
     * only half of the rest, and never more than half the band. Aiming for the whole gap parked
     * every hot small cap just under the band.
     */
    static double target(BatchOrderBook.Stock symbol,double fundamental,double news) {
        double reference=Math.max(1,symbol.reference),limit=BOT_DAILY_LIMIT_RATE;
        if(StockMarketProfile.isLargeCap(symbol.code))
            return MarketEnvironment.clamp(reference*(1+MarketEnvironment.clamp(news,-.15,.15)),
                    reference*(1-limit*TARGET_BAND_SHARE),reference*(1+limit*TARGET_BAND_SHARE));
        double gap=Math.log(Math.max(1,fundamental)/reference),size=Math.abs(gap);
        double today=Math.signum(gap)*(Math.min(size,SMALL_CAP_FULL_GAP)+.5*Math.max(0,size-SMALL_CAP_FULL_GAP));
        return reference*Math.exp(MarketEnvironment.clamp(today,Math.log(1-limit*SMALL_CAP_DAILY_SHARE),Math.log(1+limit*SMALL_CAP_DAILY_SHARE)));
    }

    double observe(BatchOrderBook.Stock symbol,double fundamental,double news,MarketEnvironment environment,long now) {
        double target=target(symbol,fundamental,news);
        // Start where the news says, so a restart neither forgets a re-rating nor freezes today's price.
        State state=states.computeIfAbsent(symbol.id,id->new State(target,now));
        double reference=Math.max(1,symbol.reference),limit=BOT_DAILY_LIMIT_RATE;
        double dt=Math.max(0,Math.min(MAX_STEP_MS,now-state.at));state.at=now;
        if(dt>0) {
            double pull=1-Math.exp(-dt*Math.log(2)/(pullHalfLifeMinutes(symbol.code)*60_000.0));
            double sigma=dailyVolatility(symbol.code)*Math.sqrt(dt/DAY_MS);
            // Drift scales with time, not with its square root: a full-strength regime moves the
            // consensus two daily deviations per day, however finely the batches slice that day.
            double drift=environment==null?0:MarketEnvironment.clamp(environment.directionBias(),-1,1)
                    *dailyVolatility(symbol.code)*2*dt/DAY_MS;
            double logValue=Math.log(state.value)+Math.log(target/state.value)*pull+drift+sigma*random.nextGaussian();
            state.value=MarketEnvironment.clamp(Math.exp(logValue),reference*(1-limit*EDGE_BAND_SHARE),reference*(1+limit*EDGE_BAND_SHARE));
        }
        return state.value;
    }
}
