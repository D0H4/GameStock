package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DerivativeBotStrategyTest {
    private static final Instant NOW = Instant.parse("2030-01-01T10:01:00Z");
    private DerivativeMarketImpact.Snapshot market(double index) {
        return new DerivativeMarketImpact.Accumulator(index, 1000, .8).snapshot(1, NOW, Set.of("GI"));
    }

    @Test void populationTradesOnlyFuturesAndOptionsInBothDirectionsWithinItsCashAndExposure() {
        var products = new HashSet<String>();
        for (int ordinal = 0; ordinal < 48; ordinal++) {
            var order = DerivativeBotStrategy.decide(ordinal, 1, 1_000_000, null, market(1000), NOW).open();
            assertNotNull(order, "Seeded bot " + ordinal + " should be able to afford a contract");
            products.add(order.kind() + ":" + order.side());
            assertTrue(order.quantity() >= 1 && order.quantity() <= 2);
            assertTrue(order.quantity() * 1000 * 1000 <= 3_000_000);
            if ("FUTURE".equals(order.kind())) {
                double notional = 1000 * 1000 * order.quantity();
                assertTrue(Math.ceil(notional / order.leverage()) + notional * .0002 <= 550_000);
            } else {
                long premium = DerivativePricing.premiumWon(DerivativePricing.optionPrice(1000, order.strike(),
                        DerivativePricing.yearsLeft(Instant.ofEpochSecond(order.expiryEpoch()), NOW), .8, "CALL".equals(order.side())));
                assertTrue(premium * order.quantity() * 1.003 <= 100_000);
            }
            assertTrue(DerivativeBotStrategy.delaySeconds(ordinal, 1) >= 9);
            assertTrue(DerivativeBotStrategy.delaySeconds(ordinal, 1) <= 30);
        }
        assertEquals(Set.of("FUTURE:LONG", "FUTURE:SHORT", "OPTION:CALL", "OPTION:PUT"), products);
    }

    @Test void losingShortsCoverLosingLongsSellAndThereIsNoAutomaticRefinancing() {
        for (String side : Set.of("LONG", "SHORT")) {
            var holding = new DerivativeBotStrategy.Holding(7, "FUTURE", side, 1, 1000, 1_000_000,
                    100_000, 0, null, NOW.minusSeconds(10));
            double adverse = "SHORT".equals(side) ? 1031 : 969;
            var decision = DerivativeBotStrategy.decide(0, 2, 0, holding, market(adverse), NOW);
            assertEquals(7, decision.close());
            assertNull(decision.open());
        }
        for (int ordinal = 0; ordinal < 48; ordinal++)
            assertNull(DerivativeBotStrategy.decide(ordinal, 3, 100_000, null, market(1000), NOW).open());
    }

    @Test void onePositionIsHeldUntilExitAndExpiredOptionsWaitForFixing() {
        var future = new DerivativeBotStrategy.Holding(9, "FUTURE", "LONG", 1, 1000, 1_000_000,
                500_000, 0, null, NOW.minusSeconds(1));
        var decision = DerivativeBotStrategy.decide(2, 2, 1_000_000, future, market(1000), NOW);
        assertNull(decision.open()); assertNull(decision.close());
        var option = new DerivativeBotStrategy.Holding(10, "OPTION", "CALL", 1, 1, 1000,
                0, 1000, NOW, NOW.minusSeconds(600));
        decision = DerivativeBotStrategy.decide(6, 2, 1_000_000, option, market(1010), NOW);
        assertNull(decision.open()); assertNull(decision.close());
        assertNull(DerivativeBotStrategy.decide(4, 1, 1_000_000, null, market(1000),
                Instant.parse("2030-01-01T10:09:40Z")).open());
    }
}
