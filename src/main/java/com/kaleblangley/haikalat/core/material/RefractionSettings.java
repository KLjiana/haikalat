package com.kaleblangley.haikalat.core.material;

/**
 * Immutable controls for one single-layer transmissive material.
 *
 * <p>The model is a bounded screen-space approximation for glass and energy
 * shields. The IOR bends the view ray through the authored surface normal, and
 * {@code thickness} is the world-space distance used to project that ray back to
 * a screen position. It samples the already-resolved opaque scene colour; it
 * never samples the target it writes and never performs layer-to-layer
 * refraction. {@code roughness} spreads the transmitted lookup over at most
 * four deterministic taps, so it is an artistic rough-transmission
 * approximation rather than a physical BTDF. This value is GL-free and is
 * stored on a {@link Material}; {@code enabled = false} is the exact no-op
 * default.</p>
 *
 * @param enabled      whether the material uses the bounded glass path
 * @param ior          index of refraction in {@code [1.0, 2.5]}
 * @param thickness    world-space refraction depth in {@code [0, 2]}
 * @param roughness    rough-transmission spread in {@code [0, 1]}
 * @param transmission refracted-background weight in {@code [0, 1]}
 * @param reflection   Fresnel-tinted reflection weight in {@code [0, 1]}
 */
public record RefractionSettings(boolean enabled, float ior, float thickness,
                                 float roughness, float transmission, float reflection) {
    /** Water is the physical lower bound; 2.5 covers common glass and shields. */
    public static final float MINIMUM_IOR = 1.0f;
    /** Upper bound that keeps the screen-space approximation bounded. */
    public static final float MAXIMUM_IOR = 2.5f;
    /** Largest authored refraction depth in world units. */
    public static final float MAXIMUM_THICKNESS = 2.0f;
    /** Largest accepted screen-space offset contributed by a material, in UV. */
    public static final float MAXIMUM_SCREEN_OFFSET_UV = 0.25f;
    /** Extra UV spread applied at roughness one. */
    public static final float MAXIMUM_ROUGHNESS_SPREAD_UV = 0.02f;

    public RefractionSettings {
        if (!Float.isFinite(ior) || ior < MINIMUM_IOR || ior > MAXIMUM_IOR) {
            throw new IllegalArgumentException("refraction ior must be finite and in ["
                    + MINIMUM_IOR + ", " + MAXIMUM_IOR + "]");
        }
        if (!Float.isFinite(thickness) || thickness < 0.0f || thickness > MAXIMUM_THICKNESS) {
            throw new IllegalArgumentException("refraction thickness must be finite and in [0, "
                    + MAXIMUM_THICKNESS + "]");
        }
        requireUnitRange(roughness, "roughness");
        requireUnitRange(transmission, "transmission");
        requireUnitRange(reflection, "reflection");
    }

    /** Default-off settings; no glass pass or shader variant is created for them. */
    public static RefractionSettings disabled() {
        return new RefractionSettings(false, 1.5f, 0.15f, 0.0f, 1.0f, 0.0f);
    }

    /** Enables the bounded single-layer refraction model. */
    public static RefractionSettings of(float ior, float thickness, float roughness,
                                        float transmission, float reflection) {
        return new RefractionSettings(true, ior, thickness, roughness, transmission, reflection);
    }

    public boolean active() {
        return enabled;
    }

    public RefractionSettings withEnabled(boolean value) {
        return new RefractionSettings(value, ior, thickness, roughness, transmission, reflection);
    }

    public RefractionSettings withIor(float value) {
        return new RefractionSettings(enabled, value, thickness, roughness, transmission, reflection);
    }

    public RefractionSettings withThickness(float value) {
        return new RefractionSettings(enabled, ior, value, roughness, transmission, reflection);
    }

    public RefractionSettings withRoughness(float value) {
        return new RefractionSettings(enabled, ior, thickness, value, transmission, reflection);
    }

    public RefractionSettings withTransmission(float value) {
        return new RefractionSettings(enabled, ior, thickness, roughness, value, reflection);
    }

    public RefractionSettings withReflection(float value) {
        return new RefractionSettings(enabled, ior, thickness, roughness, transmission, value);
    }

    private static void requireUnitRange(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException("refraction " + name
                    + " must be finite and in [0, 1]");
        }
    }
}
