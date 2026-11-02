package com.papyrus.infrastructure.exist;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow.Subscription;

/** Limits memory while receiving the body, rather than after allocation. */
final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Subscription subscription;

    LimitedBodySubscriber(int limit) { this.limit = limit; }
    public CompletionStage<byte[]> getBody() { return result; }
    public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1);
    }
    public void onNext(List<ByteBuffer> buffers) {
        for (var buffer : buffers) {
            if (buffer.remaining() > limit - bytes.size()) {
                subscription.cancel();
                result.completeExceptionally(new IOException("Archive response exceeds limit"));
                return;
            }
            byte[] part = new byte[buffer.remaining()];
            buffer.get(part);
            bytes.writeBytes(part);
        }
        subscription.request(1);
    }
    public void onError(Throwable ex) { result.completeExceptionally(ex); }
    public void onComplete() { result.complete(bytes.toByteArray()); }
}
