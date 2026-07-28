package com.kaleblangley.haikalat.subsystems.ui.render;

/** Deterministic normalized Gaussian weights for separable UI blur. */
public final class UiBlurKernel {
    private UiBlurKernel() {
    }

    public static float[] gaussian(float radius) {
        if (!Float.isFinite(radius) || radius < 0.0f) {
            throw new IllegalArgumentException("radius must be finite and non-negative");
        }
        int halfWidth = Math.min(32, Math.max(0, (int) Math.ceil(radius)));
        float[] weights = new float[halfWidth + 1];
        if (halfWidth == 0) {
            weights[0] = 1.0f;
            return weights;
        }
        double sigma = Math.max(0.5, radius / 3.0);
        double sum = 0.0;
        for (int index = 0; index <= halfWidth; index++) {
            double weight = Math.exp(-(index * index) / (2.0 * sigma * sigma));
            weights[index] = (float) weight;
            sum += index == 0 ? weight : weight * 2.0;
        }
        for (int index = 0; index < weights.length; index++) weights[index] /= (float) sum;
        return weights;
    }
}
