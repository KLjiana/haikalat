package com.kaleblangley.haikalat.core.curve;

final class CurveMath {
    private CurveMath() {
    }

    static float requireNormalizedTime(float value) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException("normalized time must be finite and within [0, 1]");
        }
        return value;
    }

    static float requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value;
    }
}
