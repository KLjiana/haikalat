package com.kaleblangley.haikalat.subsystems.render3d.shadow;

/**
 * Pure-JVM reference for the bounded PCSS sampling arithmetic used by
 * {@code pbr-forward.frag}.
 *
 * <p>This is the CPU-side statement of the shader contract, consumed by the
 * JVM contract tests: it pins the deterministic golden-angle disk, the
 * receiver/bias acceptance rule for blocker depths, the normalized-depth to
 * world-depth conversion, the penumbra radius and the atlas-tile clamp.  It
 * touches no GL state and is not a second render path.  Depths are normalized
 * light-space window depths in {@code [0, 1]}; world distances are in world
 * units and texel radii are in shadow-map texels.</p>
 */
final class PcssMath {
    /** Must match the literal used by {@code pcssDiskOffset} in pbr-forward.frag. */
    static final double GOLDEN_ANGLE_RADIANS = 2.39996323f;
    private static final float MIN_OCCLUDER_DISTANCE = 1.0e-4f;
    private static final float MIN_TEXEL_WORLD_SIZE = 1.0e-6f;

    private PcssMath() {
    }

    /**
     * Deterministic disk tap used by both the blocker search and the filter.
     *
     * @param index        tap index in {@code [0, taps)}
     * @param taps         positive tap count
     * @param radiusTexels non-negative disk radius in shadow texels
     * @return {@code {offsetX, offsetY}} in shadow texels
     */
    static float[] diskOffset(int index, int taps, float radiusTexels) {
        if (taps < 1) {
            throw new IllegalArgumentException("taps must be positive");
        }
        if (index < 0 || index >= taps) {
            throw new IllegalArgumentException("index must be in [0, taps)");
        }
        if (!Float.isFinite(radiusTexels) || radiusTexels < 0.0f) {
            throw new IllegalArgumentException("radiusTexels must be finite and non-negative");
        }
        double ratio = (index + 0.5) / taps;
        double radius = Math.sqrt(ratio) * radiusTexels;
        double angle = index * GOLDEN_ANGLE_RADIANS;
        return new float[]{(float) (Math.cos(angle) * radius),
                (float) (Math.sin(angle) * radius)};
    }

    /**
     * Average normalized depth of samples that are in front of the receiver by
     * more than {@code depthBias}, exactly as the shader's blocker test.
     *
     * @return the average blocker depth, or {@link Float#NaN} when no sample is
     *         a blocker
     */
    static float averageBlockerDepth(float receiverDepth, float depthBias,
                                     float[] sampledDepths) {
        if (!Float.isFinite(receiverDepth) || receiverDepth < 0.0f || receiverDepth > 1.0f) {
            throw new IllegalArgumentException("receiverDepth must be finite and in [0, 1]");
        }
        if (!Float.isFinite(depthBias) || depthBias < 0.0f) {
            throw new IllegalArgumentException("depthBias must be finite and non-negative");
        }
        if (sampledDepths == null) {
            throw new IllegalArgumentException("sampledDepths must not be null");
        }
        double sum = 0.0;
        int blockers = 0;
        for (float sampled : sampledDepths) {
            if (!Float.isFinite(sampled)) {
                throw new IllegalArgumentException("sampled depths must be finite");
            }
            if (sampled < receiverDepth - depthBias) {
                sum += sampled;
                blockers++;
            }
        }
        return blockers == 0 ? Float.NaN : (float) (sum / blockers);
    }

    /**
     * Linear world distance from the light plane for a normalized window depth.
     * Directional cascades use an orthographic projection, so this conversion
     * is affine.
     */
    static float worldDepth(float normalizedDepth, float nearDistance, float depthRange) {
        return nearDistance + normalizedDepth * depthRange;
    }

    /**
     * Penumbra radius in shadow texels.
     *
     * <p>{@code penumbraWorld = (receiverWorld - blockerWorld) * lightSize /
     * blockerWorld}, then divided by the cascade's world units per texel and
     * clamped to {@code [0, maxFilterRadiusTexels]}.</p>
     */
    static float penumbraTexels(float receiverDepth, float averageBlockerDepth,
                                float nearDistance, float depthRange, float lightWorldSize,
                                float texelWorldSize, float maxFilterRadiusTexels) {
        if (!Float.isFinite(receiverDepth) || receiverDepth < 0.0f || receiverDepth > 1.0f) {
            throw new IllegalArgumentException("receiverDepth must be finite and in [0, 1]");
        }
        if (!Float.isFinite(averageBlockerDepth) || averageBlockerDepth < 0.0f
                || averageBlockerDepth > 1.0f) {
            throw new IllegalArgumentException(
                    "averageBlockerDepth must be finite and in [0, 1]");
        }
        if (!Float.isFinite(nearDistance) || !Float.isFinite(depthRange) || depthRange <= 0.0f) {
            throw new IllegalArgumentException(
                    "nearDistance must be finite and depthRange must be finite and positive");
        }
        if (!Float.isFinite(lightWorldSize) || lightWorldSize < 0.0f) {
            throw new IllegalArgumentException("lightWorldSize must be finite and non-negative");
        }
        if (!Float.isFinite(texelWorldSize) || texelWorldSize <= 0.0f) {
            throw new IllegalArgumentException("texelWorldSize must be finite and positive");
        }
        if (!Float.isFinite(maxFilterRadiusTexels) || maxFilterRadiusTexels < 0.0f) {
            throw new IllegalArgumentException(
                    "maxFilterRadiusTexels must be finite and non-negative");
        }
        float receiverWorld = worldDepth(receiverDepth, nearDistance, depthRange);
        float blockerWorld = worldDepth(averageBlockerDepth, nearDistance, depthRange);
        float separation = Math.max(receiverWorld - blockerWorld, 0.0f);
        float occluderDistance = Math.max(blockerWorld, MIN_OCCLUDER_DISTANCE);
        float penumbraWorld = separation * lightWorldSize / occluderDistance;
        float texels = penumbraWorld / Math.max(texelWorldSize, MIN_TEXEL_WORLD_SIZE);
        return Math.min(Math.max(texels, 0.0f), maxFilterRadiusTexels);
    }

    /** Clamp one atlas coordinate to the selected cascade's guarded tile. */
    static float clampToTile(float value, float minimum, float maximum) {
        if (!Float.isFinite(value) || !Float.isFinite(minimum) || !Float.isFinite(maximum)
                || minimum > maximum) {
            throw new IllegalArgumentException("tile clamp requires finite min <= max");
        }
        return Math.min(Math.max(value, minimum), maximum);
    }
}
