package com.kaleblangley.haikalat.subsystems.ui.animation;

/** Deterministic easing curves for retained UI transitions. */
public enum UiEasing {
    LINEAR {
        @Override public float apply(float time) { return checked(time); }
    },
    EASE_IN_CUBIC {
        @Override public float apply(float time) {
            float t = checked(time);
            return t * t * t;
        }
    },
    EASE_OUT_CUBIC {
        @Override public float apply(float time) {
            float t = 1.0f - checked(time);
            return 1.0f - t * t * t;
        }
    },
    EASE_IN_OUT_CUBIC {
        @Override public float apply(float time) {
            float t = checked(time);
            return t < 0.5f ? 4.0f * t * t * t
                    : 1.0f - (float) Math.pow(-2.0f * t + 2.0f, 3.0) * 0.5f;
        }
    },
    /** Damped spring response; callers clamp property values to their valid range. */
    SPRING {
        @Override public float apply(float time) {
            float t = checked(time);
            if (t == 0.0f || t == 1.0f) return t;
            return 1.0f - (float) (Math.exp(-7.0 * t)
                    * (Math.cos(11.0 * t) + 0.45 * Math.sin(11.0 * t)));
        }
    };

    public abstract float apply(float time);

    static float checked(float time) {
        if (!Float.isFinite(time) || time < 0.0f || time > 1.0f) {
            throw new IllegalArgumentException("easing time must be within [0, 1]");
        }
        return time;
    }
}
