package com.kaleblangley.haikalat.subsystems.render3d;

/**
 * Validated runtime parameters for the native TAA resolve.  Construction
 * rejects nonsensical combinations instead of letting the shader clamp them
 * silently.  Units: depths are world units in the current view space.
 */
public record TaaSettings(
        float historyWeight,
        float depthAbsoluteTolerance,
        float depthRelativeTolerance,
        float reactiveStrength,
        boolean varianceClipping,
        int neighborhoodRadius) {
    public static final float DEFAULT_HISTORY_WEIGHT = 0.90f;
    public static final float DEFAULT_DEPTH_ABSOLUTE_TOLERANCE = 0.01f;
    public static final float DEFAULT_DEPTH_RELATIVE_TOLERANCE = 0.01f;

    public TaaSettings {
        if (!Float.isFinite(historyWeight) || historyWeight < 0.0f || historyWeight >= 1.0f) {
            throw new IllegalArgumentException("TAA history weight must be in [0, 1)");
        }
        if (!Float.isFinite(depthAbsoluteTolerance) || depthAbsoluteTolerance < 0.0f) {
            throw new IllegalArgumentException("TAA absolute depth tolerance must be finite and non-negative");
        }
        if (!Float.isFinite(depthRelativeTolerance) || depthRelativeTolerance < 0.0f) {
            throw new IllegalArgumentException("TAA relative depth tolerance must be finite and non-negative");
        }
        if (depthAbsoluteTolerance == 0.0f && depthRelativeTolerance == 0.0f) {
            throw new IllegalArgumentException(
                    "TAA depth validation cannot disable both absolute and relative tolerances");
        }
        if (!Float.isFinite(reactiveStrength) || reactiveStrength < 0.0f || reactiveStrength > 1.0f) {
            throw new IllegalArgumentException("TAA reactive strength must be in [0, 1]");
        }
        if (neighborhoodRadius < 1 || neighborhoodRadius > 2) {
            throw new IllegalArgumentException("TAA neighborhood radius must be 1 or 2");
        }
    }

    public static TaaSettings defaults() {
        return new TaaSettings(DEFAULT_HISTORY_WEIGHT, DEFAULT_DEPTH_ABSOLUTE_TOLERANCE,
                DEFAULT_DEPTH_RELATIVE_TOLERANCE, 1.0f, true, 1);
    }

    public TaaSettings withHistoryWeight(float value) {
        return new TaaSettings(value, depthAbsoluteTolerance, depthRelativeTolerance,
                reactiveStrength, varianceClipping, neighborhoodRadius);
    }

    /** Absolute plus relative depth acceptance window for a given expected depth. */
    public float depthTolerance(float expectedDepth) {
        return Math.max(depthAbsoluteTolerance,
                depthRelativeTolerance * Math.abs(expectedDepth));
    }
}
