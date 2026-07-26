package com.kaleblangley.haikalat.subsystems.animation;

import java.util.Arrays;
import java.util.Objects;

/** Mutable, reusable per-instance output for morph-target weights. */
public final class MorphWeightBuffer {
    /** Safety envelope used by runtime weights and conservative morph bounds. */
    public static final float MAX_ABSOLUTE_WEIGHT = 8.0f;

    private final float[] weights;
    private long revision;

    public MorphWeightBuffer(int targetCount) {
        if (targetCount <= 0 || targetCount > 8) {
            throw new IllegalArgumentException("targetCount must be in [1, 8]");
        }
        weights = new float[targetCount];
    }

    public MorphWeightBuffer(float... initialWeights) {
        this(Objects.requireNonNull(initialWeights, "initialWeights").length);
        set(initialWeights);
    }

    public int targetCount() {
        return weights.length;
    }

    public float weight(int targetIndex) {
        return weights[targetIndex];
    }

    public long revision() {
        return revision;
    }

    public MorphWeightBuffer setWeight(int targetIndex, float value) {
        requireWeight(value, "value");
        float canonical = value == 0.0f ? 0.0f : value;
        if (Float.floatToIntBits(weights[targetIndex]) != Float.floatToIntBits(canonical)) {
            weights[targetIndex] = canonical;
            revision++;
        }
        return this;
    }

    public MorphWeightBuffer set(float... values) {
        Objects.requireNonNull(values, "values");
        requireCount(values.length);
        boolean changed = false;
        for (int index = 0; index < values.length; index++) {
            float value = values[index];
            if (!validWeight(value)) {
                throw invalidWeight("values[" + index + "]");
            }
            float canonical = value == 0.0f ? 0.0f : value;
            changed |= Float.floatToIntBits(weights[index]) != Float.floatToIntBits(canonical);
            weights[index] = canonical;
        }
        if (changed) revision++;
        return this;
    }

    public MorphWeightBuffer set(MorphWeightBuffer source) {
        Objects.requireNonNull(source, "source");
        requireCount(source.targetCount());
        return set(source.weights);
    }

    public MorphWeightBuffer clear() {
        boolean changed = false;
        for (float weight : weights) changed |= weight != 0.0f;
        Arrays.fill(weights, 0.0f);
        if (changed) revision++;
        return this;
    }

    public MorphWeightBuffer blend(MorphWeightBuffer first, MorphWeightBuffer second,
                                   float alpha) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        requireCount(first.targetCount());
        requireCount(second.targetCount());
        if (!Float.isFinite(alpha) || alpha < 0.0f || alpha > 1.0f) {
            throw new IllegalArgumentException("alpha must be finite and in [0, 1]");
        }
        boolean changed = false;
        for (int index = 0; index < weights.length; index++) {
            float value = first.weights[index]
                    + (second.weights[index] - first.weights[index]) * alpha;
            requireWeight(value, "blended weight");
            float canonical = value == 0.0f ? 0.0f : value;
            changed |= Float.floatToIntBits(weights[index]) != Float.floatToIntBits(canonical);
            weights[index] = canonical;
        }
        if (changed) revision++;
        return this;
    }

    public MorphWeightBuffer additive(MorphWeightBuffer base, MorphWeightBuffer value,
                                      MorphWeightBuffer reference, float layerWeight) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(reference, "reference");
        requireCount(base.targetCount());
        requireCount(value.targetCount());
        requireCount(reference.targetCount());
        if (!Float.isFinite(layerWeight) || layerWeight < 0.0f || layerWeight > 1.0f) {
            throw new IllegalArgumentException("layerWeight must be finite and in [0, 1]");
        }
        boolean changed = false;
        for (int index = 0; index < weights.length; index++) {
            float result = base.weights[index]
                    + (value.weights[index] - reference.weights[index]) * layerWeight;
            requireWeight(result, "additive weight");
            float canonical = result == 0.0f ? 0.0f : result;
            changed |= Float.floatToIntBits(weights[index]) != Float.floatToIntBits(canonical);
            weights[index] = canonical;
        }
        if (changed) revision++;
        return this;
    }

    public float[] toArray() {
        return weights.clone();
    }

    public void copyTo(float[] destination, int offset) {
        Objects.requireNonNull(destination, "destination");
        if (offset < 0 || offset > destination.length - weights.length) {
            throw new IndexOutOfBoundsException("destination range is too small");
        }
        System.arraycopy(weights, 0, destination, offset, weights.length);
    }

    private void requireCount(int count) {
        if (count != weights.length) {
            throw new IllegalArgumentException("target count must be " + weights.length);
        }
    }

    private static void requireWeight(float value, String name) {
        if (!validWeight(value)) throw invalidWeight(name);
    }

    private static boolean validWeight(float value) {
        return Float.isFinite(value) && Math.abs(value) <= MAX_ABSOLUTE_WEIGHT;
    }

    private static IllegalArgumentException invalidWeight(String name) {
        return new IllegalArgumentException(name + " must be finite and in ["
                + -MAX_ABSOLUTE_WEIGHT + ", " + MAX_ABSOLUTE_WEIGHT + "]");
    }
}
