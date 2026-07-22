package com.kaleblangley.haikalat.core.mesh;

import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
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
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL31.glDrawArraysInstanced;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL30.GL_VERTEX_ARRAY_BINDING;
import static org.lwjgl.opengl.GL30.glBindVertexArray;

/**
 * 已上传到 OpenGL 的运行时网格资源。
 * 在 GL context 创建前，应使用 {@link MeshData} 完成资产解析、内建几何体构造和纯 JVM 测试。
 */
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
    private final Bounds3f localBounds;
    private boolean closed;

    private Mesh(Builder builder) {
        this.primitiveMode = builder.primitiveMode;
        this.vertexLayout = builder.layout;
        this.indexCount = builder.indexCount;
        this.localBounds = builder.localBounds == null ? Bounds3f.unbounded() : builder.localBounds;
        int previousVertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int previousArrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        this.vertexArray = new VertexArray();
        vertexArray.bind();
        try {
            this.indexBuffer = builder.indexData == null ? null
                    : GlBuffer.elementArrayBuffer(GL_STATIC_DRAW).upload(builder.indexData);
            if (indexBuffer != null) {
                vertexArray.bindElementBuffer(indexBuffer);
            }
            if (builder.attribDatas != null) {
                this.interleaved = false;
                this.vertexCount = builder.nonILVertexCount;
                List<GlBuffer> bufs = new ArrayList<>(builder.attribDatas.size());
                for (Builder.AttribData ad : builder.attribDatas) {
                    GlBuffer buf = GlBuffer.arrayBuffer(GL_STATIC_DRAW).upload(ad.data);
                    bufs.add(buf);
                    buf.bind();
                    glVertexAttribPointer(ad.location, ad.componentCount, GL_FLOAT, false,
                            ad.componentCount * Float.BYTES, 0);
                    glEnableVertexAttribArray(ad.location);
                }
                this.vertexBuffer = bufs.get(0);
                this.extraBuffers = bufs.size() > 1
                        ? bufs.subList(1, bufs.size()).toArray(GlBuffer[]::new)
                        : null;
            } else {
                this.interleaved = true;
                this.vertexCount = builder.vertexCount;
                this.vertexBuffer = GlBuffer.arrayBuffer(GL_STATIC_DRAW).upload(builder.vertexData);
                this.extraBuffers = null;
                vertexBuffer.bind();
                builder.layout.apply();
            }
        } finally {
            glBindVertexArray(previousVertexArray);
            glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
        }
    }

    /** @return 新建一个 Mesh Builder */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 将纯网格数据上传到 OpenGL buffer 和顶点数组。
     * 该方法要求当前线程持有有效的 GL context，并把生命周期所有权转交给返回的 {@code Mesh}。
     */
    public static Mesh from(MeshData data) {
        Objects.requireNonNull(data, "data");
        Builder builder = builder()
                .vertices(data.vertices(), data.layout().strideBytes(),
                        data.layout().attributes().toArray(VertexAttribute[]::new))
                .bounds(data.localBounds())
                .primitiveMode(data.primitiveMode());
        if (data.hasIndices()) {
            builder.indices(data.indices());
        }
        return builder.build();
    }

    /**
     * 绘制此网格（自动绑定 VAO）。
     *
     * @return 自身，支持链式调用
     */
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

    /**
     * 实例化绘制此网格（自动绑定 VAO）。
     *
     * @param instanceCount 实例数量
     * @return 自身，支持链式调用
     */
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

    /**
     * 实例化绘制此网格（不绑定 VAO，调用者需自行绑定）。
     *
     * @param instanceCount 实例数量
     * @return 自身，支持链式调用
     */
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

    /**
     * 绘制此网格（不绑定 VAO）。
     *
     * @return 自身，支持链式调用
     */
    public Mesh drawBound() {
        ensureOpen();
        if (indexBuffer != null) {
            glDrawElements(primitiveMode, indexCount, GL_UNSIGNED_INT, 0L);
        } else {
            glDrawArrays(primitiveMode, 0, vertexCount);
        }
        return this;
    }

    /**
     * 绑定此网格的 VAO。
     *
     * @return 自身，支持链式调用
     */
    public Mesh bind() {
        ensureOpen();
        vertexArray.bind();
        return this;
    }

    /** 解绑 VAO。 */
    public Mesh unbind() {
        vertexArray.unbind();
        return this;
    }

    /** @return 此网格的顶点数组对象 */
    public VertexArray vertexArray() {
        return vertexArray;
    }

    /** @return 此网格的主顶点缓冲区 */
    public GlBuffer vertexBuffer() {
        return vertexBuffer;
    }

    /** @return 顶点布局描述 */
    public VertexLayout vertexLayout() {
        return vertexLayout;
    }

    /** @return 此网格的索引缓冲区，可能为 null */
    public GlBuffer indexBuffer() {
        return indexBuffer;
    }

    /** @return 所有顶点缓冲区数组（包括主缓冲和额外属性缓冲） */
    public GlBuffer[] allVertexBuffers() {
        if (extraBuffers == null) {
            return new GlBuffer[]{vertexBuffer};
        }
        GlBuffer[] result = new GlBuffer[extraBuffers.length + 1];
        result[0] = vertexBuffer;
        System.arraycopy(extraBuffers, 0, result, 1, extraBuffers.length);
        return result;
    }

    /** @return 顶点数据是否为交错布局 */
    public boolean isInterleaved() {
        return interleaved;
    }

    /** @return 图元绘制模式 */
    public int primitiveMode() {
        return primitiveMode;
    }

    /** @return 顶点数量 */
    public int vertexCount() {
        return vertexCount;
    }

    /** @return 索引数量 */
    public int indexCount() {
        return indexCount;
    }

    /** @return 与网格生命周期无关的不可变局部包围盒 */
    public Bounds3f localBounds() {
        return localBounds;
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
        private Bounds3f localBounds;
        private boolean explicitBounds;

        private Builder() {
        }

        /**
         * 以交错顶点数据构造 Builder。
         *
         * @param data        顶点浮点数组
         * @param strideBytes 每个顶点的字节跨度
         * @param attributes  顶点属性描述
         */
        public Builder vertices(float[] data, int strideBytes, VertexAttribute... attributes) {
            Objects.requireNonNull(data, "data");
            this.vertexData = DirectBuffers.copyOf(data);
            this.layout = VertexLayout.interleaved(strideBytes, attributes);
            this.vertexCount = vertexCountFromStride(data.length, strideBytes);
            if (!explicitBounds) this.localBounds = MeshBounds.fromInterleaved(data, layout);
            return this;
        }

        /** 为程序化或非交错网格显式指定不可变局部包围盒。 */
        public Builder bounds(Bounds3f bounds) {
            this.localBounds = Objects.requireNonNull(bounds, "bounds");
            this.explicitBounds = true;
            return this;
        }

        /**
         * 设置顶点布局描述（用于非交错属性时指定 stride 等）。
         *
         * @param layout 顶点布局
         */
        public Builder layout(VertexLayout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            return this;
        }

        /**
         * 添加单独的非交错顶点属性数据。
         *
         * @param location       shader 中属性位置
         * @param data           属性浮点数组
         * @param componentCount 每个属性的分量数
         */
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

        /**
         * 设置索引数组。
         *
         * @param data 索引整型数组
         */
        public Builder indices(int[] data) {
            Objects.requireNonNull(data, "data");
            this.indexData = DirectBuffers.copyOf(data);
            this.indexCount = data.length;
            return this;
        }

        /**
         * 设置图元绘制模式（默认 GL_TRIANGLES）。
         *
         * @param value 图元模式
         */
        public Builder primitiveMode(int value) {
            this.primitiveMode = value;
            return this;
        }

        /** @return 构建完成的 Mesh 实例 */
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
