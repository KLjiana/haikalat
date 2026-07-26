package com.kaleblangley.haikalat.subsystems.ui.animation;

import com.kaleblangley.haikalat.core.curve.Curve1f;
import com.kaleblangley.haikalat.core.curve.Curves;

/** Deterministic easing curves for retained UI transitions. */
public enum UiEasing {
    LINEAR(Curves.LINEAR),
    EASE_IN_CUBIC(Curves.EASE_IN_CUBIC),
    EASE_OUT_CUBIC(Curves.EASE_OUT_CUBIC),
    EASE_IN_OUT_CUBIC(Curves.EASE_IN_OUT_CUBIC),
    /** Damped spring response; callers clamp property values to their valid range. */
    SPRING(Curves.spring(7.0f, 11.0f));

    private final Curve1f curve;

    UiEasing(Curve1f curve) {
        this.curve = curve;
    }

    public float apply(float time) {
        return curve.sample(time);
    }
}
