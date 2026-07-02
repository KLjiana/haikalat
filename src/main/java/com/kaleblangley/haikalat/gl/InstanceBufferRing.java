package com.kaleblangley.haikalat.gl;

import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

public final class InstanceBufferRing implements GlResource {
    private final GlBuffer buffer;
    private final int maxInstances;
    private final int strideBytes;
    private int activeCount;
    private boolean closed;

    public InstanceBufferRing(int maxInstances) {
        if (maxInstances <= 0) {
            throw new IllegalArgumentException("maxInstances must be positive");
        }
        this.maxInstances = maxInstances;
        this.strideBytes = 16 * Float.BYTES;
        this.buffer = GlBuffer.arrayBuffer(org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW)
                .allocate((long) maxInstances * strideBytes);
    }

    public int maxInstances() {
        return maxInstances;
    }

    public int strideBytes() {
        return strideBytes;
    }

    public void beginFrame() {
        ensureOpen();
        activeCount = 0;
    }

    public void upload(List<Matrix4f> transforms) {
        ensureOpen();
        if (transforms.size() > maxInstances) {
            throw new GlException("Instance count exceeds buffer capacity: " + transforms.size() + " > " + maxInstances);
        }
        if (transforms.isEmpty()) {
            activeCount = 0;
            return;
        }

        FloatBuffer data = createMatrixBuffer(transforms.size());
        for (Matrix4f transform : transforms) {
            transform.get(data);
            data.position(data.position() + 16);
        }
        data.flip();
        buffer.update(0L, data);
        activeCount = transforms.size();
    }

    public GlBuffer activeBuffer() {
        ensureOpen();
        return buffer;
    }

    public int activeCount() {
        return activeCount;
    }

    public void markSubmitted() {
        ensureOpen();
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
        buffer.close();
        closed = true;
    }

    private FloatBuffer createMatrixBuffer(int matrixCount) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(matrixCount * strideBytes).order(ByteOrder.nativeOrder());
        return bytes.asFloatBuffer();
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("InstanceBufferRing is closed");
        }
    }
}
