package com.kaleblangley.haikalat.subsystems.render3d.shadow;

/**
 * Immutable controls for the main directional light's percentage-closer soft
 * shadow (PCSS).
 *
 * <p>The effect refines the existing directional shadow map: it runs a bounded
 * blocker search, estimates a penumbra from the blocker/receiver separation and
 * a world-space light size, then filters the shadow test with a variable-radius
 * PCF disk.  It reuses the current cascade atlas and adds no texture, pass,
 * manager or factory.  Point and spot shadows keep their fixed PCF presets.</p>
 *
 * <p>Depth comparisons use the same normalized light-space window depth
 * {@code [0, 1]} as the existing directional path ({@code texture(uShadowMap,
 * uv).r}); {@code depthBias} is expressed in those units and is added to the
 * existing {@code uShadowBias} slope correction.  {@code lightWorldSize} is a
 * world-space light diameter.  The penumbra uses the normalized orthographic
 * depth ratio (the linear range cancels in the PCSS fraction) and the selected
 * cascade's world texels per unit.</p>
 *
 * <p>Every search and filter tap is clamped to the selected cascade tile and
 * the filter radius is capped by {@link #filterRadiusTexels()}, so a request
 * cannot sample across atlas tiles or exceed the declared tap budget.  The
 * default is disabled and selects the pre-existing hard/PCF path with no PCSS
 * sample and no image change.  Settings belong to
 * {@link com.kaleblangley.haikalat.subsystems.render3d.ShadowSettings} and are
 * published as part of a Render3D feature request, not mutated per frame.</p>
 *
 * @param enabled              whether the PCSS path may replace directional PCF
 * @param lightWorldSize       world-space light diameter in {@code [0, 16]}; 0 is an exact no-op
 * @param blockerRadiusTexels  blocker-search disk radius in shadow texels in {@code [1, 16]}
 * @param blockerTaps          blocker-search sample count in {@code [1, 32]}
 * @param filterRadiusTexels   maximum PCF disk radius in shadow texels in {@code [1, 16]}
 * @param filterTaps           variable-radius PCF sample count in {@code [1, 32]}
 * @param depthBias            additional normalized depth bias in {@code [0, 0.05]}
 */
public record PcssSettings(boolean enabled, float lightWorldSize, int blockerRadiusTexels,
                           int blockerTaps, int filterRadiusTexels, int filterTaps,
                           float depthBias) {
    public static final float MAX_LIGHT_WORLD_SIZE = 16.0f;
    public static final int MAX_BLOCKER_RADIUS_TEXELS = 16;
    public static final int MAX_BLOCKER_TAPS = 32;
    public static final int MAX_FILTER_RADIUS_TEXELS = 16;
    public static final int MAX_FILTER_TAPS = 32;
    public static final int MAX_TOTAL_TAPS = 48;
    public static final float MAX_DEPTH_BIAS = 0.05f;

    private static final PcssSettings DISABLED =
            new PcssSettings(false, 1.0f, 4, 16, 8, 16, 0.001f);

    public PcssSettings {
        if (!Float.isFinite(lightWorldSize) || lightWorldSize < 0.0f
                || lightWorldSize > MAX_LIGHT_WORLD_SIZE) {
            throw new IllegalArgumentException("lightWorldSize must be finite and in [0, "
                    + MAX_LIGHT_WORLD_SIZE + "]");
        }
        if (blockerRadiusTexels < 1 || blockerRadiusTexels > MAX_BLOCKER_RADIUS_TEXELS) {
            throw new IllegalArgumentException("blockerRadiusTexels must be in [1, "
                    + MAX_BLOCKER_RADIUS_TEXELS + "]");
        }
        if (blockerTaps < 1 || blockerTaps > MAX_BLOCKER_TAPS) {
            throw new IllegalArgumentException("blockerTaps must be in [1, "
                    + MAX_BLOCKER_TAPS + "]");
        }
        if (filterRadiusTexels < 1 || filterRadiusTexels > MAX_FILTER_RADIUS_TEXELS) {
            throw new IllegalArgumentException("filterRadiusTexels must be in [1, "
                    + MAX_FILTER_RADIUS_TEXELS + "]");
        }
        if (filterTaps < 1 || filterTaps > MAX_FILTER_TAPS) {
            throw new IllegalArgumentException("filterTaps must be in [1, "
                    + MAX_FILTER_TAPS + "]");
        }
        if (blockerTaps + filterTaps > MAX_TOTAL_TAPS) {
            throw new IllegalArgumentException("blockerTaps + filterTaps must be at most "
                    + MAX_TOTAL_TAPS);
        }
        if (!Float.isFinite(depthBias) || depthBias < 0.0f || depthBias > MAX_DEPTH_BIAS) {
            throw new IllegalArgumentException("depthBias must be finite and in [0, "
                    + MAX_DEPTH_BIAS + "]");
        }
    }

    /** Default-off configuration; changing only {@code enabled} keeps the shared tuning. */
    public static PcssSettings disabled() {
        return DISABLED;
    }

    /**
     * True only when the enabled flag and a non-zero light size can change the
     * image.  A zero light size is treated as an exact no-op so it selects the
     * existing hard/PCF path.
     */
    public boolean active() {
        return enabled && lightWorldSize > 0.0f;
    }

    public PcssSettings withEnabled(boolean value) {
        return new PcssSettings(value, lightWorldSize, blockerRadiusTexels, blockerTaps,
                filterRadiusTexels, filterTaps, depthBias);
    }
}
