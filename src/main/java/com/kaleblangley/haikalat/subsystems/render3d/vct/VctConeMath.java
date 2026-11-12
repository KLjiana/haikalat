package com.kaleblangley.haikalat.subsystems.render3d.vct;

/**
 * GL-free cone-tracing parameters shared by the compute trace, the composite
 * pass and the JVM reference tests.
 *
 * <p>A cone with radius {@code r} at a sample should read a mip whose voxel
 * covers roughly the cone diameter: {@code lod = log2(2r / voxelSize)}. This is
 * the standard cone/mip footprint relation and is independent of the sampling
 * hardware; the shaders clamp it to the available chain.</p>
 */
final class VctConeMath {
    /** Smallest specular aperture in radians. */
    static final float MINIMUM_APERTURE = 0.02f;
    /** Largest accepted cone aperture in radians. */
    static final float MAXIMUM_APERTURE = 1.2f;
    /** Fraction of the cone radius used as one march step. */
    static final float CONE_STEP_FRACTION = 0.75f;

    private VctConeMath() {
    }

    /**
     * Mip level for a cone sample.
     *
     * @param coneRadius cone radius at the sample distance
     * @param voxelSize  world edge of one base voxel
     * @return unclamped level, {@code 0} for a radius below half a voxel
     */
    static float mipLevelForCone(float coneRadius, float voxelSize) {
        if (!Float.isFinite(coneRadius) || coneRadius < 0.0f) {
            throw new IllegalArgumentException("coneRadius must be finite and non-negative");
        }
        if (!Float.isFinite(voxelSize) || voxelSize <= 0.0f) {
            throw new IllegalArgumentException("voxelSize must be finite and positive");
        }
        float footprint = (2.0f * coneRadius) / voxelSize;
        if (footprint <= 1.0f) return 0.0f;
        return (float) (Math.log(footprint) / Math.log(2.0));
    }

    /** Roughness-to-aperture mapping used for the single specular cone. */
    static float apertureForRoughness(float roughness, float apertureScale) {
        if (!Float.isFinite(roughness) || roughness < 0.0f || roughness > 1.0f) {
            throw new IllegalArgumentException("roughness must be in [0,1]");
        }
        if (!Float.isFinite(apertureScale) || apertureScale <= 0.0f) {
            throw new IllegalArgumentException("apertureScale must be finite and positive");
        }
        float aperture = roughness * roughness * apertureScale;
        return Math.max(MINIMUM_APERTURE, Math.min(MAXIMUM_APERTURE, aperture));
    }

    /** World-space march step for a cone sample. */
    static float stepDistance(float voxelSize, float coneRadius) {
        if (!Float.isFinite(voxelSize) || voxelSize <= 0.0f) {
            throw new IllegalArgumentException("voxelSize must be finite and positive");
        }
        if (!Float.isFinite(coneRadius) || coneRadius < 0.0f) {
            throw new IllegalArgumentException("coneRadius must be finite and non-negative");
        }
        return Math.max(voxelSize, coneRadius * CONE_STEP_FRACTION);
    }

    /**
     * Fractional mip weights {@code [base, blend]} for a level.
     * Kept here so the JVM reference and the shader contract test use one
     * documented relation without reading production GL state.
     */
    static float[] mipWeights(float level) {
        if (!Float.isFinite(level) || level < 0.0f) {
            throw new IllegalArgumentException("mip level must be finite and non-negative");
        }
        int base = (int) Math.floor(level);
        return new float[]{base, level - base};
    }
}
