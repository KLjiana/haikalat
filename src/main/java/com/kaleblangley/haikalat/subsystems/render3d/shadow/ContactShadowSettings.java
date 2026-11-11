package com.kaleblangley.haikalat.subsystems.render3d.shadow;

/**
 * Immutable controls for the main directional light's short-range contact
 * shadow.
 *
 * <p>The effect refines the existing cascaded shadow map along the light
 * direction; it creates no attachment and no draw pass.  Distances and
 * thickness are world units.  The default is disabled and contributes no
 * samples or image change.  Settings belong to {@code ShadowSettings} and are
 * published as part of a Render3D feature request, not mutated per frame.</p>
 *
 * @param enabled   whether contact shadow samples may contribute
 * @param distance  maximum world-space march length toward the light
 * @param thickness maximum world-space depth tolerance for a contact occluder
 * @param steps     bounded march sample count in {@code [1, 32]}
 * @param strength  maximum direct-light attenuation in {@code [0, 1]}
 */
public record ContactShadowSettings(boolean enabled, float distance, float thickness,
                                    int steps, float strength) {
    public static final int MAX_STEPS = 32;
    public static final float MAX_DISTANCE = 10.0f;

    private static final ContactShadowSettings DISABLED =
            new ContactShadowSettings(false, 1.0f, 0.5f, 12, 0.8f);

    public ContactShadowSettings {
        if (!Float.isFinite(distance) || distance <= 0.0f || distance > MAX_DISTANCE) {
            throw new IllegalArgumentException("distance must be finite and in (0, "
                    + MAX_DISTANCE + "]");
        }
        if (!Float.isFinite(thickness) || thickness <= 0.0f || thickness > distance) {
            throw new IllegalArgumentException(
                    "thickness must be finite, positive and no greater than distance");
        }
        if (steps < 1 || steps > MAX_STEPS) {
            throw new IllegalArgumentException("steps must be in [1, " + MAX_STEPS + "]");
        }
        if (!Float.isFinite(strength) || strength < 0.0f || strength > 1.0f) {
            throw new IllegalArgumentException("strength must be finite and in [0, 1]");
        }
    }

    /** Default-off configuration; changing only {@code enabled} keeps the shared tuning. */
    public static ContactShadowSettings disabled() {
        return DISABLED;
    }

    /** True only when the enabled flag and a non-zero strength can change the image. */
    public boolean active() {
        return enabled && strength > 0.0f;
    }

    public ContactShadowSettings withEnabled(boolean value) {
        return new ContactShadowSettings(value, distance, thickness, steps, strength);
    }
}
