package com.kaleblangley.haikalat.gl.render;

import com.kaleblangley.haikalat.gl.RenderSettings;
import com.kaleblangley.haikalat.gl.command.CommandBuffer;
import com.kaleblangley.haikalat.gl.command.RenderCommand;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class GlRenderThread implements AutoCloseable {
    private final RenderLoop renderLoop;
    private final Runnable frameAction;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Thread thread;

    public GlRenderThread(RenderSettings settings, Runnable frameAction) {
        this(settings, frameAction, "GL-RenderThread");
    }

    public GlRenderThread(RenderSettings settings, Runnable frameAction, String threadName) {
        this.renderLoop = new RenderLoop(Objects.requireNonNull(settings, "settings"));
        this.frameAction = Objects.requireNonNull(frameAction, "frameAction");
        this.thread = new Thread(this::runLoop, Objects.requireNonNull(threadName, "threadName"));
        this.thread.setDaemon(true);
    }

    public RenderLoop renderLoop() {
        return renderLoop;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Render thread already started");
        }
        thread.start();
    }

    public void submit(RenderCommand command) {
        renderLoop.submit(command);
    }

    public void execute(CommandBuffer buffer) {
        renderLoop.executeCommandBuffer(buffer);
    }

    public Throwable failure() {
        return failure.get();
    }

    public boolean isAlive() {
        return thread.isAlive();
    }

    public void requestStop() {
        renderLoop.requestStop();
    }

    public void join() throws InterruptedException {
        thread.join();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            requestStop();
        }
    }

    private void runLoop() {
        try {
            while (renderLoop.isRunning() && !closed.get()) {
                renderLoop.beginFrame();
                renderLoop.drainCommands();
                frameAction.run();
                renderLoop.endFrame();
            }
        } catch (Throwable throwable) {
            failure.set(throwable);
            renderLoop.requestStop();
        } finally {
            closed.set(true);
        }
    }
}
