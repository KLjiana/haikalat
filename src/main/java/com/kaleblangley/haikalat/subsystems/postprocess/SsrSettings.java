package com.kaleblangley.haikalat.subsystems.postprocess;

/**
 * Bounded screen-space reflection settings for the HDR colour chain.
 *
 * <p>The first version is a purely spatial post-process pass: it reads the
 * resolved linear HDR colour plus the shared single-sample depth, world normal
 * and material roughness channels, marches one reflection ray per pixel for a
 * bounded number of steps and writes a distinct RGBA16F target.  No history,
 * reprojection or temporal filter is owned here; the spatial result is designed
 * to be consumed by the later temporal-denoising feature instead.</p>
 *
 * <h2>Bounds and units</h2>
 * <ul>
 *   <li>{@code maxDistanceMetres} is the maximum world-space ray length.  The
 *       ray is also bounded by the screen: any ray that leaves the raster or
 *       leaves the depth range before a confident hit contributes nothing.</li>
 *   <li>{@code maxSteps} is the hard per-pixel march budget.  The world-space
 *       spacing is {@code maxDistanceMetres / maxSteps}, so a larger step count
 *       both costs more and resolves thinner occluders.</li>
 *   <li>{@code thicknessMetres} is the depth window that turns a ray passing
 *       behind a visible surface into a hit.  A separation larger than the
 *       thickness is treated as occlusion and contributes nothing; surfaces
 *       thinner than the world step can still be skipped, which is the known
 *       spatial-path limitation.</li>
 *   <li>{@code roughnessCutoff} rejects rough materials outright.  The shader
 *       additionally fades the screen-space colour toward the environment
 *       colour as roughness grows below the cutoff.</li>
 *   <li>{@code minimumConfidence} is the hit-confidence floor.  The confidence
 *       combines the depth separation at the hit, the distance of the hit from
 *       the raster border, the hit normal facing the ray, the view angle
 *       (grazing reflections are unreliable) and the distance along the ray.</li>
 *   <li>{@code strength} scales the final Fresnel-weighted replacement of the
 *       environment reflection; {@code edgeFadePixels} is the border band over
 *       which a hit fades out.</li>
 * </ul>
 *
 * <h2>Fallback policy</h2>
 * <p>Any rejected or low-confidence sample makes the pass write the input
 * colour exactly.  The forward PBR shading already contains the environment/IBL
 * specular term, so the pass only replaces that term where a confident
 * screen-space hit exists: offscreen, backface, thin/occluded, rough and
 * grazing pixels keep the existing environment/IBL colour.  The pass therefore
 * requires a bound {@link SsrEnvironmentSource}; without one it is rejected at
 * assembly.</p>
 *
 * <p>{@link #disabled()} is the exact no-op value: no pass, shader, attachment
 * or draw is created while the effect is inactive.</p>
 */
public record SsrSettings(
        boolean enabled,
        float maxDistanceMetres,
        int maxSteps,
        float thicknessMetres,
        float roughnessCutoff,
        float minimumConfidence,
        float strength,
        float edgeFadePixels
) {
    /** Largest accepted world-space ray length. */
    public static final float MAXIMUM_DISTANCE_METRES = 1000.0f;
    /** Smallest accepted world-space ray length. */
    public static final float MINIMUM_DISTANCE_METRES = 0.05f;
    /** Hard march budget; matches the shader's compile-time {@code SSR_MAX_STEPS}. */
    public static final int MAXIMUM_STEPS = 64;
    /** Smallest accepted hit thickness in world units. */
    public static final float MINIMUM_THICKNESS_METRES = 0.001f;
    /** Largest accepted border fade band in output pixels. */
    public static final float MAXIMUM_EDGE_FADE_PIXELS = 256.0f;
    /**
     * Largest accepted confidence floor.  The shader maps the floor through
     * {@code smoothstep(floor, 1, confidence)}; keeping the floor below one
     * guarantees a non-degenerate edge.
     */
    public static final float MAXIMUM_MINIMUM_CONFIDENCE = 0.95f;

    public SsrSettings {
        if (!Float.isFinite(maxDistanceMetres)
                || maxDistanceMetres < MINIMUM_DISTANCE_METRES
                || maxDistanceMetres > MAXIMUM_DISTANCE_METRES) {
            throw new IllegalArgumentException("maxDistanceMetres must be finite and in ["
                    + MINIMUM_DISTANCE_METRES + ", " + MAXIMUM_DISTANCE_METRES + "]");
        }
        if (maxSteps < 1 || maxSteps > MAXIMUM_STEPS) {
            throw new IllegalArgumentException("maxSteps must be in [1, " + MAXIMUM_STEPS + "]");
        }
        if (!Float.isFinite(thicknessMetres) || thicknessMetres < MINIMUM_THICKNESS_METRES
                || thicknessMetres > maxDistanceMetres) {
            throw new IllegalArgumentException("thicknessMetres must be finite and in ["
                    + MINIMUM_THICKNESS_METRES + ", maxDistanceMetres]");
        }
        if (!Float.isFinite(roughnessCutoff) || roughnessCutoff < 0.0f
                || roughnessCutoff > 1.0f) {
            throw new IllegalArgumentException("roughnessCutoff must be finite and in [0, 1]");
        }
        if (!Float.isFinite(minimumConfidence) || minimumConfidence < 0.0f
                || minimumConfidence > MAXIMUM_MINIMUM_CONFIDENCE) {
            throw new IllegalArgumentException("minimumConfidence must be finite and in [0, "
                    + MAXIMUM_MINIMUM_CONFIDENCE + "]");
        }
        if (!Float.isFinite(strength) || strength <= 0.0f || strength > 1.0f) {
            throw new IllegalArgumentException("strength must be finite and in (0, 1]");
        }
        if (!Float.isFinite(edgeFadePixels) || edgeFadePixels < 0.0f
                || edgeFadePixels > MAXIMUM_EDGE_FADE_PIXELS) {
            throw new IllegalArgumentException("edgeFadePixels must be finite and in [0, "
                    + MAXIMUM_EDGE_FADE_PIXELS + "]");
        }
    }

    /** Exact no-op value used when no screen-space reflection was requested. */
    public static SsrSettings disabled() {
        return new SsrSettings(false, 12.0f, 32, 0.5f, 0.4f, 0.15f, 0.9f, 24.0f);
    }

    /** Enables the effect with the shared default tuning. */
    public static SsrSettings defaults() {
        return disabled().withEnabled(true);
    }

    /** @return true when the pass, shader and attachments should exist */
    public boolean active() {
        return enabled;
    }

    /** @return world-space distance between two march samples */
    public float worldStepMetres() {
        return maxDistanceMetres / maxSteps;
    }

    /**
     * A march whose world spacing exceeds the hit thickness can skip a thinner
     * occluder.  The setting is accepted (the spatial path stays bounded) but
     * callers can report the approximation.
     *
     * @return true when every march step is at most as thick as the hit window
     */
    public boolean resolvesStepsWithinThickness() {
        return worldStepMetres() <= thicknessMetres;
    }

    public SsrSettings withEnabled(boolean value) {
        return new SsrSettings(value, maxDistanceMetres, maxSteps, thicknessMetres,
                roughnessCutoff, minimumConfidence, strength, edgeFadePixels);
    }

    public SsrSettings withMaxDistanceMetres(float value) {
        return new SsrSettings(enabled, value, maxSteps, thicknessClamped(value, thicknessMetres),
                roughnessCutoff, minimumConfidence, strength, edgeFadePixels);
    }

    public SsrSettings withMaxSteps(int value) {
        return new SsrSettings(enabled, maxDistanceMetres, value, thicknessMetres,
                roughnessCutoff, minimumConfidence, strength, edgeFadePixels);
    }

    public SsrSettings withThicknessMetres(float value) {
        return new SsrSettings(enabled, maxDistanceMetres, maxSteps, value,
                roughnessCutoff, minimumConfidence, strength, edgeFadePixels);
    }

    public SsrSettings withRoughnessCutoff(float value) {
        return new SsrSettings(enabled, maxDistanceMetres, maxSteps, thicknessMetres,
                value, minimumConfidence, strength, edgeFadePixels);
    }

    public SsrSettings withMinimumConfidence(float value) {
        return new SsrSettings(enabled, maxDistanceMetres, maxSteps, thicknessMetres,
                roughnessCutoff, value, strength, edgeFadePixels);
    }

    public SsrSettings withStrength(float value) {
        return new SsrSettings(enabled, maxDistanceMetres, maxSteps, thicknessMetres,
                roughnessCutoff, minimumConfidence, value, edgeFadePixels);
    }

    public SsrSettings withEdgeFadePixels(float value) {
        return new SsrSettings(enabled, maxDistanceMetres, maxSteps, thicknessMetres,
                roughnessCutoff, minimumConfidence, strength, value);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Keeps thickness inside the new distance so a shorter ray can never carry
     * a thickness that the validation would reject.
     */
    private static float thicknessClamped(float distance, float thickness) {
        return Math.min(thickness, distance);
    }

    public static final class Builder {
        private boolean enabled;
        private float maxDistanceMetres = 12.0f;
        private int maxSteps = 32;
        private float thicknessMetres = 0.5f;
        private float roughnessCutoff = 0.4f;
        private float minimumConfidence = 0.15f;
        private float strength = 0.9f;
        private float edgeFadePixels = 24.0f;

        private Builder() {
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder maxDistanceMetres(float value) {
            maxDistanceMetres = value;
            return this;
        }

        public Builder maxSteps(int value) {
            maxSteps = value;
            return this;
        }

        public Builder thicknessMetres(float value) {
            thicknessMetres = value;
            return this;
        }

        public Builder roughnessCutoff(float value) {
            roughnessCutoff = value;
            return this;
        }

        public Builder minimumConfidence(float value) {
            minimumConfidence = value;
            return this;
        }

        public Builder strength(float value) {
            strength = value;
            return this;
        }

        public Builder edgeFadePixels(float value) {
            edgeFadePixels = value;
            return this;
        }

        public SsrSettings build() {
            return new SsrSettings(enabled, maxDistanceMetres, maxSteps,
                    thicknessClamped(maxDistanceMetres, thicknessMetres), roughnessCutoff,
                    minimumConfidence, strength, edgeFadePixels);
        }
    }
}
