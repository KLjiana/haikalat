package com.kaleblangley.haikalat.gl.material;

import com.kaleblangley.haikalat.gl.GlException;
import com.kaleblangley.haikalat.gl.GlResource;
import com.kaleblangley.haikalat.gl.buffer.GlBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT;
import static org.lwjgl.opengl.GL31.glBindBufferRange;
import static org.lwjgl.opengl.GL31.glGetInteger;

public final class UniformBlock implements GlResource {
    private final GlBuffer buffer;
    private final int sizeBytes;
    private final ByteBuffer mappedData;
    private boolean dirty;
    private boolean closed;

    public UniformBlock(int sizeBytes) {
        this.sizeBytes = alignSize(sizeBytes);
        this.buffer = GlBuffer.arrayBuffer(GL_DYNAMIC_DRAW).allocate(this.sizeBytes);
        this.mappedData = ByteBuffer.allocateDirect(this.sizeBytes).order(ByteOrder.nativeOrder());
        this.dirty = false;
    }

    public UniformBlock setFloat(int offsetBytes, float value) {
        mappedData.putFloat(offsetBytes, value);
        dirty = true;
        return this;
    }

    public UniformBlock setInt(int offsetBytes, int value) {
        mappedData.putInt(offsetBytes, value);
        dirty = true;
        return this;
    }

    public UniformBlock setVec3(int offsetBytes, float x, float y, float z) {
        mappedData.putFloat(offsetBytes, x);
        mappedData.putFloat(offsetBytes + 4, y);
        mappedData.putFloat(offsetBytes + 8, z);
        dirty = true;
        return this;
    }

    public UniformBlock setMat4(int offsetBytes, FloatBuffer data) {
        mappedData.position(offsetBytes);
        mappedData.asFloatBuffer().put(data);
        dirty = true;
        return this;
    }

    public void bind(int bindingPoint) {
        ensureOpen();
        mappedData.flip();
        buffer.update(0, mappedData);
        glBindBufferRange(GL_UNIFORM_BUFFER, bindingPoint, buffer.id(), 0, sizeBytes);
        mappedData.compact();
        dirty = false;
    }

    public int sizeBytes() {
        return sizeBytes;
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
        buffer.close();
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new GlException("UniformBlock is closed");
    }

    private static int alignSize(int size) {
        int alignment = 256;
        try {
            int glAlignment = glGetInteger(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT);
            if (glAlignment > 0) alignment = glAlignment;
        } catch (Exception ignored) {
        }
        return ((size + alignment - 1) / alignment) * alignment;
    }
}
