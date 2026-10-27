package com.gamestock.backend.market;

import java.time.Instant;
import java.util.Comparator;
import java.util.SplittableRandom;

import static com.gamestock.backend.market.DerivativeModels.OrderRequest;
import static com.gamestock.backend.market.MarketEnvironment.clamp;

/** Eight persistent styles: half futures, half long calls/puts; no spot or ETF orders. */
final class DerivativeBotStrategy {
    enum Style { FUTURE_TREND, FUTURE_FADE, FUTURE_LONG, FUTURE_SHORT,
        OPTION_TREND, OPTION_FADE, OPTION_CALL, OPTION_PUT }

    record Holding(long id, String kind, String side, int quantity, double entryPrice, long entryValue,
                   long margin, double strike, Instant expiry, Instant opened) { }
    record Decision(OrderRequest open, Long close) {
        static Decision waitForMarket() { return new Decision(null, null); }
    }

    static Style style(int ordinal) { return Style.values()[Math.floorMod(ordinal, Style.values().length)]; }

    static long delaySeconds(int ordinal, long actions) { return 9 + random(ordinal, actions).nextInt(22); }

    static Decision decide(int ordinal, long actions, long cash, Holding holding,
                           DerivativeMarketImpact.Snapshot market, Instant now) {
        Style style = style(ordinal);
        var random = random(ordinal, actions);
        double trend = clamp((market.index() / market.previousIndex() - 1) * 100, -1, 1);
        double noise = random.nextDouble(-.22, .22);
        double signal = switch (style) {
            case FUTURE_TREND, OPTION_TREND -> trend + noise;
            case FUTURE_FADE, OPTION_FADE -> -trend + noise;
            case FUTURE_LONG, OPTION_CALL -> .35 + trend * .2 + noise;
            case FUTURE_SHORT, OPTION_PUT -> -.35 + trend * .2 + noise;
        };
        if (holding != null) {
            long age = now.getEpochSecond() - holding.opened().getEpochSecond();
            // Expired options are settled by the fixing worker, never at a new market price.
            if (holding.expiry() != null && !holding.expiry().isAfter(now)) return Decision.waitForMarket();
            boolean positive = "LONG".equals(holding.side()) || "CALL".equals(holding.side());
            double pnl, capital;
            if ("FUTURE".equals(holding.kind())) {
                pnl = (market.index() - holding.entryPrice()) * DerivativePricing.CONTRACT_MULTIPLIER
                        * holding.quantity() * (positive ? 1 : -1);
                capital = holding.margin();
            } else {
                if (holding.expiry() == null) return Decision.waitForMarket();
                double points = DerivativePricing.optionPrice(market.index(), holding.strike(),
                        DerivativePricing.yearsLeft(holding.expiry(), now), market.volatility(), positive);
                pnl = DerivativePricing.premiumWon(points) * (double) holding.quantity() - holding.entryValue();
                capital = holding.entryValue();
            }
            double performance = pnl / Math.max(1, capital);
            boolean stop = performance <= ("FUTURE".equals(holding.kind()) ? -.30 : -.45);
            boolean profit = performance >= ("FUTURE".equals(holding.kind()) ? .45 : .80);
            boolean flip = age >= 45 && Math.abs(signal) > .25 && positive != (signal > 0);
            boolean nearExpiry = holding.expiry() != null && !holding.expiry().isAfter(now.plusSeconds(25));
            long horizon = 120 + Math.floorMod(ordinal * 47, 360);
            return stop || profit || flip || nearExpiry || age >= horizon
                    ? new Decision(null, holding.id()) : Decision.waitForMarket();
        }
        if (cash <= 100_000 || !(market.index() > 0)) return Decision.waitForMarket();
        boolean positive = signal >= 0;
        int maxQuantity = Math.min(2, (int) Math.floor(3_000_000 / (market.index() * DerivativePricing.CONTRACT_MULTIPLIER)));
        if (maxQuantity <= 0) return Decision.waitForMarket();
        if (style.ordinal() < 4) {
            int leverage = DerivativePricing.LEVERAGES.get(Math.floorMod(ordinal / 8, 3));
            long notional = DerivativePricing.contractNotional(market.index(), 1);
            long cost = (long) Math.ceil(notional / (double) leverage)
                    + DerivativePricing.fee(notional, DerivativePricing.FUTURE_FEE_RATE);
            int quantity = (int) Math.min(maxQuantity, Math.min(cash - 100_000, cash * .55) / Math.max(1, cost));
            return quantity < 1 ? Decision.waitForMarket() : new Decision(new OrderRequest("FUTURE",
                    positive ? "LONG" : "SHORT", quantity, "FUTIDX", leverage, null, null, null), null);
        }
        String cycle = ordinal % 3 == 0 ? "1H" : "10M";
        Instant expiry = DerivativePricing.expiryFor(cycle, now);
        if (!expiry.isAfter(now.plusSeconds(60))) return Decision.waitForMarket();
        double strike = DerivativePricing.strikes(market.index()).stream()
                .min(Comparator.comparingDouble(value -> Math.abs(value - market.index()))).orElse(market.index());
        long premium = DerivativePricing.premiumWon(DerivativePricing.optionPrice(market.index(), strike,
                DerivativePricing.yearsLeft(expiry, now), market.volatility(), positive));
        long cost = premium + DerivativePricing.fee(premium, DerivativePricing.OPTION_FEE_RATE);
        int quantity = (int) Math.min(maxQuantity, Math.min(cash - 100_000, cash * .10) / Math.max(1, cost));
        return quantity < 1 ? Decision.waitForMarket() : new Decision(new OrderRequest("OPTION",
                positive ? "CALL" : "PUT", quantity, null, null, cycle, strike, expiry.getEpochSecond()), null);
    }

    private static SplittableRandom random(int ordinal, long actions) {
        return new SplittableRandom(0x5d0f4a8c39b12761L ^ (ordinal + 1L) * 0x9e3779b97f4a7c15L ^ actions);
    }
}
