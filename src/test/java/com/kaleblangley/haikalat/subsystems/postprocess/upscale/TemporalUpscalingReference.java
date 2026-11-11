package com.kaleblangley.haikalat.subsystems.postprocess.upscale;

/**
 * Independent {@code double} reference for the temporal-upscaling math.
 *
 * <p>This class deliberately re-derives the documented formulas instead of
 * calling production helpers: reprojection through a row-major previous
 * view-projection, the relative depth/exposure/reactive windows, the manual
 * bilinear resample and the bounded unsharp filter.  The accompanying test
 * compares it with hand-computed constants for fixed input so the expected
 * values are reproducible without a GPU.</p>
 */
final class TemporalUpscalingReference {
    private static final double MINIMUM_DEPTH_TOLERANCE = 1.0e-4;
    private static final double DEPTH_REJECTION_THRESHOLD = 0.02;
    private static final double EPSILON = 1.0e-12;

    private TemporalUpscalingReference() {
    }

    static double luminance(double red, double green, double blue) {
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
    }

    static double depthTolerance(double expectedDepth) {
        return Math.max(MINIMUM_DEPTH_TOLERANCE,
                DEPTH_REJECTION_THRESHOLD * Math.max(Math.abs(expectedDepth), 1.0));
    }

    static double exposureRelativeChange(double current, double previous) {
        if (!(current > 0.0) || !(previous > 0.0)) {
            throw new IllegalArgumentException("exposure values must be positive");
        }
        return Math.abs(current - previous) / Math.max(current, previous);
    }

    static boolean exposureAccepted(double current, double previous, double threshold) {
        return exposureRelativeChange(current, previous) <= threshold;
    }

    static boolean reactiveReset(double currentLuminance, double historyLuminance,
                                 double threshold) {
        if (!Double.isFinite(currentLuminance) || !Double.isFinite(historyLuminance)) {
            return true;
        }
        double scale = Math.max(Math.max(Math.abs(currentLuminance),
                Math.abs(historyLuminance)), 1.0e-6);
        return Math.abs(currentLuminance - historyLuminance) / scale > threshold;
    }

    /** Row-major 4x4 matrix multiplied by a homogeneous position. */
    static double[] transform(double[] rowMajorMatrix, double[] position) {
        requireMatrix(rowMajorMatrix);
        if (position.length != 4) throw new IllegalArgumentException("position must be vec4");
        double[] result = new double[4];
        for (int row = 0; row < 4; row++) {
            double value = 0.0;
            for (int column = 0; column < 4; column++) {
                value += rowMajorMatrix[row * 4 + column] * position[column];
            }
            result[row] = value;
        }
        return result;
    }

    /**
     * @return the perspective divide in {@code [0,1]} UV form
     */
    static double[] previousUv(double[] previousClip) {
        if (Math.abs(previousClip[3]) <= EPSILON) {
            throw new IllegalArgumentException("previous clip w is degenerate");
        }
        return new double[]{
                previousClip[0] / previousClip[3] * 0.5 + 0.5,
                previousClip[1] / previousClip[3] * 0.5 + 0.5
        };
    }

    /** @return the positive view-space depth encoded by a previous clip position */
    static double previousViewDepth(double[] previousClip, double[] previousInverseProjection) {
        double[] previousView = transform(previousInverseProjection, previousClip);
        if (Math.abs(previousView[3]) <= EPSILON) {
            throw new IllegalArgumentException("previous view w is degenerate");
        }
        return Math.abs(previousView[2] / previousView[3]);
    }

    /** @return the stable-raster displacement in output pixels */
    static double reprojectionPixels(double[] stableUv, double[] previousUv,
                                     int outputWidth, int outputHeight) {
        double dx = (previousUv[0] - stableUv[0]) * outputWidth;
        double dy = (previousUv[1] - stableUv[1]) * outputHeight;
        return Math.hypot(dx, dy);
    }

    /**
     * Manual bilinear sample of an RGBA float image with row-major texels,
     * clamped half-texel footprint exactly like the shader.
     */
    static double[] bilinearRgba(double[] rgba, int width, int height,
                                 double u, double v) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("extent must be positive");
        double texelX = u * width - 0.5;
        double texelY = v * height - 0.5;
        int originX = (int) Math.floor(texelX);
        int originY = (int) Math.floor(texelY);
        double fractionX = texelX - Math.floor(texelX);
        double fractionY = texelY - Math.floor(texelY);
        double[] result = new double[4];
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                int pixelX = clamp(originX + x, 0, width - 1);
                int pixelY = clamp(originY + y, 0, height - 1);
                double weight = (x == 0 ? 1.0 - fractionX : fractionX)
                        * (y == 0 ? 1.0 - fractionY : fractionY);
                int offset = (pixelY * width + pixelX) * 4;
                for (int channel = 0; channel < 4; channel++) {
                    result[channel] += rgba[offset + channel] * weight;
                }
            }
        }
        return result;
    }

    /**
     * Bounded local-contrast unsharp filter with the same 3x3 cross as the
     * shader.
     */
    static double[] sharpen(double[] center, double[] north, double[] south,
                            double[] east, double[] west, double amount) {
        if (!(amount > 0.0)) return center.clone();
        double[] result = new double[3];
        for (int channel = 0; channel < 3; channel++) {
            double average = (north[channel] + south[channel] + east[channel]
                    + west[channel]) * 0.25;
            double low = Math.min(Math.min(north[channel], south[channel]),
                    Math.min(east[channel], west[channel]));
            double high = Math.max(Math.max(north[channel], south[channel]),
                    Math.max(east[channel], west[channel]));
            double sharpened = center[channel]
                    + (center[channel] - average) * amount;
            result[channel] = Math.min(Math.max(sharpened,
                    Math.min(low, center[channel])), Math.max(high, center[channel]));
        }
        return result;
    }

    private static void requireMatrix(double[] matrix) {
        if (matrix.length != 16) {
            throw new IllegalArgumentException("matrix must be row-major 4x4");
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
