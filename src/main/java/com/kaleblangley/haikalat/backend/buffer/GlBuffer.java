package com.kaleblangley.haikalat.backend.buffer;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.util.DirectBuffers;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Objects;

import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL45.*;

public final class GlBuffer implements GlResource, BufferUploadTarget {
    private final int target;
    private final int usage;
    private final int id;
    private final long resourceSequence;
    private boolean labeled;
    private boolean closed;

    public GlBuffer(int target, int usage) {
        this.target = target;
        this.usage = usage;
        this.id = glCreateBuffers();
        this.resourceSequence = GlDebug.trackResource("BUFFER", id,
                "GlBuffer target=" + target, -1L);
        labelIfNeeded();
    }

    public static GlBuffer arrayBuffer(int usage) {
        return new GlBuffer(GL_ARRAY_BUFFER, usage);
    }

    public static GlBuffer elementArrayBuffer(int usage) {
        return new GlBuffer(GL_ELEMENT_ARRAY_BUFFER, usage);
    }

    public static GlBuffer uniformBuffer(int usage) {
        return new GlBuffer(GL_UNIFORM_BUFFER, usage);
    }

    public static GlBuffer shaderStorageBuffer(int usage) {
        return new GlBuffer(GL_SHADER_STORAGE_BUFFER, usage);
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
        labelIfNeeded();
        return this;
    }

    public GlBuffer unbind() {
        glBindBuffer(target, 0);
        return this;
    }

    public GlBuffer allocate(long sizeBytes) {
        ensureOpen();
        glNamedBufferData(id, sizeBytes, usage);
        GlDebug.updateResourceBytes(resourceSequence, sizeBytes);
        return this;
    }

    /** 为持久映射或显式管理的 buffer 分配 OpenGL 4.4+ 不可变存储。 */
    public GlBuffer allocateStorage(long sizeBytes, int flags) {
        ensureOpen();
        glNamedBufferStorage(id, sizeBytes, flags);
        GlDebug.updateResourceBytes(resourceSequence, sizeBytes);
        return this;
    }

    public GlBuffer upload(ByteBuffer data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        glNamedBufferData(id, data, usage);
        GlDebug.updateResourceBytes(resourceSequence, data.remaining());
        return this;
    }

    public GlBuffer upload(FloatBuffer data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        glNamedBufferData(id, data, usage);
        GlDebug.updateResourceBytes(resourceSequence, (long) data.remaining() * Float.BYTES);
        return this;
    }

    public GlBuffer upload(float[] data) {
        return upload(DirectBuffers.copyOf(data));
    }

    public GlBuffer upload(IntBuffer data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        glNamedBufferData(id, data, usage);
        GlDebug.updateResourceBytes(resourceSequence, (long) data.remaining() * Integer.BYTES);
        return this;
    }

    public GlBuffer update(long offsetBytes, ByteBuffer data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        glNamedBufferSubData(id, offsetBytes, data);
        return this;
    }

    public GlBuffer update(long offsetBytes, FloatBuffer data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        glNamedBufferSubData(id, offsetBytes, data);
        return this;
    }

    /** 在不重新绑定 target 的情况下，将 buffer range 读回调用方持有的 direct storage。 */
    public GlBuffer read(long offsetBytes, ByteBuffer destination) {
        ensureOpen();
        Objects.requireNonNull(destination, "destination");
        glGetNamedBufferSubData(id, offsetBytes, destination);
        return this;
    }

    public ByteBuffer mapRange(long offsetBytes, long lengthBytes, int access) {
        ensureOpen();
        return glMapNamedBufferRange(id, offsetBytes, lengthBytes, access);
    }

    public boolean unmap() {
        ensureOpen();
        return glUnmapNamedBuffer(id);
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
        GlDebug.closeResource(resourceSequence);
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("Buffer is closed");
        }
    }

    private void labelIfNeeded() {
        if (labeled) {
            return;
        }
        GlDebug.labelObject(GL_BUFFER, id, "GlBuffer target=" + target);
        labeled = true;
    }
}
