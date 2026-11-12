package com.kaleblangley.haikalat.subsystems.postprocess.ssgi;

import com.kaleblangley.haikalat.subsystems.postprocess.SsgiSettings;

/**
 * Independent single-precision-free reference for the temporal SSGI stage.
 *
 * <p>These helpers evaluate only the documented reprojection, rejection,
 * neighbourhood-clamp, blend and convergence equations; they never call
 * {@code SsgiPass}, {@code SsgiTemporalStorage} or a production helper. The JVM
 * tests use them as the expected-value oracle and the real-GL test compares the
 * accumulated image against {@link #temporalPrediction} and
 * {@link #rotationAverage} on the same synthetic scene.</p>
 */
public final class SsgiTemporalReference {
    /** Mirrors the shader's per-frame rotation step. */
    public static final double GOLDEN_RATIO = 0.61803398875;

    private SsgiTemporalReference() {
    }

    /** Deterministic per-frame sequence rotation for a zero-based frame index. */
    public static double rotationForFrame(int frameIndex) {
        if (frameIndex < 0) {
            throw new IllegalArgumentException("frame index must be non-negative");
        }
        return fractional(frameIndex * GOLDEN_RATIO);
    }

    /** Reprojected previous raster UV under the stable-raster velocity contract. */
    public static double[] previousUv(double u, double v, double velocityU, double velocityV) {
        return new double[]{u + velocityU, v + velocityV};
    }

    /** Reprojection distance in output pixels. */
    public static double reprojectionPixels(double u, double v, double previousU,
                                            double previousV, int width, int height) {
        double dx = (previousU - u) * width;
        double dy = (previousV - v) * height;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Relative previous-depth acceptance window: {@code max(1e-4,
     * rejection * max(abs(expected), 1))}.
     */
    public static double depthTolerance(double rejection, double expectedPreviousDepth) {
        return Math.max(1.0e-4, rejection * Math.max(Math.abs(expectedPreviousDepth), 1.0));
    }

    /** Per-pixel acceptance predicate for one history tap. */
    public static boolean accepts(double velocityU, double velocityV, double validity,
                                  double expectedPreviousDepth, double historyDepth,
                                  double historyOctX, double historyOctY,
                                  double currentOctX, double currentOctY,
                                  SsgiSettings settings, int width, int height,
                                  double u, double v) {
        if (validity < 0.5) return false;
        if (!Double.isFinite(velocityU) || !Double.isFinite(velocityV)) return false;
        if (Math.abs(velocityU) >= 4.0 || Math.abs(velocityV) >= 4.0) return false;
        if (!(expectedPreviousDepth > 0.0) || expectedPreviousDepth >= 1.0e12) return false;
        double[] previous = previousUv(u, v, velocityU, velocityV);
        if (previous[0] < 0.0 || previous[0] > 1.0
                || previous[1] < 0.0 || previous[1] > 1.0) {
            return false;
        }
        if (reprojectionPixels(u, v, previous[0], previous[1], width, height)
                > settings.temporalVelocityRejection()) {
            return false;
        }
        if (!(historyDepth > 0.0)) return false;
        if (Math.abs(historyDepth - expectedPreviousDepth)
                > depthTolerance(settings.temporalDepthRejection(), expectedPreviousDepth)) {
            return false;
        }
        double[] historyNormal = SsgiIndirectReference.octDecode(historyOctX, historyOctY);
        double[] currentNormal = SsgiIndirectReference.octDecode(currentOctX, currentOctY);
        double dot = historyNormal[0] * currentNormal[0]
                + historyNormal[1] * currentNormal[1]
                + historyNormal[2] * currentNormal[2];
        return dot >= settings.temporalNormalRejection();
    }

    /**
     * Warm-up exponential history weight. The first retained sample is
     * current-only; later samples ramp {@code (n-1)/n} and are capped by both the
     * retained-sample horizon and the configured blend.
     */
    public static double historyWeight(double temporalBlend, int historyFrames,
                                       int accumulatedSamples) {
        if (temporalBlend <= 0.0 || accumulatedSamples <= 1) return 0.0;
        int horizon = Math.min(accumulatedSamples, historyFrames);
        return Math.min(temporalBlend, (horizon - 1.0) / horizon);
    }

    /** One exponential blend step: {@code history * w + spatial * (1 - w)}. */
    public static double[] blend(double[] history, double[] spatial, double weight) {
        return new double[]{
                history[0] * weight + spatial[0] * (1.0 - weight),
                history[1] * weight + spatial[1] * (1.0 - weight),
                history[2] * weight + spatial[2] * (1.0 - weight)};
    }

    /** Depth-matched 3x3 neighbourhood clamp. */
    public static double[] clampNeighbourhood(double[] history, double[] minimum,
                                              double[] maximum) {
        return new double[]{
                Math.max(minimum[0], Math.min(maximum[0], history[0])),
                Math.max(minimum[1], Math.min(maximum[1], history[1])),
                Math.max(minimum[2], Math.min(maximum[2], history[2]))};
    }

    /**
     * Added spatial GI for one frame, i.e. the composited reference minus the
     * receiver base colour.
     */
    public static double[] spatialAdded(SsgiIndirectReference.Scene scene,
                                        SsgiIndirectReference.Camera camera,
                                        SsgiSettings settings,
                                        SsgiIndirectReference.Environment environment,
                                        int pixelX, int pixelY, int sampleCount,
                                        double frameRotation) {
        double[] composited = SsgiIndirectReference.renderPixel(scene, camera, settings,
                environment, pixelX, pixelY, sampleCount, frameRotation);
        int pixel = pixelY * scene.width() + pixelX;
        return new double[]{
                composited[0] - scene.colorRgb()[pixel * 3],
                composited[1] - scene.colorRgb()[pixel * 3 + 1],
                composited[2] - scene.colorRgb()[pixel * 3 + 2]};
    }

    /**
     * Frame-by-frame temporal prediction with no rejection or clamping.
     *
     * @return the accumulated added GI after {@code frames} successful frames
     */
    public static double[] temporalPrediction(SsgiIndirectReference.Scene scene,
                                              SsgiIndirectReference.Camera camera,
                                              SsgiSettings settings,
                                              SsgiIndirectReference.Environment environment,
                                              int pixelX, int pixelY, int frames) {
        if (frames <= 0) throw new IllegalArgumentException("frames must be positive");
        double[] accumulated = spatialAdded(scene, camera, settings, environment, pixelX, pixelY,
                settings.sampleCount(), rotationForFrame(0));
        for (int frame = 1; frame < frames; frame++) {
            double[] spatial = spatialAdded(scene, camera, settings, environment, pixelX,
                    pixelY, settings.sampleCount(), rotationForFrame(frame));
            double weight = historyWeight(settings.temporalBlend(), settings.historyFrames(),
                    frame + 1);
            accumulated = blend(accumulated, spatial, weight);
        }
        return accumulated;
    }

    /**
     * Rotation-averaged added GI, the high-sample reference the temporal
     * accumulator converges to when every frame is accepted.
     */
    public static double[] rotationAverage(SsgiIndirectReference.Scene scene,
                                           SsgiIndirectReference.Camera camera,
                                           SsgiSettings settings,
                                           SsgiIndirectReference.Environment environment,
                                           int pixelX, int pixelY, int rotations) {
        if (rotations <= 0) throw new IllegalArgumentException("rotations must be positive");
        double[] sum = new double[3];
        for (int rotation = 0; rotation < rotations; rotation++) {
            double[] spatial = spatialAdded(scene, camera, settings, environment, pixelX,
                    pixelY, settings.sampleCount(), (double) rotation / rotations);
            sum[0] += spatial[0];
            sum[1] += spatial[1];
            sum[2] += spatial[2];
        }
        return new double[]{sum[0] / rotations, sum[1] / rotations, sum[2] / rotations};
    }

    /** Convenience: composited colour from a base and an added GI vector. */
    public static double[] composite(double[] base, double[] added) {
        return new double[]{base[0] + added[0], base[1] + added[1], base[2] + added[2]};
    }

    public static double[] sceneBase(SsgiIndirectReference.Scene scene, int x, int y) {
        int pixel = y * scene.width() + x;
        return new double[]{scene.colorRgb()[pixel * 3], scene.colorRgb()[pixel * 3 + 1],
                scene.colorRgb()[pixel * 3 + 2]};
    }

    private static double fractional(double value) {
        return value - Math.floor(value);
    }
}
