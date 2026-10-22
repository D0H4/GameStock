package com.gamestock.backend.market;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * ETF·선물·옵션 화면과 API가 공유하는 값. 모두 모의 시세이며 실제 금융상품이 아니다.
 */
public final class DerivativeModels {
    private DerivativeModels() { }

    /** ETF 구성 종목 한 줄. weight는 0~1 비율이다. */
    public record Constituent(String code, String name, double weight, double changePercent) { }

    public record EtfQuote(String code, String name, String theme, String description,
                           double price, double prevClose, double changePercent,
                           List<Constituent> constituents, List<Double> series) { }

    public record FutureQuote(String code, String name, double price, double prevClose,
                              double changePercent, int multiplier, List<Integer> leverages,
                              double maintenanceRate, List<Double> series) { }

    /** 만기 회차. ten-minute=0DTE, hourly=주간, sunday=월간으로 안내한다. */
    public record OptionCycle(String id, String label, String tag, String expiryAt,
                              long expiryEpoch, long secondsLeft) { }

    public record OptionContract(String id, String cycleId, String type, double strike,
                                 double mark, long premium, double breakeven,
                                 String expiryAt, long expiryEpoch, long secondsLeft) { }

    public record DerivativeSnapshot(double indexValue, double indexPrevClose, double indexChangePercent,
                                     List<EtfQuote> etfs, FutureQuote future,
                                     List<OptionCycle> cycles, List<OptionContract> options,
                                     String serverTime) { }

    public record Position(long id, String kind, String symbol, String name, String side,
                           int quantity, double entryPrice, double markPrice,
                           long entryValue, long margin, int leverage,
                           String optionType, double strike, String expiryAt,
                           long value, long unrealizedPnl, double unrealizedPercent,
                           String status, String openedAt) { }

    public record Trade(long id, long positionId, String action, String kind, String symbol,
                        String side, int quantity, double price, long cashFlow, long fee,
                        String createdAt) { }

    public record Account(long cash, long positionValue, long equity, long unrealizedPnl,
                          long realizedPnl, long totalFees,
                          List<Position> positions, List<Trade> trades) { }

    /**
     * kind: ETF | FUTURE | OPTION
     * side: ETF BUY/SELL, FUTURE LONG/SHORT, OPTION CALL/PUT
     * cycleId: OPTION일 때 10M | 1H | SUN
     */
    public record OrderRequest(@NotBlank String kind, @NotBlank String side,
                               @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.gamestock.backend.config.StrictIntegerDeserializer.class) @Min(1) @Max(100) int quantity,
                               String symbol,
                               @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.gamestock.backend.config.StrictIntegerDeserializer.Nullable.class) Integer leverage,
                               String cycleId, Double strike,
                               @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.gamestock.backend.config.StrictLongDeserializer.class) Long expiryEpoch) { }

    public record CloseRequest(@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.gamestock.backend.config.StrictIntegerDeserializer.class) @Min(1) @Max(100) int quantity) { }

    public record OrderResult(String message, Account account) { }
}
