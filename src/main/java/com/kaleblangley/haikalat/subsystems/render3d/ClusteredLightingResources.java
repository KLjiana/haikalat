package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.UniformBlock;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;

import java.nio.ByteBuffer;
import java.util.Objects;

import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL44.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT;
import static org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT;
import static org.lwjgl.opengl.GL44.GL_MAP_READ_BIT;

/**
 * Generation-owned GPU resources for clustered forward lighting.
 *
 * <p>Buffers are sized from the frozen settings and the current viewport, and
 * every allocation is checksummed against the configured memory budget before
 * a single native object is created.  Resize follows the candidate-first
 * lifecycle: the old storage is retired only after the candidate is complete.</p>
 */
final class ClusteredLightingResources implements AutoCloseable {
    static final int LIGHT_TABLE_BINDING = 0;
    static final int CLUSTER_BOUNDS_BINDING = 1;
    static final int CLUSTER_HEADERS_BINDING = 2;
    static final int CLUSTER_INDICES_BINDING = 3;
    static final int CLUSTER_COUNTERS_BINDING = 4;
    static final int LIGHT_BOUNDS_BINDING = 5;
    static final int CLUSTER_PARAMETERS_BINDING = 6;
    static final String CLUSTER_PARAMETERS_BLOCK = "ClusterParametersBlock";
    static final String LIGHT_TABLE_BLOCK = "LightTableBlock";
    static final String LIGHT_BOUNDS_BLOCK = "LightBoundsBlock";
    static final String CLUSTER_BOUNDS_BLOCK = "ClusterBoundsBlock";

    static final int BOUNDS_BYTES_PER_CLUSTER = 32;
    static final int HEADER_BYTES_PER_CLUSTER = 16;
    static final int INDICES_BYTES_PER_ENTRY = 4;
    static final int COUNTER_SLOTS = 1;
    static final int COUNTER_SLOT_COUNT = 2;
    static final int COUNTER_BYTES = COUNTER_SLOTS * 16;
    static final int PARAMETERS_BYTES = 256;

    private final ClusteredLightingSettings settings;
    private final int maxTotalLights;
    private final long lightTableBytes;
    private final ShaderProgram boundsProgram;
    private final ShaderProgram assignProgram;
    private final ShaderProgram statsProgram;
    private ClusterStorage storage;
    private boolean closed;

    ClusteredLightingResources(int width, int height, ClusteredLightingSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.maxTotalLights = Math.addExact(settings.maxDirectionalLights(),
                settings.maxLocalLights());
        this.lightTableBytes = LightTablePacker.byteSize(maxTotalLights);
        // Reject oversized grids before allocating any GL object.
        long storageBytes = requireStorageBytes(width, height);
        ShaderProgram createdBounds = null;
        ShaderProgram createdAssign = null;
        ShaderProgram createdStats = null;
        ClusterStorage createdStorage = null;
        try {
            createdBounds = ShaderProgram.fromComputeResource(ClusteredLightingResources.class,
                    "/shaders/render3d/clustered/cluster-bounds.comp");
            createdAssign = ShaderProgram.fromComputeResource(ClusteredLightingResources.class,
                    "/shaders/render3d/clustered/cluster-assign.comp");
            createdStats = ShaderProgram.fromComputeResource(ClusteredLightingResources.class,
                    "/shaders/render3d/clustered/cluster-stats.comp");
            createdBounds.storageBlockIndex(CLUSTER_BOUNDS_BLOCK);
            createdStorage = new ClusterStorage(width, height, settings, lightTableBytes,
                    storageBytes);
        } catch (RuntimeException | Error failure) {
            closeSuppressing(createdStorage, failure);
            closeSuppressing(createdStats, failure);
            closeSuppressing(createdAssign, failure);
            closeSuppressing(createdBounds, failure);
            throw failure;
        }
        boundsProgram = createdBounds;
        assignProgram = createdAssign;
        statsProgram = createdStats;
        storage = createdStorage;
    }

    private ClusterStorage createStorage(int width, int height) {
        return new ClusterStorage(width, height, settings, lightTableBytes,
                requireStorageBytes(width, height));
    }

    private long requireStorageBytes(int width, int height) {
        long clusterCount = clusterCount(width, height);
        long bounds = Math.multiplyExact(BOUNDS_BYTES_PER_CLUSTER, clusterCount);
        long headers = Math.multiplyExact(HEADER_BYTES_PER_CLUSTER, clusterCount);
        long indices = Math.multiplyExact(
                Math.multiplyExact((long) settings.inlineIndicesPerCluster(), INDICES_BYTES_PER_ENTRY),
                clusterCount);
        long total = Math.addExact(lightTableBytes,
                Math.addExact(localBoundsBytes(), Math.addExact(bounds, Math.addExact(headers,
                        Math.addExact(indices, COUNTER_BYTES + PARAMETERS_BYTES)))));
        if (total > settings.memoryBudgetBytes()) {
            throw new IllegalStateException("clustered lighting needs " + total
                    + " bytes but the configured budget is " + settings.memoryBudgetBytes()
                    + " bytes (grid " + clusterCount + " clusters, K "
                    + settings.inlineIndicesPerCluster() + ")");
        }
        return total;
    }

    private long localBoundsBytes() {
        return LightTablePacker.boundsByteSize(settings.maxLocalLights());
    }

    int clusterCount(int width, int height) {
        int nx = (width + settings.tileSize() - 1) / settings.tileSize();
        int ny = (height + settings.tileSize() - 1) / settings.tileSize();
        return Math.multiplyExact(Math.multiplyExact(nx, ny), settings.zSlices());
    }

    ClusteredLightingSettings settings() {
        return settings;
    }

    int maxTotalLights() {
        return maxTotalLights;
    }

    long lightTableBytes() {
        return lightTableBytes;
    }

    ShaderProgram boundsProgram() {
        ensureOpen();
        return boundsProgram;
    }

    ShaderProgram assignProgram() {
        ensureOpen();
        return assignProgram;
    }

    ShaderProgram statsProgram() {
        ensureOpen();
        return statsProgram;
    }

    ClusterStorage storage() {
        ensureOpen();
        return storage;
    }

    /** Candidate-first resize; the active storage stays valid on any failure. */
    void resize(int width, int height) {
        ensureOpen();
        ResizeCandidate candidate = prepareResize(width, height);
        try {
            commitResize(candidate);
        } finally {
            candidate.close();
        }
    }

    ResizeCandidate prepareResize(int width, int height) {
        ensureOpen();
        if (width == storage.width && height == storage.height) {
            return new ResizeCandidate(this, null);
        }
        return new ResizeCandidate(this, createStorage(width, height));
    }

    void commitResize(ResizeCandidate candidate) {
        ensureOpen();
        Objects.requireNonNull(candidate, "candidate").commitInto(this);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        try {
            storage.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            assignProgram.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        try {
            statsProgram.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        try {
            boundsProgram.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("ClusteredLightingResources is closed");
    }

    private static void closeSuppressing(AutoCloseable value, Throwable failure) {
        if (value == null) return;
        try {
            value.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /** A complete, immutable-shape GPU storage set for one grid shape. */
    static final class ClusterStorage implements AutoCloseable {
        final int width;
        final int height;
        final int nx;
        final int ny;
        final int nz;
        final int clusterCount;
        final int inlineCapacity;
        final GlBuffer lightTable;
        final GlBuffer lightBounds;
        final GlBuffer clusterBounds;
        final GlBuffer clusterHeaders;
        final GlBuffer clusterIndices;
        final GlBuffer[] clusterCounters;
        final ByteBuffer[] counterMappings;
        final UniformBlock clusterParameters;
        final long totalBytes;
        final long lightTableBytes;
        final long lightBoundsBytes;
        final long clusterBoundsBytes;
        final long clusterHeadersBytes;
        final long clusterIndicesBytes;
        private boolean closed;

        ClusterStorage(int width, int height, ClusteredLightingSettings settings,
                       long lightTableBytes, long totalBytes) {
            this.width = width;
            this.height = height;
            this.nx = (width + settings.tileSize() - 1) / settings.tileSize();
            this.ny = (height + settings.tileSize() - 1) / settings.tileSize();
            this.nz = settings.zSlices();
            this.clusterCount = Math.multiplyExact(Math.multiplyExact(nx, ny), nz);
            this.inlineCapacity = settings.inlineIndicesPerCluster();
            this.totalBytes = totalBytes;
            this.lightTableBytes = lightTableBytes;
            this.lightBoundsBytes = LightTablePacker.boundsByteSize(settings.maxLocalLights());
            this.clusterBoundsBytes = Math.multiplyExact(
                    (long) BOUNDS_BYTES_PER_CLUSTER, clusterCount);
            this.clusterHeadersBytes = Math.multiplyExact(
                    (long) HEADER_BYTES_PER_CLUSTER, clusterCount);
            this.clusterIndicesBytes = Math.multiplyExact(
                    Math.multiplyExact((long) inlineCapacity, INDICES_BYTES_PER_ENTRY),
                    clusterCount);
            long[] nativeSizes = new long[]{lightTableBytes, lightBoundsBytes, clusterBoundsBytes,
                    clusterHeadersBytes, clusterIndicesBytes, COUNTER_BYTES};
            for (long size : nativeSizes) {
                if (size <= 0L || size > Integer.MAX_VALUE) {
                    throw new IllegalStateException(
                            "clustered lighting buffer size exceeds a single GL buffer: " + size);
                }
            }
            GlBuffer createdLightTable = null;
            GlBuffer createdLightBounds = null;
            GlBuffer createdBounds = null;
            GlBuffer createdHeaders = null;
            GlBuffer createdIndices = null;
            GlBuffer[] createdCounters = new GlBuffer[COUNTER_SLOT_COUNT];
            ByteBuffer[] createdMappings = new ByteBuffer[COUNTER_SLOT_COUNT];
            UniformBlock createdParameters = null;
            try {
                createdLightTable = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW)
                        .allocate(lightTableBytes);
                createdLightBounds = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW)
                        .allocate(lightBoundsBytes);
                createdBounds = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW)
                        .allocate(clusterBoundsBytes);
                createdHeaders = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW)
                        .allocate(clusterHeadersBytes);
                createdIndices = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW)
                        .allocate(clusterIndicesBytes);
                for (int slot = 0; slot < COUNTER_SLOT_COUNT; slot++) {
                    // Double-buffered diagnostics: the GPU writes one slot while
                    // the CPU reads the other after its fence signals.  The
                    // persistent coherent map avoids a synchronous readback of
                    // live GPU storage.
                    int flags = GL_DYNAMIC_STORAGE_BIT | GL_MAP_READ_BIT
                            | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
                    createdCounters[slot] = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW)
                            .allocateStorage(COUNTER_BYTES, flags);
                    createdMappings[slot] = createdCounters[slot].mapRange(0L, COUNTER_BYTES,
                            GL_MAP_READ_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT);
                    if (createdMappings[slot] == null) {
                        throw new IllegalStateException(
                                "Failed to persistently map clustered counter slot " + slot);
                    }
                    createdMappings[slot].order(java.nio.ByteOrder.nativeOrder());
                }
                createdParameters = new UniformBlock(PARAMETERS_BYTES);
            } catch (RuntimeException | Error failure) {
                closeSuppressing(createdParameters, failure);
                for (GlBuffer counter : createdCounters) {
                    closeSuppressing(counter, failure);
                }
                closeSuppressing(createdIndices, failure);
                closeSuppressing(createdHeaders, failure);
                closeSuppressing(createdBounds, failure);
                closeSuppressing(createdLightBounds, failure);
                closeSuppressing(createdLightTable, failure);
                throw failure;
            }
            lightTable = createdLightTable;
            lightBounds = createdLightBounds;
            clusterBounds = createdBounds;
            clusterHeaders = createdHeaders;
            clusterIndices = createdIndices;
            clusterCounters = createdCounters;
            counterMappings = createdMappings;
            clusterParameters = createdParameters;
        }

        boolean sameShapeAs(ClusterStorage other) {
            return width == other.width && height == other.height;
        }

        void uploadLightTable(CommandBuffer cmd, ByteBuffer packed) {
            ensureOpen();
            cmd.uploadBufferRegion(lightTable, 0L, packed);
        }

        void uploadLightBounds(CommandBuffer cmd, ByteBuffer packed) {
            ensureOpen();
            cmd.uploadBufferRegion(lightBounds, 0L, packed);
        }

        void bindLightBoundsRead(CommandBuffer cmd) {
            ensureOpen();
            cmd.bindStorageBuffer(LIGHT_BOUNDS_BINDING, lightBounds, 0L, lightBoundsBytes);
        }

        void bindClusterParameters(CommandBuffer cmd) {
            ensureOpen();
            cmd.bindUniformBlock(CLUSTER_PARAMETERS_BINDING, clusterParameters);
        }

        void bindBoundsRead(CommandBuffer cmd) {
            ensureOpen();
            cmd.bindStorageBuffer(CLUSTER_BOUNDS_BINDING, clusterBounds, 0L,
                    clusterBoundsBytes);
        }

        void bindCountersReadWrite(CommandBuffer cmd, int slot) {
            ensureOpen();
            cmd.bindStorageBuffer(CLUSTER_COUNTERS_BINDING, clusterCounters[slot], 0L,
                    COUNTER_BYTES);
        }

        void uploadCounters(CommandBuffer cmd, int slot, ByteBuffer data) {
            ensureOpen();
            cmd.uploadBufferRegion(clusterCounters[slot], 0L, data);
        }

        void bindLightTableRead(CommandBuffer cmd) {
            ensureOpen();
            cmd.bindStorageBuffer(LIGHT_TABLE_BINDING, lightTable, 0L, lightTableBytes);
        }

        void bindClusterHeadersRead(CommandBuffer cmd) {
            ensureOpen();
            cmd.bindStorageBuffer(CLUSTER_HEADERS_BINDING, clusterHeaders, 0L,
                    clusterHeadersBytes);
        }

        void bindClusterIndicesRead(CommandBuffer cmd) {
            ensureOpen();
            cmd.bindStorageBuffer(CLUSTER_INDICES_BINDING, clusterIndices, 0L,
                    clusterIndicesBytes);
        }

        void bindForwardReads(CommandBuffer cmd) {
            bindLightTableRead(cmd);
            bindClusterHeadersRead(cmd);
            bindClusterIndicesRead(cmd);
        }

        /**
         * Aggregates one counter slot.  Production diagnostics must only call
         * this after the slot's fence signaled.
         */
        int[] readCounters(int slot) {
            ensureOpen();
            ByteBuffer data = counterMappings[slot].duplicate()
                    .order(java.nio.ByteOrder.nativeOrder());
            return new int[]{data.getInt(0), data.getInt(4), data.getInt(8), data.getInt(12)};
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            failure = closeOne(clusterParameters, failure);
            for (int slot = 0; slot < COUNTER_SLOT_COUNT; slot++) {
                try {
                    clusterCounters[slot].unmap();
                } catch (RuntimeException unmapFailure) {
                    if (failure == null) failure = unmapFailure;
                    else failure.addSuppressed(unmapFailure);
                }
                failure = closeOne(clusterCounters[slot], failure);
            }
            failure = closeOne(clusterIndices, failure);
            failure = closeOne(clusterHeaders, failure);
            failure = closeOne(clusterBounds, failure);
            failure = closeOne(lightBounds, failure);
            failure = closeOne(lightTable, failure);
            if (failure != null) throw failure;
        }

        private static RuntimeException closeOne(AutoCloseable value, RuntimeException failure) {
            try {
                value.close();
            } catch (Exception closeFailure) {
                RuntimeException runtime = closeFailure instanceof RuntimeException existing
                        ? existing
                        : new IllegalStateException("Failed to close cluster storage resource",
                        closeFailure);
                if (failure == null) return runtime;
                failure.addSuppressed(runtime);
            }
            return failure;
        }

        private void ensureOpen() {
            if (closed) throw new IllegalStateException("ClusterStorage is closed");
        }
    }

    /** Resize transaction owned by {@link ClusteredLightingResources}. */
    static final class ResizeCandidate implements AutoCloseable {
        private final ClusteredLightingResources owner;
        private ClusterStorage candidate;
        private ClusterStorage retired;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(ClusteredLightingResources owner, ClusterStorage candidate) {
            this.owner = owner;
            this.candidate = candidate;
        }

        void commitInto(ClusteredLightingResources expectedOwner) {
            if (owner != expectedOwner) {
                throw new IllegalArgumentException("resize candidate belongs to another owner");
            }
            if (closed) throw new IllegalStateException("resize candidate is closed");
            if (committed) throw new IllegalStateException("resize candidate already committed");
            if (candidate == null) {
                committed = true;
                return;
            }
            retired = expectedOwner.storage;
            expectedOwner.storage = candidate;
            candidate = null;
            committed = true;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (RuntimeException closeFailure) {
                    failure = closeFailure;
                } finally {
                    candidate = null;
                }
            }
            if (retired != null) {
                try {
                    retired.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                } finally {
                    retired = null;
                }
            }
            if (failure != null) throw failure;
        }
    }
}
