package com.kaleblangley.haikalat.gl;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class InstancedMeshBatch implements AutoCloseable {
    private final Mesh mesh;
    private final VertexArray vertexArray;
    private final InstanceBufferRing instanceBuffers;
    private final VertexLayout instanceLayout;
    private final List<Matrix4f> transforms = new ArrayList<>();
    private boolean closed;

    private InstancedMeshBatch(Mesh mesh, int maxInstances, int baseAttributeLocation) {
        this.mesh = Objects.requireNonNull(mesh, "mesh");
        this.vertexArray = new VertexArray();
        this.instanceBuffers = new InstanceBufferRing(maxInstances);
        this.instanceLayout = VertexLayout.instanceMatrix(baseAttributeLocation);

        vertexArray.bind();
        mesh.vertexBuffer().bind();
        mesh.vertexLayout().apply();
        if (mesh.indexBuffer() != null) {
            mesh.indexBuffer().bind();
        }
        instanceBuffers.activeBuffer().bind();
        instanceLayout.apply();
        vertexArray.unbind();
    }

    public static InstancedMeshBatch of(Mesh mesh, int maxInstances, int baseAttributeLocation) {
        return new InstancedMeshBatch(mesh, maxInstances, baseAttributeLocation);
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
        for (Matrix4f transform : batch) {
            submit(transform);
        }
        return this;
    }

    public int flush() {
        ensureOpen();
        if (transforms.isEmpty()) {
            return 0;
        }
        instanceBuffers.upload(transforms);
        vertexArray.bind();
        mesh.drawInstancedBound(transforms.size());
        int drawn = transforms.size();
        transforms.clear();
        return drawn;
    }

    public int pendingInstances() {
        return transforms.size();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        instanceBuffers.close();
        vertexArray.close();
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("InstancedMeshBatch is closed");
        }
    }
}
