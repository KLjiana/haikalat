package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.sync.GpuFence;
import com.kaleblangley.haikalat.backend.sync.GpuFenceTarget;

import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL30.GL_MAP_WRITE_BIT;
import static org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT;
import static org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT;

/** UI 顶点的三槽 persistent-mapped ring；仅由 render thread 使用。 */
final class UiVertexRing implements AutoCloseable, GpuFenceTarget {
    static final int SLOT_COUNT = 3;
    static final int VERTICES_PER_QUAD = 4;
    static final int VERTEX_STRIDE_BYTES = 28;
    private static final long FENCE_TIMEOUT_NANOS = 1_000_000_000L;

    private final GlBuffer buffer;
    private final ByteBuffer mapping;
    private final GpuFence[] fences = new GpuFence[SLOT_COUNT];
    private final int maximumQuads;
    private final int slotSizeBytes;
    private int writeSlot;
    private int activeSlot = -1;
    private int pendingFenceSlot = -1;
    private volatile long waitNanos;
    private boolean closed;

    UiVertexRing(int maximumQuads) {
        if (maximumQuads <= 0) {
            throw new IllegalArgumentException("maximumQuads must be positive");
        }
        long slotBytes = Math.multiplyExact((long) maximumQuads,
                (long) VERTICES_PER_QUAD * VERTEX_STRIDE_BYTES);
        long totalBytes = Math.multiplyExact(slotBytes, SLOT_COUNT);
        if (totalBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("UI persistent vertex ring exceeds ByteBuffer capacity");
        }
        this.maximumQuads = maximumQuads;
        slotSizeBytes = Math.toIntExact(slotBytes);

        int flags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
        GlBuffer created = GlBuffer.arrayBuffer(GL_DYNAMIC_DRAW);
        try {
            created.allocateStorage(totalBytes, flags);
            ByteBuffer mapped = created.mapRange(0L, totalBytes, flags);
            if (mapped == null) throw new GlException("Failed to persistently map UI vertex ring");
            mapping = mapped.order(ByteOrder.nativeOrder());
            buffer = created;
        } catch (RuntimeException | Error failure) {
            created.close();
            throw failure;
        }
    }

    WriteSlice beginWrite(int quadCount) {
        ensureOpen();
        if (activeSlot >= 0) throw new IllegalStateException("UI vertex write is already active");
        if (quadCount < 0 || quadCount > maximumQuads) {
            throw new IllegalArgumentException("UI quad count exceeds ring capacity: "
                    + quadCount + " > " + maximumQuads);
        }
        insertPendingFenceFallback();
        waitForSlot(writeSlot);
        activeSlot = writeSlot;
        int offset = Math.multiplyExact(writeSlot, slotSizeBytes);
        int bytes = Math.multiplyExact(quadCount,
                VERTICES_PER_QUAD * VERTEX_STRIDE_BYTES);
        ByteBuffer view = mapping.duplicate().order(ByteOrder.nativeOrder());
        view.position(offset).limit(offset + bytes);
        return new WriteSlice(writeSlot, offset,
                view.slice().order(ByteOrder.nativeOrder()));
    }

    void markSubmitted() {
        ensureOpen();
        if (activeSlot < 0) throw new IllegalStateException("no active UI vertex write");
        VarHandle.releaseFence();
        pendingFenceSlot = activeSlot;
        writeSlot = (activeSlot + 1) % SLOT_COUNT;
        activeSlot = -1;
    }

    void abortWrite() {
        activeSlot = -1;
    }

    /** 由 CommandExecutor 在对应 UI draw 真正执行后调用。 */
    @Override
    public void insertGpuFence() {
        ensureOpen();
        insertPendingFenceFallback();
    }

    /** 异常命令流仍为失败前可能已经发出的 draw 建立保守 fence。 */
    @Override
    public void executionFailed(Throwable failure) {
        ensureOpen();
        insertPendingFenceFallback();
    }

    GlBuffer buffer() {
        ensureOpen();
        return buffer;
    }

    int maximumQuads() {
        return maximumQuads;
    }

    /** 返回等待 GPU 释放 ring slot 的累计纳秒数。 */
    long waitNanos() {
        return waitNanos;
    }

    @Override
    public void close() {
        if (closed) return;
        RuntimeException failure = null;
        try {
            insertPendingFenceFallback();
        } catch (RuntimeException exception) {
            failure = exception;
        }
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            try {
                waitForSlot(slot);
            } catch (RuntimeException exception) {
                failure = append(failure, exception);
            }
        }
        try {
            buffer.unmap();
        } catch (RuntimeException exception) {
            failure = append(failure, exception);
        }
        try {
            buffer.close();
        } catch (RuntimeException exception) {
            failure = append(failure, exception);
        }
        closed = true;
        if (failure != null) throw failure;
    }

    private void insertPendingFenceFallback() {
        if (pendingFenceSlot < 0) return;
        closeFence(pendingFenceSlot);
        fences[pendingFenceSlot] = GpuFence.insert();
        pendingFenceSlot = -1;
    }

    private void waitForSlot(int slot) {
        GpuFence fence = fences[slot];
        if (fence == null) return;
        long start = System.nanoTime();
        try {
            if (!fence.waitFor(FENCE_TIMEOUT_NANOS)) {
                throw new GlException("Timed out waiting for UI vertex ring slot " + slot);
            }
        } finally {
            waitNanos += System.nanoTime() - start;
            closeFence(slot);
        }
    }

    private void closeFence(int slot) {
        if (fences[slot] == null) return;
        fences[slot].close();
        fences[slot] = null;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("UI vertex ring is closed");
    }

    private static RuntimeException append(RuntimeException current, RuntimeException next) {
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }

    record WriteSlice(int slot, int slotOffsetBytes, ByteBuffer bytes) {
    }
}
