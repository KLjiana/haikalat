package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;

import org.joml.Vector3f;

import java.util.Objects;

/**
 * Bounded, GL-free configuration of the volumetric cloud layer.
 *
 * <p>The layer is the slab {@code baseAltitudeMetres <= y <= topAltitudeMetres}.
 * Its density at a world position {@code p} is</p>
 *
 * <pre>{@code
 * extinction * coverage(p.xz) * verticalProfile(p.y)
 * extinction  = density * (absorption + scattering)
 * verticalProfile(y) = smoothstep(0, E, t) * (1 - smoothstep(1 - E, 1, t))
 * t           = (y - base) / (top - base),  E = 0.25
 * }</pre>
 *
 * <p>and exactly zero outside the slab. The horizontal coverage is the
 * world-space cloud mask of the existing cloud-shadow feature #10: the same
 * {@link CloudShadowSettings} value is reused unchanged (coverage, softness,
 * scale and wind speed), and the same hash/noise/coverage functions are used by
 * both shaders. The cloud-shadow interface projects a receiver onto a plane
 * through the origin along the light direction; this layer evaluates the same
 * field at the cloud's own world {@code xz}. Sharing the parameters and the
 * mask functions keeps one authored cloud field instead of two, but the shadow
 * footprint and the visible layer are not the same projection (documented
 * limitation, not a hidden promise).</p>
 *
 * <p>Lighting is a bounded single-scatter model: for every march sample the
 * shader performs at most {@link #lightSteps()} optical-depth samples toward
 * the light (the direction is {@code -lightDirection}) over at most
 * {@link #maximumLightDistanceMetres()}, then accumulates
 * {@code light * phase * albedo * lightTransmittance * (1 - exp(-sigma * delta))}
 * front to back. There is no multiple scattering, no shadow map and no
 * participating-light coupling.</p>
 *
 * <p>The cloud signal is rendered at {@link #renderScale()} and reconstructed to
 * the target extent by a depth-aware spatial filter plus an optional temporal
 * history. Every component is validated at construction, and
 * {@link #disabled()} is the exact no-op value: while disabled no pass, shader,
 * framebuffer or draw exists. Vector components return defensive copies.</p>
 */
public record VolumetricCloudSettings(
        boolean enabled,
        CloudShadowSettings coverageField,
        float baseAltitudeMetres,
        float topAltitudeMetres,
        float density,
        float absorption,
        float scattering,
        Vector3f lightDirection,
        Vector3f lightColor,
        float anisotropy,
        int maximumSteps,
        float stepSizeMetres,
        int lightSteps,
        float maximumLightDistanceMetres,
        float renderScale,
        float historyBlend,
        float exposureThreshold,
        float depthToleranceMetres,
        float cameraCutTranslationMetres,
        float cameraCutAngleDegrees) {
    /** Largest accepted density scale. */
    public static final float MAXIMUM_DENSITY = 64.0f;
    /** Largest accepted absorption or scattering coefficient at unit density. */
    public static final float MAXIMUM_COEFFICIENT = 64.0f;
    /** Largest accepted absolute altitude in world units. */
    public static final float MAXIMUM_ABSOLUTE_ALTITUDE = 100_000.0f;
    /** Smallest accepted {@code top - base} layer thickness in world units. */
    public static final float MINIMUM_LAYER_THICKNESS = 1.0f;
    /** Largest accepted layer thickness in world units. */
    public static final float MAXIMUM_LAYER_THICKNESS = 100_000.0f;
    /** Smallest accepted per-pixel view-march step budget. */
    public static final int MINIMUM_STEPS = 1;
    /** Largest accepted per-pixel view-march step budget. */
    public static final int MAXIMUM_STEPS = 64;
    /** Largest accepted target march step in world metres. */
    public static final float MAXIMUM_STEP_SIZE = 512.0f;
    /** Smallest accepted per-sample light-march step budget. */
    public static final int MINIMUM_LIGHT_STEPS = 1;
    /** Largest accepted per-sample light-march step budget. */
    public static final int MAXIMUM_LIGHT_STEPS = 8;
    /** Largest accepted light-march distance in world metres. */
    public static final float MAXIMUM_LIGHT_DISTANCE = 20_000.0f;
    /** Largest accepted linear light-radiance component. */
    public static final float MAXIMUM_LIGHT_COMPONENT = 16.0f;
    /** Largest accepted absolute Henyey-Greenstein anisotropy. */
    public static final float MAXIMUM_ANISOTROPY = 0.9f;
    /** Smallest accepted low-resolution render scale. */
    public static final float MINIMUM_RENDER_SCALE = 0.1f;
    /** Largest accepted history weight. */
    public static final float MAXIMUM_HISTORY_BLEND = 0.99f;
    /** Largest accepted depth-disocclusion tolerance in world metres. */
    public static final float MAXIMUM_DEPTH_TOLERANCE = 1_000.0f;
    /** Largest accepted camera-cut translation in world metres. */
    public static final float MAXIMUM_CAMERA_CUT_TRANSLATION = 10_000.0f;
    /** Largest accepted camera-cut angle in degrees. */
    public static final float MAXIMUM_CAMERA_CUT_ANGLE = 180.0f;

    private static final VolumetricCloudSettings DISABLED = new VolumetricCloudSettings(
            false,
            CloudShadowSettings.disabled(),
            900.0f, 1_600.0f,
            1.0f, 0.05f, 0.95f,
            new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f, 1.0f, 1.0f),
            0.2f,
            32, 40.0f, 4, 2_000.0f,
            0.5f, 0.9f, 0.1f, 2.0f,
            5.0f, 30.0f);

    public VolumetricCloudSettings {
        if (coverageField == null) {
            throw new IllegalArgumentException("coverageField must not be null");
        }
        if (!finite(baseAltitudeMetres) || Math.abs(baseAltitudeMetres) > MAXIMUM_ABSOLUTE_ALTITUDE) {
            throw new IllegalArgumentException("baseAltitudeMetres must be finite and within ±"
                    + MAXIMUM_ABSOLUTE_ALTITUDE);
        }
        if (!finite(topAltitudeMetres) || Math.abs(topAltitudeMetres) > MAXIMUM_ABSOLUTE_ALTITUDE) {
            throw new IllegalArgumentException("topAltitudeMetres must be finite and within ±"
                    + MAXIMUM_ABSOLUTE_ALTITUDE);
        }
        float thickness = topAltitudeMetres - baseAltitudeMetres;
        if (!(thickness >= MINIMUM_LAYER_THICKNESS) || thickness > MAXIMUM_LAYER_THICKNESS) {
            throw new IllegalArgumentException("the cloud layer thickness (top - base) must be in ["
                    + MINIMUM_LAYER_THICKNESS + ", " + MAXIMUM_LAYER_THICKNESS + "]");
        }
        if (!finite(density) || density < 0.0f || density > MAXIMUM_DENSITY) {
            throw new IllegalArgumentException("density must be finite and in [0, "
                    + MAXIMUM_DENSITY + "]");
        }
        if (!finite(absorption) || absorption < 0.0f || absorption > MAXIMUM_COEFFICIENT) {
            throw new IllegalArgumentException("absorption must be finite and in [0, "
                    + MAXIMUM_COEFFICIENT + "]");
        }
        if (!finite(scattering) || scattering < 0.0f || scattering > MAXIMUM_COEFFICIENT) {
            throw new IllegalArgumentException("scattering must be finite and in [0, "
                    + MAXIMUM_COEFFICIENT + "]");
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
        if (lightSteps < MINIMUM_LIGHT_STEPS || lightSteps > MAXIMUM_LIGHT_STEPS) {
            throw new IllegalArgumentException("lightSteps must be in ["
                    + MINIMUM_LIGHT_STEPS + ", " + MAXIMUM_LIGHT_STEPS + "]");
        }
        if (!finite(maximumLightDistanceMetres) || maximumLightDistanceMetres <= 0.0f
                || maximumLightDistanceMetres > MAXIMUM_LIGHT_DISTANCE) {
            throw new IllegalArgumentException("maximumLightDistanceMetres must be finite and in (0, "
                    + MAXIMUM_LIGHT_DISTANCE + "]");
        }
        if (!finite(renderScale) || renderScale < MINIMUM_RENDER_SCALE
                || renderScale > 1.0f) {
            throw new IllegalArgumentException("renderScale must be finite and in ["
                    + MINIMUM_RENDER_SCALE + ", 1]");
        }
        if (!finite(historyBlend) || historyBlend < 0.0f || historyBlend > MAXIMUM_HISTORY_BLEND) {
            throw new IllegalArgumentException("historyBlend must be finite and in [0, "
                    + MAXIMUM_HISTORY_BLEND + "]");
        }
        if (!finite(exposureThreshold) || exposureThreshold < 0.0f || exposureThreshold > 1.0f) {
            throw new IllegalArgumentException("exposureThreshold must be finite and in [0, 1]");
        }
        if (!finite(depthToleranceMetres) || depthToleranceMetres <= 0.0f
                || depthToleranceMetres > MAXIMUM_DEPTH_TOLERANCE) {
            throw new IllegalArgumentException("depthToleranceMetres must be finite and in (0, "
                    + MAXIMUM_DEPTH_TOLERANCE + "]");
        }
        if (!finite(cameraCutTranslationMetres) || cameraCutTranslationMetres < 0.0f
                || cameraCutTranslationMetres > MAXIMUM_CAMERA_CUT_TRANSLATION) {
            throw new IllegalArgumentException("cameraCutTranslationMetres must be finite and in [0, "
                    + MAXIMUM_CAMERA_CUT_TRANSLATION + "]");
        }
        if (!finite(cameraCutAngleDegrees) || cameraCutAngleDegrees < 0.0f
                || cameraCutAngleDegrees > MAXIMUM_CAMERA_CUT_ANGLE) {
            throw new IllegalArgumentException("cameraCutAngleDegrees must be finite and in [0, "
                    + MAXIMUM_CAMERA_CUT_ANGLE + "]");
        }
    }

    /** Exact no-op value: no cloud pass or GPU resource exists while disabled. */
    public static VolumetricCloudSettings disabled() {
        return DISABLED;
    }

    /** @return true when the cloud pass and its draws should exist */
    public boolean active() {
        return enabled;
    }

    /** @return a bounded copy with only the enabled flag changed */
    public VolumetricCloudSettings withEnabled(boolean value) {
        return new VolumetricCloudSettings(value, coverageField, baseAltitudeMetres,
                topAltitudeMetres, density, absorption, scattering, new Vector3f(lightDirection),
                new Vector3f(lightColor), anisotropy, maximumSteps, stepSizeMetres, lightSteps,
                maximumLightDistanceMetres, renderScale, historyBlend, exposureThreshold,
                depthToleranceMetres, cameraCutTranslationMetres, cameraCutAngleDegrees);
    }

    /**
     * @param value a bounded coverage field
     * @return a copy with the shared #10 coverage field replaced
     */
    public VolumetricCloudSettings withCoverageField(CloudShadowSettings value) {
        return new VolumetricCloudSettings(enabled, Objects.requireNonNull(value, "value"),
                baseAltitudeMetres, topAltitudeMetres, density, absorption, scattering,
                new Vector3f(lightDirection), new Vector3f(lightColor), anisotropy, maximumSteps,
                stepSizeMetres, lightSteps, maximumLightDistanceMetres, renderScale, historyBlend,
                exposureThreshold, depthToleranceMetres, cameraCutTranslationMetres,
                cameraCutAngleDegrees);
    }

    /** Extinction coefficient at unit coverage, in 1/metre. */
    public float extinction() {
        return density * (absorption + scattering);
    }

    /**
     * Single-scattering albedo: {@code scattering / (absorption + scattering)}
     * with an exact {@code 0} fallback when both coefficients are zero.
     */
    public float scatteringAlbedo() {
        float sum = absorption + scattering;
        return sum > 0.0f ? scattering / sum : 0.0f;
    }

    /**
     * Single documented low-resolution extent rule, used by the pass and the
     * tests: {@code max(1, round(fullExtent * renderScale))}.
     *
     * @param fullExtent target extent in pixels on one axis
     * @return the low-resolution extent on that axis
     */
    public int lowResolutionExtent(int fullExtent) {
        if (fullExtent <= 0) {
            throw new IllegalArgumentException("full extent must be positive");
        }
        return Math.max(1, Math.round(fullExtent * renderScale));
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
