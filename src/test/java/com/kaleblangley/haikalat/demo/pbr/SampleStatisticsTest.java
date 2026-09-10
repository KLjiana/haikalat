package com.kaleblangley.haikalat.demo.pbr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SampleStatisticsTest {
    @Test
    void percentilesSortFrameOrderedSamplesWithoutMutatingThem() {
        float[] frameOrdered = {5.0f, 1.0f, 3.0f, 2.0f, 4.0f};
        float[] snapshot = frameOrdered.clone();

        assertEquals(3.0f, SampleStatistics.percentileMillis(frameOrdered, 0.50), 1.0e-6f);
        assertEquals(5.0f, SampleStatistics.percentileMillis(frameOrdered, 0.95), 1.0e-6f);
        assertEquals(1.0f, SampleStatistics.percentileMillis(frameOrdered, 0.0), 1.0e-6f);
        assertEquals(5.0f, SampleStatistics.maxMillis(frameOrdered), 1.0e-6f);
        for (int index = 0; index < snapshot.length; index++) {
            assertEquals(snapshot[index], frameOrdered[index],
                    "percentile must not reorder the caller's frame sequence");
        }
    }

    @Test
    void singleSampleIsItsOwnPercentile() {
        assertEquals(7.5f, SampleStatistics.percentileMillis(new float[]{7.5f}, 0.50), 1.0e-6f);
        assertEquals(7.5f, SampleStatistics.percentileMillis(new float[]{7.5f}, 0.95), 1.0e-6f);
        assertEquals(7.5f, SampleStatistics.maxMillis(new float[]{7.5f}), 1.0e-6f);
    }

    @Test
    void emptySamplesAreExplicitFailures() {
        assertThrows(IllegalArgumentException.class,
                () -> SampleStatistics.percentileMillis(new float[0], 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> SampleStatistics.maxMillis(new float[0]));
        assertThrows(IllegalArgumentException.class,
                () -> SampleStatistics.percentileMillis(null, 0.5));
        assertThrows(IllegalArgumentException.class, () -> SampleStatistics.coverage(10, 0));
    }

    @Test
    void coverageReportsCompletionRatio() {
        assertEquals(0.5, SampleStatistics.coverage(45, 90), 1.0e-9);
        assertEquals(1.0, SampleStatistics.coverage(90, 90), 1.0e-9);
    }
}
