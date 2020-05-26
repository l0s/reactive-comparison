package integrationtest;

import java.io.IOException;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonNodeBodyHandler implements BodyHandler<JsonNode> {

    private final ObjectMapper mapper;

    public JsonNodeBodyHandler(final ObjectMapper mapper) {
        Objects.requireNonNull(mapper);
        this.mapper = mapper;
    }

    public JsonNodeBodyHandler() {
        this(new ObjectMapper());
    }

    public BodySubscriber<JsonNode> apply(final ResponseInfo ignore) {
        return new JsonNodeBodySubscriber();
    }

    protected class JsonNodeBodySubscriber implements BodySubscriber<JsonNode> {
        final CompletableFuture<JsonNode> completionStage = new CompletableFuture<JsonNode>();
        final List<ByteBuffer> buffers = new ArrayList<ByteBuffer>();
        Optional<Subscription> subscription = Optional.empty();
        int size = 0;
    
        public void onSubscribe(Subscription subscription) {
            if (this.subscription.isPresent()) {
                subscription.cancel();
                return;
            }
            this.subscription = Optional.of(subscription);
            subscription.request(Long.MAX_VALUE);
        }
    
        public void onNext(final List<ByteBuffer> item) {
            buffers.addAll(item);
            for (final var buffer : item) {
                size += buffer.remaining();
            }
        }
    
        public void onError(final Throwable throwable) {
            completionStage.completeExceptionally(throwable);
            subscription = Optional.empty();
        }
    
        public void onComplete() {
            final var bytes = new byte[size];
            var offset = 0;
            for (final var buffer : buffers) {
                final var length = buffer.remaining();
                buffer.get(bytes, offset, length);
                offset += length;
            }
            try {
                completionStage.complete(mapper.readTree(bytes));
            } catch (final IOException e) {
                onError(e);
            }
            subscription = Optional.empty();
        }
    
        public CompletionStage<JsonNode> getBody() {
            return completionStage;
        }
    }

}