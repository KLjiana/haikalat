package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import java.util.Objects;

/**
 * Bounded, GL-free configuration of the depth-based collision response of the
 * production GPU particle owner.
 *
 * <p>Collision is an explicitly enabled extension of
 * {@link GpuParticleSystem}: the disabled value keeps the exact #27 update
 * program and trajectory, and the owner never allocates collision resources for
 * it. When enabled, each live particle is projected with the same view
 * projection used for drawing, the opaque scene depth at that pixel is fetched
 * with {@code texelFetch} and the particle is tested against the reconstructed
 * surface plane:</p>
 *
 * <ul>
 *   <li>{@code thickness} is a world-space band in front of (camera side of) the
 *       reconstructed surface. A particle deeper behind the surface than this
 *       band is treated as being on the far side of a wall and keeps free
 *       flight; this is the bounded thickness rejection, not a ray march.</li>
 *   <li>{@code restitution} in [0, 1] scales the normal velocity after a
 *       collision ({@code 1} is a perfect elastic bounce, {@code 0} cancels the
 *       normal velocity and slides).</li>
 *   <li>{@code friction} in [0, 1] removes that fraction of the tangential
 *       velocity on a collision.</li>
 *   <li>{@code maximumSearchSteps} bounds the total number of depth fetches per
 *       colliding particle (the centre texel plus at most {@code steps - 1}
 *       deterministic ring taps), and {@code searchRadiusPixels} bounds the
 *       screen-space radius of those taps. The default of one step and zero
 *       radius samples only the particle's own texel.</li>
 *   <li>{@code depthSampling} selects point sampling of the centre texel or a
 *       conservative 2x2 minimum that prefers the nearest surface in the
 *       footprint (useful for thin geometry at the cost of extra fetches).</li>
 * </ul>
 *
 * <p>{@code opaqueDepthOnly} must be {@code true}: this version documents and
 * accepts only the shared opaque scene depth. A depth buffer that already
 * contains transparent surfaces is not a supported input, so it is rejected
 * instead of being silently used. The input is a single-sample depth texture;
 * the caller must provide the shared single-sample depth view (or a resolved
 * copy) rather than a multisampled raster depth attachment.</p>
 *
 * <p>All fields are validated at construction. Values are rejected instead of
 * clamped so an author error fails at the owner boundary before a frame is
 * recorded.</p>
 */
public record GpuParticleCollisionSettings(
        boolean enabled,
        DepthSampling depthSampling,
        float thickness,
        float restitution,
        float friction,
        int maximumSearchSteps,
        float searchRadiusPixels,
        boolean opaqueDepthOnly) {
    /** Largest accepted world-space collision thickness. */
    public static final float MAXIMUM_THICKNESS = 100.0f;
    /** Largest accepted number of depth fetches for one particle. */
    public static final int MAXIMUM_SEARCH_STEPS = 16;
    /** Largest accepted screen-space search radius in pixels. */
    public static final float MAXIMUM_SEARCH_RADIUS_PIXELS = 32.0f;
    /** Largest accepted single-sample scene-depth extent on either axis. */
    public static final int MAXIMUM_SCENE_EXTENT = 16_384;

    /** How the single-sample scene depth is fetched for a particle. */
    public enum DepthSampling {
        /** Point sample of the particle's own texel. */
        TEXEL_CENTRE,
        /** Minimum of the 2x2 texels around the particle's texel. */
        CONSERVATIVE_MIN_2X2
    }

    public GpuParticleCollisionSettings {
        depthSampling = Objects.requireNonNull(depthSampling, "depthSampling");
        if (!Float.isFinite(thickness) || thickness < 0.0f
                || thickness > MAXIMUM_THICKNESS) {
            throw new IllegalArgumentException("thickness must be finite and in [0, "
                    + MAXIMUM_THICKNESS + "]");
        }
        if (enabled && !(thickness > 0.0f)) {
            throw new IllegalArgumentException(
                    "enabled collision requires a positive thickness");
        }
        if (!Float.isFinite(restitution) || restitution < 0.0f || restitution > 1.0f) {
            throw new IllegalArgumentException("restitution must be finite and in [0, 1]");
        }
        if (!Float.isFinite(friction) || friction < 0.0f || friction > 1.0f) {
            throw new IllegalArgumentException("friction must be finite and in [0, 1]");
        }
        if (maximumSearchSteps < 1 || maximumSearchSteps > MAXIMUM_SEARCH_STEPS) {
            throw new IllegalArgumentException("maximumSearchSteps must be in [1, "
                    + MAXIMUM_SEARCH_STEPS + "]");
        }
        if (!Float.isFinite(searchRadiusPixels) || searchRadiusPixels < 0.0f
                || searchRadiusPixels > MAXIMUM_SEARCH_RADIUS_PIXELS) {
            throw new IllegalArgumentException("searchRadiusPixels must be finite and in [0, "
                    + MAXIMUM_SEARCH_RADIUS_PIXELS + "]");
        }
        if (!opaqueDepthOnly) {
            throw new IllegalArgumentException("this implementation only supports opaque "
                    + "scene depth; transparent depth input is not supported");
        }
    }

    /**
     * @return the disabled value; the owner keeps the unmodified #27 update
     *         program and records no collision state
     */
    public static GpuParticleCollisionSettings disabled() {
        return new GpuParticleCollisionSettings(false, DepthSampling.TEXEL_CENTRE,
                0.0f, 0.0f, 0.0f, 1, 0.0f, true);
    }

    /**
     * Enables collision with an explicit single-sample opaque-depth contract.
     *
     * @param depthSampling      point or conservative 2x2 sampling
     * @param thickness          world-space thickness band in (0, {@link #MAXIMUM_THICKNESS}]
     * @param restitution        normal-velocity scale in [0, 1]
     * @param friction           tangential-velocity loss in [0, 1]
     * @param maximumSearchSteps depth fetches per particle in [1, {@link #MAXIMUM_SEARCH_STEPS}]
     * @param searchRadiusPixels ring-tap radius in [0, {@link #MAXIMUM_SEARCH_RADIUS_PIXELS}]
     * @return an enabled opaque-depth-only configuration
     */
    public static GpuParticleCollisionSettings enabled(DepthSampling depthSampling,
                                                       float thickness, float restitution,
                                                       float friction, int maximumSearchSteps,
                                                       float searchRadiusPixels) {
        return new GpuParticleCollisionSettings(true, depthSampling, thickness, restitution,
                friction, maximumSearchSteps, searchRadiusPixels, true);
    }

    /**
     * @param value world-space thickness in (0, {@link #MAXIMUM_THICKNESS}]
     * @return a copy with only the thickness changed
     */
    public GpuParticleCollisionSettings withThickness(float value) {
        return new GpuParticleCollisionSettings(enabled, depthSampling, value, restitution,
                friction, maximumSearchSteps, searchRadiusPixels, opaqueDepthOnly);
    }

    /**
     * @param value normal-velocity scale in [0, 1]
     * @return a copy with only the restitution changed
     */
    public GpuParticleCollisionSettings withRestitution(float value) {
        return new GpuParticleCollisionSettings(enabled, depthSampling, thickness, value,
                friction, maximumSearchSteps, searchRadiusPixels, opaqueDepthOnly);
    }
}
