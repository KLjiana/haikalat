package com.kaleblangley.haikalat.core.material;

/**
 * Immutable controls for the bounded anisotropic highlight of one PBR material.
 *
 * <p>The model stretches the existing metallic-roughness GGX specular lobe
 * along one tangent-space direction. {@link #rotation} selects that direction
 * inside the mesh tangent frame (0 aligns the stretch with the vertex tangent,
 * positive values rotate toward the handedness-corrected bitangent); the
 * envelope uses Burley's anisotropic GGX distribution and the matching
 * anisotropic Smith visibility term. The base diffuse lobe, Fresnel term and
 * the optional clearcoat layer stay isotropic.</p>
 *
 * <p>{@code strength = 0} is the exact no-op default: the PBR shader keeps its
 * original isotropic GGX/Smith path and its original prefiltered-IBL sample
 * sequence, so existing materials render bit for bit as before. The positive
 * path is bounded by the same minimum GGX alpha as the base roughness
 * ({@link #MINIMUM_ALPHA}), so a full-strength axis never divides by zero.</p>
 *
 * <p>Anisotropy is a per-material value, not a {@code RenderFeatureSettings}
 * topology switch: it creates no pass, texture, attachment or GPU owner. The
 * shader consumes the tangent returned by {@code pbr-forward.vert}; when a
 * mesh has no usable tangent it derives a bounded fallback frame from the
 * geometric normal instead of disabling the effect.</p>
 *
 * @param strength anisotropy amount in {@code [0, 1]}; 0 is exact isotropic
 * @param rotation anisotropy direction rotation in radians; any finite value is
 *                 accepted (the shader evaluates {@code sin}/{@code cos}, so the
 *                 direction is periodic)
 */
public record AnisotropicSettings(float strength, float rotation) {
    /** Largest accepted anisotropy strength; zero is the isotropic endpoint. */
    public static final float MAXIMUM_STRENGTH = 1.0f;
    /**
     * Lower bound of the anisotropic GGX alpha on either tangent axis. It is
     * the square of the base shader's {@code 0.045} roughness floor, so the
     * full-strength minor axis cannot collapse the distribution to a singularity.
     */
    public static final float MINIMUM_ALPHA = 0.002025f;

    public AnisotropicSettings {
        if (!Float.isFinite(strength) || strength < 0.0f || strength > MAXIMUM_STRENGTH) {
            throw new IllegalArgumentException(
                    "anisotropic strength must be finite and in [0, 1]");
        }
        if (!Float.isFinite(rotation)) {
            throw new IllegalArgumentException("anisotropic rotation must be finite radians");
        }
    }

    /** Inert settings: the shader keeps the exact isotropic BRDF and IBL paths. */
    public static AnisotropicSettings disabled() {
        return new AnisotropicSettings(0.0f, 0.0f);
    }

    /** Enables anisotropy with an explicit strength and tangent-space rotation. */
    public static AnisotropicSettings of(float strength, float rotation) {
        return new AnisotropicSettings(strength, rotation);
    }

    /** Enables anisotropy along the mesh tangent (rotation zero). */
    public static AnisotropicSettings ofStrength(float strength) {
        return new AnisotropicSettings(strength, 0.0f);
    }

    /** Positive strength selects the anisotropic shader path; zero is isotropic. */
    public boolean active() {
        return strength > 0.0f;
    }

    public AnisotropicSettings withStrength(float value) {
        return new AnisotropicSettings(value, rotation);
    }

    public AnisotropicSettings withRotation(float value) {
        return new AnisotropicSettings(strength, value);
    }
}
