package com.kaleblangley.haikalat.subsystems.vfx;

/** Immutable normalized UV rectangle. */
public record VfxUvRegion(float minimumU, float minimumV, float maximumU, float maximumV) {
    public static final VfxUvRegion FULL = new VfxUvRegion(0.0f, 0.0f, 1.0f, 1.0f);

    public VfxUvRegion {
        requireUnit(minimumU, "minimumU");
        requireUnit(minimumV, "minimumV");
        requireUnit(maximumU, "maximumU");
        requireUnit(maximumV, "maximumV");
        if (maximumU <= minimumU || maximumV <= minimumV) {
            throw new IllegalArgumentException("UV maximums must be greater than minimums");
        }
    }

    private static void requireUnit(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be finite and in [0, 1]");
        }
    }
}
