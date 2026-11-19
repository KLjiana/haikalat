package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import org.joml.Vector3f;

import java.util.Objects;

/**
 * Bounded, GL-free configuration of the smoke volume-rendering consumer.
 *
 * <p>The consumer samples one density field produced by
 * {@link SmokeSimulation} and one resolved opaque scene depth. Coefficients are
 * per world metre at unit stored density: the extinction at a sample is
 * {@code densityScale * density * (absorption + scattering)} and the albedo is
 * {@code scattering / (absorption + scattering)}. The march is a bounded
 * midpoint quadrature over the grid AABB clipped by the opaque surface; the
 * single-scatter contribution is
 * {@code sum(T_i * light * phase * albedo * (1 - exp(-extinction_i * delta)))},
 * with a normalized Henyey-Greenstein phase and a hard per-pixel step budget.
 * A long interval widens the step instead of exceeding the budget.</p>
 *
 * <p>{@link Blend} selects how the premultiplied contribution composites over
 * the copied HDR source: {@code OCCLUDING} uses
 * {@code ONE, ONE_MINUS_SRC_ALPHA} and attenuates the source by the smoke
 * transmittance, {@code ADDITIVE} uses {@code ONE, ONE} and ignores the
 * coverage. {@link #contributionWeight()} scales the optical depth exactly
 * once, so {@code 0} is an exact no-op for the smoke medium and the in-scatter
 * stays energy-consistent with the transmittance.</p>
 *
 * <p>The consumer deliberately owns no fog or particle medium: its shader
 * declares no second density/source sampler, so the volumetric-fog and
 * volumetric-particle media are each integrated once by their own passes. The
 * pass is recorded after the fog composite and after the particle pass when
 * both are active. See {@code docs/planning/v0.28.1-s5-smoke.md}.</p>
 *
 * <p>Every component is validated at construction. {@link #disabled()} is the
 * exact no-op value: no pass, shader, framebuffer or draw exists while
 * disabled. Vector components return defensive copies.</p>
 */
public record SmokeRenderSettings(
        boolean enabled,
        float absorption,
        float scattering,
        float densityScale,
        Vector3f lightDirection,
        Vector3f lightColor,
        float anisotropy,
        int maximumSteps,
        float stepSizeMetres,
        Blend blend,
        float contributionWeight,
        float maximumScreenCoverage) {
    /** Largest accepted absorption or scattering coefficient per world metre. */
    public static final float MAXIMUM_COEFFICIENT = 64.0f;
    /** Largest accepted density scale applied to the stored field. */
    public static final float MAXIMUM_DENSITY_SCALE = 64.0f;
    /** Smallest accepted per-pixel march step budget. */
    public static final int MINIMUM_STEPS = 1;
    /** Largest accepted per-pixel march step budget. */
    public static final int MAXIMUM_STEPS = 64;
    /** Largest accepted target march step in world metres. */
    public static final float MAXIMUM_STEP_SIZE = 64.0f;
    /** Largest accepted linear light-radiance component. */
    public static final float MAXIMUM_LIGHT_COMPONENT = 16.0f;
    /** Largest accepted absolute Henyey-Greenstein anisotropy. */
    public static final float MAXIMUM_ANISOTROPY = 0.9f;

    /** How the smoke contribution composites over the copied HDR colour. */
    public enum Blend {
        /** Attenuate the copied colour by the smoke transmittance and add the in-scatter. */
        OCCLUDING,
        /** Keep the copied colour and add the in-scatter only. */
        ADDITIVE
    }

    private static final SmokeRenderSettings DISABLED = new SmokeRenderSettings(
            false, 0.2f, 0.8f, 1.0f, new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f),
            0.0f, 32, 0.1f, Blend.OCCLUDING, 1.0f, 1.0f);

    public SmokeRenderSettings {
        if (!finite(absorption) || absorption < 0.0f || absorption > MAXIMUM_COEFFICIENT) {
            throw new IllegalArgumentException("absorption must be finite and in [0, "
                    + MAXIMUM_COEFFICIENT + "] per world metre");
        }
        if (!finite(scattering) || scattering < 0.0f || scattering > MAXIMUM_COEFFICIENT) {
            throw new IllegalArgumentException("scattering must be finite and in [0, "
                    + MAXIMUM_COEFFICIENT + "] per world metre");
        }
        if (!finite(densityScale) || densityScale < 0.0f || densityScale > MAXIMUM_DENSITY_SCALE) {
            throw new IllegalArgumentException("densityScale must be finite and in [0, "
                    + MAXIMUM_DENSITY_SCALE + "]");
        }
        lightDirection = normalizeDirection(lightDirection);
        lightColor = lightColor(lightColor);
        if (!finite(anisotropy) || Math.abs(anisotropy) > MAXIMUM_ANISOTROPY) {
            throw new IllegalArgumentException("anisotropy must be finite and in [-"
                    + MAXIMUM_ANISOTROPY + ", " + MAXIMUM_ANISOTROPY + "]");
        }
        if (maximumSteps < MINIMUM_STEPS || maximumSteps > MAXIMUM_STEPS) {
            throw new IllegalArgumentException("maximumSteps must be in ["
                    + MINIMUM_STEPS + ", " + MAXIMUM_STEPS + "]");
        }
        if (!finite(stepSizeMetres) || stepSizeMetres <= 0.0f
                || stepSizeMetres > MAXIMUM_STEP_SIZE) {
            throw new IllegalArgumentException("stepSizeMetres must be finite and in (0, "
                    + MAXIMUM_STEP_SIZE + "]");
        }
        if (blend == null) {
            throw new IllegalArgumentException("blend must not be null");
        }
        if (!finite(contributionWeight) || contributionWeight < 0.0f
                || contributionWeight > 1.0f) {
            throw new IllegalArgumentException("contributionWeight must be finite and in [0, 1]");
        }
        if (!finite(maximumScreenCoverage) || maximumScreenCoverage <= 0.0f
                || maximumScreenCoverage > 1.0f) {
            throw new IllegalArgumentException("maximumScreenCoverage must be finite and in (0, 1]");
        }
    }

    /** Exact no-op value: no volume pass or GPU resource exists while disabled. */
    public static SmokeRenderSettings disabled() {
        return DISABLED;
    }

    /** @return true when the volume pass and its draw should exist */
    public boolean active() {
        return enabled;
    }

    /** @return a bounded copy with only the enabled flag changed */
    public SmokeRenderSettings withEnabled(boolean value) {
        return new SmokeRenderSettings(value, absorption, scattering, densityScale,
                new Vector3f(lightDirection), new Vector3f(lightColor), anisotropy,
                maximumSteps, stepSizeMetres, blend, contributionWeight,
                maximumScreenCoverage);
    }

    /** Extinction coefficient per unit stored density, in 1/metre. */
    public float densityExtinction() {
        return densityScale * (absorption + scattering);
    }

    /**
     * Single-scattering albedo: {@code scattering / (absorption + scattering)}
     * with an exact {@code 0} fallback when both coefficients are zero.
     */
    public float scatteringAlbedo() {
        float sum = absorption + scattering;
        return sum > 0.0f ? scattering / sum : 0.0f;
    }

    /** @return a defensive copy of the world-space light-travel direction */
    @Override
    public Vector3f lightDirection() {
        return new Vector3f(lightDirection);
    }

    /** @return a defensive copy of the linear light radiance */
    @Override
    public Vector3f lightColor() {
        return new Vector3f(lightColor);
    }

    Vector3f lightDirectionInternal() {
        return lightDirection;
    }

    Vector3f lightColorInternal() {
        return lightColor;
    }

    private static Vector3f normalizeDirection(Vector3f value) {
        Objects.requireNonNull(value, "lightDirection");
        float lengthSquared = value.lengthSquared();
        if (!Float.isFinite(lengthSquared) || lengthSquared < 1.0e-12f) {
            throw new IllegalArgumentException("lightDirection must be finite and non-zero");
        }
        return new Vector3f(value).mul(1.0f / (float) Math.sqrt(lengthSquared));
    }

    private static Vector3f lightColor(Vector3f value) {
        Objects.requireNonNull(value, "lightColor");
        if (!value.isFinite() || value.x < 0.0f || value.y < 0.0f || value.z < 0.0f
                || value.x > MAXIMUM_LIGHT_COMPONENT || value.y > MAXIMUM_LIGHT_COMPONENT
                || value.z > MAXIMUM_LIGHT_COMPONENT) {
            throw new IllegalArgumentException("lightColor components must be in [0, "
                    + MAXIMUM_LIGHT_COMPONENT + "]");
        }
        return new Vector3f(value);
    }

    private static boolean finite(float value) {
        return Float.isFinite(value);
    }
}
