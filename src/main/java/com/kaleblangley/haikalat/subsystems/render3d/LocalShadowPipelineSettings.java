package com.kaleblangley.haikalat.subsystems.render3d;

import java.util.Objects;

/** Immutable local-shadow capacity, selection, quality and cache policy. */
public record LocalShadowPipelineSettings(
        LocalShadowSettings point,
        LocalShadowSettings spot,
        int maxPointLights,
        int maxSpotLights,
        ShadowSelectionMode selectionMode,
        ShadowFilterMode filterMode,
        float normalBias,
        float replacementThreshold,
        boolean cacheStaticTiles) {

    public static final int MAX_POINT_SHADOW_LIGHTS = 2;
    public static final int MAX_SPOT_SHADOW_LIGHTS = 4;

    public LocalShadowPipelineSettings {
        point = Objects.requireNonNull(point, "point");
        spot = Objects.requireNonNull(spot, "spot");
        selectionMode = Objects.requireNonNull(selectionMode, "selectionMode");
        filterMode = Objects.requireNonNull(filterMode, "filterMode");
        if (maxPointLights < 0 || maxPointLights > MAX_POINT_SHADOW_LIGHTS) {
            throw new IllegalArgumentException("maxPointLights must be in [0, 2]");
        }
        if (maxSpotLights < 0 || maxSpotLights > MAX_SPOT_SHADOW_LIGHTS) {
            throw new IllegalArgumentException("maxSpotLights must be in [0, 4]");
        }
        if (!Float.isFinite(normalBias) || normalBias < 0.0f) {
            throw new IllegalArgumentException("normalBias must be finite and non-negative");
        }
        if (!Float.isFinite(replacementThreshold) || replacementThreshold < 1.0f) {
            throw new IllegalArgumentException(
                    "replacementThreshold must be finite and at least 1.0");
        }
    }

    /** v0.23.0-compatible one-point/one-spot resource and filtering policy. */
    public static LocalShadowPipelineSettings legacyDefaults() {
        LocalShadowSettings defaults = LocalShadowSettings.defaults();
        return new LocalShadowPipelineSettings(defaults, defaults, 1, 1,
                ShadowSelectionMode.SCENE_ORDER, ShadowFilterMode.PCF_3X3,
                0.0f, 1.15f, false);
    }

    /** v0.23.1 bounded multi-light policy used by the shadow-budget demo. */
    public static LocalShadowPipelineSettings balanced() {
        LocalShadowSettings defaults = LocalShadowSettings.defaults();
        return new LocalShadowPipelineSettings(defaults, defaults, 2, 4,
                ShadowSelectionMode.CAMERA_IMPORTANCE, ShadowFilterMode.PCF_3X3,
                0.002f, 1.15f, true);
    }

    boolean topologyEquals(LocalShadowPipelineSettings other) {
        return other != null
                && point.resolution() == other.point.resolution()
                && spot.resolution() == other.spot.resolution()
                && maxPointLights == other.maxPointLights
                && maxSpotLights == other.maxSpotLights;
    }

    boolean legacySamplingContract() {
        LocalShadowSettings defaults = LocalShadowSettings.defaults();
        return point.equals(defaults) && spot.equals(defaults)
                && maxPointLights == 1 && maxSpotLights == 1
                && selectionMode == ShadowSelectionMode.SCENE_ORDER
                && filterMode == ShadowFilterMode.PCF_3X3
                && Float.floatToIntBits(normalBias) == Float.floatToIntBits(0.0f)
                && !cacheStaticTiles;
    }
}
