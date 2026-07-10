package com.kaleblangley.haikalat.runtime;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.upload.UploadSystem;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.opengl.GL.createCapabilities;

public final class GlRenderThread implements AutoCloseable {
    private final long window;
    private final FrameDriver frameDriver;
    private final Consumer<CommandBuffer> frameCallback;
    private Runnable initHook;
    private Runnable cleanupHook;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final CountDownLatch initLatch = new CountDownLatch(1);
    private Thread thread;

    public GlRenderThread(long window, RenderSettings settings, Consumer<CommandBuffer> frameCallback) {
        this.window = window;
        this.frameDriver = new FrameDriver(Objects.requireNonNull(settings, "settings"));
        this.frameCallback = Objects.requireNonNull(frameCallback, "frameCallback");
    }

    /** Thread-safe upload queue flushed by the render thread at the start of each frame. */
    public UploadSystem uploadQueue() {
        return frameDriver.uploadQueue();
    }

    /**
     * 设置在 GL 上下文初始化后执行的回调。
     *
     * @param init 初始化回调
     */
    public GlRenderThread onInit(Runnable init) {
        this.initHook = init;
        return this;
    }

    /**
     * 设置在渲染循环退出后执行的清理回调。
     *
     * @param cleanup 清理回调
     */
    public GlRenderThread onCleanup(Runnable cleanup) {
        this.cleanupHook = cleanup;
        return this;
    }

    public FrameDriver renderLoop() {
        return frameDriver;
    }

    /**
     * 启动渲染线程，等待 GL 初始化完成后返回。
     *
     * @return 渲染完成的 CompletableFuture
     */
    public CompletableFuture<Void> start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Already started");
        }
        thread = new Thread(this::runLoop, "GL-RenderThread");
        thread.setDaemon(true);
        thread.start();
        try {
            initLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return completion;
    }

    public boolean isAlive() {
        return thread != null && thread.isAlive();
    }

    /**
     * 请求停止渲染循环并等待线程结束（支持超时）。
     *
     * @param timeout 超时时间，null 表示无限等待
     * @throws TimeoutException 若等待超时
     */
    public void shutdown(Duration timeout) throws TimeoutException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        frameDriver.requestStop();
        try {
            if (timeout != null) {
                completion.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
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
        Throwable failure = null;
        try {
            glfwMakeContextCurrent(window);
            createCapabilities();
            GlDebug.enableDebugCallback();
            glfwSwapInterval(frameDriver.settings().vsync() ? 1 : 0);
            try {
                if (initHook != null) {
                    initHook.run();
                }
            } finally {
                initLatch.countDown();
            }
            while (frameDriver.isRunning() && !closed.get()) {
                frameDriver.beginFrame();
                CommandBuffer cmd = frameDriver.device().createCommandBuffer();
                frameCallback.accept(cmd);
                frameDriver.device().execute(cmd);
                frameDriver.endFrame();
                glfwSwapBuffers(window);
            }
        } catch (Throwable t) {
            failure = t;
            frameDriver.requestStop();
        } finally {
            try {
                if (cleanupHook != null) cleanupHook.run();
            } catch (Exception ignored) {}
            frameDriver.close();
            try { glfwMakeContextCurrent(0); } catch (Exception ignored) {}
            if (failure == null) {
                completion.complete(null);
            } else {
                completion.completeExceptionally(failure);
            }
        }
    }
}
