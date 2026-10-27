package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DerivativeMarketImpactTest {
    private static final Instant NOW = Instant.parse("2030-01-01T10:01:00Z");

    private DerivativeMarketImpact.Snapshot futures(double index, double previous, int longs, int shorts) {
        var pressure = new DerivativeMarketImpact.Accumulator(index, previous, .8);
        pressure.future("LONG", longs, 1000, longs * 100_000L);
        pressure.future("SHORT", shorts, 1000, shorts * 100_000L);
        return pressure.snapshot(1, NOW, Set.of("GI", "TR"));
    }

    @Test void shortsSqueezeOnlyOnAnAdverseMoveOrMarginLossAndLongsMirrorThem() {
        var flat = futures(1000, 1000, 2, 18);
        var rising = futures(1003, 1000, 2, 18);
        var falling = futures(997, 1000, 2, 18);
        assertEquals(0, flat.squeeze());
        assertEquals(0, falling.squeeze());
        assertTrue(flat.direction() < 0);
        assertTrue(rising.squeeze() > .5);
        assertTrue(rising.direction() > 0, "Crowded shorts cover into a rising index");
        assertTrue(futures(997, 1000, 18, 2).direction() < 0, "Crowded longs sell into a falling index");
        assertEquals(0, futures(1003, 1000, 10, 10).squeeze());
        assertTrue(futures(1030, 1030, 2, 18).squeeze() > 0, "Accumulated margin loss still matters without fresh momentum");
    }

    @Test void realCoveringAndLongLiquidationsPersistAfterOpenInterestDisappearsButAreBounded() {
        var cover = new DerivativeMarketImpact.Accumulator(1000, 1000, .8);
        cover.closedFuture("SHORT", 100_000_000_000.0);
        assertEquals(.45, cover.snapshot(1, NOW, Set.of("GI")).direction(), 1e-9);
        var sell = new DerivativeMarketImpact.Accumulator(1000, 1000, .8);
        sell.closedFuture("LONG", 100_000_000_000.0);
        assertEquals(-.45, sell.snapshot(1, NOW, Set.of("GI")).direction(), 1e-9);
        cover.future("SHORT", 1_000_000, 500, 1);
        assertTrue(Math.abs(cover.snapshot(1, NOW, Set.of("GI")).direction()) <= .85);
    }

    @Test void optionDeltaMatchesPremiumSensitivityAndCallsPutsDriveOppositePressure() {
        for (double spot : new double[]{800, 990, 1000, 1010, 1200}) {
            for (boolean call : new boolean[]{true, false}) {
                double delta = DerivativePricing.optionDelta(spot, 1000, .01, .8, call);
                double finiteDifference = (DerivativePricing.optionPrice(spot + .01, 1000, .01, .8, call)
                        - DerivativePricing.optionPrice(spot - .01, 1000, .01, .8, call)) / .02;
                assertEquals(finiteDifference, delta, .00002);
                assertTrue(call ? delta >= 0 && delta <= 1 : delta >= -1 && delta <= 0);
            }
        }
        assertEquals(0, DerivativePricing.optionDelta(1000, 1000, 0, .8, true));
        for (boolean call : new boolean[]{true, false}) {
            var flat = new DerivativeMarketImpact.Accumulator(1000, 1000, .8);
            flat.option(call, 10, 1000, .00002);
            assertTrue(call ? flat.snapshot(1, NOW, Set.of("GI")).direction() > 0
                    : flat.snapshot(1, NOW, Set.of("GI")).direction() < 0);
            var rising = new DerivativeMarketImpact.Accumulator(1002, 1000, .8);
            rising.option(call, 10, 1000, .00002);
            assertTrue(rising.snapshot(1, NOW, Set.of("GI")).hedgeFlow() > 0);
            var falling = new DerivativeMarketImpact.Accumulator(998, 1000, .8);
            falling.option(call, 10, 1000, .00002);
            assertTrue(falling.snapshot(1, NOW, Set.of("GI")).hedgeFlow() < 0);
        }
    }

    @Test void snapshotsDecayExpireRejectOldVersionsAndOnlyAffectBasketMembers() {
        var impact = new DerivativeMarketImpact();
        assertEquals(MarketEnvironment.neutral(), impact.environment("GI", NOW));
        impact.publish(futures(1003, 1000, 2, 18));
        double initial = impact.environment("GI", NOW).directionBias();
        assertTrue(initial > 0);
        assertEquals(initial / 2, impact.environment("GI", NOW.plusSeconds(6)).directionBias(), 1e-9);
        impact.publish(futures(997, 1000, 18, 2)); // same version must not replace the snapshot
        assertEquals(initial, impact.environment("GI", NOW).directionBias());
        assertEquals(MarketEnvironment.neutral(), impact.environment("OTHER", NOW));
        assertEquals(MarketEnvironment.neutral(), impact.environment("GI", NOW.minusSeconds(1)));
        assertEquals(MarketEnvironment.neutral(), impact.environment("GI", NOW.plusSeconds(12)));
        impact.publish(new DerivativeMarketImpact.Accumulator(1000, 1000, .8).snapshot(2, NOW, Set.of()));
        assertNull(impact.current(NOW));
    }

    @Test void productionSpotEnvironmentConsumesTheSnapshotWithoutChangingAStockPrice() {
        var market = new MarketService(mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(org.springframework.jdbc.core.JdbcTemplate.class), mock(UserFeatureService.class), mock(TradingProtectionService.class));
        ReflectionTestUtils.setField(market, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        var impact = new DerivativeMarketImpact();
        market.configureDerivativeImpact(impact);
        assertEquals(MarketEnvironment.neutral(), ReflectionTestUtils.invokeMethod(market, "environment", "GI"));
        impact.publish(futures(1003, 1000, 2, 18));
        MarketEnvironment actual = ReflectionTestUtils.invokeMethod(market, "environment", "GI");
        assertTrue(actual.directionBias() > 0);
        assertTrue(actual.buyPressure() > actual.sellPressure());
        assertEquals(MarketEnvironment.neutral(), ReflectionTestUtils.invokeMethod(market, "environment", "OTHER"));
    }
}
