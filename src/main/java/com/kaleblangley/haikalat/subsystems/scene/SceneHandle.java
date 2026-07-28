package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.subsystems.resources.AssetId;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;

/** Stable application handle whose current scene version changes atomically. */
public final class SceneHandle implements AutoCloseable {
    private final AssetId id;
    private final AtomicReference<SceneVersion> current = new AtomicReference<>();
    private final CompletableFuture<SceneVersion> firstReady = new CompletableFuture<>();
    private final Runnable closeCallback;
    private volatile Status status = Status.LOADING;
    private volatile Throwable failure;
    private volatile boolean closed;

    public SceneHandle(AssetId id) {
        this(id, () -> {});
    }

    SceneHandle(AssetId id, Runnable closeCallback) {
        this.id = Objects.requireNonNull(id, "id");
        this.closeCallback = Objects.requireNonNull(closeCallback, "closeCallback");
    }

    public AssetId id() {
        return id;
    }

    public SceneVersion current() {
        SceneVersion version = current.get();
        if (closed || version == null) {
            throw new IllegalStateException("scene handle has no active version");
        }
        return version;
    }

    public Optional<SceneVersion> currentOptional() {
        return Optional.ofNullable(current.get());
    }

    public CompletableFuture<SceneVersion> firstReady() {
        return firstReady;
    }

    public Status status() {
        return status;
    }

    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    /**
     * Commits a complete candidate. The returned version remains owned by the
     * caller and must be retired after the current frame no longer uses it.
     */
    public SceneVersion commit(SceneVersion candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (closed) throw new IllegalStateException("scene handle is closed");
        if (candidate.isClosed()) throw new IllegalArgumentException("candidate is closed");
        SceneVersion previous = current.getAndSet(candidate);
        status = Status.READY;
        failure = null;
        firstReady.complete(candidate);
        return previous;
    }

    public boolean hasCurrent() {
        return !closed && current.get() != null;
    }

    void markFailure(Throwable failure) {
        if (closed) return;
        this.failure = Objects.requireNonNull(failure, "failure");
        status = Status.FAILED;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        try {
            closeCallback.run();
        } catch (RuntimeException callbackFailure) {
            failure = callbackFailure;
        }
        SceneVersion version = current.getAndSet(null);
        if (version != null) {
            try {
                version.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        status = Status.CLOSED;
        firstReady.cancel(false);
        if (failure != null) throw failure;
    }

    public enum Status {
        LOADING,
        READY,
        FAILED,
        CLOSED
    }
}
