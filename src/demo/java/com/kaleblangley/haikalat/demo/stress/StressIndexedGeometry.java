package com.kaleblangley.haikalat.demo.stress;

import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.util.DirectBuffers;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;

/** Empty VAO plus an optional generated uint8 EBO. This path deliberately owns no VBO. */
final class StressIndexedGeometry implements GlResource {
    private final VertexArray vertexArray = new VertexArray();
    private final GlBuffer elementBuffer;
    private boolean closed;

    StressIndexedGeometry(GeneratedStressPrimitive primitive) {
        byte[] indices = primitive.indices();
        if (indices.length == 0) {
            elementBuffer = null;
        } else {
            elementBuffer = GlBuffer.elementArrayBuffer(GL_STATIC_DRAW)
                    .upload(DirectBuffers.copyOf(indices));
            vertexArray.bindElementBuffer(elementBuffer);
        }
    }

    void recordDraw(CommandBuffer commands, GeneratedStressPrimitive primitive, int instances) {
        commands.bindVertexArray(vertexArray.id());
        if (primitive.indexed()) {
            commands.drawElementsInstanced(GL_TRIANGLES, primitive.indexCount(),
                    primitive.indexType(), 0L, instances);
        } else {
            commands.drawArraysInstanced(GL_TRIANGLES, 0,
                    primitive.logicalVertexCount(), instances);
        }
    }

    @Override
    public int id() {
        return vertexArray.id();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        if (elementBuffer != null) elementBuffer.close();
        vertexArray.close();
        closed = true;
    }
}
