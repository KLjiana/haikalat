package com.kaleblangley.haikalat.demo.pbr;

import java.util.Arrays;

/**
 * Percentile helpers for benchmark samples.
 *
 * <p>Inputs are always frame-ordered; every method sorts a private copy so the
 * caller can keep the original sequence for attribution.  Empty input is an
 * explicit failure, never a zero-millisecond measurement.</p>
 */
public final class SampleStatistics {
    private SampleStatistics() {
    }

    public static float percentileMillis(float[] frameOrderedSamples, double fraction) {
        requireSamples(frameOrderedSamples);
        if (!(fraction >= 0.0 && fraction <= 1.0)) {
            throw new IllegalArgumentException("fraction must be in [0, 1]");
        }
        float[] sorted = frameOrderedSamples.clone();
        Arrays.sort(sorted);
        int rank = Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1);
        return sorted[Math.min(rank, sorted.length - 1)];
    }

    public static float maxMillis(float[] frameOrderedSamples) {
        requireSamples(frameOrderedSamples);
        float maximum = frameOrderedSamples[0];
        for (float sample : frameOrderedSamples) {
            maximum = Math.max(maximum, sample);
        }
        return maximum;
    }

    public static double coverage(int available, int expected) {
        if (expected <= 0) throw new IllegalArgumentException("expected sample count must be positive");
        if (available < 0) throw new IllegalArgumentException("available sample count must be non-negative");
        return available / (double) expected;
    }

    private static void requireSamples(float[] samples) {
        if (samples == null || samples.length == 0) {
            throw new IllegalArgumentException("no samples available");
        }
    }
}
