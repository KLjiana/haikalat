package com.kaleblangley.haikalat.gl.render;

import com.kaleblangley.haikalat.gl.GlException;
import com.kaleblangley.haikalat.gl.GlResource;
import com.kaleblangley.haikalat.gl.buffer.GlBuffer;
import com.kaleblangley.haikalat.gl.mesh.VertexArray;
import com.kaleblangley.haikalat.gl.mesh.VertexAttribute;
import com.kaleblangley.haikalat.gl.mesh.VertexLayout;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
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
        if (closed) return;
        vbo.close();
        vao.close();
        closed = true;
    }

    void ensureOpen() {
        if (closed) throw new GlException("ScreenQuad closed");
    }
}
