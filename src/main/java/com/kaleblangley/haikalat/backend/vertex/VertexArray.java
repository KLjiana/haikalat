package com.kaleblangley.haikalat.backend.vertex;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;

import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL45.glCreateVertexArrays;
import static org.lwjgl.opengl.GL45.glVertexArrayElementBuffer;

public final class VertexArray implements GlResource {
    private final int id;
    private boolean closed;

    public VertexArray() {
        this.id = glCreateVertexArrays();
    }

    public VertexArray bind() {
        ensureOpen();
        glBindVertexArray(id);
        return this;
    }

    public VertexArray unbind() {
        glBindVertexArray(0);
        return this;
    }

    public VertexArray bindVertexBuffer(GlBuffer buffer, VertexLayout layout) {
        ensureOpen();
        buffer.bind();
        bind();
        layout.apply();
        return this;
    }

    public VertexArray bindElementBuffer(GlBuffer buffer) {
        ensureOpen();
        glVertexArrayElementBuffer(id, buffer.id());
        return this;
    }

    public VertexArray bindAttributeBuffer(int location, GlBuffer buffer, int size, int type, boolean normalized, int stride) {
        ensureOpen();
        bind();
        buffer.bind();
        glVertexAttribPointer(location, size, type, normalized, stride, 0);
        glEnableVertexAttribArray(location);
        return this;
    }

    public VertexArray bindAttributeBuffer(VertexAttribute attribute, GlBuffer buffer, int stride) {
        return bindAttributeBuffer(attribute.index(), buffer, attribute.size(), attribute.type(), attribute.normalized(), stride);
    }

    @Override
    public int id() {
        return id;
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
        glDeleteVertexArrays(id);
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("Vertex array is closed");
        }
    }
}
