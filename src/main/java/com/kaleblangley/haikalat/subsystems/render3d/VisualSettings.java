package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import org.joml.Vector3f;

import java.util.Objects;

/** Immutable renderer-facing values from one global visual profile. */
public record VisualSettings(float manualExposure,
                             float iblIntensity,
                             float iblRotationRadians,
                             Vector3f directionalLightDirection,
                             Vector3f directionalLightColor,
                             float directionalLightIntensity,
                             AntiAliasingMode antiAliasingMode,
                             BloomSettings bloom,
                             OutdoorEnvironmentSettings outdoor,
                             VolumetricFogSettings volumetricFog) {
    public VisualSettings {
        if (!Float.isFinite(manualExposure) || manualExposure <= 0.0f) {
            throw new IllegalArgumentException("manualExposure must be finite and positive");
        }
        if (!Float.isFinite(iblIntensity) || iblIntensity < 0.0f) {
            throw new IllegalArgumentException("iblIntensity must be finite and non-negative");
        }
        if (!Float.isFinite(iblRotationRadians)) {
            throw new IllegalArgumentException("iblRotationRadians must be finite");
        }
        directionalLightDirection = copyFinite(directionalLightDirection,
                "directionalLightDirection");
        directionalLightColor = copyFinite(directionalLightColor, "directionalLightColor");
        if (directionalLightDirection.lengthSquared() == 0.0f) {
            throw new IllegalArgumentException("directionalLightDirection must be non-zero");
        }
        if (Math.abs(directionalLightDirection.lengthSquared() - 1.0f) > 1.0e-6f) {
            directionalLightDirection.normalize();
        }
        if (directionalLightColor.x < 0.0f || directionalLightColor.y < 0.0f
                || directionalLightColor.z < 0.0f) {
            throw new IllegalArgumentException(
                    "directionalLightColor components must be non-negative");
        }
        if (!Float.isFinite(directionalLightIntensity) || directionalLightIntensity < 0.0f) {
            throw new IllegalArgumentException(
                    "directionalLightIntensity must be finite and non-negative");
        }
        antiAliasingMode = Objects.requireNonNull(antiAliasingMode, "antiAliasingMode");
        bloom = Objects.requireNonNull(bloom, "bloom");
        outdoor = Objects.requireNonNull(outdoor, "outdoor");
        volumetricFog = Objects.requireNonNull(volumetricFog, "volumetricFog");
    }

    /** Existing sky/surface callers default to no physical volume. */
    public VisualSettings(float manualExposure, float iblIntensity, float iblRotationRadians,
                          Vector3f directionalLightDirection, Vector3f directionalLightColor,
                          float directionalLightIntensity, AntiAliasingMode antiAliasingMode,
                          BloomSettings bloom, OutdoorEnvironmentSettings outdoor) {
        this(manualExposure, iblIntensity, iblRotationRadians, directionalLightDirection,
                directionalLightColor, directionalLightIntensity, antiAliasingMode, bloom,
                outdoor, VolumetricFogSettings.disabled());
    }

    public VisualSettings withVolumetricFog(VolumetricFogSettings value) {
        return new VisualSettings(manualExposure, iblIntensity, iblRotationRadians,
                directionalLightDirection, directionalLightColor, directionalLightIntensity,
                antiAliasingMode, bloom, outdoor, value);
    }

    /** Compatibility constructor for callers whose direct light follows the outdoor sky. */
    public VisualSettings(float manualExposure, float iblIntensity, float iblRotationRadians,
                          AntiAliasingMode antiAliasingMode, BloomSettings bloom,
                          OutdoorEnvironmentSettings outdoor) {
        this(manualExposure, iblIntensity, iblRotationRadians,
                Objects.requireNonNull(outdoor, "outdoor").sky().sunDirection(),
                outdoor.sky().sunColor(), outdoor.sky().sunIntensity(),
                antiAliasingMode, bloom, outdoor, VolumetricFogSettings.disabled());
    }

    @Override
    public Vector3f directionalLightDirection() {
        return new Vector3f(directionalLightDirection);
    }

    @Override
    public Vector3f directionalLightColor() {
        return new Vector3f(directionalLightColor);
    }

    private static Vector3f copyFinite(Vector3f value, String name) {
        Vector3f copy = new Vector3f(Objects.requireNonNull(value, name));
        if (!Float.isFinite(copy.x) || !Float.isFinite(copy.y) || !Float.isFinite(copy.z)) {
            throw new IllegalArgumentException(name + " must contain finite values");
        }
        return copy;
    }
}
