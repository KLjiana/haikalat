package com.kaleblangley.haikalat.core.buffer;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.sync.GpuFence;
import com.kaleblangley.haikalat.core.mesh.InstanceDataLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL30.GL_MAP_WRITE_BIT;
import static org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT;
import static org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL33.glVertexAttribDivisor;

public final class InstanceBufferRing implements GlResource {
    private static final int FRAME_COUNT = 3;
    private static final int MAT4_FLOATS = 16;
    private static final int MAT4_BYTES = MAT4_FLOATS * Float.BYTES;
    private static final long FENCE_TIMEOUT_NANOS = 1_000_000_000L;

    private final GlBuffer buffer;
    private final InstanceDataLayout layout;
    private final InstanceUploadStrategy uploadStrategy;
    private final GpuFence[] fences = new GpuFence[FRAME_COUNT];
    private final long frameSize;
    private final int maxInstances;
    private final ByteBuffer persistentMapping;
    private int writeSlot;
    private int activeCount;
    private boolean frameBegun;
    private boolean closed;

    public InstanceBufferRing(int maxInstances) {
        this(maxInstances, InstanceDataLayout.mat4Transform(0), InstanceUploadStrategy.PERSISTENT_MAPPED);
    }

    public InstanceBufferRing(int maxInstances, InstanceDataLayout layout, InstanceUploadStrategy uploadStrategy) {
        if (maxInstances <= 0) {
            throw new IllegalArgumentException("maxInstances must be positive");
        }
        this.maxInstances = maxInstances;
        this.layout = Objects.requireNonNull(layout, "layout");
        this.uploadStrategy = Objects.requireNonNull(uploadStrategy, "uploadStrategy");
        this.frameSize = (long) maxInstances * layout.strideBytes();

        buffer = GlBuffer.arrayBuffer(GL_DYNAMIC_DRAW);
        if (uploadStrategy.persistent()) {
            int flags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
            buffer.allocateStorage(frameSize * FRAME_COUNT, flags);
            persistentMapping = buffer.mapRange(0L, frameSize * FRAME_COUNT, flags);
            if (persistentMapping == null) {
                buffer.close();
                throw new GlException("Failed to persistently map instance buffer");
            }
        } else {
            buffer.allocate(frameSize * FRAME_COUNT);
            persistentMapping = null;
        }
    }

    public boolean isPersistent() {
        return uploadStrategy.persistent();
    }

    public InstanceUploadStrategy uploadStrategy() {
        return uploadStrategy;
    }

    public InstanceDataLayout layout() {
        return layout;
    }

    public int maxInstances() {
        return maxInstances;
    }

    public int strideBytes() {
        return layout.strideBytes();
    }

    public void beginFrame() {
        ensureOpen();
        if (frameBegun) {
            throw new GlException("Instance buffer frame already begun");
        }
        waitForWritableSlot();
        activeCount = 0;
        frameBegun = true;
    }

    public void upload(List<Matrix4f> transforms) {
        upload(transforms, 0);
    }

    public void upload(List<Matrix4f> transforms, int startInstance) {
        ensureOpen();
        ensureFrameBegun();
        Objects.requireNonNull(transforms, "transforms");
        if (!layout.supportsMatrixTransforms()) {
            throw new GlException("Instance layout does not support Matrix4f uploads: " + layout.name());
        }
        if (startInstance < 0) {
            throw new IllegalArgumentException("startInstance must be non-negative");
        }
        int count = transforms.size();
        if (startInstance + count > maxInstances) {
            throw new GlException("Instance count exceeds buffer capacity: "
                    + (startInstance + count) + " > " + maxInstances);
        }
        if (count == 0) {
            return;
        }

        FloatBuffer data = createMatrixBuffer(count, startInstance);
        int paddingFloats = (layout.strideBytes() - MAT4_BYTES) / Float.BYTES;
        for (Matrix4f transform : transforms) {
            Objects.requireNonNull(transform, "transform").get(data);
            data.position(data.position() + MAT4_FLOATS + paddingFloats);
        }
        data.flip();

        if (!uploadStrategy.persistent()) {
            buffer.update(frameOffset(startInstance), data);
        }
        activeCount = Math.max(activeCount, startInstance + count);
    }

    public void bindAttributes(int baseLocation) {
        bindAttributes(InstanceDataLayout.mat4Transform(baseLocation), 0);
    }

    public void bindAttributes(InstanceDataLayout layout, int startInstance) {
        ensureOpen();
        ensureFrameBegun();
        Objects.requireNonNull(layout, "layout");
        if (startInstance < 0) {
            throw new IllegalArgumentException("startInstance must be non-negative");
        }

        long offset = frameOffset(startInstance);
        buffer.bind();
        for (VertexAttribute attribute : layout.attributes()) {
            glVertexAttribPointer(
                    attribute.index(),
                    attribute.size(),
                    attribute.type(),
                    attribute.normalized(),
                    layout.strideBytes(),
                    offset + attribute.offsetBytes()
            );
            glEnableVertexAttribArray(attribute.index());
            if (attribute.divisor() != 0) {
                glVertexAttribDivisor(attribute.index(), attribute.divisor());
            }
        }
    }

    public void finishFrame() {
        ensureOpen();
        ensureFrameBegun();
        if (activeCount > 0) {
            insertFenceForCurrentSlot();
            writeSlot = (writeSlot + 1) % FRAME_COUNT;
        }
        frameBegun = false;
    }

    public GlBuffer activeBuffer() {
        ensureOpen();
        return buffer;
    }

    public int activeCount() {
        return activeCount;
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
        if (closed) {
            return;
        }
        for (int i = 0; i < fences.length; i++) {
            closeFence(i);
        }
        if (persistentMapping != null) {
            buffer.unmap();
        }
        buffer.close();
        frameBegun = false;
        closed = true;
    }

    private FloatBuffer createMatrixBuffer(int matrixCount, int startInstance) {
        if (persistentMapping != null) {
            int offset = Math.toIntExact(frameOffset(startInstance));
            int size = Math.multiplyExact(matrixCount, layout.strideBytes());
            ByteBuffer region = persistentMapping.duplicate().order(ByteOrder.nativeOrder());
            region.position(offset).limit(offset + size);
            return region.slice().order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        ByteBuffer bytes = ByteBuffer.allocateDirect(matrixCount * layout.strideBytes())
                .order(ByteOrder.nativeOrder());
        return bytes.asFloatBuffer();
    }

    private long frameOffset(int startInstance) {
        return (long) writeSlot * frameSize + (long) startInstance * layout.strideBytes();
    }

    private void waitForWritableSlot() {
        if (uploadStrategy == InstanceUploadStrategy.TRIPLE_BUFFER_SUB_DATA || fences[writeSlot] == null) {
            return;
        }
        boolean signaled = fences[writeSlot].waitFor(FENCE_TIMEOUT_NANOS);
        if (!signaled) {
            throw new GlException("Timed out waiting for instance buffer slot " + writeSlot);
        }
        closeFence(writeSlot);
    }

    private void insertFenceForCurrentSlot() {
        if (uploadStrategy == InstanceUploadStrategy.TRIPLE_BUFFER_SUB_DATA) {
            return;
        }
        closeFence(writeSlot);
        fences[writeSlot] = GpuFence.insert();
    }

    private void closeFence(int slot) {
        if (fences[slot] != null) {
            fences[slot].close();
            fences[slot] = null;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("InstanceBufferRing is closed");
        }
    }

    private void ensureFrameBegun() {
        if (!frameBegun) {
            throw new GlException("Call beginFrame before using the instance buffer ring");
        }
    }
}
