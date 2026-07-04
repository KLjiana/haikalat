package com.kaleblangley.haikalat.core.mesh;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.buffer.InstanceBufferRing;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;

public final class InstancedMeshBatch implements AutoCloseable {
    private final List<MeshEntry> meshes = new ArrayList<>();
    private final InstanceBufferRing instanceBuffers;
    private final VertexLayout instanceLayout;
    private final List<Matrix4f> transforms = new ArrayList<>();
    private boolean closed;

    private InstancedMeshBatch(int maxInstances, int baseAttributeLocation) {
        this.instanceLayout = VertexLayout.instanceMatrix(baseAttributeLocation);
        this.instanceBuffers = new InstanceBufferRing(maxInstances);
    }

    /**
     * 创建实例化批次并添加一个网格。
     *
     * @param mesh                  网格
     * @param maxInstances          每批次最大实例数
     * @param baseAttributeLocation 实例矩阵属性的起始 location
     * @return 新建的 InstancedMeshBatch
     */
    public static InstancedMeshBatch of(Mesh mesh, int maxInstances, int baseAttributeLocation) {
        return new InstancedMeshBatch(maxInstances, baseAttributeLocation).addMesh(mesh);
    }

    /**
     * 创建实例化批次并添加多个网格。
     *
     * @param meshes                网格列表
     * @param maxInstances          每批次最大实例数
     * @param baseAttributeLocation 实例矩阵属性的起始 location
     * @return 新建的 InstancedMeshBatch
     */
    public static InstancedMeshBatch of(List<Mesh> meshes, int maxInstances, int baseAttributeLocation) {
        InstancedMeshBatch batch = new InstancedMeshBatch(maxInstances, baseAttributeLocation);
        for (Mesh m : meshes) batch.addMesh(m);
        return batch;
    }

    /**
     * 向批次中注册一个网格，为其创建独立的 VAO。
     *
     * @param mesh 要注册的网格
     * @return 自身，支持链式调用
     */
    public InstancedMeshBatch addMesh(Mesh mesh) {
        Objects.requireNonNull(mesh, "mesh");
        VertexArray vao = new VertexArray();
        vao.bind();
        setupMeshAttributes(mesh);
        instanceBuffers.activeBuffer().bind();
        instanceLayout.apply();
        vao.unbind();
        meshes.add(new MeshEntry(vao, mesh));
        return this;
    }

    /** 开始新一帧，清空已提交的变换并置位实例缓冲区轮。 */
    public InstancedMeshBatch beginFrame() {
        ensureOpen();
        transforms.clear();
        instanceBuffers.beginFrame();
        return this;
    }

    /**
     * 提交一个实例的模型变换矩阵。
     *
     * @param transform 模型矩阵
     * @return 自身，支持链式调用
     */
    public InstancedMeshBatch submit(Matrix4f transform) {
        ensureOpen();
        transforms.add(new Matrix4f(Objects.requireNonNull(transform, "transform")));
        return this;
    }

    /**
     * 批量提交多个实例的模型变换矩阵。
     *
     * @param batch 模型矩阵集合
     * @return 自身，支持链式调用
     */
    public InstancedMeshBatch submitAll(Iterable<Matrix4f> batch) {
        ensureOpen();
        for (Matrix4f transform : batch) submit(transform);
        return this;
    }

    /**
     * 将当前所有待渲染的实例上传至 GPU 并绘制，绘制完成后清空变换列表。
     *
     * @return 本次绘制的实例数量
     */
    public int flush() {
        ensureOpen();
        if (transforms.isEmpty()) return 0;
        instanceBuffers.upload(transforms);
        int count = transforms.size();
        for (MeshEntry entry : meshes) {
            entry.vao.bind();
            instanceBuffers.bindAttributes(instanceLayout.attributes().get(0).index());
            entry.mesh.drawInstancedBound(count);
        }
        instanceBuffers.finishFrame();
        int drawn = transforms.size();
        transforms.clear();
        return drawn;
    }

    /** @return 是否使用持久化映射缓冲区 */
    public boolean isPersistent() {
        return instanceBuffers.isPersistent();
    }

    /** @return 当前待渲染的实例数量 */
    public int pendingInstances() {
        return transforms.size();
    }

    @Override
    public void close() {
        if (closed) return;
        instanceBuffers.close();
        for (MeshEntry entry : meshes) entry.vao.close();
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new GlException("InstancedMeshBatch is closed");
    }

    private static void setupMeshAttributes(Mesh mesh) {
        GlBuffer[] allBufs = mesh.allVertexBuffers();
        List<VertexAttribute> attrs = mesh.vertexLayout().attributes();
        if (!mesh.isInterleaved()) {
            for (int i = 0; i < attrs.size(); i++) {
                VertexAttribute attr = attrs.get(i);
                allBufs[i].bind();
                glVertexAttribPointer(attr.index(), attr.size(), attr.type(), attr.normalized(),
                        attr.size() * Float.BYTES, 0);
                glEnableVertexAttribArray(attr.index());
            }
        } else {
            allBufs[0].bind();
            mesh.vertexLayout().apply();
        }
        if (mesh.indexBuffer() != null) mesh.indexBuffer().bind();
    }

    private record MeshEntry(VertexArray vao, Mesh mesh) {}
}
