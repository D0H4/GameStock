package com.gamestock.backend.market;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static com.gamestock.backend.market.MarketModels.*;

/**
 * 첫 화면이 기다리는 공개 시세(/stocks, /market-status, /market-events)를 미리 계산해 둔다.
 * 봇 매칭이 DB 커넥션과 스케줄러 스레드를 계속 쓰기 때문에 요청마다 조회하면
 * 응답이 2초 넘게 밀린다. 전용 스레드가 짧은 주기로 갱신하고 요청은 그 값을 바로 돌려준다.
 *
 * 무료 DB(1 ECPU)는 접속자가 없을 때도 이 갱신을 감당해야 한다. 시세는 체결·정비 커밋이
 * 알려 올 때만 다시 읽고, VI 해제처럼 시간이 지나서 바뀌는 값은 몇 초 주기로 확인한다.
 * 뉴스 본문은 수집·본문 저장 때만 다시 읽고, 등락률만 최신 시세로 다시 계산한다.
 */
@Component
public class PublicMarketCache {
    private static final Logger log = LoggerFactory.getLogger(PublicMarketCache.class);
    private static final long TICK_MS = 1_000;
    // 커밋 알림 없이 바뀌는 값(VI·서킷브레이커 시간 만료, 관리자 설정)을 놓치지 않는 확인 주기.
    private static final long QUOTE_FALLBACK_MS = 5_000;
    // 다른 인스턴스가 저장한 뉴스처럼 알림이 오지 않는 변경을 반영하는 주기.
    private static final long NEWS_FALLBACK_MS = 300_000;
    // 갱신 스레드가 멈췄을 때 오래된 값을 계속 내보내지 않도록 직접 조회로 되돌아가는 한계.
    private static final long MAX_STALE_MS = 15_000;

    private final MarketService market;
    private final Entry<List<Stock>> stocks;
    private final Entry<MarketStatus> status;
    private final AtomicBoolean quotesDirty = new AtomicBoolean(true);
    private final AtomicBoolean newsDirty = new AtomicBoolean(true);
    private volatile List<MarketEvent> news;
    private volatile long newsLoadedAt;
    private volatile List<MarketEvent> events;
    private volatile long eventsPricedAt;
    private final ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "public-market-cache");
        thread.setDaemon(true);
        return thread;
    });

    public PublicMarketCache(MarketService market) {
        this.market = market;
        stocks = new Entry<>("stocks", market::stocks);
        status = new Entry<>("market-status", market::marketStatus);
        // 예외가 밖으로 나가면 ScheduledExecutorService가 이후 갱신을 조용히 멈춘다.
        refresher.scheduleWithFixedDelay(() -> {
            try { refresh(); }
            catch (RuntimeException error) { log.warn("Public market cache refresh failed: {}", error.getMessage()); }
        }, 0, TICK_MS, TimeUnit.MILLISECONDS);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void marketChanged(MarketChangedEvent event) { quotesDirty.set(true); }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void newsChanged(NewsChangedEvent event) { newsDirty.set(true); }

    public List<Stock> stocks() { return stocks.get(); }
    public MarketStatus marketStatus() { return status.get(); }

    public List<MarketEvent> marketEvents() {
        List<MarketEvent> current = events;
        if (current != null && System.currentTimeMillis() - eventsPricedAt <= MAX_STALE_MS) return current;
        return market.marketEvents();
    }

    void refresh() {
        long now = System.currentTimeMillis();
        boolean quotesDue = quotesDirty.getAndSet(false) || now - stocks.loadedAt >= QUOTE_FALLBACK_MS;
        boolean newsDue = newsDirty.getAndSet(false) || now - newsLoadedAt >= NEWS_FALLBACK_MS;
        if (quotesDue) {
            stocks.refresh();
            status.refresh();
        }
        if (newsDue) {
            try {
                news = market.latestNews();
                newsLoadedAt = System.currentTimeMillis();
            } catch (RuntimeException error) {
                newsDirty.set(true);
                log.warn("Public news cache refresh failed: {}", error.getMessage());
            }
        }
        List<Stock> quotes = stocks.value;
        if ((quotesDue || newsDue) && news != null && quotes != null) {
            Map<String, Long> prices = new HashMap<>();
            quotes.forEach(stock -> prices.put(stock.code(), stock.price()));
            events = market.repriceNews(news, prices);
            eventsPricedAt = stocks.loadedAt;
        }
    }

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
