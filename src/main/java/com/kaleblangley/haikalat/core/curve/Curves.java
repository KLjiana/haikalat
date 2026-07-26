package com.kaleblangley.haikalat.core.curve;

/** Common allocation-free easing curves and curve factories. */
public final class Curves {
    public static final Curve1f LINEAR = CurveMath::requireNormalizedTime;
    public static final Curve1f EASE_IN_CUBIC = time -> {
        float value = CurveMath.requireNormalizedTime(time);
        return value * value * value;
    };
    public static final Curve1f EASE_OUT_CUBIC = time -> {
        float value = 1.0f - CurveMath.requireNormalizedTime(time);
        return 1.0f - value * value * value;
    };
    public static final Curve1f EASE_IN_OUT_CUBIC = time -> {
        float value = CurveMath.requireNormalizedTime(time);
        if (value < 0.5f) return 4.0f * value * value * value;
        float inverse = -2.0f * value + 2.0f;
        return 1.0f - inverse * inverse * inverse * 0.5f;
    };

    private Curves() {
    }

    /**
     * Creates a deterministic damped spring. The response may overshoot; property consumers
     * remain responsible for applying their own range constraints.
     */
    public static Curve1f spring(float damping, float frequency) {
        CurveMath.requireFinite(damping, "damping");
        CurveMath.requireFinite(frequency, "frequency");
        if (damping < 0.0f) throw new IllegalArgumentException("damping must be non-negative");
        if (frequency <= 0.0f) throw new IllegalArgumentException("frequency must be positive");
        return time -> {
            float value = CurveMath.requireNormalizedTime(time);
            if (value == 0.0f || value == 1.0f) return value;
            return 1.0f - (float) (Math.exp(-damping * value)
                    * (Math.cos(frequency * value) + 0.45 * Math.sin(frequency * value)));
        };
    }

    public static Curve1f cubicBezier(float x1, float y1, float x2, float y2) {
        return new CubicBezierEasing(x1, y1, x2, y2);
    }
}
