package com.gamestock.backend.openapi;

import com.gamestock.backend.market.DerivativeModels;
import com.gamestock.backend.market.DerivativeService;
import com.gamestock.backend.market.MarketModels;
import com.gamestock.backend.market.MarketService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.gamestock.backend.openapi.OpenApiModels.*;

@RestController
@RequestMapping("/api/openapi/v1")
public class OpenApiController {
    private final OpenApiKeyService keys;
    private final OpenApiRateLimiter limiter;
    private final OpenApiQuoteService quotes;
    private final OpenApiTradingService trading;
    private final MarketService market;
    private final DerivativeService derivatives;
    private final OpenApiOrderQuery orderQuery;

    public OpenApiController(OpenApiKeyService keys, OpenApiRateLimiter limiter, OpenApiQuoteService quotes,
                              OpenApiTradingService trading, MarketService market, DerivativeService derivatives, OpenApiOrderQuery orderQuery) {
        this.keys = keys; this.limiter = limiter; this.quotes = quotes; this.trading = trading;
        this.market = market; this.derivatives = derivatives;
        this.orderQuery = orderQuery;
    }

    @GetMapping("/quotes")
    public Quotes quotes() { return new Quotes(quotes.stocks(), quotes.derivatives()); }

    @GetMapping("/stocks")
    public List<MarketModels.Stock> stocks() { return quotes.stocks(); }

    @GetMapping("/stocks/{symbol}")
    public MarketModels.Stock stock(@PathVariable String symbol) { return quotes.stock(symbol); }

    @GetMapping("/stocks/{symbol}/orderbook")
    public MarketModels.OrderBook orderbook(@PathVariable String symbol) {
        quotes.stock(symbol);
        return market.orderBook(symbol);
    }

    @GetMapping("/stocks/{symbol}/candles")
    public List<MarketModels.ChartCandle> candles(@PathVariable String symbol,
                                                  @RequestParam(defaultValue = "1d") String range) {
        quotes.stock(symbol);
        if (!List.of("1h", "6h", "12h", "1d", "3d", "1w", "2w", "1m", "1y").contains(range))
            throw new IllegalArgumentException("지원하는 차트 기간은 1h, 6h, 12h, 1d, 3d, 1w, 2w, 1m, 1y입니다.");
        return market.chartHistory(symbol, range);
    }

    @GetMapping("/etfs")
    public List<DerivativeModels.EtfQuote> etfs() { return quotes.derivatives().etfs(); }

    @GetMapping("/futures")
    public List<DerivativeModels.FutureQuote> futures() { return List.of(quotes.derivatives().future()); }

    @GetMapping("/options")
    public List<DerivativeModels.OptionContract> options(@RequestParam(required = false) String cycleId,
                                                         @RequestParam(required = false) OptionType type) {
        if (cycleId != null && !List.of("10M", "1H", "SUN").contains(cycleId))
            throw new IllegalArgumentException("cycleId는 10M, 1H, SUN 중 하나입니다.");
        return quotes.derivatives().options().stream()
                .filter(contract -> cycleId == null || cycleId.equals(contract.cycleId()))
                .filter(contract -> type == null || type.name().equals(contract.type())).toList();
    }

    @GetMapping("/account")
    public Account account(@RequestHeader(value = "Authorization", required = false) String authorization) {
        var principal = access(authorization, false);
        return new Account(market.portfolio(principal.userId()), OpenApiAccounts.account(derivatives.account(principal.userId())));
    }

    @GetMapping("/positions")
    public List<DerivativeModels.Position> positions(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return derivatives.account(access(authorization, false).userId()).positions();
    }

    @GetMapping("/trades")
    public List<DerivativeModels.Trade> trades(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return derivatives.account(access(authorization, false).userId()).trades().stream().map(OpenApiAccounts::trade).toList();
    }

    @GetMapping("/orders")
    public List<MarketModels.ActiveOrder> orders(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                 @RequestParam(required = false) String symbol,
                                                 @RequestParam(defaultValue = "50") int limit,
                                                 @RequestParam(required = false) Long beforeId) {
        var principal = access(authorization, false);
        if (symbol != null) quotes.stock(symbol);
        return orderQuery.open(principal.userId(), symbol, limit, beforeId);
    }

    @GetMapping("/orders/{orderId}")
    public MarketModels.ActiveOrder orderStatus(@PathVariable long orderId,
                                                 @RequestHeader(value = "Authorization", required = false) String authorization) {
        return orderQuery.get(access(authorization, false).userId(), orderId);
    }

    @GetMapping("/orders/history")
    public List<MarketModels.OrderHistory> history(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @RequestParam String symbol) {
        var principal = access(authorization, false);
        quotes.stock(symbol);
        return market.orderHistory(symbol, principal.userId());
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public CompletableFuture<Receipt> order(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String requestId,
                                             @Valid @RequestBody Order request) {
        return trading.order(access(authorization, true), requestId, request);
    }

    @DeleteMapping("/orders/{orderId}")
    public CompletableFuture<Receipt> cancel(@PathVariable long orderId,
                                              @RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        return trading.cancel(access(authorization, true), requestId, orderId);
    }

    @PostMapping("/positions/{positionId}/close")
    public CompletableFuture<Receipt> close(@PathVariable long positionId,
                                             @RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String requestId,
                                             @Valid @RequestBody Close request) {
        return trading.close(access(authorization, true), requestId, positionId, request);
    }

    @GetMapping("/requests/{requestId}")
    public Receipt receipt(@PathVariable String requestId,
                             @RequestHeader(value = "Authorization", required = false) String authorization) {
        return trading.receipt(access(authorization, false).userId(), requestId);
    }

    private Principal access(String authorization, boolean trade) {
        Principal principal = keys.authenticate(authorization, trade);
        limiter.account(principal.userId(), trade);
        return principal;
    }
}
