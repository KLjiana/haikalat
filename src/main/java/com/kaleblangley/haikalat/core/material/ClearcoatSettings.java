package com.kaleblangley.haikalat.core.material;

/**
 * Immutable controls for one bounded clearcoat overlay layer on a PBR material.
 *
 * <p>The model is a single thin dielectric coat applied on top of the existing
 * metallic-roughness base layer. It uses a fixed normal-incidence reflectance
 * ({@link #F0}, the glTF clearcoat convention) and its own roughness, so it can
 * be glossy or rough independently of the base surface. The coat reflects
 * {@code F} of the incoming light and transmits {@code 1 - F} to the base
 * layer; the shader applies that split to both direct lighting and the existing
 * prefiltered IBL specular, so enabling the layer never adds a second material
 * system, environment or BRDF table.</p>
 *
 * <p>The optional clearcoat normal map shares the base map's tangent-space
 * convention (green = +Y in tangent space, right-handed TBN from
 * {@code pbr-forward.vert}); {@code normalStrength} scales only the sampled XY
 * slope, and zero restores the geometric normal exactly. The coat is oriented
 * by the geometric normal plus that map, not by the base normal map.</p>
 *
 * <p>This value is GL-free and is stored on a {@link Material};
 * {@code enabled = false} is the exact no-op default, in which case the PBR
 * shader keeps its original single-layer output bit for bit.</p>
 *
 * @param enabled        whether the material evaluates the clearcoat layer
 * @param roughness      independent coat roughness in {@code [0, 1]}; the shader
 *                       applies the same {@link #MINIMUM_ROUGHNESS} floor as the
 *                       base layer
 * @param normalStrength tangent-space XY scale for the optional clearcoat normal
 *                       map in {@code [0, 1]}; zero is the geometric normal
 */
public record ClearcoatSettings(boolean enabled, float roughness, float normalStrength) {
    /**
     * Normal-incidence reflectance of the coat interface. 0.04 is the shared
     * value used by the glTF clearcoat material model and the base dielectric
     * default of this renderer.
     */
    public static final float F0 = 0.04f;
    /** Coat roughness floor of the PBR shader; below it GGX becomes numerically unstable. */
    public static final float MINIMUM_ROUGHNESS = 0.045f;
    /** Largest accepted tangent-space XY scale for the clearcoat normal input. */
    public static final float MAXIMUM_NORMAL_STRENGTH = 1.0f;

    public ClearcoatSettings {
        requireUnitRange(roughness, "roughness");
        requireUnitRange(normalStrength, "normalStrength");
    }

    /** Inert, all-zero settings; no coat term is evaluated and the base layer is untouched. */
    public static ClearcoatSettings disabled() {
        return new ClearcoatSettings(false, 0.0f, 0.0f);
    }

    /** Enables the coat with an explicit roughness and normal strength. */
    public static ClearcoatSettings of(float roughness, float normalStrength) {
        return new ClearcoatSettings(true, roughness, normalStrength);
    }

    /** Enables the coat with a flat normal input (no clearcoat normal perturbation). */
    public static ClearcoatSettings ofRoughness(float roughness) {
        return new ClearcoatSettings(true, roughness, 0.0f);
    }

    public boolean active() {
        return enabled;
    }

    public ClearcoatSettings withEnabled(boolean value) {
        return new ClearcoatSettings(value, roughness, normalStrength);
    }

    public ClearcoatSettings withRoughness(float value) {
        return new ClearcoatSettings(enabled, value, normalStrength);
    }

    public ClearcoatSettings withNormalStrength(float value) {
        return new ClearcoatSettings(enabled, roughness, value);
    }

    /**
     * Scalar coat Fresnel for one cosine (view or half-vector). {@code F0 = 0.04}
     * at normal incidence and 1 at grazing; the transmitted share is
     * {@code 1 - fresnel(cosTheta)}.
     */
    public static float fresnel(float cosTheta) {
        if (!Float.isFinite(cosTheta)) {
            throw new IllegalArgumentException("clearcoat cosine must be finite");
        }
        float clamped = Math.max(0.0f, Math.min(1.0f, cosTheta));
        double oneMinus = 1.0 - clamped;
        return (float) (F0 + (1.0 - F0) * Math.pow(oneMinus, 5.0));
    }

    /**
     * Combine a base-layer radiance with the coat-layer radiance for one
     * interface Fresnel value. The split is convex, so the result never leaves
     * the interval between the two layers and {@code fresnel = 0} returns the
     * base term exactly.
     */
    public static float compose(float base, float coat, float fresnel) {
        if (!Float.isFinite(base) || !Float.isFinite(coat) || !Float.isFinite(fresnel)
                || fresnel < 0.0f || fresnel > 1.0f) {
            throw new IllegalArgumentException(
                    "clearcoat composition requires finite terms and Fresnel in [0, 1]");
        }
        return base * (1.0f - fresnel) + coat * fresnel;
    }

    private static void requireUnitRange(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException("clearcoat " + name
                    + " must be finite and in [0, 1]");
        }
    }
}
