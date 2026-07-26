package com.kaleblangley.haikalat.core.curve;

import java.util.Arrays;
import java.util.Objects;

/** Immutable multi-key scalar property track. */
public final class FloatTrack implements Curve1f {
    private final Key[] keys;

    public FloatTrack(Key... keys) {
        Objects.requireNonNull(keys, "keys");
        if (keys.length == 0) throw new IllegalArgumentException("track requires at least one key");
        this.keys = keys.clone();
        float previous = -1.0f;
        for (int index = 0; index < this.keys.length; index++) {
            Key key = Objects.requireNonNull(this.keys[index], "keys[" + index + "]");
            if (key.time <= previous) {
                throw new IllegalArgumentException("key times must be strictly increasing");
            }
            previous = key.time;
        }
    }

    @Override
    public float sample(float normalizedTime) {
        float time = CurveMath.requireNormalizedTime(normalizedTime);
        if (time <= keys[0].time) return keys[0].value;
        int last = keys.length - 1;
        if (time >= keys[last].time) return keys[last].value;

        int low = segmentIndex(time, last);
        Key start = keys[low];
        Key end = keys[low + 1];
        float duration = end.time - start.time;
        float progress = (time - start.time) / duration;
        float result = switch (start.interpolation) {
            case STEP -> start.value;
            case LINEAR -> (float) (start.value
                    + ((double) end.value - start.value) * progress);
            case CUBIC_HERMITE -> hermite(start.value, start.outTangent * duration,
                    end.value, end.inTangent * duration, progress);
        };
        if (!Float.isFinite(result)) {
            throw new IllegalStateException("track produced a non-finite value");
        }
        return result;
    }

    public int keyCount() {
        return keys.length;
    }

    public Key key(int index) {
        return keys[index];
    }

    @Override
    public String toString() {
        return "FloatTrack" + Arrays.toString(keys);
    }

    private static float hermite(float first, float firstTangent, float second,
                                 float secondTangent, float progress) {
        float squared = progress * progress;
        float cubed = squared * progress;
        return (2.0f * cubed - 3.0f * squared + 1.0f) * first
                + (cubed - 2.0f * squared + progress) * firstTangent
                + (-2.0f * cubed + 3.0f * squared) * second
                + (cubed - squared) * secondTangent;
    }

    private int segmentIndex(float time, int last) {
        if (keys.length <= 4) {
            int index = 0;
            while (index + 1 < last && time >= keys[index + 1].time) index++;
            return index;
        }
        int low = 0;
        int high = last;
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (keys[middle].time <= time) low = middle;
            else high = middle;
        }
        return low;
    }

    public enum Interpolation {
        STEP,
        LINEAR,
        CUBIC_HERMITE
    }

    /** Tangents use value-per-normalized-time units; interpolation controls the following segment. */
    public record Key(float time, float value, float inTangent, float outTangent,
                      Interpolation interpolation) {
        public Key {
            CurveMath.requireNormalizedTime(time);
            CurveMath.requireFinite(value, "value");
            CurveMath.requireFinite(inTangent, "inTangent");
            CurveMath.requireFinite(outTangent, "outTangent");
            Objects.requireNonNull(interpolation, "interpolation");
        }

        public Key(float time, float value, Interpolation interpolation) {
            this(time, value, 0.0f, 0.0f, interpolation);
        }
    }
}
