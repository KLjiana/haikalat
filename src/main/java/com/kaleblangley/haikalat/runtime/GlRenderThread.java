package com.kaleblangley.haikalat.runtime;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.upload.BufferUploadTarget;
import com.kaleblangley.haikalat.core.upload.UploadSystem;

import java.nio.FloatBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    private final AtomicBoolean acceptingUploads = new AtomicBoolean();
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final Object lifecycleLock = new Object();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final CountDownLatch initLatch = new CountDownLatch(1);
    private Thread thread;

    public GlRenderThread(long window, RenderSettings settings, Consumer<CommandBuffer> frameCallback) {
        this.window = window;
        this.frameDriver = new FrameDriver(Objects.requireNonNull(settings, "settings"));
        this.frameCallback = Objects.requireNonNull(frameCallback, "frameCallback");
    }

    /** Queues one copied float upload and its post-upload publication without exposing flush/close. */
    public boolean enqueueFloatUpload(BufferUploadTarget target, long offsetBytes, FloatBuffer data,
                                      UploadSystem.UploadRequest afterUpload) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(afterUpload, "afterUpload");
        if (!acceptingUploads.get()) {
            return false;
        }
        try {
            frameDriver.uploadQueue().uploadFloats(target, offsetBytes, data, afterUpload);
            return true;
        } catch (IllegalStateException closedDuringSubmission) {
            if (!acceptingUploads.get()) {
                return false;
            }
            throw closedDuringSubmission;
        }
    }

    public UploadStats uploadStats() {
        UploadSystem uploads = frameDriver.uploadQueue();
        return new UploadStats(uploads.totalBytesUploaded(), uploads.totalGpuUpdates(), uploads.pendingCount());
    }

    /**
     * 设置在 GL 上下文初始化后执行的回调。
     *
     * @param init 初始化回调
     */
    public GlRenderThread onInit(Runnable init) {
        synchronized (lifecycleLock) {
            ensureNotStarted("onInit");
            this.initHook = init;
        }
        return this;
    }

    /**
     * 设置在渲染循环退出后执行的清理回调。
     *
     * @param cleanup 清理回调
     */
    public GlRenderThread onCleanup(Runnable cleanup) {
        synchronized (lifecycleLock) {
            ensureNotStarted("onCleanup");
            this.cleanupHook = cleanup;
        }
        return this;
    }

    /**
     * 启动渲染线程，等待 GL 初始化完成后返回。
     *
     * @return 渲染完成的 CompletableFuture
     */
    public CompletableFuture<Void> start() {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("Render thread is already closed");
            }
            if (!started.compareAndSet(false, true)) {
                throw new IllegalStateException("Already started");
            }
            state.set(State.STARTING);
            thread = new Thread(this::runLoop, "GL-RenderThread");
            thread.setDaemon(true);
            thread.start();
        }
        boolean interrupted = false;
        while (true) {
            try {
                initLatch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return completion.copy();
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
        boolean neverStarted;
        synchronized (lifecycleLock) {
            closed.set(true);
            acceptingUploads.set(false);
            neverStarted = !started.get();
            if (neverStarted) {
                state.set(State.TERMINATED);
                frameDriver.close();
                initLatch.countDown();
                completion.complete(null);
            } else {
                state.updateAndGet(current -> terminal(current) ? current : State.STOPPING);
                frameDriver.requestStop();
            }
        }
        if (neverStarted) {
            return;
        }
        if (Thread.currentThread() == thread) {
            return;
        }
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
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("Timed out waiting for GL render thread shutdown", timeout);
        }
    }

    public State state() {
        return state.get();
    }

    private void runLoop() {
        Throwable failure = null;
        try {
            glfwMakeContextCurrent(window);
            createCapabilities();
            GlDebug.enableDebugCallback();
            glfwSwapInterval(frameDriver.settings().vsync() ? 1 : 0);
            if (initHook != null) {
                initHook.run();
            }
            synchronized (lifecycleLock) {
                if (!closed.get() && state.compareAndSet(State.STARTING, State.RUNNING)) {
                    acceptingUploads.set(true);
                } else {
                    frameDriver.requestStop();
                }
            }
            initLatch.countDown();
            while (frameDriver.isRunning() && !closed.get()) {
                frameDriver.beginFrame();
                CommandBuffer cmd = frameDriver.device().createCommandBuffer();
                frameCallback.accept(cmd);
                frameDriver.submit(cmd);
                frameDriver.endFrame();
                glfwSwapBuffers(window);
            }
        } catch (Throwable t) {
            failure = t;
            frameDriver.requestStop();
        } finally {
            initLatch.countDown();
            acceptingUploads.set(false);
            UploadSystem uploads = frameDriver.uploadQueue();
            try {
                uploads.seal();
                if (failure == null) {
                    uploads.flush();
                } else {
                    uploads.clear();
                }
            } catch (Throwable drainFailure) {
                failure = accumulate(failure, drainFailure);
            }
            try {
                frameDriver.close();
            } catch (Throwable closeFailure) {
                failure = accumulate(failure, closeFailure);
            }
            try {
                if (cleanupHook != null) cleanupHook.run();
            } catch (Throwable cleanupFailure) {
                failure = accumulate(failure, cleanupFailure);
            }
            try {
                glfwMakeContextCurrent(0);
            } catch (Throwable releaseFailure) {
                failure = accumulate(failure, releaseFailure);
            }
            if (failure == null) {
                state.set(State.TERMINATED);
                completion.complete(null);
            } else {
                state.set(State.FAILED);
                completion.completeExceptionally(failure);
            }
        }
    }

    private void ensureNotStarted(String operation) {
        if (started.get()) {
            throw new IllegalStateException(operation + " must be configured before start");
        }
    }

    private static boolean terminal(State state) {
        return state == State.TERMINATED || state == State.FAILED;
    }

    private static Throwable accumulate(Throwable existing, Throwable next) {
        if (existing == null) {
            return next;
        }
        existing.addSuppressed(next);
        return existing;
    }

    public record UploadStats(long bytesUploaded, long gpuUpdates, int pendingRequests) {
    }

    public enum State {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        TERMINATED,
        FAILED
    }
}
