package com.kaleblangley.haikalat.core.buffer;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.sync.GpuFence;
import com.kaleblangley.haikalat.core.mesh.PackedInstanceLayout;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL30.GL_MAP_WRITE_BIT;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT;
import static org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT;
import static org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT;

/**
 * 存储 16 字节 packed instance 布局。
 *
 * <p>不可变数据只上传一次。动态数据使用三个持久映射且 offset 对齐的槽位；
 * change journal 只复制复用槽位尚未同步的范围。</p>
 */
public final class PackedInstanceBuffer implements GlResource {
    private static final int SLOT_COUNT = 3;
    private static final long FENCE_TIMEOUT_NANOS = 1_000_000_000L;

    private final GlBuffer buffer;
    private final int instanceCount;
    private final boolean dynamic;
    private final long slotStrideBytes;
    private final int offsetAlignment;
    private final ByteBuffer mapping;
    private final ByteBuffer shadow;
    private final GpuFence[] fences;
    private final long[] slotVersions;
    private final List<Change> changes;
    private long version;
    private long lastSynchronizedBytes;
    private int writeSlot;
    private boolean frameBegun;
    private boolean closed;

    private PackedInstanceBuffer(ByteBuffer initialData, int instanceCount, boolean dynamic) {
        if (instanceCount <= 0) throw new IllegalArgumentException("instanceCount must be positive");
        this.instanceCount = instanceCount;
        this.dynamic = dynamic;
        long payloadBytes = Math.multiplyExact((long) instanceCount, PackedInstanceLayout.STRIDE_BYTES);
        validateData(initialData, payloadBytes);

        buffer = GlBuffer.shaderStorageBuffer(dynamic ? GL_DYNAMIC_DRAW : GL_STATIC_DRAW);
        if (!dynamic) {
            offsetAlignment = 1;
            slotStrideBytes = payloadBytes;
            mapping = null;
            shadow = null;
            fences = new GpuFence[0];
            slotVersions = new long[0];
            changes = List.of();
            buffer.upload(slice(initialData, Math.toIntExact(payloadBytes)));
            return;
        }

        offsetAlignment = Math.max(1, glGetInteger(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT));
        slotStrideBytes = align(payloadBytes, offsetAlignment);
        long totalBytes = Math.multiplyExact(slotStrideBytes, SLOT_COUNT);
        int flags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
        buffer.allocateStorage(totalBytes, flags);
        mapping = buffer.mapRange(0L, totalBytes, flags);
        if (mapping == null) {
            buffer.close();
            throw new GlException("Failed to persistently map compact instance SSBO");
        }
        mapping.order(ByteOrder.nativeOrder());
        shadow = ByteBuffer.allocateDirect(Math.toIntExact(payloadBytes)).order(ByteOrder.nativeOrder());
        copy(initialData, initialData.position(), shadow, 0, Math.toIntExact(payloadBytes));
        fences = new GpuFence[SLOT_COUNT];
        slotVersions = new long[SLOT_COUNT];
        changes = new ArrayList<>();
        changes.add(new Change(0, Math.toIntExact(payloadBytes), ++version));
    }

    /** 创建内容只上传一次的 SSBO。 */
    public static PackedInstanceBuffer immutable(ByteBuffer data, int instanceCount) {
        return new PackedInstanceBuffer(Objects.requireNonNull(data, "data"), instanceCount, false);
    }

    /** 使用 packed 数据创建并初始化持久映射 SSBO ring。 */
    public static PackedInstanceBuffer dynamic(ByteBuffer initialData, int instanceCount) {
        return new PackedInstanceBuffer(Objects.requireNonNull(initialData, "initialData"), instanceCount, true);
    }

    /**
     * 更新 CPU shadow copy。每个 ring slot 下次参与 draw 前，只复制这一字节范围。
     */
    public void updateRange(int startInstance, ByteBuffer packedInstances, int updateCount) {
        ensureOpen();
        if (!dynamic) throw new GlException("Immutable packed instance data cannot be updated");
        if (frameBegun) throw new GlException("Update packed instances before beginFrame");
        if (startInstance < 0 || updateCount < 0 || startInstance + updateCount > instanceCount) {
            throw new IndexOutOfBoundsException("Packed instance update exceeds buffer capacity");
        }
        if (updateCount == 0) return;
        int byteCount = Math.multiplyExact(updateCount, PackedInstanceLayout.STRIDE_BYTES);
        validateData(packedInstances, byteCount);
        int byteOffset = Math.multiplyExact(startInstance, PackedInstanceLayout.STRIDE_BYTES);
        copy(packedInstances, packedInstances.position(), shadow, byteOffset, byteCount);
        changes.add(new Change(byteOffset, byteCount, ++version));
    }

    /** 等待活动槽位，并只同步该槽位尚未看到的变更。 */
    public void beginFrame() {
        ensureOpen();
        if (!dynamic) return;
        if (frameBegun) throw new GlException("Packed instance frame already begun");
        waitForWritableSlot();
        long knownVersion = slotVersions[writeSlot];
        long slotOffset = (long) writeSlot * slotStrideBytes;
        lastSynchronizedBytes = 0L;
        for (Change change : changes) {
            if (change.version() <= knownVersion) continue;
            copy(shadow, change.byteOffset(), mapping,
                    Math.toIntExact(slotOffset + change.byteOffset()), change.byteCount());
            lastSynchronizedBytes += change.byteCount();
        }
        slotVersions[writeSlot] = version;
        discardFullyPropagatedChanges();
        frameBegun = true;
    }

    /** 插入保护当前槽位的 fence，并前进到下一个对齐槽位。 */
    public void finishFrame() {
        ensureOpen();
        if (!dynamic) return;
        if (!frameBegun) throw new GlException("Call beginFrame before finishFrame");
        closeFence(writeSlot);
        fences[writeSlot] = GpuFence.insert();
        writeSlot = (writeSlot + 1) % SLOT_COUNT;
        frameBegun = false;
    }

    public GlBuffer buffer() {
        ensureOpen();
        return buffer;
    }

    public long bindingOffsetBytes() {
        ensureOpen();
        return dynamic ? (long) writeSlot * slotStrideBytes : 0L;
    }

    public long bindingSizeBytes() {
        return (long) instanceCount * PackedInstanceLayout.STRIDE_BYTES;
    }

    public int instanceCount() {
        return instanceCount;
    }

    public boolean dynamic() {
        return dynamic;
    }

    public long slotStrideBytes() {
        return slotStrideBytes;
    }

    public int offsetAlignment() {
        return offsetAlignment;
    }

    /** @return 最近一次 {@link #beginFrame()} 从 CPU shadow 复制到槽位的字节数 */
    public long lastSynchronizedBytes() {
        return lastSynchronizedBytes;
    }

    @Override
    public int id() {
        return buffer.id();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        for (int slot = 0; slot < fences.length; slot++) closeFence(slot);
        if (mapping != null) buffer.unmap();
        buffer.close();
        closed = true;
    }

    private void waitForWritableSlot() {
        GpuFence fence = fences[writeSlot];
        if (fence == null) return;
        if (!fence.waitFor(FENCE_TIMEOUT_NANOS)) {
            throw new GlException("Timed out waiting for compact instance slot " + writeSlot);
        }
        closeFence(writeSlot);
    }

    private void discardFullyPropagatedChanges() {
        long minimum = Long.MAX_VALUE;
        for (long slotVersion : slotVersions) minimum = Math.min(minimum, slotVersion);
        int discardCount = 0;
        while (discardCount < changes.size() && changes.get(discardCount).version() <= minimum) {
            discardCount++;
        }
        if (discardCount > 0) changes.subList(0, discardCount).clear();
    }

    private void closeFence(int slot) {
        if (fences[slot] == null) return;
        fences[slot].close();
        fences[slot] = null;
    }

    private static long align(long value, int alignment) {
        long remainder = value % alignment;
        return remainder == 0L ? value : Math.addExact(value, alignment - remainder);
    }

    private static void validateData(ByteBuffer data, long requiredBytes) {
        Objects.requireNonNull(data, "data");
        if (data.remaining() < requiredBytes) {
            throw new IllegalArgumentException("Expected " + requiredBytes
                    + " packed bytes, got " + data.remaining());
        }
    }

    private static ByteBuffer slice(ByteBuffer source, int byteCount) {
        ByteBuffer view = source.duplicate();
        view.limit(source.position() + byteCount);
        return view.slice().order(ByteOrder.nativeOrder());
    }

    private static void copy(ByteBuffer source, int sourceOffset,
                             ByteBuffer destination, int destinationOffset, int byteCount) {
        ByteBuffer read = source.duplicate();
        read.position(sourceOffset).limit(sourceOffset + byteCount);
        ByteBuffer write = destination.duplicate();
        write.position(destinationOffset).limit(destinationOffset + byteCount);
        write.put(read);
    }

    private void ensureOpen() {
        if (closed) throw new GlException("Packed instance buffer is closed");
    }

    private record Change(int byteOffset, int byteCount, long version) {
    }
}
