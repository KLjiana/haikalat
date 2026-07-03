package com.kaleblangley.haikalat.gl.mesh;

import com.kaleblangley.haikalat.gl.GlException;
import com.kaleblangley.haikalat.gl.buffer.GlBuffer;
import com.kaleblangley.haikalat.gl.buffer.InstanceBufferRing;
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

    public static InstancedMeshBatch of(Mesh mesh, int maxInstances, int baseAttributeLocation) {
        return new InstancedMeshBatch(maxInstances, baseAttributeLocation).addMesh(mesh);
    }

    public static InstancedMeshBatch of(List<Mesh> meshes, int maxInstances, int baseAttributeLocation) {
        InstancedMeshBatch batch = new InstancedMeshBatch(maxInstances, baseAttributeLocation);
        for (Mesh m : meshes) batch.addMesh(m);
        return batch;
    }

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

    public InstancedMeshBatch beginFrame() {
        ensureOpen();
        transforms.clear();
        instanceBuffers.beginFrame();
        return this;
    }

    public InstancedMeshBatch submit(Matrix4f transform) {
        ensureOpen();
        transforms.add(new Matrix4f(Objects.requireNonNull(transform, "transform")));
        return this;
    }

    public InstancedMeshBatch submitAll(Iterable<Matrix4f> batch) {
        ensureOpen();
        for (Matrix4f transform : batch) submit(transform);
        return this;
    }

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

    public boolean isPersistent() {
        return instanceBuffers.isPersistent();
    }

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
