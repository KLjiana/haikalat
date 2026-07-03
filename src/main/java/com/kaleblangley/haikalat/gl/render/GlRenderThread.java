package com.kaleblangley.haikalat.gl.render;

import com.kaleblangley.haikalat.gl.RenderSettings;
import com.kaleblangley.haikalat.gl.command.CommandBuffer;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.opengl.GL.createCapabilities;

public final class GlRenderThread implements AutoCloseable {
    private final long window;
    private final RenderLoop renderLoop;
    private final Consumer<CommandBuffer> frameCallback;
    private Runnable initHook;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private Thread thread;

    public GlRenderThread(long window, RenderSettings settings, Consumer<CommandBuffer> frameCallback) {
        this.window = window;
        this.renderLoop = new RenderLoop(Objects.requireNonNull(settings, "settings"));
        this.frameCallback = Objects.requireNonNull(frameCallback, "frameCallback");
    }

    public GlRenderThread onInit(Runnable init) {
        this.initHook = init;
        return this;
    }

    public RenderLoop renderLoop() {
        return renderLoop;
    }

    public CompletableFuture<Void> start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Already started");
        }
        thread = new Thread(this::runLoop, "GL-RenderThread");
        thread.setDaemon(true);
        thread.start();
        return completion;
    }

    public boolean isAlive() {
        return thread != null && thread.isAlive();
    }

    public void shutdown(Duration timeout) throws TimeoutException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        renderLoop.requestStop();
        try {
            if (timeout != null) {
                completion.get(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
            } else {
                completion.join();
            }
        } catch (java.util.concurrent.TimeoutException e) {
            if (thread != null) thread.interrupt();
            throw e;
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        try {
            shutdown(Duration.ofSeconds(3));
        } catch (TimeoutException ignored) {
        }
    }

    private void runLoop() {
        try {
            glfwMakeContextCurrent(window);
            createCapabilities();
            if (initHook != null) {
                initHook.run();
            }
            while (renderLoop.isRunning() && !closed.get()) {
                renderLoop.beginFrame();
                CommandBuffer cmd = renderLoop.device().createCommandBuffer();
                frameCallback.accept(cmd);
                renderLoop.device().execute(cmd);
                renderLoop.endFrame();
                glfwSwapBuffers(window);
            }
            completion.complete(null);
        } catch (Throwable t) {
            completion.completeExceptionally(t);
            renderLoop.requestStop();
        } finally {
            try { glfwMakeContextCurrent(0); } catch (Exception ignored) {}
        }
    }
}
