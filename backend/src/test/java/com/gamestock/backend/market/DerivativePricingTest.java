package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DerivativePricingTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test void blackScholesPutCallParityHoldsWithoutRates() {
        double spot = 1000;
        double strike = 1010;
        double years = 7.0 / 365;
        double volatility = 0.9;
        double call = DerivativePricing.optionPrice(spot, strike, years, volatility, true);
        double put = DerivativePricing.optionPrice(spot, strike, years, volatility, false);
        assertEquals(spot - strike, call - put, 1e-9);
        assertTrue(call > 0);
        assertTrue(put > 0);
    }

    @Test void optionValueRisesWithTimeAndFallsWithDistance() {
        double near = DerivativePricing.optionPrice(1000, 1000, 10.0 / 525_600, 0.8, true);
        double far = DerivativePricing.optionPrice(1000, 1000, 60.0 / 525_600, 0.8, true);
        assertTrue(far > near, "만기가 길수록 프리미엄이 커야 한다");
        double atTheMoney = DerivativePricing.optionPrice(1000, 1000, 60.0 / 525_600, 0.8, true);
        double away = DerivativePricing.optionPrice(1000, 1100, 60.0 / 525_600, 0.8, true);
        assertTrue(atTheMoney > away);
        assertTrue(DerivativePricing.optionPrice(1000, 900, 60.0 / 525_600, 0.8, false)
                > DerivativePricing.optionPrice(1000, 800, 60.0 / 525_600, 0.8, false));
    }

    @Test void premiumIsAtLeastTenWonAndRounded() {
        assertEquals(10, DerivativePricing.premiumWon(0));
        assertEquals(1_234_000, DerivativePricing.premiumWon(1234));
        assertEquals(1, DerivativePricing.fee(1, 0.001));
        assertEquals(0, DerivativePricing.fee(0, 0.001));
    }

    @Test void tenMinuteExpiryLandsOnTheNextTenMinuteBoundary() {
        Instant now = Instant.parse("2026-10-22T03:03:17Z");
        Instant expiry = DerivativePricing.expiryFor(DerivativePricing.CYCLE_TEN_MINUTES, now);
        assertEquals(Instant.parse("2026-10-22T03:10:00Z"), expiry);
        assertTrue(expiry.isAfter(now));
        assertEquals(0, expiry.getEpochSecond() % 600);
    }

    @Test void hourlyExpiryLandsOnTheNextHour() {
        Instant now = Instant.parse("2026-10-22T03:00:00Z");
        assertEquals(Instant.parse("2026-10-22T04:00:00Z"),
                DerivativePricing.expiryFor(DerivativePricing.CYCLE_HOURLY, now));
    }

    @Test void sundayExpiryUsesKoreanSundayEvening() {
        // 목요일 오전 → 다음 일요일 18:00 KST
        Instant thursday = ZonedDateTime.of(2026, 10, 22, 9, 0, 0, 0, SEOUL).toInstant();
        Instant expiry = DerivativePricing.expiryFor(DerivativePricing.CYCLE_SUNDAY, thursday);
        ZonedDateTime local = expiry.atZone(SEOUL);
        assertEquals(DayOfWeek.SUNDAY, local.getDayOfWeek());
        assertEquals(18, local.getHour());
        assertEquals(0, local.getMinute());
        assertTrue(expiry.isAfter(thursday));

        // 일요일 18:00 이후 → 다음 주 일요일
        Instant sundayEvening = ZonedDateTime.of(2026, 10, 25, 18, 30, 0, 0, SEOUL).toInstant();
        Instant next = DerivativePricing.expiryFor(DerivativePricing.CYCLE_SUNDAY, sundayEvening);
        assertEquals(ZonedDateTime.of(2026, 11, 1, 18, 0, 0, 0, SEOUL).toInstant(), next);
    }

    @Test void strikesSurroundTheIndexAndStayPositive() {
        List<Double> strikes = DerivativePricing.strikes(1000);
        assertTrue(strikes.contains(1000.0));
        assertTrue(strikes.contains(980.0));
        assertTrue(strikes.contains(1020.0));
        assertTrue(strikes.stream().allMatch(strike -> strike > 0));
        assertEquals(strikes.stream().distinct().count(), strikes.size());
        assertTrue(DerivativePricing.strikes(3).stream().allMatch(strike -> strike >= 1));
    }

    @Test void realizedVolatilityStaysInsideItsClamp() {
        assertEquals(0.8, DerivativePricing.realizedVolatility(List.of()), 0.0001);
        List<double[]> samples = new ArrayList<>();
        for (int i = 0; i < 200; i += 1) {
            samples.add(new double[]{i * 2.0, 1000 * Math.exp(Math.sin(i) * 0.01)});
        }
        double volatility = DerivativePricing.realizedVolatility(samples);
        assertTrue(volatility >= 0.25 && volatility <= 1.25);
    }
}
