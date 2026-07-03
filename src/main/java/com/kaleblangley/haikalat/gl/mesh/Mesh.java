package com.kaleblangley.haikalat.gl.mesh;

import com.kaleblangley.haikalat.gl.GlException;
import com.kaleblangley.haikalat.gl.GlResource;
import com.kaleblangley.haikalat.gl.buffer.GlBuffer;
import com.kaleblangley.haikalat.util.DirectBuffers;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glDrawElements;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL31.glDrawArraysInstanced;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;

public final class Mesh implements GlResource {
    private final VertexArray vertexArray;
    private final GlBuffer vertexBuffer;
    private final GlBuffer indexBuffer;
    private final VertexLayout vertexLayout;
    private final int primitiveMode;
    private final int vertexCount;
    private final int indexCount;
    private final GlBuffer[] extraBuffers;
    private final boolean interleaved;
    private boolean closed;

    private Mesh(Builder builder) {
        this.primitiveMode = builder.primitiveMode;
        this.vertexLayout = builder.layout;
        this.indexCount = builder.indexCount;
        this.indexBuffer = builder.indexData == null ? null
                : GlBuffer.elementArrayBuffer(GL_STATIC_DRAW).upload(builder.indexData);

        this.vertexArray = new VertexArray();

        if (builder.attribDatas != null) {
            this.interleaved = false;
            this.vertexCount = builder.nonILVertexCount;
            List<GlBuffer> bufs = new ArrayList<>(builder.attribDatas.size());
            vertexArray.bind();
            for (Builder.AttribData ad : builder.attribDatas) {
                GlBuffer buf = GlBuffer.arrayBuffer(GL_STATIC_DRAW).upload(ad.data);
                bufs.add(buf);
                buf.bind();
                glVertexAttribPointer(ad.location, ad.componentCount, GL_FLOAT, false,
                        ad.componentCount * Float.BYTES, 0);
                glEnableVertexAttribArray(ad.location);
            }
            if (indexBuffer != null) {
                indexBuffer.bind();
            }
            vertexArray.unbind();
            this.vertexBuffer = bufs.get(0);
            this.extraBuffers = bufs.size() > 1
                    ? bufs.subList(1, bufs.size()).toArray(GlBuffer[]::new)
                    : null;
        } else {
            this.interleaved = true;
            this.vertexCount = builder.vertexCount;
            this.vertexBuffer = GlBuffer.arrayBuffer(GL_STATIC_DRAW).upload(builder.vertexData);
            this.extraBuffers = null;
            vertexArray.bind();
            vertexBuffer.bind();
            builder.layout.apply();
            if (indexBuffer != null) {
                indexBuffer.bind();
            }
            vertexArray.unbind();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Mesh draw() {
        ensureOpen();
        vertexArray.bind();
        if (indexBuffer != null) {
            glDrawElements(primitiveMode, indexCount, GL_UNSIGNED_INT, 0L);
        } else {
            glDrawArrays(primitiveMode, 0, vertexCount);
        }
        return this;
    }

    public Mesh drawInstanced(int instanceCount) {
        ensureOpen();
        if (instanceCount <= 0) {
            return this;
        }
        vertexArray.bind();
        if (indexBuffer != null) {
            glDrawElementsInstanced(primitiveMode, indexCount, GL_UNSIGNED_INT, 0L, instanceCount);
        } else {
            glDrawArraysInstanced(primitiveMode, 0, vertexCount, instanceCount);
        }
        return this;
    }

    public Mesh drawInstancedBound(int instanceCount) {
        ensureOpen();
        if (instanceCount <= 0) {
            return this;
        }
        if (indexBuffer != null) {
            glDrawElementsInstanced(primitiveMode, indexCount, GL_UNSIGNED_INT, 0L, instanceCount);
        } else {
            glDrawArraysInstanced(primitiveMode, 0, vertexCount, instanceCount);
        }
        return this;
    }

    public Mesh drawBound() {
        ensureOpen();
        if (indexBuffer != null) {
            glDrawElements(primitiveMode, indexCount, GL_UNSIGNED_INT, 0L);
        } else {
            glDrawArrays(primitiveMode, 0, vertexCount);
        }
        return this;
    }

    public Mesh bind() {
        ensureOpen();
        vertexArray.bind();
        return this;
    }

    public Mesh unbind() {
        vertexArray.unbind();
        return this;
    }

    public VertexArray vertexArray() {
        return vertexArray;
    }

    public GlBuffer vertexBuffer() {
        return vertexBuffer;
    }

    public VertexLayout vertexLayout() {
        return vertexLayout;
    }

    public GlBuffer indexBuffer() {
        return indexBuffer;
    }

    public GlBuffer[] allVertexBuffers() {
        if (extraBuffers == null) {
            return new GlBuffer[]{vertexBuffer};
        }
        GlBuffer[] result = new GlBuffer[extraBuffers.length + 1];
        result[0] = vertexBuffer;
        System.arraycopy(extraBuffers, 0, result, 1, extraBuffers.length);
        return result;
    }

    public boolean isInterleaved() {
        return interleaved;
    }

    public int primitiveMode() {
        return primitiveMode;
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int indexCount() {
        return indexCount;
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
        if (closed) {
            return;
        }
        if (indexBuffer != null) {
            indexBuffer.close();
        }
        if (extraBuffers != null) {
            for (GlBuffer buf : extraBuffers) {
                buf.close();
            }
        }
        vertexBuffer.close();
        vertexArray.close();
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("Mesh is closed");
        }
    }

    public static final class Builder {
        private FloatBuffer vertexData;
        private IntBuffer indexData;
        private VertexLayout layout;
        private int primitiveMode = GL_TRIANGLES;
        private int vertexCount;
        private int indexCount;
        private List<AttribData> attribDatas;
        private int nonILVertexCount;

        private Builder() {
        }

        public Builder vertices(float[] data, int strideBytes, VertexAttribute... attributes) {
            Objects.requireNonNull(data, "data");
            this.vertexData = DirectBuffers.copyOf(data);
            this.layout = VertexLayout.interleaved(strideBytes, attributes);
            this.vertexCount = vertexCountFromStride(data.length, strideBytes);
            return this;
        }

        public Builder layout(VertexLayout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            return this;
        }

        public Builder attribute(int location, float[] data, int componentCount) {
            Objects.requireNonNull(data, "data");
            if (componentCount <= 0) {
                throw new IllegalArgumentException("componentCount must be positive");
            }
            if (attribDatas == null) {
                attribDatas = new ArrayList<>();
            }
            attribDatas.add(new AttribData(location, DirectBuffers.copyOf(data), componentCount));
            if (nonILVertexCount == 0) {
                nonILVertexCount = data.length / componentCount;
            }
            return this;
        }

        public Builder indices(int[] data) {
            Objects.requireNonNull(data, "data");
            this.indexData = DirectBuffers.copyOf(data);
            this.indexCount = data.length;
            return this;
        }

        public Builder primitiveMode(int value) {
            this.primitiveMode = value;
            return this;
        }

        public Mesh build() {
            if (layout == null) {
                throw new GlException("Vertex layout is required");
            }
            if (attribDatas != null) {
                if (attribDatas.isEmpty()) {
                    throw new GlException("No vertex attribute data");
                }
                return new Mesh(this);
            }
            if (vertexData == null) {
                throw new GlException("Vertex data is required");
            }
            return new Mesh(this);
        }

        private static int vertexCountFromStride(int floatCount, int strideBytes) {
            if (strideBytes % Float.BYTES != 0) {
                throw new IllegalArgumentException("strideBytes must be aligned to float size");
            }
            int floatsPerVertex = strideBytes / Float.BYTES;
            if (floatsPerVertex <= 0 || floatCount % floatsPerVertex != 0) {
                throw new IllegalArgumentException("vertex data is not divisible by stride");
            }
            return floatCount / floatsPerVertex;
        }

        record AttribData(int location, FloatBuffer data, int componentCount) {
        }
    }
}
