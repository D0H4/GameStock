package com.gamestock.backend.openapi;

import com.gamestock.backend.market.DerivativeModels.DerivativeSnapshot;
import com.gamestock.backend.market.DerivativeService;
import com.gamestock.backend.market.MarketModels.Stock;
import com.gamestock.backend.market.PublicMarketCache;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Share the existing stock cache and coalesce derivative requests into at most one snapshot/second. */
@Service
public class OpenApiQuoteService {
    private final PublicMarketCache market;
    private final DerivativeService derivatives;
    private volatile Cached cached;
    public OpenApiQuoteService(PublicMarketCache market, DerivativeService derivatives) {
        this.market = market; this.derivatives = derivatives;
    }

    public List<Stock> stocks() { return market.stocks(); }
    public Stock stock(String symbol) {
        return stocks().stream().filter(stock -> stock.code().equals(symbol)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다."));
    }

    public DerivativeSnapshot derivatives() {
        Cached value = cached;
        if (value != null && System.nanoTime() < value.until()) return value.snapshot();
        synchronized (this) {
            value = cached;
            if (value != null && System.nanoTime() < value.until()) return value.snapshot();
            DerivativeSnapshot snapshot = derivatives.snapshot();
            cached = new Cached(snapshot, System.nanoTime() + 1_000_000_000L);
            return snapshot;
        }
    }
    private record Cached(DerivativeSnapshot snapshot, long until) { }
}
