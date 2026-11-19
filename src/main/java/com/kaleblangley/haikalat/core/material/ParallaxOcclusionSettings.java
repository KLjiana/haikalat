package com.kaleblangley.haikalat.core.material;

import java.util.Objects;

/**
 * Immutable controls for the bounded tangent-space parallax occlusion mapping of
 * one PBR material.
 *
 * <p>The model marches the interpolated view ray through a height map in the
 * mesh tangent frame and returns a parallax-offset UV for the <em>following</em>
 * material texture lookups (base colour, metallic-roughness, normal, occlusion
 * and emissive). It is a shading approximation only: the generated fragment
 * depth, the shadow-map depth written by the shadow caster, velocity and the
 * geometric silhouette are untouched, and there is no self-shadowing or
 * silhouette expansion. It must therefore never be described as geometric
 * displacement.</p>
 *
 * <p>{@link #heightScale} is the UV-space height of the tallest feature. A ray
 * with a tangent-space lateral slope of one (45 degrees from the surface normal)
 * reaches that full height at an offset of {@code heightScale} UV units. The
 * height input is linear data sampled from a single channel
 * ({@link #heightChannel}, {@code R} by default) and can be inverted with
 * {@link #flipHeight}. The shared missing-map fallback is RGBA
 * {@code (0,0,0,0)}: height zero in every channel, including {@code ALPHA}, so
 * a missing map with {@code flipHeight = false} is a material with a flat
 * surface for any channel — the march finds the surface layer immediately and
 * the sampled UV equals the interpolated UV. {@code flipHeight = true} inverts
 * the sampled height, so the inverted zero fallback becomes one (the maximum
 * height) and is intentionally <em>not</em> a no-op; supply the height map when
 * the inverted convention is required.</p>
 *
 * <p>The effect fades out with both the view angle and the camera distance.
 * {@link #fadeStart}/{@link #fadeEnd} are view angles in degrees measured from
 * the surface normal (0 = head-on, 90 = grazing): the weight is one up to
 * {@code fadeStart}, follows a smoothstep down to zero at {@code fadeEnd}, and
 * stays zero beyond it. {@link #fadeStartDistance}/{@link #maxDistance} are
 * camera distances in world units with the same smoothstep shape. The two
 * weights multiply, so either window can disable the effect on its own. The
 * fade scales the marched UV offset (not the BRDF), so the result is continuous
 * up to the exact disabled path.</p>
 *
 * <p>{@code enabled = false} or {@code heightScale = 0} is the exact no-op
 * default: the PBR shader keeps the original {@code vTexCoord} sampling
 * sequence bit for bit. {@link #maxSteps} is capped by
 * {@link #MAXIMUM_STEP_COUNT}; the shader uses a compile-time bounded loop, so
 * no authored value can create an unbounded march.</p>
 *
 * <p>This value is GL-free, stored on a {@link Material} and uploaded by
 * {@code PbrMaterials}. It creates no pass, texture, attachment or GPU owner;
 * the shared height-map role uses the existing fallback texture when no map is
 * supplied.</p>
 *
 * @param enabled           whether the material evaluates parallax occlusion
 * @param heightScale       UV-space height in {@code [0, 1]}; zero is the exact
 *                          original-UV path
 * @param minSteps          minimum height-layer count in {@code [1, 128]}; used
 *                          for near-normal views
 * @param maxSteps          maximum height-layer count in
 *                          {@code [minSteps, 128]}; used toward grazing views
 * @param fadeStart         view angle in degrees in {@code [0, 90)} where the
 *                          angular fade starts; the weight is one at or below it
 * @param fadeEnd           view angle in degrees in
 *                          {@code (fadeStart, 90]} where the angular fade ends;
 *                          the weight is zero at or beyond it
 * @param fadeStartDistance camera distance in world units where the distance
 *                          fade starts; the weight is one at or below it
 * @param maxDistance       camera distance in world units in
 *                          {@code (fadeStartDistance, +inf)} where the distance
 *                          fade ends; the weight is zero at or beyond it
 * @param heightChannel     linear height-map channel that carries the height
 * @param flipHeight        invert the sampled height ({@code 1 - h})
 */
public record ParallaxOcclusionSettings(
        boolean enabled,
        float heightScale,
        int minSteps,
        int maxSteps,
        float fadeStart,
        float fadeEnd,
        float fadeStartDistance,
        float maxDistance,
        HeightChannel heightChannel,
        boolean flipHeight) {
    /** Largest accepted UV-space height; zero is the exact no-op endpoint. */
    public static final float MAXIMUM_HEIGHT_SCALE = 1.0f;
    /** Smallest accepted height-layer count. */
    public static final int MINIMUM_STEP_COUNT = 1;
    /** Largest accepted height-layer count; matches the shader's bounded loop. */
    public static final int MAXIMUM_STEP_COUNT = 128;
    /** Largest accepted view angle in degrees (the surface-grazing limit). */
    public static final float MAXIMUM_FADE_ANGLE = 90.0f;
    /** Default height scale of {@link #builder()}; 5% of the UV space. */
    public static final float DEFAULT_HEIGHT_SCALE = 0.05f;
    /** Default near-normal layer count. */
    public static final int DEFAULT_MIN_STEPS = 8;
    /** Default grazing layer count. */
    public static final int DEFAULT_MAX_STEPS = 32;
    /** Default angle in degrees where the angular fade starts. */
    public static final float DEFAULT_FADE_START = 60.0f;
    /** Default angle in degrees where the angular fade ends. */
    public static final float DEFAULT_FADE_END = 85.0f;
    /** Default camera distance where the distance fade starts. */
    public static final float DEFAULT_FADE_START_DISTANCE = 8.0f;
    /** Default camera distance where the distance fade ends. */
    public static final float DEFAULT_MAX_DISTANCE = 20.0f;

    public ParallaxOcclusionSettings {
        if (!Float.isFinite(heightScale) || heightScale < 0.0f
                || heightScale > MAXIMUM_HEIGHT_SCALE) {
            throw new IllegalArgumentException("parallax heightScale must be finite and in [0, 1]");
        }
        if (minSteps < MINIMUM_STEP_COUNT || minSteps > MAXIMUM_STEP_COUNT) {
            throw new IllegalArgumentException("parallax minSteps must be in [1, 128]");
        }
        if (maxSteps < minSteps || maxSteps > MAXIMUM_STEP_COUNT) {
            throw new IllegalArgumentException(
                    "parallax maxSteps must be in [minSteps, 128]");
        }
        if (!Float.isFinite(fadeStart) || fadeStart < 0.0f
                || fadeStart >= MAXIMUM_FADE_ANGLE) {
            throw new IllegalArgumentException(
                    "parallax fadeStart degrees must be finite and in [0, 90)");
        }
        if (!Float.isFinite(fadeEnd) || fadeEnd <= fadeStart
                || fadeEnd > MAXIMUM_FADE_ANGLE) {
            throw new IllegalArgumentException(
                    "parallax fadeEnd degrees must be finite and in (fadeStart, 90]");
        }
        if (!Float.isFinite(fadeStartDistance) || fadeStartDistance < 0.0f) {
            throw new IllegalArgumentException(
                    "parallax fadeStartDistance must be finite and non-negative");
        }
        if (!Float.isFinite(maxDistance) || maxDistance <= fadeStartDistance) {
            throw new IllegalArgumentException(
                    "parallax maxDistance must be finite and greater than fadeStartDistance");
        }
        Objects.requireNonNull(heightChannel, "heightChannel");
    }

    /** Inert defaults: disabled with a zero height scale, so no UV is modified. */
    public static ParallaxOcclusionSettings disabled() {
        return new ParallaxOcclusionSettings(false, 0.0f, DEFAULT_MIN_STEPS, DEFAULT_MAX_STEPS,
                DEFAULT_FADE_START, DEFAULT_FADE_END, DEFAULT_FADE_START_DISTANCE,
                DEFAULT_MAX_DISTANCE, HeightChannel.RED, false);
    }

    /** Enables parallax occlusion with an explicit height scale and the other defaults. */
    public static ParallaxOcclusionSettings of(float heightScale) {
        return builder().heightScale(heightScale).build();
    }

    /** Enables parallax occlusion with an explicit height scale and layer range. */
    public static ParallaxOcclusionSettings of(float heightScale, int minSteps, int maxSteps) {
        return builder().heightScale(heightScale).steps(minSteps, maxSteps).build();
    }

    /** A builder with the documented production defaults, enabled by default. */
    public static Builder builder() {
        return new Builder();
    }

    /** A builder seeded with this value, including its enabled state. */
    public Builder toBuilder() {
        return new Builder().enabled(enabled).heightScale(heightScale).steps(minSteps, maxSteps)
                .fadeAngles(fadeStart, fadeEnd)
                .distanceRange(fadeStartDistance, maxDistance)
                .heightChannel(heightChannel).flipHeight(flipHeight);
    }

    /** True only when the shader evaluates the positive parallax path. */
    public boolean active() {
        return enabled && heightScale > 0.0f;
    }

    public ParallaxOcclusionSettings withEnabled(boolean value) {
        return new ParallaxOcclusionSettings(value, heightScale, minSteps, maxSteps, fadeStart,
                fadeEnd, fadeStartDistance, maxDistance, heightChannel, flipHeight);
    }

    public ParallaxOcclusionSettings withHeightScale(float value) {
        return new ParallaxOcclusionSettings(enabled, value, minSteps, maxSteps, fadeStart,
                fadeEnd, fadeStartDistance, maxDistance, heightChannel, flipHeight);
    }

    public ParallaxOcclusionSettings withSteps(int minimum, int maximum) {
        return new ParallaxOcclusionSettings(enabled, heightScale, minimum, maximum, fadeStart,
                fadeEnd, fadeStartDistance, maxDistance, heightChannel, flipHeight);
    }

    public ParallaxOcclusionSettings withFadeAngles(float start, float end) {
        return new ParallaxOcclusionSettings(enabled, heightScale, minSteps, maxSteps, start, end,
                fadeStartDistance, maxDistance, heightChannel, flipHeight);
    }

    public ParallaxOcclusionSettings withDistanceRange(float start, float maximum) {
        return new ParallaxOcclusionSettings(enabled, heightScale, minSteps, maxSteps, fadeStart,
                fadeEnd, start, maximum, heightChannel, flipHeight);
    }

    public ParallaxOcclusionSettings withHeightChannel(HeightChannel value) {
        return new ParallaxOcclusionSettings(enabled, heightScale, minSteps, maxSteps, fadeStart,
                fadeEnd, fadeStartDistance, maxDistance, value, flipHeight);
    }

    public ParallaxOcclusionSettings withFlipHeight(boolean value) {
        return new ParallaxOcclusionSettings(enabled, heightScale, minSteps, maxSteps, fadeStart,
                fadeEnd, fadeStartDistance, maxDistance, heightChannel, value);
    }

    /**
     * Channel of the linear height input. The shader index is the GLSL channel
     * selector ({@code 0 = R}, {@code 1 = G}, {@code 2 = B}, {@code 3 = A}).
     */
    public enum HeightChannel {
        RED(0), GREEN(1), BLUE(2), ALPHA(3);

        private final int shaderIndex;

        HeightChannel(int shaderIndex) {
            this.shaderIndex = shaderIndex;
        }

        /** The frozen {@code uParallaxHeightChannel} value for this channel. */
        public int shaderIndex() {
            return shaderIndex;
        }

        /** Resolve a shader channel selector; out-of-range values are rejected. */
        public static HeightChannel fromShaderIndex(int index) {
            return switch (index) {
                case 0 -> RED;
                case 1 -> GREEN;
                case 2 -> BLUE;
                case 3 -> ALPHA;
                default -> throw new IllegalArgumentException(
                        "parallax height channel must be in [0, 3], got " + index);
            };
        }
    }

    /** Mutable builder for {@link ParallaxOcclusionSettings}; validated on {@link #build()}. */
    public static final class Builder {
        private boolean enabled = true;
        private float heightScale = DEFAULT_HEIGHT_SCALE;
        private int minSteps = DEFAULT_MIN_STEPS;
        private int maxSteps = DEFAULT_MAX_STEPS;
        private float fadeStart = DEFAULT_FADE_START;
        private float fadeEnd = DEFAULT_FADE_END;
        private float fadeStartDistance = DEFAULT_FADE_START_DISTANCE;
        private float maxDistance = DEFAULT_MAX_DISTANCE;
        private HeightChannel heightChannel = HeightChannel.RED;
        private boolean flipHeight;

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder heightScale(float value) {
            heightScale = value;
            return this;
        }

        public Builder steps(int minimum, int maximum) {
            minSteps = minimum;
            maxSteps = maximum;
            return this;
        }

        public Builder fadeAngles(float start, float end) {
            fadeStart = start;
            fadeEnd = end;
            return this;
        }

        public Builder distanceRange(float start, float maximum) {
            fadeStartDistance = start;
            maxDistance = maximum;
            return this;
        }

        public Builder heightChannel(HeightChannel value) {
            heightChannel = value;
            return this;
        }

        public Builder flipHeight(boolean value) {
            flipHeight = value;
            return this;
        }

        public ParallaxOcclusionSettings build() {
            return new ParallaxOcclusionSettings(enabled, heightScale, minSteps, maxSteps,
                    fadeStart, fadeEnd, fadeStartDistance, maxDistance, heightChannel, flipHeight);
        }
    }
}
