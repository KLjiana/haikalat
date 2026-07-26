package com.kaleblangley.haikalat.core.curve;

import java.util.Objects;

/** Fixed-size immutable linear lookup table baked from a scalar curve. */
public final class CurveLut implements Curve1f {
    private final float[] samples;

    public CurveLut(Curve1f curve, int sampleCount) {
        Curve1f source = Objects.requireNonNull(curve, "curve");
        if (sampleCount < 2) throw new IllegalArgumentException("sampleCount must be at least 2");
        samples = new float[sampleCount];
        for (int index = 0; index < sampleCount; index++) {
            float value = source.sample((float) index / (sampleCount - 1));
            samples[index] = CurveMath.requireFinite(value, "curve sample");
        }
    }

    @Override
    public float sample(float normalizedTime) {
        float time = CurveMath.requireNormalizedTime(normalizedTime);
        if (time == 1.0f) return samples[samples.length - 1];
        float position = time * (samples.length - 1);
        int index = (int) position;
        float progress = position - index;
        float result = (float) (samples[index]
                + ((double) samples[index + 1] - samples[index]) * progress);
        if (!Float.isFinite(result)) {
            throw new IllegalStateException("lookup interpolation produced a non-finite value");
        }
        return result;
    }

    public int sampleCount() {
        return samples.length;
    }

    public float sampleValue(int index) {
        return samples[index];
    }
}
