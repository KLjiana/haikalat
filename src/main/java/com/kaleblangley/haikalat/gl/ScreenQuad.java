package com.kaleblangley.haikalat.gl;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;

public final class ScreenQuad implements GlResource {
    private static final float[] VERTICES = {
            -1.0f, -1.0f, 0.0f, 0.0f,
            1.0f, -1.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 1.0f, 1.0f,
            -1.0f, -1.0f, 0.0f, 0.0f,
            1.0f, 1.0f, 1.0f, 1.0f,
            -1.0f, 1.0f, 0.0f, 1.0f
    };

    private final VertexArray vao = new VertexArray();
    private final GlBuffer vbo = GlBuffer.arrayBuffer(GL_STATIC_DRAW).upload(VERTICES);
    private boolean closed;

    public ScreenQuad() {
        vao.bind();
        vbo.bind();
        VertexLayout.interleaved(
                4 * Float.BYTES,
                VertexAttribute.builder().index(0).size(2).type(GL_FLOAT).offsetBytes(0).build(),
                VertexAttribute.builder().index(1).size(2).type(GL_FLOAT).offsetBytes(2L * Float.BYTES).build()
        ).apply();
        vao.unbind();
    }

    public ScreenQuad bind() {
        ensureOpen();
        vao.bind();
        return this;
    }

    public ScreenQuad draw() {
        ensureOpen();
        vao.bind();
        glDrawArrays(GL_TRIANGLES, 0, 6);
        return this;
    }

    @Override
    public int id() {
        return vao.id();
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
        vbo.close();
        vao.close();
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("ScreenQuad is closed");
        }
    }
}
