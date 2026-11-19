package com.kaleblangley.haikalat.subsystems.render3d.shadow;

/**
 * Immutable controls for the selected directional light's world-space cloud
 * shadow.
 *
 * <p>The mask is procedural in {@code pbr-forward.frag}; it creates no texture,
 * attachment, pass or manager.  Coverage, softness and strength are normalized
 * {@code [0, 1]} values.  Scale is the world-space noise frequency and speed is
 * the cloud travel in world units per second.  The default is disabled and
 * contributes no sample or image change.  Settings belong to
 * {@link com.kaleblangley.haikalat.subsystems.render3d.ShadowSettings} and are
 * published as part of a Render3D feature request, not mutated per frame.</p>
 *
 * @param enabled  whether the cloud mask may attenuate the selected light
 * @param coverage normalized sky coverage in {@code [0, 1]}; larger is denser
 * @param softness normalized penumbra width in {@code [0, 1]}
 * @param scale    world-space noise frequency in {@code (0, 4]}
 * @param speed    cloud travel in world units per second in {@code [0, 20]}
 * @param strength maximum direct-light attenuation in {@code [0, 1]}
 */
public record CloudShadowSettings(boolean enabled, float coverage, float softness,
                                  float scale, float speed, float strength) {
    public static final float MAX_SCALE = 4.0f;
    public static final float MAX_SPEED = 20.0f;

    private static final CloudShadowSettings DISABLED =
            new CloudShadowSettings(false, 0.55f, 0.35f, 0.12f, 1.2f, 0.65f);

    public CloudShadowSettings {
        if (!Float.isFinite(coverage) || coverage < 0.0f || coverage > 1.0f) {
            throw new IllegalArgumentException("coverage must be finite and in [0, 1]");
        }
        if (!Float.isFinite(softness) || softness < 0.0f || softness > 1.0f) {
            throw new IllegalArgumentException("softness must be finite and in [0, 1]");
        }
        if (!Float.isFinite(scale) || scale <= 0.0f || scale > MAX_SCALE) {
            throw new IllegalArgumentException("scale must be finite and in (0, "
                    + MAX_SCALE + "]");
        }
        if (!Float.isFinite(speed) || speed < 0.0f || speed > MAX_SPEED) {
            throw new IllegalArgumentException("speed must be finite and in [0, "
                    + MAX_SPEED + "]");
        }
        if (!Float.isFinite(strength) || strength < 0.0f || strength > 1.0f) {
            throw new IllegalArgumentException("strength must be finite and in [0, 1]");
        }
    }

    /** Default-off configuration; changing only {@code enabled} keeps the shared tuning. */
    public static CloudShadowSettings disabled() {
        return DISABLED;
    }

    /** True only when the enabled flag and a non-zero strength can change the image. */
    public boolean active() {
        return enabled && strength > 0.0f;
    }

    public CloudShadowSettings withEnabled(boolean value) {
        return new CloudShadowSettings(value, coverage, softness, scale, speed, strength);
    }
}
