package com.kaleblangley.haikalat.core.material;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

/**
 * Immutable bounded subsurface-scattering input for one PBR material.
 *
 * <p>The model has two cooperating parts and one shared set of values:</p>
 * <ul>
 *   <li>the PBR forward shader adds one bounded transmission term for light
 *       arriving on the back side of a thin surface. The term is evaluated per
 *       light, is attenuated by the authored thickness and profile scale and is
 *       added next to the unchanged diffuse/specular BRDF, so direct specular
 *       is never multiplied away by the scattering weight;</li>
 *   <li>the optional screen-space pass diffuses the already shaded linear HDR
 *       colour on the same geometric surface. Its per-pixel mask, effective
 *       radius and thickness arrive through the shared scene-surface
 *       {@code SUBSURFACE} channel, not through a second lighting system.</li>
 * </ul>
 *
 * <p>All lengths are in <strong>world units (metres)</strong>:
 * {@link #profileScaleMetres} is the mean free path of the profile,
 * {@link #thicknessMetres} is the constant thickness or the scale applied to
 * the derived thickness source, {@link #diffusionRadiusMetres} is the authored
 * screen-space scatter radius of a thin patch and
 * {@link #maxDistanceMetres} is both the camera-distance fade end of the
 * material mask and the world-space gathering bound of the screen-space pass.
 * Thickness is clamped to {@link #MAXIMUM_THICKNESS}; the shader additionally
 * clamps profile scale, radius and distance so a raw uniform override can never
 * create an unbounded march or an unbounded gather.</p>
 *
 * <p>{@link #thicknessSource} selects how the material thickness is obtained:
 * a constant authored value, a proxy from the occlusion map
 * ({@code (1 - occlusion) * thicknessMetres}, useful for wax/skin cavities) or
 * a proxy from the base-colour alpha
 * ({@code (1 - alpha) * thicknessMetres}, useful for thin leaves). Only the
 * forward and surface-mask producers read those maps; no extra texture role is
 * created.</p>
 *
 * <p>{@link #profile} selects the analytic channel penetration. {@link
 * Profile#UNIFORM} uses the same scattering length for every channel (wax),
 * {@link Profile#SKIN} uses the fixed red/green/blue penetration ratios
 * {@code 1.0 / 0.55 / 0.30} so red survives a thicker layer. There is no
 * per-pixel profile texture in this version.</p>
 *
 * <p>{@code enabled = false} is the exact no-op default: the PBR shader keeps
 * its original direct/indirect output and the surface mask is zero, so no
 * screen-space pass is created for a scene that never enables the feature.
 * This value is GL-free and creates no GPU owner; {@code PbrMaterials} uploads
 * the matching uniforms.</p>
 *
 * @param enabled                whether the material evaluates subsurface scattering
 * @param scatterRed             red scatter tint in {@code [0, 1]}
 * @param scatterGreen           green scatter tint in {@code [0, 1]}
 * @param scatterBlue            blue scatter tint in {@code [0, 1]}
 * @param intensity              scatter strength in {@code [0, 4]}; zero is inert
 * @param profile                analytic channel-penetration profile
 * @param profileScaleMetres     mean free path in metres; must be positive when enabled
 * @param thicknessSource        source of the per-pixel thickness
 * @param thicknessMetres        authored thickness or derived-thickness scale in
 *                               metres in {@code [0, 2]}
 * @param diffusionRadiusMetres  authored screen-space scatter radius in metres
 *                               in {@code [0, 2]}
 * @param screenSpaceEnabled     whether this material contributes to the
 *                               screen-space diffusion mask
 * @param maxDistanceMetres      camera-distance fade and gather bound in metres
 */
public record SubsurfaceScatteringSettings(
        boolean enabled,
        float scatterRed,
        float scatterGreen,
        float scatterBlue,
        float intensity,
        Profile profile,
        float profileScaleMetres,
        ThicknessSource thicknessSource,
        float thicknessMetres,
        float diffusionRadiusMetres,
        boolean screenSpaceEnabled,
        float maxDistanceMetres) {
    /** Smallest accepted profile mean free path in metres. */
    public static final float MINIMUM_PROFILE_SCALE = 0.001f;
    /** Largest accepted profile mean free path in metres. */
    public static final float MAXIMUM_PROFILE_SCALE = 10.0f;
    /** Largest accepted authored or derived thickness in metres. */
    public static final float MAXIMUM_THICKNESS = 2.0f;
    /** Largest accepted screen-space diffusion radius in metres. */
    public static final float MAXIMUM_DIFFUSION_RADIUS = 2.0f;
    /** Largest accepted camera-distance/gather bound in metres. */
    public static final float MAXIMUM_DISTANCE = 100.0f;
    /** Largest accepted scatter intensity. */
    public static final float MAXIMUM_INTENSITY = 4.0f;
    /** Per-channel penetration of the skin profile (red, green, blue). */
    public static final float SKIN_RED_PENETRATION = 1.0f;
    public static final float SKIN_GREEN_PENETRATION = 0.55f;
    public static final float SKIN_BLUE_PENETRATION = 0.30f;

    public SubsurfaceScatteringSettings {
        requireUnitRange(scatterRed, "scatterRed");
        requireUnitRange(scatterGreen, "scatterGreen");
        requireUnitRange(scatterBlue, "scatterBlue");
        if (!Float.isFinite(intensity) || intensity < 0.0f || intensity > MAXIMUM_INTENSITY) {
            throw new IllegalArgumentException("subsurface intensity must be finite and in [0, "
                    + MAXIMUM_INTENSITY + "]");
        }
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(thicknessSource, "thicknessSource");
        if (!Float.isFinite(profileScaleMetres) || profileScaleMetres < 0.0f
                || profileScaleMetres > MAXIMUM_PROFILE_SCALE) {
            throw new IllegalArgumentException("subsurface profileScaleMetres must be finite and in [0, "
                    + MAXIMUM_PROFILE_SCALE + "]");
        }
        if (enabled && profileScaleMetres < MINIMUM_PROFILE_SCALE) {
            throw new IllegalArgumentException("enabled subsurface scattering requires profileScaleMetres >= "
                    + MINIMUM_PROFILE_SCALE);
        }
        if (!Float.isFinite(thicknessMetres) || thicknessMetres < 0.0f
                || thicknessMetres > MAXIMUM_THICKNESS) {
            throw new IllegalArgumentException("subsurface thicknessMetres must be finite and in [0, "
                    + MAXIMUM_THICKNESS + "]");
        }
        if (!Float.isFinite(diffusionRadiusMetres) || diffusionRadiusMetres < 0.0f
                || diffusionRadiusMetres > MAXIMUM_DIFFUSION_RADIUS) {
            throw new IllegalArgumentException(
                    "subsurface diffusionRadiusMetres must be finite and in [0, "
                            + MAXIMUM_DIFFUSION_RADIUS + "]");
        }
        if (!Float.isFinite(maxDistanceMetres) || maxDistanceMetres < 0.0f
                || maxDistanceMetres > MAXIMUM_DISTANCE) {
            throw new IllegalArgumentException("subsurface maxDistanceMetres must be finite and in [0, "
                    + MAXIMUM_DISTANCE + "]");
        }
        // A disabled material keeps its authored screen-space flag and bounds so
        // re-enabling it later restores the exact previous configuration. The
        // complete request is validated whenever the material is enabled.
        if (enabled && screenSpaceEnabled && diffusionRadiusMetres <= 0.0f) {
            throw new IllegalArgumentException(
                    "screen-space subsurface scattering requires a positive diffusion radius");
        }
        if (enabled && screenSpaceEnabled && maxDistanceMetres <= 0.0f) {
            throw new IllegalArgumentException(
                    "screen-space subsurface scattering requires a positive maximum distance");
        }
    }

    /** Inert, all-zero settings; no forward term and no screen-space mask contribution. */
    public static SubsurfaceScatteringSettings disabled() {
        return new SubsurfaceScatteringSettings(false, 0.0f, 0.0f, 0.0f, 0.0f,
                Profile.UNIFORM, 0.0f, ThicknessSource.CONSTANT, 0.0f, 0.0f, false, 0.0f);
    }

    /** A builder with the documented production defaults, enabled by default. */
    public static Builder builder() {
        return new Builder();
    }

    /** Enables a skin-like profile with the default tint, scale, thickness and radius. */
    public static SubsurfaceScatteringSettings ofSkin() {
        return builder().profile(Profile.SKIN).build();
    }

    /** Enables a wax-like uniform profile with the default tint, scale, thickness and radius. */
    public static SubsurfaceScatteringSettings ofWax() {
        return builder().profile(Profile.UNIFORM).build();
    }

    /** A builder seeded with this value, including its enabled state. */
    public Builder toBuilder() {
        return new Builder().enabled(enabled)
                .scatterColor(scatterRed, scatterGreen, scatterBlue)
                .intensity(intensity)
                .profile(profile)
                .profileScaleMetres(profileScaleMetres)
                .thicknessSource(thicknessSource)
                .thicknessMetres(thicknessMetres)
                .diffusionRadiusMetres(diffusionRadiusMetres)
                .screenSpaceEnabled(screenSpaceEnabled)
                .maxDistanceMetres(maxDistanceMetres);
    }

    /** True only when the forward shader evaluates the positive scatter path. */
    public boolean active() {
        return enabled && intensity > 0.0f && profileScaleMetres >= MINIMUM_PROFILE_SCALE
                && scatterRed + scatterGreen + scatterBlue > 0.0f;
    }

    /** True only when this material contributes to the screen-space diffusion pass. */
    public boolean screenSpaceActive() {
        return active() && screenSpaceEnabled && diffusionRadiusMetres > 0.0f
                && maxDistanceMetres > 0.0f;
    }

    /** Defensive copy of the authored scatter tint. */
    public Vector3f scatterColor() {
        return new Vector3f(scatterRed, scatterGreen, scatterBlue);
    }

    /** Fixed per-channel penetration multiplier of this profile. */
    public Vector3f penetration() {
        return profile == Profile.SKIN
                ? new Vector3f(SKIN_RED_PENETRATION, SKIN_GREEN_PENETRATION, SKIN_BLUE_PENETRATION)
                : new Vector3f(1.0f, 1.0f, 1.0f);
    }

    public SubsurfaceScatteringSettings withEnabled(boolean value) {
        return new SubsurfaceScatteringSettings(value, scatterRed, scatterGreen, scatterBlue,
                intensity, profile, profileScaleMetres, thicknessSource, thicknessMetres,
                diffusionRadiusMetres, screenSpaceEnabled, maxDistanceMetres);
    }

    public SubsurfaceScatteringSettings withScatterColor(Vector3fc value) {
        Objects.requireNonNull(value, "scatterColor");
        return new SubsurfaceScatteringSettings(enabled, value.x(), value.y(), value.z(),
                intensity, profile, profileScaleMetres, thicknessSource, thicknessMetres,
                diffusionRadiusMetres, screenSpaceEnabled, maxDistanceMetres);
    }

    public SubsurfaceScatteringSettings withScatterColor(float red, float green, float blue) {
        return new SubsurfaceScatteringSettings(enabled, red, green, blue, intensity, profile,
                profileScaleMetres, thicknessSource, thicknessMetres, diffusionRadiusMetres,
                screenSpaceEnabled, maxDistanceMetres);
    }

    public SubsurfaceScatteringSettings withIntensity(float value) {
        return new SubsurfaceScatteringSettings(enabled, scatterRed, scatterGreen, scatterBlue,
                value, profile, profileScaleMetres, thicknessSource, thicknessMetres,
                diffusionRadiusMetres, screenSpaceEnabled, maxDistanceMetres);
    }

    public SubsurfaceScatteringSettings withProfile(Profile value) {
        return new SubsurfaceScatteringSettings(enabled, scatterRed, scatterGreen, scatterBlue,
                intensity, value, profileScaleMetres, thicknessSource, thicknessMetres,
                diffusionRadiusMetres, screenSpaceEnabled, maxDistanceMetres);
    }

    public SubsurfaceScatteringSettings withProfileScaleMetres(float value) {
        return new SubsurfaceScatteringSettings(enabled, scatterRed, scatterGreen, scatterBlue,
                intensity, profile, value, thicknessSource, thicknessMetres,
                diffusionRadiusMetres, screenSpaceEnabled, maxDistanceMetres);
    }

    public SubsurfaceScatteringSettings withThicknessSource(ThicknessSource value) {
        return new SubsurfaceScatteringSettings(enabled, scatterRed, scatterGreen, scatterBlue,
                intensity, profile, profileScaleMetres, value, thicknessMetres,
                diffusionRadiusMetres, screenSpaceEnabled, maxDistanceMetres);
    }

    public SubsurfaceScatteringSettings withThicknessMetres(float value) {
        return new SubsurfaceScatteringSettings(enabled, scatterRed, scatterGreen, scatterBlue,
                intensity, profile, profileScaleMetres, thicknessSource, value,
                diffusionRadiusMetres, screenSpaceEnabled, maxDistanceMetres);
    }

    public SubsurfaceScatteringSettings withDiffusionRadiusMetres(float value) {
        return new SubsurfaceScatteringSettings(enabled, scatterRed, scatterGreen, scatterBlue,
                intensity, profile, profileScaleMetres, thicknessSource, thicknessMetres, value,
                screenSpaceEnabled, maxDistanceMetres);
    }

    public SubsurfaceScatteringSettings withScreenSpaceEnabled(boolean value) {
        return new SubsurfaceScatteringSettings(enabled, scatterRed, scatterGreen, scatterBlue,
                intensity, profile, profileScaleMetres, thicknessSource, thicknessMetres,
                diffusionRadiusMetres, value, maxDistanceMetres);
    }

    public SubsurfaceScatteringSettings withMaxDistanceMetres(float value) {
        return new SubsurfaceScatteringSettings(enabled, scatterRed, scatterGreen, scatterBlue,
                intensity, profile, profileScaleMetres, thicknessSource, thicknessMetres,
                diffusionRadiusMetres, screenSpaceEnabled, value);
    }

    /** Analytic channel-penetration profile. */
    public enum Profile {
        /** Same mean free path in every channel (wax, paraffin, generic translucent solids). */
        UNIFORM(0),
        /** Fixed red-dominant penetration ratios for skin-like materials. */
        SKIN(1);

        private final int shaderIndex;

        Profile(int shaderIndex) {
            this.shaderIndex = shaderIndex;
        }

        /** The frozen {@code uSubsurfaceProfile} value of this profile. */
        public int shaderIndex() {
            return shaderIndex;
        }

        /** Resolve a shader selector; out-of-range values are rejected. */
        public static Profile fromShaderIndex(int index) {
            return switch (index) {
                case 0 -> UNIFORM;
                case 1 -> SKIN;
                default -> throw new IllegalArgumentException(
                        "subsurface profile must be in [0, 1], got " + index);
            };
        }
    }

    /** Source of the per-pixel thickness consumed by the forward and surface-mask shaders. */
    public enum ThicknessSource {
        /** {@code thicknessMetres} is used directly. */
        CONSTANT(0),
        /** {@code (1 - occlusion.r) * thicknessMetres}; the occlusion map is the thickness proxy. */
        OCCLUSION(1),
        /** {@code (1 - baseColour.a) * thicknessMetres}; thin masked leaves/petals. */
        BASE_ALPHA(2);

        private final int shaderIndex;

        ThicknessSource(int shaderIndex) {
            this.shaderIndex = shaderIndex;
        }

        /** The frozen {@code uSubsurfaceThicknessSource} value of this source. */
        public int shaderIndex() {
            return shaderIndex;
        }

        /** Resolve a shader selector; out-of-range values are rejected. */
        public static ThicknessSource fromShaderIndex(int index) {
            return switch (index) {
                case 0 -> CONSTANT;
                case 1 -> OCCLUSION;
                case 2 -> BASE_ALPHA;
                default -> throw new IllegalArgumentException(
                        "subsurface thickness source must be in [0, 2], got " + index);
            };
        }
    }

    /** Validated mutable builder for {@link SubsurfaceScatteringSettings}. */
    public static final class Builder {
        private boolean enabled = true;
        private float scatterRed = 0.85f;
        private float scatterGreen = 0.35f;
        private float scatterBlue = 0.22f;
        private float intensity = 1.0f;
        private Profile profile = Profile.SKIN;
        private float profileScaleMetres = 0.02f;
        private ThicknessSource thicknessSource = ThicknessSource.CONSTANT;
        private float thicknessMetres = 0.05f;
        private float diffusionRadiusMetres = 0.12f;
        private boolean screenSpaceEnabled = true;
        private float maxDistanceMetres = 2.0f;

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder scatterColor(Vector3fc value) {
            Objects.requireNonNull(value, "scatterColor");
            return scatterColor(value.x(), value.y(), value.z());
        }

        public Builder scatterColor(float red, float green, float blue) {
            scatterRed = red;
            scatterGreen = green;
            scatterBlue = blue;
            return this;
        }

        public Builder intensity(float value) {
            intensity = value;
            return this;
        }

        public Builder profile(Profile value) {
            profile = value;
            return this;
        }

        public Builder profileScaleMetres(float value) {
            profileScaleMetres = value;
            return this;
        }

        public Builder thicknessSource(ThicknessSource value) {
            thicknessSource = value;
            return this;
        }

        public Builder thicknessMetres(float value) {
            thicknessMetres = value;
            return this;
        }

        public Builder diffusionRadiusMetres(float value) {
            diffusionRadiusMetres = value;
            return this;
        }

        public Builder screenSpaceEnabled(boolean value) {
            screenSpaceEnabled = value;
            return this;
        }

        public Builder maxDistanceMetres(float value) {
            maxDistanceMetres = value;
            return this;
        }

        public SubsurfaceScatteringSettings build() {
            return new SubsurfaceScatteringSettings(enabled, scatterRed, scatterGreen, scatterBlue,
                    intensity, profile, profileScaleMetres, thicknessSource, thicknessMetres,
                    diffusionRadiusMetres, screenSpaceEnabled, maxDistanceMetres);
        }
    }

    private static void requireUnitRange(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException("subsurface " + name
                    + " must be finite and in [0, 1]");
        }
    }
}
