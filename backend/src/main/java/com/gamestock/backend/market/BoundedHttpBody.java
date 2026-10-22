package com.gamestock.backend.market;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Reject over-sized bodies while receiving them, including chunked responses without a length. */
final class BoundedHttpBody {
    private BoundedHttpBody() { }
    static HttpResponse.BodyHandler<byte[]> bytes(int limit) { return info -> subscriber(limit); }
    static HttpResponse.BodyHandler<String> utf8(int limit) {
        return info -> HttpResponse.BodySubscribers.mapping(subscriber(limit), bytes -> new String(bytes, StandardCharsets.UTF_8));
    }
    static HttpResponse.BodySubscriber<byte[]> subscriber(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("Positive byte limit required");
        return new HttpResponse.BodySubscriber<>() {
            final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
            Flow.Subscription subscription;
            long received;
            boolean done;
            public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(Flow.Subscription value) { subscription=value; delegate.onSubscribe(value); }
            public void onNext(List<ByteBuffer> buffers) {
                if (done) return;
                for (ByteBuffer buffer:buffers) {
                    received += buffer.remaining();
                    if (received > limit) {
                        done=true; subscription.cancel(); delegate.onError(new IOException("뉴스 응답 크기 제한을 초과했습니다.")); return;
                    }
                }
                delegate.onNext(buffers);
            }
            public void onError(Throwable error) { if (!done) { done=true; delegate.onError(error); } }
            public void onComplete() { if (!done) { done=true; delegate.onComplete(); } }
        };
    }
}
