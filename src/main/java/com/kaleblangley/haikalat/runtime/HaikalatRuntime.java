package com.kaleblangley.haikalat.runtime;

import com.kaleblangley.haikalat.core.device.RenderDevice;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Host-owned runtime boundary for embedding Haikalat without a window or render thread.
 */
public final class HaikalatRuntime implements AutoCloseable {
    public enum State {
        READY,
        CLOSED
    }

    private final RenderDevice renderDevice;
    private final long renderThreadId;
    private volatile State state = State.READY;

    private HaikalatRuntime(RenderDevice renderDevice) {
        this.renderDevice = Objects.requireNonNull(renderDevice, "renderDevice");
        renderThreadId = Thread.currentThread().threadId();
    }

    /**
     * Creates an embedded runtime around a borrowed device.
     * No window, context, render thread, or main loop is created.
     */
    public static HaikalatRuntime createEmbedded(RenderDevice renderDevice) {
        return new HaikalatRuntime(renderDevice);
    }

    public RenderDevice renderDevice() {
        requireRenderThread();
        ensureReady();
        return renderDevice;
    }

    public State state() {
        return state;
    }

    public long renderThreadId() {
        return renderThreadId;
    }

    /**
     * Runs host-scheduled work on the captured render thread.
     * The host retains ownership of timing and presentation.
     */
    public void execute(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        execute(() -> {
            operation.run();
            return null;
        });
    }

    public <T> T execute(Supplier<T> operation) {
        requireRenderThread();
        ensureReady();
        return Objects.requireNonNull(operation, "operation").get();
    }

    public void requireRenderThread() {
        long current = Thread.currentThread().threadId();
        if (current != renderThreadId) {
            throw new IllegalStateException("embedded Haikalat runtime belongs to render thread "
                    + renderThreadId + ", current=" + current);
        }
    }

    @Override
    public void close() {
        requireRenderThread();
        state = State.CLOSED;
    }

    private void ensureReady() {
        if (state != State.READY) {
            throw new IllegalStateException("embedded Haikalat runtime is closed");
        }
    }
}
