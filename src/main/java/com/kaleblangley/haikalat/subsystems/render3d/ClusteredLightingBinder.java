package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT;

/**
 * Per-frame clustered lighting bridge between the CPU light table and the
 * generation-owned GPU storage.
 *
 * <p>The binder owns the packed byte snapshot, the cluster parameter block and
 * the bounds-dirty signature.  It never allocates GL objects while recording;
 * failures leave the previous frame's resources untouched.</p>
 */
final class ClusteredLightingBinder implements AutoCloseable {
    private static final int PARAM_VIEW_OFFSET = 0;
    private static final int PARAM_VIEW_PROJECTION_OFFSET = 64;
    private static final int PARAM_INVERSE_PROJECTION_OFFSET = 128;
    private static final int PARAM_GRID_OFFSET = 192;
    private static final int PARAM_DEPTH_OFFSET = 208;
    private static final int PARAM_COUNTS_OFFSET = 224;
    private static final int PARAM_EDGE_EXPAND_OFFSET = 240;

    private final ClusteredLightingResources resources;
    private final ClusteredLightingSettings settings;
    private final ByteBuffer packed;
    private final ByteBuffer packedBounds;
    private final ByteBuffer zeroCounters;
    private final Map<Integer, Boolean> programCapabilities = new HashMap<>();
    private final com.kaleblangley.haikalat.backend.sync.GpuFence[] counterFences =
            new com.kaleblangley.haikalat.backend.sync.GpuFence[
                    ClusteredLightingResources.COUNTER_SLOT_COUNT];
    private final long[] counterSequences =
            new long[ClusteredLightingResources.COUNTER_SLOT_COUNT];
    private final ClusteredLightingResources.ClusterStorage[] counterFenceStorages =
            new ClusteredLightingResources.ClusterStorage[
                    ClusteredLightingResources.COUNTER_SLOT_COUNT];
    private int counterWriteSlot;
    private long preparedFrameSequence = -1L;
    private ClusteredLightingResources.ClusterStorage lastStorage;
    private long lastBoundsSignature = Long.MIN_VALUE;
    private ClusteredLightingResources.ClusterStorage pendingStorage;
    private long pendingBoundsSignature = Long.MIN_VALUE;
    private boolean pendingBoundsDispatched;
    private ClusterGrid stagedGrid;
    private FrameLightTable stagedTable;
    private ClusteredLightingResources.ClusterStorage preparedStorage;

    ClusteredLightingBinder(ClusteredLightingResources resources,
                            ClusteredLightingSettings settings) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.packed = LightTablePacker.allocate(resources.maxTotalLights());
        this.packedBounds = LightTablePacker.allocateBounds(settings.maxLocalLights());
        this.zeroCounters = ByteBuffer.allocateDirect(ClusteredLightingResources.COUNTER_BYTES)
                .order(ByteOrder.nativeOrder());
    }

    /** CPU-side frame staging: packs the light table and updates the parameter block. */
    void prepare(ClusterGrid grid, FrameLightTable table, ShadowFramePlan plan,
                 long frameSequence) {
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(plan, "plan");
        if (table.totalCount() > resources.maxTotalLights()) {
            throw new IllegalStateException("clustered light table capacity exceeded");
        }
        // A resize replaces the counter storage; fences from the retired
        // allocation must never validate the replacement.
        ClusteredLightingResources.ClusterStorage storage = resources.storage();
        if (storage != preparedStorage) {
            for (int slot = 0; slot < counterFences.length; slot++) {
                closeCounterFence(slot);
            }
            preparedStorage = storage;
        }
        LightTablePacker.pack(table, plan, packed);
        LightTablePacker.packBounds(table, packedBounds);
        updateParameters(grid, table);
        stagedGrid = grid;
        stagedTable = table;
        preparedFrameSequence = frameSequence;
    }

    ClusterGrid stagedGrid() {
        return stagedGrid;
    }

    FrameLightTable stagedTable() {
        return stagedTable;
    }

    /** Uploads the light table and clears this frame's diagnostic counter slot. */
    void recordUpload(CommandBuffer cmd) {
        ClusteredLightingResources.ClusterStorage storage = requireStaged();
        storage.uploadLightTable(cmd, packed);
        if (stagedTable.localCount() > 0) {
            storage.uploadLightBounds(cmd, packedBounds);
        }
        storage.uploadCounters(cmd, counterWriteSlot, zeroCounters);
        cmd.memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    }

    /** Dispatches the bounds pass only when the grid/bounds signature changed. */
    void recordBounds(CommandBuffer cmd) {
        ClusteredLightingResources.ClusterStorage storage = requireStaged();
        if (stagedTable.localCount() == 0) {
            return;
        }
        if (storage == lastStorage && stagedGrid.signature() == lastBoundsSignature) {
            return;
        }
        int groups = (storage.clusterCount + 63) / 64;
        cmd.bindShader(resources.boundsProgram());
        storage.bindClusterParameters(cmd);
        storage.bindBoundsRead(cmd);
        cmd.dispatchCompute(groups, 1, 1)
                .memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        pendingBoundsDispatched = true;
        pendingBoundsSignature = stagedGrid.signature();
        pendingStorage = storage;
    }

    /** Dispatches deterministic cluster assignment for the current frame. */
    void recordAssign(CommandBuffer cmd) {
        ClusteredLightingResources.ClusterStorage storage = requireStaged();
        if (stagedTable.localCount() == 0) {
            return;
        }
        cmd.bindShader(resources.assignProgram());
        storage.bindClusterParameters(cmd);
        storage.bindLightTableRead(cmd);
        storage.bindLightBoundsRead(cmd);
        storage.bindBoundsRead(cmd);
        storage.bindClusterHeadersRead(cmd);
        storage.bindClusterIndicesRead(cmd);
        int groups = (storage.clusterCount + 3) / 4;
        cmd.dispatchCompute(groups, 1, 1);
        cmd.memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    }

    /**
     * Reduces cluster headers into the diagnostic counters and inserts the
     * completion fence used by the non-blocking diagnostics readback.
     */
    void recordStats(CommandBuffer cmd) {
        ClusteredLightingResources.ClusterStorage storage = requireStaged();
        if (stagedTable.localCount() == 0) {
            return;
        }
        int counterSlot = counterWriteSlot;
        long counterSequence = preparedFrameSequence;
        int groups = (storage.clusterCount + 255) / 256;
        cmd.bindShader(resources.statsProgram());
        storage.bindClusterParameters(cmd);
        storage.bindClusterHeadersRead(cmd);
        storage.bindCountersReadWrite(cmd, counterSlot);
        cmd.dispatchCompute(groups, 1, 1)
                .memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
                .insertGpuFence(new com.kaleblangley.haikalat.backend.sync.GpuFenceTarget() {
                    @Override
                    public void insertGpuFence() {
                        closeCounterFence(counterSlot);
                        counterFences[counterSlot] =
                                com.kaleblangley.haikalat.backend.sync.GpuFence.insert();
                        counterSequences[counterSlot] = counterSequence;
                        counterFenceStorages[counterSlot] = storage;
                    }

                    @Override
                    public void executionFailed(Throwable failure) {
                        // No fence was inserted; the slot stays reusable.
                    }
                });
        counterWriteSlot = (counterWriteSlot + 1)
                % ClusteredLightingResources.COUNTER_SLOT_COUNT;
    }

    /**
     * Non-blocking readback of the newest completed counter slot.
     *
     * @return the snapshot, or {@code null} while no fence has signaled yet
     */
    CounterSnapshot tryCounterSnapshot() {
        int selected = -1;
        long selectedSequence = Long.MIN_VALUE;
        ClusteredLightingResources.ClusterStorage current = resources.storage();
        for (int slot = 0; slot < counterFences.length; slot++) {
            com.kaleblangley.haikalat.backend.sync.GpuFence fence = counterFences[slot];
            if (fence == null || counterFenceStorages[slot] != current || !fence.isSignaled()) {
                continue;
            }
            if (counterSequences[slot] > selectedSequence) {
                selected = slot;
                selectedSequence = counterSequences[slot];
            }
        }
        if (selected < 0) {
            return null;
        }
        int[] values = resources.storage().readCounters(selected);
        for (int slot = 0; slot < counterFences.length; slot++) {
            if (slot != selected && counterFences[slot] != null
                    && counterFences[slot].isSignaled()) {
                closeCounterFence(slot);
            }
        }
        closeCounterFence(selected);
        return new CounterSnapshot(selectedSequence, values[0], values[1], values[2]);
    }

    /** Explicit debug/test-only blocking aggregation; never used by diagnostics. */
    int[] readCountersBlocking() {
        org.lwjgl.opengl.GL11.glFinish();
        CounterSnapshot snapshot = tryCounterSnapshot();
        if (snapshot == null) {
            throw new IllegalStateException("clustered counters unavailable after drain");
        }
        return new int[]{snapshot.overflowClusters(), snapshot.maxInlineCount(),
                snapshot.droppedIndices(), 0};
    }

    private void closeCounterFence(int slot) {
        if (counterFences[slot] != null) {
            counterFences[slot].close();
            counterFences[slot] = null;
        }
        counterFenceStorages[slot] = null;
    }

    @Override
    public void close() {
        for (int slot = 0; slot < counterFences.length; slot++) {
            closeCounterFence(slot);
        }
    }

    record CounterSnapshot(long frameSequence, int overflowClusters, int maxInlineCount,
                           int droppedIndices) {
    }

    /** Commits staged bounds validity only after the frame reached the GPU successfully. */
    void frameSucceeded() {
        if (pendingBoundsDispatched) {
            lastBoundsSignature = pendingBoundsSignature;
            lastStorage = pendingStorage;
        }
        pendingBoundsDispatched = false;
    }

    /** Discards staged bounds validity so the next frame re-dispatches them. */
    void frameFailed() {
        pendingBoundsDispatched = false;
        lastBoundsSignature = Long.MIN_VALUE;
        lastStorage = null;
    }

    /**
     * Binds the light table, cluster lists and parameters for lit programs.
     *
     * <p>Lit capability is the explicit presence of the cluster parameter block
     * and the light table storage block in the linked program.  Unlit materials
     * are skipped without guessing from uniform-assignment success.</p>
     */
    void bindForward(CommandBuffer cmd, ShaderProgram shader) {
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(shader, "shader");
        if (!isLit(shader)) {
            return;
        }
        ClusteredLightingResources.ClusterStorage storage = resources.storage();
        storage.bindClusterParameters(cmd);
        storage.bindForwardReads(cmd);
    }

    private boolean isLit(ShaderProgram shader) {
        Boolean known = programCapabilities.get(shader.id());
        if (known != null) {
            return known;
        }
        if (!shader.hasUniformBlock(ClusteredLightingResources.CLUSTER_PARAMETERS_BLOCK)) {
            programCapabilities.put(shader.id(), false);
            return false;
        }
        shader.storageBlockIndex(ClusteredLightingResources.LIGHT_TABLE_BLOCK);
        shader.bindUniformBlock(ClusteredLightingResources.CLUSTER_PARAMETERS_BLOCK,
                ClusteredLightingResources.CLUSTER_PARAMETERS_BINDING);
        programCapabilities.put(shader.id(), true);
        return true;
    }

    long totalResourceBytes() {
        return resources.storage().totalBytes;
    }

    private ClusteredLightingResources.ClusterStorage requireStaged() {
        if (stagedGrid == null || stagedTable == null) {
            throw new IllegalStateException("clustered lighting frame was not prepared");
        }
        return resources.storage();
    }

    private void updateParameters(ClusterGrid grid, FrameLightTable table) {
        ClusteredLightingResources.ClusterStorage storage = resources.storage();
        storage.clusterParameters
                .setMat4(PARAM_VIEW_OFFSET, grid.view())
                .setMat4(PARAM_VIEW_PROJECTION_OFFSET, grid.stableViewProjection())
                .setMat4(PARAM_INVERSE_PROJECTION_OFFSET, grid.inverseStableProjection());
        int gridOffset = PARAM_GRID_OFFSET;
        storage.clusterParameters.setInt(gridOffset, grid.nx())
                .setInt(gridOffset + 4, grid.ny())
                .setInt(gridOffset + 8, grid.nz())
                .setInt(gridOffset + 12, storage.inlineCapacity);
        int depthOffset = PARAM_DEPTH_OFFSET;
        storage.clusterParameters.setFloat(depthOffset, grid.nearPlane())
                .setFloat(depthOffset + 4, grid.farPlane())
                .setFloat(depthOffset + 8, grid.perspective() ? 1.0f : 0.0f)
                .setFloat(depthOffset + 12, 0.0f);
        int countOffset = PARAM_COUNTS_OFFSET;
        storage.clusterParameters.setInt(countOffset, table.directionalCount())
                .setInt(countOffset + 4, table.localCount())
                .setInt(countOffset + 8, 0)
                .setInt(countOffset + 12, 0);
        float expandX = 2.0f * grid.edgeExpansionPixels() / grid.width();
        float expandY = 2.0f * grid.edgeExpansionPixels() / grid.height();
        storage.clusterParameters.setVec4(PARAM_EDGE_EXPAND_OFFSET, expandX, expandY, 0.0f, 0.0f);
    }
}
