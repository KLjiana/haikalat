package com.kaleblangley.haikalat.core.curve;

/** CSS-style cubic Bezier easing with fixed endpoints {@code (0,0)} and {@code (1,1)}. */
public final class CubicBezierEasing implements Curve1f {
    private static final int NEWTON_ITERATIONS = 8;
    private static final int BISECTION_ITERATIONS = 24;
    private static final double DERIVATIVE_EPSILON = 1.0e-7;

    private final float x1;
    private final float y1;
    private final float x2;
    private final float y2;

    public CubicBezierEasing(float x1, float y1, float x2, float y2) {
        this.x1 = requireUnitControl(x1, "x1");
        this.y1 = CurveMath.requireFinite(y1, "y1");
        this.x2 = requireUnitControl(x2, "x2");
        this.y2 = CurveMath.requireFinite(y2, "y2");
    }

    @Override
    public float sample(float normalizedTime) {
        float time = CurveMath.requireNormalizedTime(normalizedTime);
        if (time == 0.0f || time == 1.0f) return time;

        double parameter = time;
        boolean useBisection = false;
        for (int iteration = 0; iteration < NEWTON_ITERATIONS; iteration++) {
            double error = coordinate(parameter, x1, x2) - time;
            if (Math.abs(error) <= 1.0e-7) break;
            double derivative = derivative(parameter, x1, x2);
            if (Math.abs(derivative) < DERIVATIVE_EPSILON) {
                useBisection = true;
                break;
            }
            double next = parameter - error / derivative;
            if (next <= 0.0 || next >= 1.0 || !Double.isFinite(next)) {
                useBisection = true;
                break;
            }
            parameter = next;
        }
        if (useBisection || Math.abs(coordinate(parameter, x1, x2) - time) > 1.0e-6) {
            double lower = 0.0;
            double upper = 1.0;
            for (int iteration = 0; iteration < BISECTION_ITERATIONS; iteration++) {
                parameter = (lower + upper) * 0.5;
                if (coordinate(parameter, x1, x2) < time) lower = parameter;
                else upper = parameter;
            }
            parameter = (lower + upper) * 0.5;
        }
        float result = (float) coordinate(parameter, y1, y2);
        if (!Float.isFinite(result)) {
            throw new IllegalStateException("cubic Bezier produced a non-finite value");
        }
        return result;
    }

    public float x1() { return x1; }
    public float y1() { return y1; }
    public float x2() { return x2; }
    public float y2() { return y2; }

    private static float requireUnitControl(float value, String name) {
        CurveMath.requireFinite(value, name);
        if (value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be within [0, 1]");
        }
        return value;
    }

    private static double coordinate(double parameter, double first, double second) {
        double inverse = 1.0 - parameter;
        return 3.0 * inverse * inverse * parameter * first
                + 3.0 * inverse * parameter * parameter * second
                + parameter * parameter * parameter;
    }

    private static double derivative(double parameter, double first, double second) {
        double inverse = 1.0 - parameter;
        return 3.0 * inverse * inverse * first
                + 6.0 * inverse * parameter * (second - first)
                + 3.0 * parameter * parameter * (1.0 - second);
    }
}
