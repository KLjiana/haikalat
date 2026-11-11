package com.kaleblangley.haikalat.subsystems.postprocess.motionblur;

import com.kaleblangley.haikalat.subsystems.postprocess.MotionBlurSettings;

import java.util.Objects;

/**
 * Pure-JVM reference for the bounded screen-space motion reconstruction used by
 * the motion-blur shader.
 *
 * <p>The shared velocity buffer stores {@code previousStableUv - currentStableUv}
 * in UV units.  Its value is already jitter-free, while colour, velocity and
 * validity are all sampled at the same current raster coordinate, so no
 * explicit jitter correction is needed.  Offsets are scaled by the shutter
 * fraction, clamped to the configured pixel radius, and collapsed to zero below
 * half a pixel so a static camera is byte-identical to the input.</p>
 */
final class MotionBlurMath {
    /** Sub-pixel motion below this threshold is treated as static. */
    static final float MINIMUM_MOTION_PIXELS = 0.5f;
    /** Matches the shader's defensive upper bound on a finite velocity. */
    static final float MAXIMUM_ABSOLUTE_VELOCITY = 1.0e4f;

    private MotionBlurMath() {
    }

    /** Pixel-space reconstruction vector from the current toward the previous position. */
    record ScreenMotion(float uPixels, float vPixels) {
        static final ScreenMotion ZERO = new ScreenMotion(0.0f, 0.0f);

        float lengthPixels() {
            return (float) Math.hypot(uPixels, vPixels);
        }

        ScreenMotion scaled(float factor) {
            return new ScreenMotion(uPixels * factor, vPixels * factor);
        }
    }

    /**
     * @param velocityUvX   {@code previousStableUv.x - currentStableUv.x}
     * @param velocityUvY   {@code previousStableUv.y - currentStableUv.y}
     * @param width         output width in pixels
     * @param height        output height in pixels
     * @param settings      motion-blur settings; inactive settings return zero
     * @return shutter-scaled, radius-clamped pixel motion
     */
    static ScreenMotion offsetPixels(float velocityUvX, float velocityUvY,
                                     int width, int height, MotionBlurSettings settings) {
        Objects.requireNonNull(settings, "settings");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("motion-blur extent must be positive");
        }
        if (!settings.active()) {
            return ScreenMotion.ZERO;
        }
        // Invalid velocity is the documented velocity-less fallback: the pass
        // leaves the pixel unblurred instead of propagating NaN/Inf.
        if (!Float.isFinite(velocityUvX) || !Float.isFinite(velocityUvY)
                || Math.abs(velocityUvX) > MAXIMUM_ABSOLUTE_VELOCITY
                || Math.abs(velocityUvY) > MAXIMUM_ABSOLUTE_VELOCITY) {
            return ScreenMotion.ZERO;
        }
        double u = (double) velocityUvX * width * settings.shutterFraction();
        double v = (double) velocityUvY * height * settings.shutterFraction();
        double length = Math.hypot(u, v);
        if (!(length >= MINIMUM_MOTION_PIXELS) || !Double.isFinite(length)) {
            return ScreenMotion.ZERO;
        }
        double maximum = settings.maxRadiusPixels();
        if (length > maximum) {
            double scale = maximum / length;
            u *= scale;
            v *= scale;
        }
        return new ScreenMotion((float) u, (float) v);
    }

    /** The fraction of the reconstruction segment used by a bounded gather tap. */
    static float tapFraction(int tapIndex, int tapCount) {
        if (tapCount <= 0) {
            throw new IllegalArgumentException("tap count must be positive");
        }
        if (tapIndex < 0 || tapIndex >= tapCount) {
            throw new IllegalArgumentException("tap index out of range");
        }
        return (tapIndex + 0.5f) / tapCount;
    }

    /** Pixel offset of one backward-gather tap from the current pixel. */
    static ScreenMotion tapOffset(ScreenMotion motion, int tapIndex, int tapCount) {
        Objects.requireNonNull(motion, "motion");
        return motion.scaled(tapFraction(tapIndex, tapCount));
    }
}
