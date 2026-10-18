package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class NewsExcerptSecurityTest {
    @Test void privateAddressesAndNonWebPortsAreRejectedWithoutMakingHttpRequests() {
        for (String host : List.of("127.0.0.1", "10.0.0.1", "169.254.169.254", "100.64.0.1", "[::1]", "[fd00::1]", "[fc00::1]", "[::ffff:127.0.0.1]"))
            assertThrows(Exception.class, () -> NewsExcerptService.requirePublicHost(URI.create("http://" + host + "/")), host);
        assertThrows(Exception.class, () -> NewsExcerptService.requirePublicHost(URI.create("http://8.8.8.8:3306/")));
        assertDoesNotThrow(() -> NewsExcerptService.requirePublicHost(URI.create("https://8.8.8.8/")));
    }

    @Test void knownArticleMustBeTheCompleteLiteralToken() {
        String id = "abcdefghijklmnopqrst_123";
        String description = "뉴스입니다. 출처: https://news.google.com/rss/articles/" + id + "?hl=ko";
        assertTrue(NewsExcerptService.containsArticle(description, id));
        assertFalse(NewsExcerptService.containsArticle(description, "____________________"));
        assertFalse(NewsExcerptService.containsArticle(description, "abcdefghijklmnopqrst"));
        assertFalse(NewsExcerptService.containsArticle(null, id));
    }

    @Test void oversizedChunkCancelsTransferBeforeAccumulatingTheBody() {
        var body = new NewsExcerptService.BoundedBodySubscriber(8);
        AtomicBoolean cancelled = new AtomicBoolean();
        body.onSubscribe(new Flow.Subscription() {
            public void request(long n) { }
            public void cancel() { cancelled.set(true); }
        });
        body.onNext(List.of(ByteBuffer.wrap(new byte[5])));
        body.onNext(List.of(ByteBuffer.wrap(new byte[4])));
        assertTrue(cancelled.get());
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
        body.onComplete();
    }

    @Test void bodyAtTheLimitIsReturnedIntact() {
        var body = new NewsExcerptService.BoundedBodySubscriber(3);
        body.onSubscribe(new Flow.Subscription() {
            public void request(long n) { }
            public void cancel() { fail("Valid body was cancelled"); }
        });
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1}), ByteBuffer.wrap(new byte[]{2,3})));
        body.onComplete();
        assertArrayEquals(new byte[]{1,2,3}, body.getBody().toCompletableFuture().join());
    }
}
