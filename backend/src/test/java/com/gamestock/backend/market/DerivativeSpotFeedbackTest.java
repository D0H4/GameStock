package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DerivativeSpotFeedbackTest {
    private MarketSimulationHarness.Path run(boolean shortSqueeze, boolean funded) {
        var setup = new MarketSimulationHarness.Setup();
        setup.symbols = Set.of("TR");
        setup.sessionMillis = 120_000;
        if (!funded) setup.broke = Set.of("TR");
        var impact = new DerivativeMarketImpact();
        setup.extraEnvironment = (code,time) -> {
            var pressure = new DerivativeMarketImpact.Accumulator(shortSqueeze ? 1003 : 997, 1000, .8);
            pressure.future("SHORT", shortSqueeze ? 18 : 2, 1000, shortSqueeze ? 1_800_000 : 200_000);
            pressure.future("LONG", shortSqueeze ? 2 : 18, 1000, shortSqueeze ? 200_000 : 1_800_000);
            impact.publish(pressure.snapshot(time, Instant.ofEpochMilli(time), Set.of("TR")));
            return impact.environment(code, Instant.ofEpochMilli(time));
        };
        return MarketSimulationHarness.run(setup).get("TR");
    }

    @Test void squeezePressureChangesActualFundedFillsAndNeverMovesPriceWithoutParticipants() {
        var shorts = run(true, true);
        var longs = run(false, true);
        assertTrue(shorts.fills() > 0 && longs.fills() > 0);
        assertTrue(shorts.last() > longs.last(), "Covering should outperform forced long selling under the same seed: " + shorts + " / " + longs);
        var unfunded = run(true, false);
        assertEquals(0, unfunded.fills());
        assertEquals(0, unfunded.last(), "A squeeze signal cannot set a price without a funded counterparty");
        // The production matching harness also checks conserved cash (including fees) and shares.
    }
}
