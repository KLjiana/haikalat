package com.kaleblangley.haikalat.subsystems.resources;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Executes encoded-resource reads and CPU-only decoding away from the render thread. */
public final class AsyncResourceDecoder implements AutoCloseable {
    private final ResourceSource source;
    private final ResourceGenerationTracker generations;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final long maxBytes;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates a decoder backed by owned Java 21 virtual threads. */
    public AsyncResourceDecoder(ResourceSource source) {
        this(source, new ResourceGenerationTracker(),
                Executors.newVirtualThreadPerTaskExecutor(), ResourceSource.DEFAULT_MAX_BYTES, true);
    }

    /** Creates a decoder using a caller-owned executor and generation tracker. */
    public AsyncResourceDecoder(ResourceSource source, ResourceGenerationTracker generations,
                                Executor executor, long maxBytes) {
        this(source, generations, executor, maxBytes, false);
    }

    private AsyncResourceDecoder(ResourceSource source, ResourceGenerationTracker generations,
                                 Executor executor, long maxBytes, boolean ownsExecutor) {
        this.source = Objects.requireNonNull(source, "source");
        this.generations = Objects.requireNonNull(generations, "generations");
        this.executor = Objects.requireNonNull(executor, "executor");
        if (maxBytes < 0L || maxBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "maxBytes must be in 0.." + (Integer.MAX_VALUE - 1L));
        }
        this.maxBytes = maxBytes;
        this.ownedExecutor = ownsExecutor ? (ExecutorService) executor : null;
    }

    /** Captures the current generation before scheduling the read and decode work. */
    public <T> CompletableFuture<Result<T>> decode(AssetId assetId, Decoder<T> decoder) {
        ensureOpen();
        Objects.requireNonNull(decoder, "decoder");
        ResourceGenerationTracker.Ticket ticket = generations.capture(assetId);
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] encoded = Objects.requireNonNull(source.read(assetId, maxBytes),
                        "ResourceSource returned null");
                T value = Objects.requireNonNull(decoder.decode(assetId, encoded),
                        "ResourceDecoder returned null");
                return new Result<>(ticket, value);
            } catch (Exception failure) {
                throw new CompletionException("Failed to decode resource " + assetId, failure);
            }
        }, executor);
    }

    /** Publishes a decoded value only if no invalidation occurred while it was in flight. */
    public <T> boolean publishIfCurrent(Result<T> result, Consumer<? super T> publisher) {
        Objects.requireNonNull(result, "result");
        return generations.publishIfCurrent(result.ticket(), result.value(), publisher);
    }

    public ResourceGeneration invalidate(AssetId assetId) {
        return generations.invalidate(assetId);
    }

    public ResourceGeneration currentGeneration(AssetId assetId) {
        return generations.current(assetId);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("AsyncResourceDecoder is closed");
        }
    }

    @FunctionalInterface
    public interface Decoder<T> {
        T decode(AssetId assetId, byte[] encoded) throws Exception;
    }

    public record Result<T>(ResourceGenerationTracker.Ticket ticket, T value) {
        public Result {
            Objects.requireNonNull(ticket, "ticket");
            Objects.requireNonNull(value, "value");
        }

        public AssetId assetId() {
            return ticket.assetId();
        }

        public ResourceGeneration generation() {
            return ticket.generation();
        }
    }
}
