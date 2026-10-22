package com.gamestock.backend.market;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * ETF·선물·옵션 가격 계산. DB나 스프링에 의존하지 않아 단위 테스트로 검증한다.
 * 모든 가격은 모의 시장 값이며 실제 금융상품의 공정가치가 아니다.
 */
final class DerivativePricing {
    private DerivativePricing() { }

    /** 1계약 = 지수 1포인트당 1,000원. */
    static final int CONTRACT_MULTIPLIER = 1_000;
    /** 선물 유지증거금 비율. 평가액이 증거금의 10% 이하이면 강제청산한다. */
    static final double MAINTENANCE_RATE = 0.10;
    static final List<Integer> LEVERAGES = List.of(2, 5, 10);
    static final double ETF_FEE_RATE = 0.001;
    static final double FUTURE_FEE_RATE = 0.0002;
    static final double OPTION_FEE_RATE = 0.003;
    private static final double YEAR_SECONDS = 365.0 * 24 * 60 * 60;
    private static final double DEFAULT_VOLATILITY = 0.80;
    private static final double MIN_VOLATILITY = 0.25;
    private static final double MAX_VOLATILITY = 1.25;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    static final String CYCLE_TEN_MINUTES = "10M";
    static final String CYCLE_HOURLY = "1H";
    static final String CYCLE_SUNDAY = "SUN";

    /** 표준정규분포 누적확률. 아브라모비츠-스테군 근사(오차 7.5e-8). */
    static double normalCdf(double x) {
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(x));
        double density = 0.3989422804014327 * Math.exp(-x * x / 2.0);
        double tail = density * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937
                + t * (-1.821255978 + t * 1.330274429))));
        return x >= 0 ? 1.0 - tail : tail;
    }

    /** 무위험 이자율 0의 블랙-숄즈 유럽형 옵션 가격(지수 포인트). */
    static double optionPrice(double spot, double strike, double years, double volatility, boolean call) {
        if (spot <= 0 || strike <= 0) return 0;
        double t = Math.max(1.0, years * YEAR_SECONDS) / YEAR_SECONDS;
        double vol = Math.max(0.01, volatility);
        double root = vol * Math.sqrt(t);
        double d1 = (Math.log(spot / strike) + 0.5 * vol * vol * t) / root;
        double d2 = d1 - root;
        double value = call
                ? spot * normalCdf(d1) - strike * normalCdf(d2)
                : strike * normalCdf(-d2) - spot * normalCdf(-d1);
        return Math.max(0, value);
    }

    /** 만기 시점의 내재가치(지수 포인트). */
    static double intrinsic(double spot, double strike, boolean call) {
        return call ? Math.max(0, spot - strike) : Math.max(0, strike - spot);
    }

    /** 지수 포인트를 원화 프리미엄으로 바꾼다. 최소 10원. */
    static long premiumWon(double points) {
        return Math.max(10, Math.round(points * CONTRACT_MULTIPLIER));
    }

    static long contractNotional(double index, int quantity) {
        return Math.round(index * CONTRACT_MULTIPLIER * quantity);
    }

    static long fee(long amount, double rate) {
        return Math.max(0, (long) Math.ceil(amount * rate));
    }

    /**
     * 회차별 다음 만기. 모두 현재 시각보다 뒤이며, 10분·1시간 회차는 정각 경계에 맞춘다.
     * 일요일 회차는 한국시간 일요일 18:00이다.
     */
    static Instant expiryFor(String cycleId, Instant now) {
        if (CYCLE_TEN_MINUTES.equals(cycleId)) {
            long epoch = now.getEpochSecond();
            return Instant.ofEpochSecond((epoch / 600 + 1) * 600);
        }
        if (CYCLE_HOURLY.equals(cycleId)) {
            long epoch = now.getEpochSecond();
            return Instant.ofEpochSecond((epoch / 3600 + 1) * 3600);
        }
        if (CYCLE_SUNDAY.equals(cycleId)) {
            ZonedDateTime local = now.atZone(SEOUL);
            ZonedDateTime candidate = local.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                    .withHour(18).withMinute(0).withSecond(0).withNano(0);
            if (!candidate.toInstant().isAfter(now)) candidate = candidate.plusWeeks(1);
            return candidate.toInstant();
        }
        throw new IllegalArgumentException("지원하지 않는 옵션 만기 회차입니다.");
    }

    static List<String> cycleIds() {
        return List.of(CYCLE_TEN_MINUTES, CYCLE_HOURLY, CYCLE_SUNDAY);
    }

    static String cycleLabel(String cycleId) {
        return switch (cycleId) {
            case CYCLE_TEN_MINUTES -> "10분 만기";
            case CYCLE_HOURLY -> "1시간 만기";
            case CYCLE_SUNDAY -> "일요일 18:00";
            default -> cycleId;
        };
    }

    static String cycleTag(String cycleId) {
        return switch (cycleId) {
            case CYCLE_TEN_MINUTES -> "0DTE";
            case CYCLE_HOURLY -> "주간";
            case CYCLE_SUNDAY -> "월간";
            default -> "";
        };
    }

    /** 만기별 행사가 후보. 지수 5포인트 단위, ATM ± 약 2%. */
    static List<Double> strikes(double index) {
        double atm = Math.max(5, Math.round(index / 5.0) * 5);
        double[] offsets = {-20, -10, -5, -2, 0, 2, 5, 10, 20};
        List<Double> result = new ArrayList<>(offsets.length);
        for (double offset : offsets) result.add(Math.max(1, atm + offset));
        return result;
    }

    /** 만기까지 남은 연 단위 시간. 최소 1초로 두어 0으로 나누지 않는다. */
    static double yearsLeft(Instant expiry, Instant now) {
        return Math.max(1.0, expiry.getEpochSecond() - now.getEpochSecond()) / YEAR_SECONDS;
    }

    /**
     * 최근 지수 표본의 실현 변동성. 표본이 부족하면 기본값을 쓴다.
     * 게임 지수는 실제 지수보다 크게 움직이므로 상·하한을 둔다.
     */
    static double realizedVolatility(List<double[]> samples) {
        if (samples.size() < 5) return DEFAULT_VOLATILITY;
        double sum = 0;
        int count = 0;
        for (int i = 1; i < samples.size(); i += 1) {
            double previous = samples.get(i - 1)[1];
            double current = samples.get(i)[1];
            if (previous <= 0 || current <= 0) continue;
            sum += Math.log(current / previous);
            count += 1;
        }
        if (count < 4) return DEFAULT_VOLATILITY;
        double mean = sum / count;
        double variance = 0;
        double seconds = 0;
        for (int i = 1; i < samples.size(); i += 1) {
            double previous = samples.get(i - 1)[1];
            double current = samples.get(i)[1];
            if (previous <= 0 || current <= 0) continue;
            double value = Math.log(current / previous) - mean;
            variance += value * value;
            seconds += Math.max(0.5, samples.get(i)[0] - samples.get(i - 1)[0]);
        }
        variance = Math.sqrt(variance / Math.max(1, count - 1));
        double interval = Math.max(0.5, seconds / Math.max(1, count));
        double volatility = variance * Math.sqrt(YEAR_SECONDS / interval);
        return Math.max(MIN_VOLATILITY, Math.min(MAX_VOLATILITY, volatility));
    }
}
