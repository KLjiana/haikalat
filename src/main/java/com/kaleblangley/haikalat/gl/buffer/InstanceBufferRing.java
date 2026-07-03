package com.kaleblangley.haikalat.gl.buffer;

import com.kaleblangley.haikalat.gl.GlException;
import com.kaleblangley.haikalat.gl.GlResource;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glFlushMappedBufferRange;

public final class InstanceBufferRing implements GlResource {
    private static final int FRAME_COUNT = 3;
    private static final int MAT4_FLOATS = 16;
    private static final int MAT4_BYTES = MAT4_FLOATS * Float.BYTES;

    private GlBuffer buffer;
    private final ByteBuffer mappedPtr;
    private final long frameSize;
    private final int maxInstances;
    private final GpuFence[] fences = new GpuFence[FRAME_COUNT];
    private final boolean persistent;
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

        this.buffer = GlBuffer.arrayBuffer(GL_DYNAMIC_DRAW);
        boolean tryPersistent = PersistentMapping.isSupported();
        ByteBuffer ptr = null;
        boolean ok = false;

        if (tryPersistent) {
            try {
                buffer.allocateStorage(totalSize);
                ptr = buffer.mapPersistent(0, totalSize);
                ok = ptr != null;
            } catch (Exception ignored) {
            }
            if (ok) {
                this.mappedPtr = ptr;
            } else {
                buffer.close();
                this.buffer = GlBuffer.arrayBuffer(GL_DYNAMIC_DRAW);
                this.mappedPtr = null;
            }
        } else {
            this.mappedPtr = null;
        }

        if (!ok) {
            buffer.allocate(totalSize);
        }
        this.persistent = ok;
    }

    public boolean isPersistent() {
        return persistent;
    }

    public void beginFrame() {
        ensureOpen();
        GpuFence fence = fences[writeSlot];
        if (fence != null) {
            fence.waitFor(1_000_000_000L);
            fence.close();
            fences[writeSlot] = null;
        }
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

        if (persistent && mappedPtr != null) {
            uploadPersistent(transforms, count);
        } else {
            uploadLegacy(transforms, count);
        }
        activeCount = count;
    }

    private void uploadPersistent(List<Matrix4f> transforms, int count) {
        long offset = (long) writeSlot * frameSize;
        ByteBuffer slice = mappedPtr.duplicate();
        slice.position((int) offset);
        FloatBuffer fb = slice.asFloatBuffer();
        for (int i = 0; i < count; i++) {
            transforms.get(i).get(fb);
            fb.position(fb.position() + MAT4_FLOATS);
        }
        buffer.bind();
        glFlushMappedBufferRange(GL_ARRAY_BUFFER, offset, (long) count * MAT4_BYTES);
    }

    private void uploadLegacy(List<Matrix4f> transforms, int count) {
        long offset = (long) writeSlot * frameSize;
        FloatBuffer data = createMatrixBuffer(count);
        for (Matrix4f m : transforms) {
            m.get(data);
            data.position(data.position() + MAT4_FLOATS);
        }
        data.flip();
        buffer.update(offset, data);
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
        fences[writeSlot] = GpuFence.insert();
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
        for (int i = 0; i < FRAME_COUNT; i++) {
            if (fences[i] != null) {
                fences[i].close();
                fences[i] = null;
            }
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
