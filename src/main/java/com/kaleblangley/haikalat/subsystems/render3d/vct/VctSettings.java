package com.kaleblangley.haikalat.subsystems.render3d.vct;

import org.joml.Vector3f;

import java.util.Objects;

/**
 * One immutable source of all voxel-cone-tracing parameters.
 *
 * <p>The volume is a single bounded world-space cube: {@code center ±
 * halfExtent}. Its base resolution is one of {@code 32/64/128}³ and the mip
 * chain halves all three axes down to one texel. {@code 64} is the production
 * default, {@code 128} is the high configuration and {@code 32} exists for
 * bounded tests and low-end fallback. Runtime cost and memory are documented in
 * {@code docs/planning/v0.28.1-s5-vct.md}.</p>
 *
 * <p>Disabled settings allocate no texture, program, buffer, sampler or VAO;
 * every {@link VctPass} record entry point then records zero commands.</p>
 */
public record VctSettings(
        boolean enabled,
        int resolution,
        Vector3f center,
        float halfExtent,
        float diffuseIntensity,
        float specularIntensity,
        int diffuseCones,
        int specularCones,
        float diffuseAperture,
        float specularApertureScale,
        float opacityThreshold,
        float normalBiasVoxels,
        int thinWallDilation,
        int maxDirtyRegions,
        long maxVoxelsPerUpdate,
        long memoryBudgetBytes,
        int maxTraceSteps) {

    /** Largest accepted cube edge in world units. */
    public static final float MAXIMUM_HALF_EXTENT = 2048.0f;
    /** Upper bound for the checked-in owner memory budget. */
    public static final long MAXIMUM_MEMORY_BUDGET = 256L * 1024L * 1024L;

    public VctSettings {
        center = new Vector3f(Objects.requireNonNull(center, "center"));
        if (!center.isFinite()) throw new IllegalArgumentException("voxel center must be finite");
        if (resolution != 32 && resolution != 64 && resolution != 128) {
            throw new IllegalArgumentException("voxel resolution must be 32, 64 or 128, got " + resolution);
        }
        if (!Float.isFinite(halfExtent) || halfExtent <= 0.0f || halfExtent > MAXIMUM_HALF_EXTENT) {
            throw new IllegalArgumentException("voxel halfExtent must be finite and in (0,"
                    + MAXIMUM_HALF_EXTENT + "]");
        }
        requireFiniteNonNegative(diffuseIntensity, "diffuseIntensity", 16.0f);
        requireFiniteNonNegative(specularIntensity, "specularIntensity", 16.0f);
        if (diffuseCones < 1 || diffuseCones > 32) {
            throw new IllegalArgumentException("diffuseCones must be in [1,32]");
        }
        if (specularCones < 1 || specularCones > 16) {
            throw new IllegalArgumentException("specularCones must be in [1,16]");
        }
        if (!Float.isFinite(diffuseAperture) || diffuseAperture < 0.01f || diffuseAperture > 1.2f) {
            throw new IllegalArgumentException("diffuseAperture must be in [0.01,1.2] radians");
        }
        if (!Float.isFinite(specularApertureScale)
                || specularApertureScale < 0.05f || specularApertureScale > 4.0f) {
            throw new IllegalArgumentException("specularApertureScale must be in [0.05,4]");
        }
        if (!Float.isFinite(opacityThreshold) || opacityThreshold < 0.0f || opacityThreshold > 0.95f) {
            throw new IllegalArgumentException("opacityThreshold must be in [0,0.95]");
        }
        if (!Float.isFinite(normalBiasVoxels) || normalBiasVoxels < 0.0f || normalBiasVoxels > 4.0f) {
            throw new IllegalArgumentException("normalBiasVoxels must be in [0,4]");
        }
        if (thinWallDilation < 0 || thinWallDilation > 2) {
            throw new IllegalArgumentException("thinWallDilation must be in [0,2]");
        }
        if (maxDirtyRegions < 1 || maxDirtyRegions > 64) {
            throw new IllegalArgumentException("maxDirtyRegions must be in [1,64]");
        }
        if (maxVoxelsPerUpdate <= 0L || maxVoxelsPerUpdate > 128L * 1024L * 1024L) {
            throw new IllegalArgumentException("maxVoxelsPerUpdate must be in (0,134217728]");
        }
        if (memoryBudgetBytes < 1024L * 1024L || memoryBudgetBytes > MAXIMUM_MEMORY_BUDGET) {
            throw new IllegalArgumentException("memoryBudgetBytes must be in [1MiB,256MiB]");
        }
        if (maxTraceSteps < 8 || maxTraceSteps > 256) {
            throw new IllegalArgumentException("maxTraceSteps must be in [8,256]");
        }
        long required = VctVoxelMath.estimatedVolumeBytes(resolution);
        if (required > memoryBudgetBytes) {
            throw new IllegalArgumentException("memoryBudgetBytes " + memoryBudgetBytes
                    + " cannot hold the " + resolution + "³ mip chain (" + required + " bytes)");
        }
    }

    /** Default disabled value; constructing a pass with it allocates and records nothing. */
    public static VctSettings disabled() {
        return new VctSettings(false, 64, new Vector3f(), 16.0f,
                1.0f, 1.0f, 8, 4, 0.55f, 1.0f, 0.25f, 0.75f,
                1, 8, 4_000_000L, 32L * 1024L * 1024L, 48);
    }

    /** Production 64³ default centred at the origin. */
    public static VctSettings enabled(int resolution, float halfExtent) {
        return enabled(resolution, new Vector3f(), halfExtent);
    }

    /** Production default with an explicit bounded cube. */
    public static VctSettings enabled(int resolution, Vector3f center, float halfExtent) {
        return new VctSettings(true, resolution, center, halfExtent,
                1.0f, 1.0f, 8, 4, 0.55f, 1.0f, 0.25f, 0.75f,
                1, 8, 4_000_000L, 32L * 1024L * 1024L, 48);
    }

    @Override public Vector3f center() {
        return new Vector3f(center);
    }

    Vector3f centerInternal() {
        return center;
    }

    /** @return world-space edge length of one base voxel */
    public float voxelSize() {
        return VctVoxelMath.voxelSize(this);
    }

    /** @return complete mip count (level 0 plus downsample levels) */
    public int mipLevels() {
        return VctVoxelMath.mipLevels(resolution);
    }

    /** @return closed world-space minimum corner of the volume */
    public Vector3f boundsMinimum() {
        return VctVoxelMath.boundsMinimum(this);
    }

    public VctSettings withResolution(int newResolution) {
        return new VctSettings(enabled, newResolution, center, halfExtent, diffuseIntensity,
                specularIntensity, diffuseCones, specularCones, diffuseAperture,
                specularApertureScale, opacityThreshold, normalBiasVoxels, thinWallDilation,
                maxDirtyRegions, maxVoxelsPerUpdate, memoryBudgetBytes, maxTraceSteps);
    }

    public VctSettings withBounds(Vector3f newCenter, float newHalfExtent) {
        return new VctSettings(enabled, resolution, newCenter, newHalfExtent, diffuseIntensity,
                specularIntensity, diffuseCones, specularCones, diffuseAperture,
                specularApertureScale, opacityThreshold, normalBiasVoxels, thinWallDilation,
                maxDirtyRegions, maxVoxelsPerUpdate, memoryBudgetBytes, maxTraceSteps);
    }

    public VctSettings withConeCounts(int newDiffuseCones, int newSpecularCones) {
        return new VctSettings(enabled, resolution, center, halfExtent, diffuseIntensity,
                specularIntensity, newDiffuseCones, newSpecularCones, diffuseAperture,
                specularApertureScale, opacityThreshold, normalBiasVoxels, thinWallDilation,
                maxDirtyRegions, maxVoxelsPerUpdate, memoryBudgetBytes, maxTraceSteps);
    }

    public VctSettings withThinWallDilation(int newDilation) {
        return new VctSettings(enabled, resolution, center, halfExtent, diffuseIntensity,
                specularIntensity, diffuseCones, specularCones, diffuseAperture,
                specularApertureScale, opacityThreshold, normalBiasVoxels, newDilation,
                maxDirtyRegions, maxVoxelsPerUpdate, memoryBudgetBytes, maxTraceSteps);
    }

    public VctSettings withIntensities(float newDiffuseIntensity, float newSpecularIntensity) {
        return new VctSettings(enabled, resolution, center, halfExtent, newDiffuseIntensity,
                newSpecularIntensity, diffuseCones, specularCones, diffuseAperture,
                specularApertureScale, opacityThreshold, normalBiasVoxels, thinWallDilation,
                maxDirtyRegions, maxVoxelsPerUpdate, memoryBudgetBytes, maxTraceSteps);
    }

    public VctSettings withTrace(int newMaxTraceSteps, float newOpacityThreshold,
                                 float newNormalBiasVoxels) {
        return new VctSettings(enabled, resolution, center, halfExtent, diffuseIntensity,
                specularIntensity, diffuseCones, specularCones, diffuseAperture,
                specularApertureScale, newOpacityThreshold, newNormalBiasVoxels,
                thinWallDilation, maxDirtyRegions, maxVoxelsPerUpdate, memoryBudgetBytes,
                newMaxTraceSteps);
    }

    public VctSettings withUpdateBudget(int newMaxDirtyRegions, long newMaxVoxelsPerUpdate) {
        return new VctSettings(enabled, resolution, center, halfExtent, diffuseIntensity,
                specularIntensity, diffuseCones, specularCones, diffuseAperture,
                specularApertureScale, opacityThreshold, normalBiasVoxels, thinWallDilation,
                newMaxDirtyRegions, newMaxVoxelsPerUpdate, memoryBudgetBytes, maxTraceSteps);
    }

    private static void requireFiniteNonNegative(float value, String name, float maximum) {
        if (!Float.isFinite(value) || value < 0.0f || value > maximum) {
            throw new IllegalArgumentException(name + " must be finite and in [0," + maximum + "]");
        }
    }
}
