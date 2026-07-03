package com.kaleblangley.haikalat.gl.buffer;

import com.kaleblangley.haikalat.gl.GlException;
import com.kaleblangley.haikalat.gl.GlResource;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;

public final class InstanceBufferRing implements GlResource {
    private static final int FRAME_COUNT = 3;
    private static final int MAT4_FLOATS = 16;
    private static final int MAT4_BYTES = MAT4_FLOATS * Float.BYTES;

    private final GlBuffer buffer;
    private final long frameSize;
    private final int maxInstances;
    private int writeSlot;
    private int activeCount;
    private boolean closed;

    public InstanceBufferRing(int maxInstances) {
        if (maxInstances <= 0) {
            throw new IllegalArgumentException("maxInstances must be positive");
        }
        this.maxInstances = maxInstances;
        this.frameSize = (long) maxInstances * MAT4_BYTES;
        long totalSize = frameSize * FRAME_COUNT;

        this.buffer = GlBuffer.arrayBuffer(GL_DYNAMIC_DRAW).allocate(totalSize);
    }

    public boolean isPersistent() {
        return false;
    }

    public int maxInstances() {
        return maxInstances;
    }

    public int strideBytes() {
        return MAT4_BYTES;
    }

    public void beginFrame() {
        ensureOpen();
        activeCount = 0;
    }

    public void upload(List<Matrix4f> transforms) {
        ensureOpen();
        int count = transforms.size();
        if (count > maxInstances) {
            throw new GlException("Instance count exceeds buffer capacity: " + count + " > " + maxInstances);
        }
        if (count == 0) {
            activeCount = 0;
            return;
        }

        long offset = (long) writeSlot * frameSize;
        FloatBuffer data = createMatrixBuffer(count);
        for (Matrix4f m : transforms) {
            m.get(data);
            data.position(data.position() + MAT4_FLOATS);
        }
        data.flip();
        buffer.update(offset, data);
        activeCount = count;
    }

    public void bindAttributes(int baseLocation) {
        ensureOpen();
        long offset = (long) writeSlot * frameSize;
        buffer.bind();
        for (int i = 0; i < 4; i++) {
            glVertexAttribPointer(baseLocation + i, 4, GL_FLOAT, false, MAT4_BYTES,
                    offset + (long) i * 4 * Float.BYTES);
        }
    }

    public void finishFrame() {
        ensureOpen();
        writeSlot = (writeSlot + 1) % FRAME_COUNT;
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
        buffer.close();
        closed = true;
    }

    private FloatBuffer createMatrixBuffer(int matrixCount) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(matrixCount * MAT4_BYTES).order(ByteOrder.nativeOrder());
        return bytes.asFloatBuffer();
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("InstanceBufferRing is closed");
        }
    }
}
