package com.kaleblangley.haikalat.subsystems.animation;

import java.util.Arrays;
import java.util.Objects;

/** Immutable multi-target morph-weight animation track. */
public final class MorphWeightTrack {
    private final int targetCount;
    private final Interpolation interpolation;
    private final float[] times;
    private final float[] values;
    private final ThreadLocal<float[]> sampleScratch;

    /**
     * Creates a track using glTF-compatible storage. STEP/LINEAR store one target tuple per
     * keyframe; CUBIC_SPLINE stores in-tangent, value and out-tangent tuples per keyframe.
     */
    public MorphWeightTrack(int targetCount, Interpolation interpolation,
                            float[] timesSeconds, float[] values) {
        if (targetCount <= 0 || targetCount > 8) {
            throw new IllegalArgumentException("targetCount must be in [1, 8]");
        }
        this.targetCount = targetCount;
        sampleScratch = ThreadLocal.withInitial(() -> new float[targetCount]);
        this.interpolation = Objects.requireNonNull(interpolation, "interpolation");
        this.times = Objects.requireNonNull(timesSeconds, "timesSeconds").clone();
        this.values = Objects.requireNonNull(values, "values").clone();
        if (times.length == 0) {
            throw new IllegalArgumentException("track must contain at least one keyframe");
        }
        int tuples = interpolation == Interpolation.CUBIC_SPLINE ? 3 : 1;
        if (this.values.length != Math.multiplyExact(
                Math.multiplyExact(times.length, targetCount), tuples)) {
            throw new IllegalArgumentException("value count does not match keyframes and targets");
        }
        float previous = -1.0f;
        for (int index = 0; index < times.length; index++) {
            float time = times[index];
            if (!Float.isFinite(time) || time < 0.0f || index > 0 && time <= previous) {
                throw new IllegalArgumentException(
                        "times must be finite, non-negative, and strictly increasing");
            }
            times[index] = time == 0.0f ? 0.0f : time;
            previous = time;
        }
        for (int index = 0; index < this.values.length; index++) {
            float value = this.values[index];
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("values[" + index + "] must be finite");
            }
            if (indexIsSampleValue(index, tuples)
                    && Math.abs(value) > MorphWeightBuffer.MAX_ABSOLUTE_WEIGHT) {
                throw new IllegalArgumentException("sample weight exceeds the runtime safety range");
            }
        }
    }

    public int targetCount() {
        return targetCount;
    }

    public Interpolation interpolation() {
        return interpolation;
    }

    public float durationSeconds() {
        return times[times.length - 1];
    }

    public float[] timesSeconds() {
        return times.clone();
    }

    public float[] values() {
        return values.clone();
    }

    public void sample(float timeSeconds, MorphWeightBuffer destination) {
        if (!Float.isFinite(timeSeconds) || timeSeconds < 0.0f) {
            throw new IllegalArgumentException("timeSeconds must be finite and non-negative");
        }
        MorphWeightBuffer output = Objects.requireNonNull(destination, "destination");
        if (output.targetCount() != targetCount) {
            throw new IllegalArgumentException("destination target count must be " + targetCount);
        }
        float time = Math.min(timeSeconds, durationSeconds());
        int exact = Arrays.binarySearch(times, time);
        if (exact >= 0) {
            copyValue(exact, output);
            return;
        }
        int upper = -exact - 1;
        if (upper <= 0) {
            copyValue(0, output);
            return;
        }
        if (upper >= times.length) {
            copyValue(times.length - 1, output);
            return;
        }
        int lower = upper - 1;
        if (interpolation == Interpolation.STEP) {
            copyValue(lower, output);
            return;
        }
        float alpha = (time - times[lower]) / (times[upper] - times[lower]);
        float[] sampled = sampleScratch.get();
        if (interpolation == Interpolation.LINEAR) {
            for (int target = 0; target < targetCount; target++) {
                float first = value(lower, 0, target);
                sampled[target] = first + (value(upper, 0, target) - first) * alpha;
            }
        } else {
            float duration = times[upper] - times[lower];
            float alpha2 = alpha * alpha;
            float alpha3 = alpha2 * alpha;
            float h00 = 2.0f * alpha3 - 3.0f * alpha2 + 1.0f;
            float h10 = alpha3 - 2.0f * alpha2 + alpha;
            float h01 = -2.0f * alpha3 + 3.0f * alpha2;
            float h11 = alpha3 - alpha2;
            for (int target = 0; target < targetCount; target++) {
                sampled[target] = h00 * value(lower, 1, target)
                        + h10 * duration * value(lower, 2, target)
                        + h01 * value(upper, 1, target)
                        + h11 * duration * value(upper, 0, target);
            }
        }
        output.set(sampled);
    }

    private boolean indexIsSampleValue(int index, int tuples) {
        if (tuples == 1) return true;
        return index / targetCount % tuples == 1;
    }

    private void copyValue(int keyframe, MorphWeightBuffer output) {
        float[] sampled = sampleScratch.get();
        int tuple = interpolation == Interpolation.CUBIC_SPLINE ? 1 : 0;
        for (int target = 0; target < targetCount; target++) {
            sampled[target] = value(keyframe, tuple, target);
        }
        output.set(sampled);
    }

    private float value(int keyframe, int tuple, int target) {
        int tuples = interpolation == Interpolation.CUBIC_SPLINE ? 3 : 1;
        return values[(keyframe * tuples + tuple) * targetCount + target];
    }

    public enum Interpolation {
        STEP,
        LINEAR,
        CUBIC_SPLINE
    }
}
