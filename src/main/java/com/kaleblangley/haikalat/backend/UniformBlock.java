package com.kaleblangley.haikalat.backend;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Objects;

import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT;
import static org.lwjgl.opengl.GL31.glBindBufferRange;
import static org.lwjgl.opengl.GL31.glGetInteger;

public final class UniformBlock implements GlResource {
    private final GlBuffer buffer;
    private final int requestedSizeBytes;
    private final int sizeBytes;
    private final ByteBuffer data;
    private int dirtyEndBytes;
    private boolean closed;

    public UniformBlock(int sizeBytes) {
        this(sizeBytes, queryUniformBufferAlignment());
    }

    UniformBlock(int sizeBytes, int alignment) {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be > 0");
        }
        if (alignment <= 0) {
            throw new IllegalArgumentException("alignment must be > 0");
        }
        this.requestedSizeBytes = sizeBytes;
        this.sizeBytes = alignSize(sizeBytes, alignment);
        this.buffer = GlBuffer.uniformBuffer(GL_DYNAMIC_DRAW).allocate(this.sizeBytes);
        this.data = ByteBuffer.allocateDirect(this.sizeBytes).order(ByteOrder.nativeOrder());
    }

    public UniformBlock setFloat(int offsetBytes, float value) {
        checkRange(offsetBytes, Float.BYTES);
        data.putFloat(offsetBytes, value);
        markDirty(offsetBytes + Float.BYTES);
        return this;
    }

    public UniformBlock setInt(int offsetBytes, int value) {
        checkRange(offsetBytes, Integer.BYTES);
        data.putInt(offsetBytes, value);
        markDirty(offsetBytes + Integer.BYTES);
        return this;
    }

    public UniformBlock setVec3(int offsetBytes, float x, float y, float z) {
        checkRange(offsetBytes, 3 * Float.BYTES);
        data.putFloat(offsetBytes, x);
        data.putFloat(offsetBytes + Float.BYTES, y);
        data.putFloat(offsetBytes + 2 * Float.BYTES, z);
        markDirty(offsetBytes + 3 * Float.BYTES);
        return this;
    }

    public UniformBlock setVec4(int offsetBytes, float x, float y, float z, float w) {
        checkRange(offsetBytes, 4 * Float.BYTES);
        data.putFloat(offsetBytes, x);
        data.putFloat(offsetBytes + Float.BYTES, y);
        data.putFloat(offsetBytes + 2 * Float.BYTES, z);
        data.putFloat(offsetBytes + 3 * Float.BYTES, w);
        markDirty(offsetBytes + 4 * Float.BYTES);
        return this;
    }

    public UniformBlock setMat4(int offsetBytes, Matrix4f value) {
        Objects.requireNonNull(value, "value");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer floats = stack.mallocFloat(16);
            value.get(floats);
            return setMat4(offsetBytes, floats);
        }
    }

    public UniformBlock setMat4(int offsetBytes, FloatBuffer value) {
        Objects.requireNonNull(value, "value");
        checkRange(offsetBytes, 16 * Float.BYTES);
        FloatBuffer copy = value.duplicate();
        if (copy.remaining() < 16) {
            throw new IllegalArgumentException("mat4 requires at least 16 floats");
        }
        ByteBuffer dst = data.duplicate().order(data.order());
        dst.position(offsetBytes);
        dst.asFloatBuffer().put(copy.limit(copy.position() + 16));
        markDirty(offsetBytes + 16 * Float.BYTES);
        return this;
    }

    public void bind(int bindingPoint) {
        ensureOpen();
        flush();
        glBindBufferRange(GL_UNIFORM_BUFFER, bindingPoint, buffer.id(), 0, sizeBytes);
    }

    public void flush() {
        ensureOpen();
        if (dirtyEndBytes == 0) {
            return;
        }
        ByteBuffer upload = data.duplicate().order(data.order());
        upload.position(0).limit(dirtyEndBytes);
        buffer.update(0, upload);
        dirtyEndBytes = 0;
    }

    public int requestedSizeBytes() {
        return requestedSizeBytes;
    }

    public int sizeBytes() {
        return sizeBytes;
    }

    public boolean dirty() {
        return dirtyEndBytes > 0;
    }

    public int dirtyEndBytes() {
        return dirtyEndBytes;
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

    private void markDirty(int endBytes) {
        dirtyEndBytes = Math.max(dirtyEndBytes, endBytes);
    }

    private void checkRange(int offsetBytes, int lengthBytes) {
        if (offsetBytes < 0) {
            throw new IndexOutOfBoundsException("offsetBytes must be >= 0: " + offsetBytes);
        }
        if (offsetBytes + lengthBytes > requestedSizeBytes) {
            throw new IndexOutOfBoundsException(
                    "write exceeds requested uniform block size: offset=" + offsetBytes
                            + ", length=" + lengthBytes + ", size=" + requestedSizeBytes);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("UniformBlock is closed");
        }
    }

    private static int queryUniformBufferAlignment() {
        int alignment = 256;
        try {
            int glAlignment = glGetInteger(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT);
            if (glAlignment > 0) {
                alignment = glAlignment;
            }
        } catch (Exception ignored) {
        }
        return alignment;
    }

    static int alignSize(int size, int alignment) {
        return ((size + alignment - 1) / alignment) * alignment;
    }
}
