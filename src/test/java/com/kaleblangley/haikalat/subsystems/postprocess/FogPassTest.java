package com.kaleblangley.haikalat.subsystems.postprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FogPassTest {
    @Test
    void referenceFogIsDeterministicMonotonicAndHeightAware() {
        FogSettings settings = FogSettings.builder()
                .distanceDensity(0.02f)
                .heightDensity(0.08f)
                .heightFalloff(0.5f)
                .baseHeight(0.0f)
                .maximumOpacity(0.9f)
                .build();

        float near = FogPass.fogAmount(2.0f, 0.0f, 1.0f, settings);
        float far = FogPass.fogAmount(20.0f, 0.0f, 1.0f, settings);
        float high = FogPass.fogAmount(20.0f, 8.0f, 1.0f, settings);
        assertTrue(far > near);
        assertTrue(far > high, "lower rays should integrate more height fog");
        assertTrue(far <= settings.maximumOpacity());
        assertEquals(0.0f, FogPass.fogAmount(0.0f, 0.0f, 1.0f, settings));
        assertEquals(0.0f, FogPass.fogAmount(20.0f, 0.0f, 1.0f,
                FogSettings.disabled()));
    }

    @Test
    void settingsAndReferenceRejectNonFiniteInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> FogSettings.builder().distanceDensity(-1.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> FogSettings.builder().maximumOpacity(Float.NaN).build());
        assertThrows(IllegalArgumentException.class,
                () -> FogPass.fogAmount(Float.POSITIVE_INFINITY, 0.0f, 0.0f,
                        FogSettings.builder().build()));
    }
}
