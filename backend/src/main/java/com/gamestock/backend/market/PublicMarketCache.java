package com.gamestock.backend.market;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.gamestock.backend.market.MarketModels.*;

/**
 * 첫 화면이 기다리는 공개 시세(/stocks, /market-status, /market-events)를 미리 계산해 둔다.
 * 봇 매칭이 DB 커넥션과 스케줄러 스레드를 계속 쓰기 때문에 요청마다 조회하면
 * 응답이 2초 넘게 밀린다. 전용 스레드가 짧은 주기로 갱신하고 요청은 그 값을 바로 돌려준다.
 */
@Component
public class PublicMarketCache {
    private static final Logger log = LoggerFactory.getLogger(PublicMarketCache.class);
    private static final long QUOTE_REFRESH_MS = 1_000;
    private static final long EVENT_REFRESH_MS = 10_000;
    // 갱신 스레드가 멈췄을 때 오래된 값을 계속 내보내지 않도록 직접 조회로 되돌아가는 한계.
    private static final long MAX_STALE_MS = 15_000;

    private final Entry<List<Stock>> stocks;
    private final Entry<MarketStatus> status;
    private final Entry<List<MarketEvent>> events;
    private final ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "public-market-cache");
        thread.setDaemon(true);
        return thread;
    });

    public PublicMarketCache(MarketService market) {
        stocks = new Entry<>("stocks", market::stocks);
        status = new Entry<>("market-status", market::marketStatus);
        events = new Entry<>("market-events", market::marketEvents);
        refresher.scheduleWithFixedDelay(() -> { stocks.refresh(); status.refresh(); }, 0, QUOTE_REFRESH_MS, TimeUnit.MILLISECONDS);
        refresher.scheduleWithFixedDelay(events::refresh, 0, EVENT_REFRESH_MS, TimeUnit.MILLISECONDS);
    }

    public List<Stock> stocks() { return stocks.get(); }
    public MarketStatus marketStatus() { return status.get(); }
    public List<MarketEvent> marketEvents() { return events.get(); }

    @PreDestroy
    void stop() { refresher.shutdownNow(); }

    private static final class Entry<T> {
        private final String name;
        private final Supplier<T> loader;
        private volatile T value;
        private volatile long loadedAt;

        Entry(String name, Supplier<T> loader) { this.name = name; this.loader = loader; }

        void refresh() {
            try {
                T next = loader.get();
                value = next;
                loadedAt = System.currentTimeMillis();
            } catch (RuntimeException error) {
                log.warn("Public {} cache refresh failed: {}", name, error.getMessage());
            }
        }

        T get() {
            T current = value;
            if (current != null && System.currentTimeMillis() - loadedAt <= MAX_STALE_MS) return current;
            T next = loader.get();
            value = next;
            loadedAt = System.currentTimeMillis();
            return next;
        }
    }
}
