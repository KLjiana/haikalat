package com.kaleblangley.haikalat.subsystems.postprocess.depthoffield;

import com.kaleblangley.haikalat.subsystems.postprocess.DepthOfFieldSettings;

import java.util.Objects;

/**
 * Pure-JVM reference for the thin-lens circle-of-confusion model used by the
 * depth-of-field shader.
 *
 * <p>The shader and this helper both use the configured full-frame-equivalent
 * focal length and a fixed 36 mm sensor width.  Distances are world units
 * treated as metres.  The result is a radius in output pixels, already clamped
 * to {@link DepthOfFieldSettings#maxCocPixels()}.</p>
 */
final class DepthOfFieldMath {
    static final double SENSOR_WIDTH_METERS = 0.036;
    private static final double MIN_DISTANCE_METERS = 0.001;

    private DepthOfFieldMath() {
    }

    /**
     * @param viewDistance  non-negative camera-space distance in world units
     * @param settings      active depth-of-field settings
     * @param viewportWidth output width in pixels
     * @return clamped CoC radius in output pixels
     */
    static float cocRadiusPixels(float viewDistance, DepthOfFieldSettings settings,
                                 int viewportWidth) {
        Objects.requireNonNull(settings, "settings");
        if (!Float.isFinite(viewDistance) || viewDistance < 0.0f) {
            throw new IllegalArgumentException("viewDistance must be finite and non-negative");
        }
        if (viewportWidth <= 0) {
            throw new IllegalArgumentException("viewportWidth must be positive");
        }
        if (!settings.active()) {
            return 0.0f;
        }
        double focalLengthMeters = settings.focalLengthMm() / 1000.0;
        double distance = Math.max(viewDistance, MIN_DISTANCE_METERS);
        double focus = settings.focusDistance();
        double denominator = settings.aperture() * distance
                * Math.max(focus - focalLengthMeters, MIN_DISTANCE_METERS);
        if (denominator <= 0.0) {
            return settings.maxCocPixels();
        }
        double diameterMeters = focalLengthMeters * focalLengthMeters
                * Math.abs(distance - focus) / denominator;
        double radiusPixels = 0.5 * diameterMeters * (viewportWidth / SENSOR_WIDTH_METERS);
        if (!Double.isFinite(radiusPixels)) {
            return settings.maxCocPixels();
        }
        return (float) Math.min(settings.maxCocPixels(), Math.max(0.0, radiusPixels));
    }
}
