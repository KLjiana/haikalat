package com.kaleblangley.haikalat.gl.buffer;

import com.kaleblangley.haikalat.gl.GlException;
import com.kaleblangley.haikalat.gl.GlResource;
import com.kaleblangley.haikalat.util.DirectBuffers;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Objects;

import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glBufferSubData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL15.glUnmapBuffer;
import static org.lwjgl.opengl.GL30.glMapBufferRange;

public final class GlBuffer implements GlResource {
    private final int target;
    private final int usage;
    private final int id;
    private boolean closed;

    public GlBuffer(int target, int usage) {
        this.target = target;
        this.usage = usage;
        this.id = glGenBuffers();
    }

    public static GlBuffer arrayBuffer(int usage) {
        return new GlBuffer(GL_ARRAY_BUFFER, usage);
    }

    public static GlBuffer elementArrayBuffer(int usage) {
        return new GlBuffer(GL_ELEMENT_ARRAY_BUFFER, usage);
    }

    public static GlBuffer arrayBuffer(float[] data, int usage) {
        return arrayBuffer(usage).upload(DirectBuffers.copyOf(data));
    }

    public static GlBuffer elementArrayBuffer(int[] data, int usage) {
        return elementArrayBuffer(usage).upload(DirectBuffers.copyOf(data));
    }

    public GlBuffer bind() {
        ensureOpen();
        glBindBuffer(target, id);
        return this;
    }

    public GlBuffer unbind() {
        glBindBuffer(target, 0);
        return this;
    }

    public GlBuffer allocate(long sizeBytes) {
        ensureOpen();
        bind();
        glBufferData(target, sizeBytes, usage);
        return this;
    }

    public GlBuffer upload(ByteBuffer data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        bind();
        glBufferData(target, data, usage);
        return this;
    }

    public GlBuffer upload(FloatBuffer data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        bind();
        glBufferData(target, data, usage);
        return this;
    }

    public GlBuffer upload(float[] data) {
        return upload(DirectBuffers.copyOf(data));
    }

    public GlBuffer upload(IntBuffer data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        bind();
        glBufferData(target, data, usage);
        return this;
    }

    public GlBuffer update(long offsetBytes, ByteBuffer data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        bind();
        glBufferSubData(target, offsetBytes, data);
        return this;
    }

    public GlBuffer update(long offsetBytes, FloatBuffer data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        bind();
        glBufferSubData(target, offsetBytes, data);
        return this;
    }

    public ByteBuffer mapRange(long offsetBytes, long lengthBytes, int access) {
        ensureOpen();
        bind();
        return glMapBufferRange(target, offsetBytes, lengthBytes, access);
    }

    public boolean unmap() {
        ensureOpen();
        bind();
        return glUnmapBuffer(target);
    }

    @Override
    public int id() {
        return id;
    }

    public int target() {
        return target;
    }

    public int usage() {
        return usage;
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
        glDeleteBuffers(id);
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("Buffer is closed");
        }
    }
}
