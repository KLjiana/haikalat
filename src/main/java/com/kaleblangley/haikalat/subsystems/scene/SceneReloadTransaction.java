package com.kaleblangley.haikalat.subsystems.scene;

import java.util.Objects;

/**
 * Small CPU-side transaction used by scene/Graph reloaders. A failed
 * candidate never replaces the active value.
 */
public final class SceneReloadTransaction<T> implements AutoCloseable {
    public enum Status { OPEN, COMMITTED, FAILED, CLOSED }

    private T active;
    private T candidate;
    private Throwable failure;
    private Status status = Status.OPEN;

    private SceneReloadTransaction(T active) {
        this.active = Objects.requireNonNull(active, "active");
    }

    public static <T> SceneReloadTransaction<T> begin(T active) {
        return new SceneReloadTransaction<>(active);
    }

    public Status status() { return status; }
    public T active() { return active; }
    public T candidate() { return candidate; }
    public Throwable failure() { return failure; }

    public SceneReloadTransaction<T> stage(T value) {
        requireOpen();
        candidate = Objects.requireNonNull(value, "candidate");
        return this;
    }

    public T commit() {
        requireOpen();
        if (candidate == null) throw new IllegalStateException("reload candidate is not staged");
        active = candidate;
        candidate = null;
        status = Status.COMMITTED;
        return active;
    }

    public SceneReloadTransaction<T> fail(Throwable cause) {
        requireOpen();
        failure = Objects.requireNonNull(cause, "cause");
        candidate = null;
        status = Status.FAILED;
        return this;
    }

    @Override
    public void close() {
        if (status == Status.CLOSED) return;
        candidate = null;
        status = Status.CLOSED;
    }

    private void requireOpen() {
        if (status != Status.OPEN) throw new IllegalStateException(
                "reload transaction is " + status.name().toLowerCase());
    }
}
