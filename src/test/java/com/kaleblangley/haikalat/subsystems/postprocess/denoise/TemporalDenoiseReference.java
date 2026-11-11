package com.kaleblangley.haikalat.subsystems.postprocess.denoise;

import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector4d;

/**
 * Independent double-precision reference for the two equations that are easy
 * to get subtly wrong: previous-view-projection reprojection and the
 * depth-matched neighbourhood / moment clamp.
 *
 * <p>This class deliberately does not call production helpers.  It is used by
 * fixed-input JVM cases and by the GL convergence case to derive the expected
 * high-sample result.</p>
 */
final class TemporalDenoiseReference {
    private TemporalDenoiseReference() {
    }

    /** Reconstructs a world position from a raster UV and device depth. */
    static Vector3d reconstructWorld(Matrix4d inverseProjection, Matrix4d inverseView,
                                     double u, double v, double deviceDepth) {
        Vector4d clip = new Vector4d(u * 2.0 - 1.0, v * 2.0 - 1.0,
                deviceDepth * 2.0 - 1.0, 1.0);
        inverseProjection.transform(clip);
        if (Math.abs(clip.w) <= 1.0e-12) return null;
        Vector3d view = new Vector3d(clip.x / clip.w, clip.y / clip.w,
                clip.z / clip.w);
        Vector4d world = new Vector4d(view, 1.0);
        inverseView.transform(world);
        if (Math.abs(world.w) <= 1.0e-12) return null;
        return new Vector3d(world.x / world.w, world.y / world.w, world.z / world.w);
    }

    /** Projects a world position with a previous-frame view-projection. */
    static double[] previousUv(Matrix4d previousViewProjection, Vector3d world) {
        Vector4d clip = new Vector4d(world, 1.0);
        previousViewProjection.transform(clip);
        if (!(clip.w > 1.0e-12)) return null;
        return new double[]{clip.x / clip.w * 0.5 + 0.5,
                clip.y / clip.w * 0.5 + 0.5};
    }

    /** Expected positive previous view depth from a previous clip point. */
    static double previousViewDepth(Matrix4d previousInverseProjection, Vector4d previousClip) {
        Vector4d view = new Vector4d(previousClip);
        previousInverseProjection.transform(view);
        if (Math.abs(view.w) <= 1.0e-12) return -1.0;
        return Math.abs(view.z / view.w);
    }

    /** True when the UV is inside the raster and inside the pixel budget. */
    static boolean acceptsReprojection(double[] previousUv, double u, double v,
                                       double extentWidth, double extentHeight,
                                       double maxReprojectionPixels) {
        if (previousUv == null) return false;
        if (previousUv[0] < 0.0 || previousUv[0] > 1.0
                || previousUv[1] < 0.0 || previousUv[1] > 1.0) {
            return false;
        }
        double dx = (previousUv[0] - u) * extentWidth;
        double dy = (previousUv[1] - v) * extentHeight;
        return Math.sqrt(dx * dx + dy * dy) <= maxReprojectionPixels;
    }

    /** Relative depth window with the one-metre absolute floor and epsilon. */
    static double depthTolerance(double expectedDepth, double relativeThreshold) {
        return Math.max(1.0e-4,
                relativeThreshold * Math.max(Math.abs(expectedDepth), 1.0));
    }

    /** Depth and normal acceptance of one history sample. */
    static boolean acceptsHistory(double expectedDepth, double historyDepth,
                                  double relativeThreshold, Vector3d currentNormal,
                                  Vector3d historyNormal, double normalThreshold) {
        if (!(historyDepth > 0.0)) return false;
        if (Math.abs(historyDepth - expectedDepth)
                > depthTolerance(expectedDepth, relativeThreshold)) {
            return false;
        }
        if (currentNormal == null || historyNormal == null) return false;
        return currentNormal.dot(historyNormal) >= normalThreshold;
    }

    /** Relative luminance jump used by the reactive/shock reset. */
    static boolean brightnessShock(double currentLuminance, double historyLuminance,
                                   double reactiveThreshold) {
        double scale = Math.max(Math.max(Math.abs(currentLuminance),
                Math.abs(historyLuminance)), 1.0e-6);
        return Math.abs(currentLuminance - historyLuminance) / scale > reactiveThreshold;
    }

    /**
     * Per-channel bounds over a depth-matched square neighbourhood.
     * {@code included[x][y]} marks pixels that survive the depth test.
     */
    static double[] neighbourhoodBounds(double[][][] colours, boolean[][] included,
                                        int centerX, int centerY, int radius,
                                        double[] fallback) {
        double[] low = fallback.clone();
        double[] high = fallback.clone();
        double sum = 0.0;
        double sumSquares = 0.0;
        int count = 0;
        int width = colours.length;
        int height = colours[0].length;
        for (int y = centerY - radius; y <= centerY + radius; y++) {
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                if (x < 0 || x >= width || y < 0 || y >= height) continue;
                if (!included[x][y]) continue;
                double[] colour = colours[x][y];
                for (int channel = 0; channel < 3; channel++) {
                    low[channel] = Math.min(low[channel], colour[channel]);
                    high[channel] = Math.max(high[channel], colour[channel]);
                }
                double luminance = luminance(colour);
                sum += luminance;
                sumSquares += luminance * luminance;
                count++;
            }
        }
        if (count == 0) {
            return new double[]{low[0], low[1], low[2], high[0], high[1], high[2],
                    0.0, 0.0};
        }
        double mean = sum / count;
        double variance = Math.max(sumSquares / count - mean * mean, 0.0);
        return new double[]{low[0], low[1], low[2], high[0], high[1], high[2],
                mean, variance};
    }

    /** AABB plus luminance-moment clamp, matching the documented shader order. */
    static double[] clampHistory(double[] history, double[] low, double[] high,
                                 double mean, double variance, double sigmaFactor) {
        double[] clamped = new double[3];
        for (int channel = 0; channel < 3; channel++) {
            clamped[channel] = Math.min(Math.max(history[channel], low[channel]),
                    high[channel]);
        }
        double luminance = luminance(clamped);
        if (luminance > 1.0e-6) {
            double sigma = Math.sqrt(Math.max(variance, 0.0)) * sigmaFactor;
            double target = Math.min(Math.max(luminance, Math.max(mean - sigma, 0.0)),
                    mean + sigma);
            for (int channel = 0; channel < 3; channel++) {
                clamped[channel] *= target / luminance;
            }
        }
        for (int channel = 0; channel < 3; channel++) {
            clamped[channel] = Math.min(Math.max(clamped[channel], low[channel]),
                    high[channel]);
        }
        return clamped;
    }

    static double luminance(double[] colour) {
        return 0.2126 * colour[0] + 0.7152 * colour[1] + 0.0722 * colour[2];
    }

    static double[] rgb(double r, double g, double b) {
        return new double[]{r, g, b};
    }
}
