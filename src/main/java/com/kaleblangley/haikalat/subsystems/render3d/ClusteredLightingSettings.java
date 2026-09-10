package com.kaleblangley.haikalat.subsystems.render3d;

/**
 * Immutable clustered-forward grid, capacity and memory policy.
 *
 * <p>Defaults are frozen by the v0.24.2 cluster plan: 64px XY tiles, 24
 * logarithmic Z slices, 1024 local lights, 64 inline indices per cluster,
 * at most 8 directional lights and a 64 MiB self-owned resource budget.</p>
 */
public record ClusteredLightingSettings(
        int tileSize,
        int zSlices,
        int maxLocalLights,
        int inlineIndicesPerCluster,
        int maxDirectionalLights,
        long memoryBudgetBytes) {

    public static final int DEFAULT_TILE_SIZE = 64;
    public static final int DEFAULT_Z_SLICES = 24;
    public static final int DEFAULT_MAX_LOCAL_LIGHTS = 1024;
    public static final int DEFAULT_INLINE_INDICES = 64;
    public static final int DEFAULT_MAX_DIRECTIONAL_LIGHTS = 8;
    public static final long DEFAULT_MEMORY_BUDGET_BYTES = 64L * 1024L * 1024L;

    public ClusteredLightingSettings {
        if (tileSize < 8 || tileSize > 512) {
            throw new IllegalArgumentException("tileSize must be in [8, 512]");
        }
        if (zSlices < 1 || zSlices > 256) {
            throw new IllegalArgumentException("zSlices must be in [1, 256]");
        }
        if (maxLocalLights < 0 || maxLocalLights > 65_536) {
            throw new IllegalArgumentException("maxLocalLights must be in [0, 65536]");
        }
        if (inlineIndicesPerCluster < 1 || inlineIndicesPerCluster > 1024) {
            throw new IllegalArgumentException("inlineIndicesPerCluster must be in [1, 1024]");
        }
        if (maxDirectionalLights < 0 || maxDirectionalLights > 64) {
            throw new IllegalArgumentException("maxDirectionalLights must be in [0, 64]");
        }
        if (memoryBudgetBytes < 1024L * 1024L) {
            throw new IllegalArgumentException("memoryBudgetBytes must be at least 1 MiB");
        }
    }

    public static ClusteredLightingSettings defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable construction helper; validation happens when {@link #build()} is called. */
    public static final class Builder {
        private int tileSize = DEFAULT_TILE_SIZE;
        private int zSlices = DEFAULT_Z_SLICES;
        private int maxLocalLights = DEFAULT_MAX_LOCAL_LIGHTS;
        private int inlineIndicesPerCluster = DEFAULT_INLINE_INDICES;
        private int maxDirectionalLights = DEFAULT_MAX_DIRECTIONAL_LIGHTS;
        private long memoryBudgetBytes = DEFAULT_MEMORY_BUDGET_BYTES;

        private Builder() {
        }

        public Builder tileSize(int value) {
            tileSize = value;
            return this;
        }

        public Builder zSlices(int value) {
            zSlices = value;
            return this;
        }

        public Builder maxLocalLights(int value) {
            maxLocalLights = value;
            return this;
        }

        public Builder inlineIndicesPerCluster(int value) {
            inlineIndicesPerCluster = value;
            return this;
        }

        public Builder maxDirectionalLights(int value) {
            maxDirectionalLights = value;
            return this;
        }

        public Builder memoryBudgetBytes(long value) {
            memoryBudgetBytes = value;
            return this;
        }

        public ClusteredLightingSettings build() {
            return new ClusteredLightingSettings(tileSize, zSlices, maxLocalLights,
                    inlineIndicesPerCluster, maxDirectionalLights, memoryBudgetBytes);
        }
    }
}
