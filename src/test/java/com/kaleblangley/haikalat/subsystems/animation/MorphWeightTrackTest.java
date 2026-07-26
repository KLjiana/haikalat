package com.kaleblangley.haikalat.subsystems.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MorphWeightTrackTest {
    @Test
    void samplesStepLinearAndCubicTracks() {
        MorphWeightBuffer output = new MorphWeightBuffer(2);
        new MorphWeightTrack(2, MorphWeightTrack.Interpolation.STEP,
                new float[]{0, 1}, new float[]{0, 1, 1, 0})
                .sample(0.5f, output);
        assertEquals(0.0f, output.weight(0));
        assertEquals(1.0f, output.weight(1));

        new MorphWeightTrack(2, MorphWeightTrack.Interpolation.LINEAR,
                new float[]{0, 1}, new float[]{0, 1, 1, 0})
                .sample(0.5f, output);
        assertEquals(0.5f, output.weight(0), 1.0e-6f);
        assertEquals(0.5f, output.weight(1), 1.0e-6f);

        new MorphWeightTrack(1, MorphWeightTrack.Interpolation.CUBIC_SPLINE,
                new float[]{0, 1}, new float[]{0, 0, 1, 1, 0.5f, 0})
                .sample(0.5f, new MorphWeightBuffer(1));
    }

    @Test
    void blendsAndAppliesAdditiveWeightsWithoutAliasing() {
        MorphWeightBuffer first = new MorphWeightBuffer(0.0f, 0.5f);
        MorphWeightBuffer second = new MorphWeightBuffer(1.0f, 0.0f);
        MorphWeightBuffer output = new MorphWeightBuffer(2).blend(first, second, 0.25f);
        assertEquals(0.25f, output.weight(0), 1.0e-6f);
        assertEquals(0.375f, output.weight(1), 1.0e-6f);

        MorphWeightBuffer reference = new MorphWeightBuffer(0.25f, 0.25f);
        output.additive(first, second, reference, 0.5f);
        assertEquals(0.375f, output.weight(0), 1.0e-6f);
        assertEquals(0.375f, output.weight(1), 1.0e-6f);
        assertThrows(IllegalArgumentException.class, () -> output.setWeight(0, 9.0f));
    }
}
