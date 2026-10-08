package com.kaleblangley.haikalat.subsystems.render3d;

/** Per-light volume contribution; shadow sampling only consumes an already allocated slot. */
public record LightVolumeHints(float scatteringIntensity, boolean useAllocatedShadow,
                               boolean temporalAccumulation) {
    public static final LightVolumeHints DEFAULT = new LightVolumeHints(1,true,true);
    public static final LightVolumeHints DISABLED = new LightVolumeHints(0,true,true);
    public LightVolumeHints {
        if (!Float.isFinite(scatteringIntensity) || scatteringIntensity < 0) {
            throw new IllegalArgumentException("scatteringIntensity must be finite and non-negative");
        }
    }
}
