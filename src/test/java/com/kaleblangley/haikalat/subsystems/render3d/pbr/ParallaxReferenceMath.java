package com.kaleblangley.haikalat.subsystems.render3d.pbr;

/**
 * Independent double-precision oracle for the bounded tangent-space parallax
 * occlusion mapping in {@code pbr-forward.frag}.
 *
 * <p>This test-source class re-derives the algorithm from the documented
 * material contract and the published parallax-occlusion formulation; it never
 * calls production helpers, production settings or the shader source. The
 * real-GL acceptance test uses it as a per-pixel UV oracle, and
 * {@link ParallaxReferenceMathTest} locks fixed hand-computable samples.</p>
 *
 * <p>Coordinates are UV units, angles are degrees and distances are world
 * units, matching the shader uniforms. A {@link HeightSampler} owns texture
 * filtering and wrap behaviour, exactly like the bound GL sampler; the oracle
 * only applies the material channel inversion and the {@code [0,1]} clamp that
 * the shader performs after sampling.</p>
 */
public final class ParallaxReferenceMath {
    /** Compile-time march cap of the shader loop. Kept literal and independent. */
    public static final int MAXIMUM_STEPS = 128;
    /** Tangent-space lateral slope cap of the shader. */
    public static final double MAXIMUM_SLOPE = 8.0;
    /** Smallest tangent-space view depth that participates in the march. */
    public static final double MINIMUM_VIEW_Z = 1.0e-4;
    /** Span floor that keeps a degenerate fade window out of a division by zero. */
    public static final double MINIMUM_FADE_SPAN = 1.0e-4;

    private ParallaxReferenceMath() {
    }

    /** Scalar height input; returns un-clamped texel height in {@code [0,1]}. */
    @FunctionalInterface
    public interface HeightSampler {
        double height(double u, double v);
    }

    /**
     * Result of one march: the applied UV, the selected layer count, the number
     * of executed steps, the combined fade weight and whether a layer pair
     * bracketed the surface (the interpolation was applied).
     */
    public record Result(double u, double v, int layerCount, int steps, double weight,
                         boolean hit) {
    }

    /** A height input that is zero everywhere (a flat surface). */
    public static HeightSampler constantZeroHeight() {
        return (u, v) -> 0.0;
    }

    /** A height input that is one everywhere (the maximum possible relief). */
    public static HeightSampler constantOneHeight() {
        return (u, v) -> 1.0;
    }

    /**
     * Smoothstep fade used by both parallax windows: one at or below
     * {@code start}, zero at or above {@code end}, cubic in between.
     */
    public static double fade(double value, double start, double end) {
        double span = Math.max(end - start, MINIMUM_FADE_SPAN);
        double ramp = clamp((value - start) / span, 0.0, 1.0);
        return 1.0 - ramp * ramp * (3.0 - 2.0 * ramp);
    }

    /**
     * Combined angular and distance weight. The angle is measured from the
     * surface normal in degrees (0 head-on, 90 grazing); the distance is the
     * camera distance in world units.
     */
    public static double weight(double nDotV, double viewDistance,
                                double fadeStartDegrees, double fadeEndDegrees,
                                double fadeStartDistance, double maxDistance) {
        double angle = Math.toDegrees(Math.acos(clamp(nDotV, 0.0, 1.0)));
        return clamp(fade(angle, fadeStartDegrees, fadeEndDegrees)
                * fade(viewDistance, fadeStartDistance, maxDistance), 0.0, 1.0);
    }

    /**
     * Performs the layered march for one fragment.
     *
     * @param u                  interpolated UV x
     * @param v                  interpolated UV y
     * @param viewX              view direction dotted with the tangent axis
     * @param viewY              view direction dotted with the bitangent axis
     * @param viewZ              view direction dotted with the geometric normal
     * @param heightScale        UV-space height, as authored
     * @param minSteps           near-normal layer count
     * @param maxSteps           grazing layer count
     * @param angleWeight        angular fade in {@code [0,1]}
     * @param distanceWeight     distance fade in {@code [0,1]}
     * @param flipHeight         invert the height ({@code 1 - h})
     * @param height             scalar height sampler
     * @return the applied UV and march diagnostics; invalid input returns the
     *         original UV with zero steps
     */
    public static Result march(double u, double v, double viewX, double viewY, double viewZ,
                               double heightScale, int minSteps, int maxSteps,
                               double angleWeight, double distanceWeight,
                               boolean flipHeight, HeightSampler height) {
        Result passThrough = new Result(u, v, 0, 0, 0.0, false);
        if (!(heightScale > 0.0) || !(heightScale <= 1.0)) return passThrough;
        double combined = clamp(angleWeight * distanceWeight, 0.0, 1.0);
        if (!(combined > 0.0)) return passThrough;
        if (!(viewZ > MINIMUM_VIEW_Z)) return passThrough;
        int minimumSteps = clamp(minSteps, 1, MAXIMUM_STEPS);
        int maximumSteps = clamp(maxSteps, minimumSteps, MAXIMUM_STEPS);
        double layers = maximumSteps + (minimumSteps - maximumSteps) * clamp(viewZ, 0.0, 1.0);
        int layerCount = clamp((int) Math.ceil(layers), minimumSteps, maximumSteps);
        double slopeX = viewX / Math.max(viewZ, MINIMUM_VIEW_Z);
        double slopeY = viewY / Math.max(viewZ, MINIMUM_VIEW_Z);
        double slopeLength = Math.hypot(slopeX, slopeY);
        if (slopeLength > MAXIMUM_SLOPE) {
            double factor = MAXIMUM_SLOPE / slopeLength;
            slopeX *= factor;
            slopeY *= factor;
        }
        double stepU = slopeX * heightScale * combined / layerCount;
        double stepV = slopeY * heightScale * combined / layerCount;
        double layerDepth = 1.0 / layerCount;
        double currentU = u;
        double currentV = v;
        double currentDepth = 0.0;
        double currentHeight = sample(height, currentU, currentV, flipHeight);
        double previousU = currentU;
        double previousV = currentV;
        double previousDepth = 0.0;
        double previousHeight = currentHeight;
        int steps = 0;
        for (int i = 0; i < MAXIMUM_STEPS; i++) {
            if (i >= layerCount || !(currentDepth < currentHeight)) break;
            previousU = currentU;
            previousV = currentV;
            previousDepth = currentDepth;
            previousHeight = currentHeight;
            currentU -= stepU;
            currentV -= stepV;
            currentDepth += layerDepth;
            currentHeight = sample(height, currentU, currentV, flipHeight);
            steps = i + 1;
        }
        boolean hit = steps > 0 && previousDepth < previousHeight
                && currentHeight < currentDepth;
        if (!hit) {
            return new Result(currentU, currentV, layerCount, steps, combined, false);
        }
        double previousError = previousDepth - previousHeight;
        double currentError = currentDepth - currentHeight;
        double denominator = previousError - currentError;
        double blend = denominator < -1.0e-6
                ? clamp(previousError / denominator, 0.0, 1.0) : 0.0;
        return new Result(mix(previousU, currentU, blend), mix(previousV, currentV, blend),
                layerCount, steps, combined, true);
    }

    private static double sample(HeightSampler height, double u, double v, boolean flipHeight) {
        double value = height.height(u, v);
        double bounded = clamp(value, 0.0, 1.0);
        return clamp(flipHeight ? 1.0 - bounded : bounded, 0.0, 1.0);
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
