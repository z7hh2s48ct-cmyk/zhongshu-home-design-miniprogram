package cn.iocoder.yudao.module.infra.zhongshu.api;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Cancels before accumulating an oversized upstream response. */
public final class BoundedHttpBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;
    public BoundedHttpBody(int limit) { this.limit = limit; }
    public CompletionStage<byte[]> getBody() { return result; }
    public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
    public void onNext(List<ByteBuffer> buffers) {
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > limit - out.size()) {
                subscription.cancel(); result.completeExceptionally(new IllegalStateException("UPSTREAM_BODY_LIMIT")); return;
            }
            byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); out.writeBytes(bytes);
        }
        subscription.request(1);
    }
    public void onError(Throwable error) { result.completeExceptionally(error); }
    public void onComplete() { result.complete(out.toByteArray()); }
}
