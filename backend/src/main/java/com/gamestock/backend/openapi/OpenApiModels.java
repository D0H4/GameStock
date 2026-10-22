package com.gamestock.backend.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.gamestock.backend.market.DerivativeModels;
import com.gamestock.backend.market.MarketModels;
import jakarta.validation.constraints.*;

import java.util.List;

public final class OpenApiModels {
    private OpenApiModels() { }

    public enum Permission { READ, TRADE }
    public enum Product { STOCK, ETF, FUTURE, OPTION }
    public enum Side { BUY, SELL }
    public enum OrderType { MARKET, LIMIT }
    public enum OptionType { CALL, PUT }

    public record CreateKey(@NotBlank @Size(max = 80) String name,
                            @NotNull Permission permission,
                            @Min(1) @Max(365) Integer expiresInDays) {
        @JsonAnySetter public void unknown(String name, JsonNode value) { throw new IllegalArgumentException("지원하지 않는 API 키 필드입니다."); }
    }
    public record KeyInfo(String id, String name, String prefix, Permission permission,
                          String createdAt, String expiresAt, String revokedAt) { }
    public record IssuedKey(KeyInfo key, String token) { }
    public record Principal(String keyId, long userId, Permission permission) { }

    /** BUY/SELL mean long/short entry for futures; closing is always explicit. */
    public record Order(@NotNull Product product, @NotNull Side side,
                        @Min(1) @Max(1000) int quantity,
                        @Pattern(regexp = "[A-Z][A-Z0-9]{0,15}") String symbol,
                        OrderType orderType, @Positive Long price,
                        Integer leverage, OptionType optionType, String cycleId,
                        @Positive Double strike, @Positive Long expiryEpoch,
                        @Positive Long positionId) {
        @JsonAnySetter public void unknown(String name, JsonNode value) { throw new IllegalArgumentException("지원하지 않는 주문 필드입니다."); }
        public OrderType effectiveOrderType() { return orderType == null ? OrderType.MARKET : orderType; }

        public void validate() {
            require(product != null && side != null, "product와 side가 필요합니다.");
            require(quantity >= 1 && quantity <= (product == Product.STOCK ? 1000 : 100), "주식은 1~1000주, 나머지 상품은 1~100개를 주문할 수 있습니다.");
            boolean limit = effectiveOrderType() == OrderType.LIMIT;
            require(!limit || product == Product.STOCK, "지정가는 주식만 지원합니다.");
            require(limit ? price != null && price > 0 : price == null, "지정가 주문에만 양수 price를 지정해 주세요.");
            require(product == Product.FUTURE || leverage == null, "leverage는 선물에만 지정할 수 있습니다.");
            if (product != Product.OPTION) {
                require(symbol != null && symbol.matches("[A-Z][A-Z0-9]{0,15}"), "유효한 symbol이 필요합니다.");
                require(optionType == null && cycleId == null && strike == null && expiryEpoch == null && positionId == null,
                        "옵션 필드는 옵션 주문에만 지정할 수 있습니다.");
            }
            if (product == Product.FUTURE) {
                require("FUTIDX".equals(symbol), "지원하는 선물 종목은 FUTIDX입니다.");
                require(leverage == null || List.of(2, 5, 10).contains(leverage), "선물 레버리지는 2, 5, 10 중 하나입니다.");
            }
            if (product == Product.OPTION) {
                require(symbol == null, "옵션은 symbol 대신 만기·종류·행사가 또는 positionId로 지정합니다.");
                if (side == Side.BUY) {
                    require(positionId == null && optionType != null && cycleId != null
                                    && List.of("10M", "1H", "SUN").contains(cycleId)
                                    && strike != null && Double.isFinite(strike) && strike > 0
                                    && expiryEpoch != null && expiryEpoch > 0,
                            "옵션 매수에는 optionType, cycleId, strike, expiryEpoch가 필요합니다.");
                } else {
                    require(positionId != null && positionId > 0, "옵션 매도에는 보유한 옵션의 positionId가 필요합니다.");
                    require(optionType == null && cycleId == null && strike == null && expiryEpoch == null,
                            "옵션 매도는 positionId와 quantity로 지정합니다.");
                }
            }
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new IllegalArgumentException(message);
        }
    }

    public record Close(@Min(1) @Max(100) int quantity) {
        @JsonAnySetter public void unknown(String name, JsonNode value) { throw new IllegalArgumentException("지원하지 않는 청산 필드입니다."); }
    }
    public record Receipt(String requestId, String operation, JsonNode result) { }
    public record Cancellation(long orderId, String status) { }
    public record Account(MarketModels.Portfolio portfolio, DerivativeModels.Account derivatives) { }
    public record Quotes(List<MarketModels.Stock> stocks, DerivativeModels.DerivativeSnapshot derivatives) { }
    public record ApiError(String code, String message) { }
}
