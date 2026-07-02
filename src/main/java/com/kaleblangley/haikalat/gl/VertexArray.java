package com.kaleblangley.haikalat.gl;

import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

public final class VertexArray implements GlResource {
    private final int id;
    private boolean closed;

    public VertexArray() {
        this.id = glGenVertexArrays();
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
        bind();
        buffer.bind();
        return this;
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
