package com.gamestock.backend.market;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

import static com.gamestock.backend.market.MarketEnvironment.clamp;

/** Derivative positioning changes funded spot order decisions, never a stock price directly. */
@Component
public class DerivativeMarketImpact {
    static final long MAX_AGE_SECONDS = 12;
    private volatile Snapshot latest;

    record Snapshot(long version, Instant at, Set<String> constituents, double index, double previousIndex,
                    double volatility, double longs, double shorts, double optionDelta,
                    double squeeze, double closingFlow, double hedgeFlow, double direction) {
        Snapshot { constituents = Set.copyOf(constituents); }
    }

    synchronized void publish(Snapshot next) {
        if (latest == null || next.version() > latest.version()) latest = next;
    }

    Snapshot current(Instant now) {
        Snapshot value = latest;
        return value != null && !value.constituents().isEmpty() && !now.isBefore(value.at())
                && now.isBefore(value.at().plusSeconds(MAX_AGE_SECONDS)) ? value : null;
    }

    MarketEnvironment environment(String code, Instant now) {
        Snapshot value = current(now);
        if (value == null || !value.constituents().contains(code)) return MarketEnvironment.neutral();
        double decay = 1 - (now.toEpochMilli() - value.at().toEpochMilli()) / (MAX_AGE_SECONDS * 1000.0);
        double direction = value.direction() * decay;
        double stress = Math.abs(value.squeeze()) * decay;
        return new MarketEnvironment(direction, stress * .3, 1 + stress * .6,
                1 + Math.max(0, direction), 1 + Math.max(0, -direction),
                1 + Math.abs(direction) * .3, 1, 0, stress * .05, 0,
                1 + stress * .4, 1 + stress * .2);
    }

    /** Accumulated during the existing paginated mark pass; no position scans on the spot hot path. */
    static final class Accumulator {
        private final double index, previous, volatility;
        private double longs, shorts, stressedLongs, stressedShorts, optionDelta, hedgeFlow, closingFlow;

        Accumulator(double index, double previous, double volatility) {
            this.index = index;
            this.previous = previous > 0 && Double.isFinite(previous) ? previous : index;
            this.volatility = volatility;
        }

        void future(String side, int quantity, double entry, long margin) {
            double notional = index * DerivativePricing.CONTRACT_MULTIPLIER * quantity;
            double pnl = (index - entry) * DerivativePricing.CONTRACT_MULTIPLIER * quantity;
            boolean shortSide = "SHORT".equals(side);
            double loss = clamp(-(shortSide ? -pnl : pnl) / Math.max(1, margin), 0, 1);
            if (shortSide) { shorts += notional; stressedShorts += notional * loss; }
            else { longs += notional; stressedLongs += notional * loss; }
        }

        void option(boolean call, int quantity, double strike, double years) {
            double delta = DerivativePricing.optionDelta(index, strike, years, volatility, call);
            double priorDelta = DerivativePricing.optionDelta(previous, strike, years, volatility, call);
            double notional = index * DerivativePricing.CONTRACT_MULTIPLIER * quantity;
            optionDelta += delta * notional;
            // Model the change in a dealer's hedge, rather than buying the entire delta every tick.
            hedgeFlow += (delta - priorDelta) * notional;
        }

        void closedFuture(String side, double notional) {
            closingFlow += "SHORT".equals(side) ? notional : -notional;
        }

        Snapshot snapshot(long version, Instant at, Set<String> constituents) {
            double gross = longs + shorts;
            double scale = Math.max(2_000_000, gross + Math.abs(optionDelta));
            double move = index / previous - 1;
            double up = clamp(move / .003, 0, 1), down = clamp(-move / .003, 0, 1);
            // Crowding alone is not a squeeze: an adverse move and/or margin losses are required.
            double shortCrowd = gross == 0 ? 0 : clamp((shorts / gross - .55) / .45, 0, 1);
            double longCrowd = gross == 0 ? 0 : clamp((longs / gross - .55) / .45, 0, 1);
            double shortStress = shorts == 0 ? 0 : stressedShorts / shorts;
            double longStress = longs == 0 ? 0 : stressedLongs / longs;
            double participation = Math.min(1, gross / 2_000_000);
            double squeeze = .75 * participation * (shortCrowd * Math.max(up, shortStress)
                    - longCrowd * Math.max(down, longStress));
            double flow = clamp(closingFlow / scale, -1, 1) * .45;
            double hedge = clamp(hedgeFlow / scale * 2, -.3, .3);
            double direction = clamp(.16 * (longs - shorts) / scale + .20 * optionDelta / scale
                    + squeeze + flow + hedge, -.85, .85);
            return new Snapshot(version, at, constituents, index, previous, volatility, longs, shorts,
                    optionDelta, squeeze, flow, hedge, direction);
        }
    }
}
