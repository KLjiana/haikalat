package com.kaleblangley.haikalat.core.mesh;

import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.buffer.InstanceBufferRing;
import com.kaleblangley.haikalat.core.buffer.InstanceUploadStrategy;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;

public final class InstancedMeshBatch implements AutoCloseable {
    private final List<MeshEntry> meshes = new ArrayList<>();
    private final Map<Mesh, MeshEntry> meshesBySource = new LinkedHashMap<>();
    private final InstanceBufferRing instanceBuffers;
    private final InstanceDataLayout instanceLayout;
    private MeshEntry defaultMesh;
    private InstanceBatchStats lastStats = InstanceBatchStats.empty();
    private boolean closed;

    private InstancedMeshBatch(int maxInstances, int baseAttributeLocation) {
        this(maxInstances, InstanceDataLayout.mat4Transform(baseAttributeLocation),
                InstanceUploadStrategy.PERSISTENT_MAPPED);
    }

    private InstancedMeshBatch(int maxInstances, InstanceDataLayout instanceLayout,
                              InstanceUploadStrategy uploadStrategy) {
        this.instanceLayout = Objects.requireNonNull(instanceLayout, "instanceLayout");
        this.instanceBuffers = new InstanceBufferRing(maxInstances, instanceLayout, uploadStrategy);
    }

    public static InstancedMeshBatch of(Mesh mesh, int maxInstances, int baseAttributeLocation) {
        return new InstancedMeshBatch(maxInstances, baseAttributeLocation).addMesh(mesh);
    }

    public static InstancedMeshBatch of(Mesh mesh, int maxInstances, InstanceDataLayout layout) {
        return new InstancedMeshBatch(maxInstances, layout, InstanceUploadStrategy.PERSISTENT_MAPPED)
                .addMesh(mesh);
    }

    public static InstancedMeshBatch of(Mesh mesh, int maxInstances, InstanceDataLayout layout,
                                       InstanceUploadStrategy uploadStrategy) {
        return new InstancedMeshBatch(maxInstances, layout, uploadStrategy).addMesh(mesh);
    }

    public static InstancedMeshBatch of(List<Mesh> meshes, int maxInstances, int baseAttributeLocation) {
        InstancedMeshBatch batch = new InstancedMeshBatch(maxInstances, baseAttributeLocation);
        for (Mesh mesh : meshes) {
            batch.addMesh(mesh);
        }
        return batch;
    }

    public static InstancedMeshBatch of(List<Mesh> meshes, int maxInstances, InstanceDataLayout layout) {
        InstancedMeshBatch batch = new InstancedMeshBatch(maxInstances, layout,
                InstanceUploadStrategy.PERSISTENT_MAPPED);
        for (Mesh mesh : meshes) {
            batch.addMesh(mesh);
        }
        return batch;
    }

    public InstancedMeshBatch addMesh(Mesh mesh) {
        ensureOpen();
        Objects.requireNonNull(mesh, "mesh");
        if (meshesBySource.containsKey(mesh)) {
            return this;
        }

        VertexArray vao = new VertexArray();
        vao.bind();
        setupMeshAttributes(mesh);
        instanceBuffers.activeBuffer().bind();
        instanceLayout.vertexLayout().apply();
        vao.unbind();

        MeshEntry entry = new MeshEntry(vao, mesh, new ArrayList<>());
        meshes.add(entry);
        meshesBySource.put(mesh, entry);
        if (defaultMesh == null) {
            defaultMesh = entry;
        }
        return this;
    }

    public InstancedMeshBatch beginFrame() {
        ensureOpen();
        for (MeshEntry entry : meshes) {
            entry.transforms.clear();
        }
        lastStats = InstanceBatchStats.empty();
        instanceBuffers.beginFrame();
        return this;
    }

    public InstancedMeshBatch submit(Matrix4f transform) {
        ensureDefaultMesh();
        return submit(defaultMesh.mesh, transform);
    }

    public InstancedMeshBatch submit(Mesh mesh, Matrix4f transform) {
        ensureOpen();
        MeshEntry entry = requireMesh(mesh);
        entry.transforms.add(new Matrix4f(Objects.requireNonNull(transform, "transform")));
        return this;
    }

    public InstancedMeshBatch submitAll(Iterable<Matrix4f> batch) {
        ensureOpen();
        for (Matrix4f transform : batch) {
            submit(transform);
        }
        return this;
    }

    public InstancedMeshBatch submitAll(Mesh mesh, Iterable<Matrix4f> batch) {
        ensureOpen();
        for (Matrix4f transform : batch) {
            submit(mesh, transform);
        }
        return this;
    }

    public int flush() {
        ensureOpen();
        int submitted = pendingInstances();
        if (submitted == 0) {
            lastStats = InstanceBatchStats.empty();
            return 0;
        }
        if (submitted > instanceBuffers.maxInstances()) {
            throw new GlException("Instance count exceeds buffer capacity: "
                    + submitted + " > " + instanceBuffers.maxInstances());
        }

        int drawn = 0;
        int drawCalls = 0;
        int bufferUpdates = 0;
        int meshGroups = 0;
        int startInstance = 0;
        for (MeshEntry entry : meshes) {
            int count = entry.transforms.size();
            if (count == 0) {
                continue;
            }

            instanceBuffers.upload(entry.transforms, startInstance);
            bufferUpdates++;
            entry.vao.bind();
            instanceBuffers.bindAttributes(instanceLayout, startInstance);
            entry.mesh.drawInstancedBound(count);

            drawn += count;
            drawCalls++;
            meshGroups++;
            startInstance += count;
        }

        instanceBuffers.finishFrame();
        lastStats = new InstanceBatchStats(submitted, drawn, drawCalls, bufferUpdates, meshGroups);
        for (MeshEntry entry : meshes) {
            entry.transforms.clear();
        }
        return drawn;
    }

    public boolean isPersistent() {
        return instanceBuffers.isPersistent();
    }

    public InstanceUploadStrategy uploadStrategy() {
        return instanceBuffers.uploadStrategy();
    }

    public InstanceDataLayout instanceLayout() {
        return instanceLayout;
    }

    public InstanceBatchStats statistics() {
        return lastStats;
    }

    public int pendingInstances() {
        int count = 0;
        for (MeshEntry entry : meshes) {
            count += entry.transforms.size();
        }
        return count;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        instanceBuffers.close();
        for (MeshEntry entry : meshes) {
            entry.vao.close();
        }
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("InstancedMeshBatch is closed");
        }
    }

    private void ensureDefaultMesh() {
        ensureOpen();
        if (defaultMesh == null) {
            throw new GlException("No mesh registered in InstancedMeshBatch");
        }
    }

    private MeshEntry requireMesh(Mesh mesh) {
        MeshEntry entry = meshesBySource.get(Objects.requireNonNull(mesh, "mesh"));
        if (entry == null) {
            throw new GlException("Mesh is not registered in this InstancedMeshBatch");
        }
        return entry;
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
        if (mesh.indexBuffer() != null) {
            mesh.indexBuffer().bind();
        }
    }

    private record MeshEntry(VertexArray vao, Mesh mesh, List<Matrix4f> transforms) {
    }
}
