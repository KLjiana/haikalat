package com.kaleblangley.haikalat.core.mesh;

import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.buffer.InstanceBufferRing;
import com.kaleblangley.haikalat.core.buffer.InstanceBufferStatistics;
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
    private boolean frameBegun;
    private boolean prepared;
    private int preparedInstances;
    private int preparedBufferUpdates;
    private int preparedMeshGroups;
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
        mesh.vertexLayout().validateNoLocationOverlap(instanceLayout.vertexLayout(),
                "mesh/instance");

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
        if (frameBegun) {
            throw new GlException("Instanced mesh batch frame already begun");
        }
        instanceBuffers.beginFrame();
        for (MeshEntry entry : meshes) {
            entry.transforms.clear();
        }
        lastStats = InstanceBatchStats.empty();
        prepared = false;
        preparedInstances = 0;
        preparedBufferUpdates = 0;
        preparedMeshGroups = 0;
        frameBegun = true;
        return this;
    }

    public InstancedMeshBatch submit(Matrix4f transform) {
        ensureDefaultMesh();
        return submit(defaultMesh.mesh, transform);
    }

    public InstancedMeshBatch submit(Mesh mesh, Matrix4f transform) {
        ensureOpen();
        ensureFrameBegun();
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
        ensureFrameBegun();
        for (Matrix4f transform : batch) {
            submit(mesh, transform);
        }
        return this;
    }

    /**
     * 接收已经由命令缓冲区复制并独占到本次执行结束的矩阵快照。
     *
     * <p>普通调用方应继续使用 {@link #submit(Matrix4f)} 或 {@link #submitAll(Iterable)}；
     * 该入口避免命令执行器对同一批稳定快照再次创建 {@code Matrix4f} 对象。</p>
     */
    public InstancedMeshBatch submitOwnedSnapshots(List<Matrix4f> snapshots) {
        ensureDefaultMesh();
        ensureFrameBegun();
        Objects.requireNonNull(snapshots, "snapshots");
        for (Matrix4f snapshot : snapshots) {
            defaultMesh.transforms.add(Objects.requireNonNull(snapshot, "snapshot"));
        }
        return this;
    }

    public int flush() {
        ensureOpen();
        ensureFrameBegun();
        Throwable primaryFailure = null;
        try {
            return flushDraws();
        } catch (RuntimeException | Error error) {
            primaryFailure = error;
            throw error;
        } finally {
            finishFrame(primaryFailure);
        }
    }

    /**
     * 为同一帧的多个 pass 上传一次稳定快照，但暂不结束 ring slot 生命周期。
     * 后续可以多次调用 {@link #drawPrepared()}，并最终调用 {@link #finishPrepared()}。
     *
     * @param snapshots 当前帧不可变矩阵快照
     * @return 已准备的实例数量
     */
    public int prepareOwnedSnapshots(List<Matrix4f> snapshots) {
        ensureOpen();
        beginFrame();
        try {
            submitOwnedSnapshots(snapshots);
            return prepareDraws();
        } catch (RuntimeException | Error failure) {
            finishFrame(failure);
            throw failure;
        }
    }

    /** @return 使用当前已上传实例数据完成的绘制实例数 */
    public int drawPrepared() {
        ensureOpen();
        ensureFrameBegun();
        if (!prepared) {
            throw new GlException("Call prepareOwnedSnapshots before drawPrepared");
        }
        int drawn = 0;
        int drawCalls = 0;
        for (MeshEntry entry : meshes) {
            int count = entry.transforms.size();
            if (count == 0) {
                continue;
            }
            entry.vao.bind();
            entry.mesh.drawInstancedBound(count);
            drawn += count;
            drawCalls++;
        }
        lastStats = new InstanceBatchStats(preparedInstances, drawn, drawCalls,
                preparedBufferUpdates, preparedMeshGroups);
        return drawn;
    }

    /** 在全部复用 pass 绘制完成后插入 fence，并释放当前帧快照。 */
    public void finishPrepared() {
        ensureOpen();
        ensureFrameBegun();
        if (!prepared) {
            throw new GlException("Call prepareOwnedSnapshots before finishPrepared");
        }
        finishFrame(null);
    }

    /** Recovery hook for a cross-pass command failure; safe when no frame is active. */
    public void abortPrepared() {
        if (closed || !frameBegun) return;
        finishFrame(null);
    }

    private void finishFrame(Throwable primaryFailure) {
        try {
            instanceBuffers.finishFrame();
        } catch (RuntimeException | Error finishFailure) {
            if (primaryFailure == null) throw finishFailure;
            primaryFailure.addSuppressed(finishFailure);
        } finally {
            frameBegun = false;
            prepared = false;
            clearTransforms();
        }
    }

    private int flushDraws() {
        prepareDraws();
        return drawPrepared();
    }

    private int prepareDraws() {
        int submitted = pendingInstances();
        if (submitted == 0) {
            lastStats = InstanceBatchStats.empty();
            prepared = true;
            preparedInstances = 0;
            return 0;
        }
        if (submitted > instanceBuffers.maxInstances()) {
            throw new GlException("Instance count exceeds buffer capacity: "
                    + submitted + " > " + instanceBuffers.maxInstances());
        }

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
            meshGroups++;
            startInstance += count;
        }

        prepared = true;
        preparedInstances = submitted;
        preparedBufferUpdates = bufferUpdates;
        preparedMeshGroups = meshGroups;
        return submitted;
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

    public InstanceBufferStatistics bufferStatistics() {
        return instanceBuffers.statistics();
    }

    public void resetBufferStatistics() {
        instanceBuffers.resetStatistics();
    }

    public int pendingInstances() {
        int count = 0;
        for (MeshEntry entry : meshes) {
            count += entry.transforms.size();
        }
        return count;
    }

    /**
     * Returns a conservative local-space AABB covering every mesh registered in
     * this batch.  It is used by Render3D's batch-level shadow culling; an
     * unbounded member intentionally makes the result unbounded.
     */
    public Bounds3f aggregateLocalBounds() {
        if (meshes.isEmpty()) return Bounds3f.unbounded();
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (MeshEntry entry : meshes) {
            Bounds3f bounds = entry.mesh.localBounds();
            if (bounds.isUnbounded()) return Bounds3f.unbounded();
            minX = Math.min(minX, bounds.minX());
            minY = Math.min(minY, bounds.minY());
            minZ = Math.min(minZ, bounds.minZ());
            maxX = Math.max(maxX, bounds.maxX());
            maxY = Math.max(maxY, bounds.maxY());
            maxZ = Math.max(maxZ, bounds.maxZ());
        }
        return Bounds3f.of(minX, minY, minZ, maxX, maxY, maxZ);
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
        frameBegun = false;
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

    private void ensureFrameBegun() {
        if (!frameBegun) {
            throw new GlException("Call beginFrame before submitting or flushing instances");
        }
    }

    private void clearTransforms() {
        for (MeshEntry entry : meshes) {
            entry.transforms.clear();
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
