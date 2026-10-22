package com.gamestock.backend.openapi;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** Bounded per-instance fixed windows. Account limits apply across all of its keys. */
@Component
public class OpenApiRateLimiter {
    private static final long WINDOW_MS = 60_000;
    private static final int MAX_BUCKETS = 10_000;
    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock;

    public OpenApiRateLimiter() { this(Clock.systemUTC()); }
    OpenApiRateLimiter(Clock clock) { this.clock = clock; }

    public void ip(String address) { take("ip:" + address, 120); }
    public void account(long userId, boolean trade) {
        take("user:" + userId, 120);
        if (trade) take("trade:" + userId, 30);
    }

    private synchronized void take(String key, int limit) {
        long now = clock.millis();
        Window window = windows.get(key);
        if (window == null || now >= window.endsAt()) {
            if (windows.size() >= MAX_BUCKETS) {
                windows.values().removeIf(value -> now >= value.endsAt());
                if (windows.size() >= MAX_BUCKETS) throw new RateLimited(60);
            }
            windows.put(key, new Window(now + WINDOW_MS, 1));
            return;
        }
        if (window.count() >= limit) throw new RateLimited(Math.max(1, (window.endsAt() - now + 999) / 1000));
        windows.put(key, new Window(window.endsAt(), window.count() + 1));
    }

    private record Window(long endsAt, int count) { }
    static final class RateLimited extends ResponseStatusException {
        final long retryAfter;
        RateLimited(long retryAfter) {
            super(HttpStatus.TOO_MANY_REQUESTS, "호출 한도를 초과했습니다. Retry-After 이후에 다시 요청해 주세요.");
            this.retryAfter = retryAfter;
        }
    }
}
