package com.kaleblangley.haikalat.subsystems.postprocess;

/**
 * Bounded screen-space indirect diffuse (SSGI) request for the HDR colour chain.
 *
 * <p>The first version is a purely spatial post-process pass. It reads the
 * resolved linear HDR colour (the radiance of visible surfaces), the shared
 * single-sample depth, world normal, material roughness and material albedo
 * channels, and it traces a bounded cosine-weighted hemisphere per pixel. Every
 * ray that finds a visible surface inside the raster adds that surface's
 * radiance; every ray that leaves the raster adds the receiver's ambient
 * radiance approximation instead, so an offscreen or occluded hemisphere never
 * becomes black and never marches unboundedly. The result is composited as one
 * extra diffuse bounce:
 * {@code outgoing = base + albedo / PI * gatheredIrradiance * strength}.</p>
 *
 * <h2>Double-counting contract</h2>
 * <ul>
 *   <li><strong>Direct light:</strong> the receiver's own colour is only read,
 *       never re-added. Only the radiance of <em>other</em> visible surfaces is
 *       gathered, so a direct-lit receiver does not add its own direct term
 *       again; a direct-lit neighbour legitimately contributes one bounce.</li>
 *   <li><strong>IBL ambient:</strong> in-raster sky/background samples
 *       contribute nothing, because the environment-to-receiver path is already
 *       present in the forward IBL ambient. The pass never adds the environment
 *       cube as a second ambient term. Only the offscreen miss budget uses the
 *       ambient approximation, clamped to
 *       {@link #maximumOffscreenFraction()} of the receiver irradiance, as an
 *       estimate of unseen geometry rather than a repeated sky term.</li>
 *   <li><strong>AO:</strong> no existing ambient is subtracted and the added
 *       bounce is not multiplied by the forward AO again, so AO is applied
 *       exactly once by the forward shader. The added bounce is therefore not
 *       re-occluded in this version; that is a documented energy approximation,
 *       not a second AO application.</li>
 * </ul>
 *
 * <h2>Bounds and units</h2>
 * <ul>
 *   <li>{@code sampleCount} is the fixed cosine-weighted hemisphere budget and
 *       {@code stepCount} is the fixed march budget per sample. The product is
 *       the hard per-pixel tap bound, capped by the shader's compile-time
 *       {@code SSGI_MAX_SAMPLES * SSGI_MAX_STEPS}.</li>
 *   <li>{@code maxDistanceMetres} is the world-space march length; every step is
 *       {@code maxDistanceMetres / stepCount} metres apart.</li>
 *   <li>{@code thicknessMetres} is the view-depth window that turns a ray
 *       passing behind a visible surface into a hit. A larger separation is
 *       treated as occlusion and contributes nothing, which is the known
 *       spatial-path limitation for thin geometry.</li>
 *   <li>{@code strength} scales the final added bounce in {@code (0, 1]}.</li>
 *   <li>{@code minimumSampleRoughness} rejects mirror-like hit surfaces whose
 *       radiance is predominantly specular: samples below the floor are
 *       discarded, samples above fade in over the next 0.2 roughness.</li>
 *   <li>{@code edgeFadePixels} fades a hit near the raster border; the fade
 *       applies to the hit contribution, not to the baseline colour.</li>
 *   <li>{@code ambientFallback} is the uniform radiance approximation used when
 *       no IBL environment is bound. It is compared against the receiver's
 *       ambient baseline, so the fallback can never create energy from zero.</li>
 * </ul>
 *
 * <p>{@link #disabled()} is the exact no-op value: while the request is inactive
 * no pass, shader, attachment or draw is created and the baseline colour chain
 * is byte-for-byte unchanged.</p>
 */
public record SsgiSettings(
        boolean enabled,
        int sampleCount,
        int stepCount,
        float maxDistanceMetres,
        float thicknessMetres,
        float strength,
        float minimumSampleRoughness,
        float maximumOffscreenFraction,
        float edgeFadePixels,
        float ambientFallbackRed,
        float ambientFallbackGreen,
        float ambientFallbackBlue
) {
    /** Largest accepted hemisphere sample budget. Matches the shader's {@code SSGI_MAX_SAMPLES}. */
    public static final int MAXIMUM_SAMPLES = 16;
    /** Smallest accepted hemisphere sample budget. */
    public static final int MINIMUM_SAMPLES = 1;
    /** Largest accepted march step budget. Matches the shader's {@code SSGI_MAX_STEPS}. */
    public static final int MAXIMUM_STEPS = 16;
    /** Smallest accepted march step budget. */
    public static final int MINIMUM_STEPS = 1;
    /** Smallest accepted world-space march length. */
    public static final float MINIMUM_DISTANCE_METRES = 0.1f;
    /** Largest accepted world-space march length. */
    public static final float MAXIMUM_DISTANCE_METRES = 50.0f;
    /** Smallest accepted hit thickness. */
    public static final float MINIMUM_THICKNESS_METRES = 0.001f;
    /** Largest accepted border fade band in output pixels. */
    public static final float MAXIMUM_EDGE_FADE_PIXELS = 256.0f;
    /** Largest accepted uniform ambient fallback radiance per channel. */
    public static final float MAXIMUM_AMBIENT_RADIANCE = 16.0f;
    /**
     * Largest accepted fraction of the receiver irradiance that the offscreen
     * miss budget may contribute. One full irradiance unit is the physical upper
     * bound of a single bounce, so values above one are rejected.
     */
    public static final float MAXIMUM_OFFSCREEN_FRACTION = 1.0f;
    /** Roughness window over which a hit surface fades into the diffuse gather. */
    public static final float SAMPLE_ROUGHNESS_FADE = 0.2f;

    public SsgiSettings {
        if (sampleCount < MINIMUM_SAMPLES || sampleCount > MAXIMUM_SAMPLES) {
            throw new IllegalArgumentException("sampleCount must be in ["
                    + MINIMUM_SAMPLES + ", " + MAXIMUM_SAMPLES + "]");
        }
        if (stepCount < MINIMUM_STEPS || stepCount > MAXIMUM_STEPS) {
            throw new IllegalArgumentException("stepCount must be in ["
                    + MINIMUM_STEPS + ", " + MAXIMUM_STEPS + "]");
        }
        requireFiniteInRange(maxDistanceMetres, MINIMUM_DISTANCE_METRES,
                MAXIMUM_DISTANCE_METRES, "maxDistanceMetres");
        if (!Float.isFinite(thicknessMetres) || thicknessMetres < MINIMUM_THICKNESS_METRES
                || thicknessMetres > maxDistanceMetres) {
            throw new IllegalArgumentException("thicknessMetres must be finite and in ["
                    + MINIMUM_THICKNESS_METRES + ", maxDistanceMetres]");
        }
        if (!Float.isFinite(strength) || strength <= 0.0f || strength > 1.0f) {
            throw new IllegalArgumentException("strength must be finite and in (0, 1]");
        }
        if (!Float.isFinite(minimumSampleRoughness) || minimumSampleRoughness < 0.0f
                || minimumSampleRoughness > 1.0f) {
            throw new IllegalArgumentException(
                    "minimumSampleRoughness must be finite and in [0, 1]");
        }
        if (!Float.isFinite(maximumOffscreenFraction) || maximumOffscreenFraction < 0.0f
                || maximumOffscreenFraction > MAXIMUM_OFFSCREEN_FRACTION) {
            throw new IllegalArgumentException("maximumOffscreenFraction must be finite and in [0, "
                    + MAXIMUM_OFFSCREEN_FRACTION + "]");
        }
        if (!Float.isFinite(edgeFadePixels) || edgeFadePixels < 0.0f
                || edgeFadePixels > MAXIMUM_EDGE_FADE_PIXELS) {
            throw new IllegalArgumentException("edgeFadePixels must be finite and in [0, "
                    + MAXIMUM_EDGE_FADE_PIXELS + "]");
        }
        requireFiniteInRange(ambientFallbackRed, 0.0f, MAXIMUM_AMBIENT_RADIANCE,
                "ambientFallbackRed");
        requireFiniteInRange(ambientFallbackGreen, 0.0f, MAXIMUM_AMBIENT_RADIANCE,
                "ambientFallbackGreen");
        requireFiniteInRange(ambientFallbackBlue, 0.0f, MAXIMUM_AMBIENT_RADIANCE,
                "ambientFallbackBlue");
    }

    /** Exact no-op value used when no screen-space indirect diffuse was requested. */
    public static SsgiSettings disabled() {
        return new SsgiSettings(false, 8, 8, 8.0f, 0.5f, 0.75f, 0.15f, 0.5f, 8.0f,
                0.08f, 0.08f, 0.08f);
    }

    /** Enables the effect with the shared default tuning. */
    public static SsgiSettings defaults() {
        return disabled().withEnabled(true);
    }

    /** @return true when the pass, shader and attachments should exist */
    public boolean active() {
        return enabled;
    }

    /** @return hard per-pixel ray budget, bounded by the shader compile-time caps */
    public int tapBudget() {
        return sampleCount * stepCount;
    }

    /** @return world-space distance between two march samples */
    public float worldStepMetres() {
        return maxDistanceMetres / stepCount;
    }

    /**
     * A march whose world spacing exceeds the hit thickness can skip a thinner
     * occluder. The setting is still accepted because the path stays bounded,
     * but callers can report the approximation.
     *
     * @return true when every march step is at most as thick as the hit window
     */
    public boolean resolvesStepsWithinThickness() {
        return worldStepMetres() <= thicknessMetres;
    }

    public SsgiSettings withEnabled(boolean value) {
        return new SsgiSettings(value, sampleCount, stepCount, maxDistanceMetres,
                thicknessMetres, strength, minimumSampleRoughness, maximumOffscreenFraction,
                edgeFadePixels, ambientFallbackRed, ambientFallbackGreen, ambientFallbackBlue);
    }

    public SsgiSettings withSampleCount(int value) {
        return new SsgiSettings(enabled, value, stepCount, maxDistanceMetres, thicknessMetres,
                strength, minimumSampleRoughness, maximumOffscreenFraction, edgeFadePixels,
                ambientFallbackRed, ambientFallbackGreen, ambientFallbackBlue);
    }

    public SsgiSettings withStepCount(int value) {
        return new SsgiSettings(enabled, sampleCount, value, maxDistanceMetres, thicknessMetres,
                strength, minimumSampleRoughness, maximumOffscreenFraction, edgeFadePixels,
                ambientFallbackRed, ambientFallbackGreen, ambientFallbackBlue);
    }

    public SsgiSettings withMaxDistanceMetres(float value) {
        return new SsgiSettings(enabled, sampleCount, stepCount, value,
                thicknessClamped(value, thicknessMetres), strength, minimumSampleRoughness,
                maximumOffscreenFraction, edgeFadePixels, ambientFallbackRed,
                ambientFallbackGreen, ambientFallbackBlue);
    }

    public SsgiSettings withThicknessMetres(float value) {
        return new SsgiSettings(enabled, sampleCount, stepCount, maxDistanceMetres, value,
                strength, minimumSampleRoughness, maximumOffscreenFraction, edgeFadePixels,
                ambientFallbackRed, ambientFallbackGreen, ambientFallbackBlue);
    }

    public SsgiSettings withStrength(float value) {
        return new SsgiSettings(enabled, sampleCount, stepCount, maxDistanceMetres,
                thicknessMetres, value, minimumSampleRoughness, maximumOffscreenFraction,
                edgeFadePixels, ambientFallbackRed, ambientFallbackGreen, ambientFallbackBlue);
    }

    public SsgiSettings withMinimumSampleRoughness(float value) {
        return new SsgiSettings(enabled, sampleCount, stepCount, maxDistanceMetres,
                thicknessMetres, strength, value, maximumOffscreenFraction, edgeFadePixels,
                ambientFallbackRed, ambientFallbackGreen, ambientFallbackBlue);
    }

    public SsgiSettings withMaximumOffscreenFraction(float value) {
        return new SsgiSettings(enabled, sampleCount, stepCount, maxDistanceMetres,
                thicknessMetres, strength, minimumSampleRoughness, value, edgeFadePixels,
                ambientFallbackRed, ambientFallbackGreen, ambientFallbackBlue);
    }

    public SsgiSettings withEdgeFadePixels(float value) {
        return new SsgiSettings(enabled, sampleCount, stepCount, maxDistanceMetres,
                thicknessMetres, strength, minimumSampleRoughness, maximumOffscreenFraction,
                value, ambientFallbackRed, ambientFallbackGreen, ambientFallbackBlue);
    }

    public SsgiSettings withAmbientFallback(float red, float green, float blue) {
        return new SsgiSettings(enabled, sampleCount, stepCount, maxDistanceMetres,
                thicknessMetres, strength, minimumSampleRoughness, maximumOffscreenFraction,
                edgeFadePixels, red, green, blue);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Keeps thickness inside the new distance so a shorter ray cannot fail validation. */
    private static float thicknessClamped(float distance, float thickness) {
        return Math.min(thickness, distance);
    }

    private static void requireFiniteInRange(float value, float minimum, float maximum,
                                             String name) {
        if (!Float.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be finite and in ["
                    + minimum + ", " + maximum + "]");
        }
    }

    /** Validated mutable builder for {@link SsgiSettings}. */
    public static final class Builder {
        private boolean enabled;
        private int sampleCount = 8;
        private int stepCount = 8;
        private float maxDistanceMetres = 8.0f;
        private float thicknessMetres = 0.5f;
        private float strength = 0.75f;
        private float minimumSampleRoughness = 0.15f;
        private float maximumOffscreenFraction = 0.5f;
        private float edgeFadePixels = 8.0f;
        private float ambientFallbackRed = 0.08f;
        private float ambientFallbackGreen = 0.08f;
        private float ambientFallbackBlue = 0.08f;

        private Builder() {
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder sampleCount(int value) {
            sampleCount = value;
            return this;
        }

        public Builder stepCount(int value) {
            stepCount = value;
            return this;
        }

        public Builder maxDistanceMetres(float value) {
            maxDistanceMetres = value;
            return this;
        }

        public Builder thicknessMetres(float value) {
            thicknessMetres = value;
            return this;
        }

        public Builder strength(float value) {
            strength = value;
            return this;
        }

        public Builder minimumSampleRoughness(float value) {
            minimumSampleRoughness = value;
            return this;
        }

        public Builder maximumOffscreenFraction(float value) {
            maximumOffscreenFraction = value;
            return this;
        }

        public Builder edgeFadePixels(float value) {
            edgeFadePixels = value;
            return this;
        }

        public Builder ambientFallback(float red, float green, float blue) {
            ambientFallbackRed = red;
            ambientFallbackGreen = green;
            ambientFallbackBlue = blue;
            return this;
        }

        public SsgiSettings build() {
            return new SsgiSettings(enabled, sampleCount, stepCount,
                    maxDistanceMetres, thicknessClamped(maxDistanceMetres, thicknessMetres),
                    strength, minimumSampleRoughness, maximumOffscreenFraction,
                    edgeFadePixels, ambientFallbackRed, ambientFallbackGreen,
                    ambientFallbackBlue);
        }
    }
}
