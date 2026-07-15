package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.runtime.AutoExposureSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoExposurePassTest {
    @Test
    void reductionShaderCarriesExplicitSumAndWeight() throws IOException {
        String source;
        try (var stream = AutoExposurePassTest.class.getResourceAsStream(
                "/postprocess/luminance_reduce.frag")) {
            assertTrue(stream != null);
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(source.contains("out vec2 FragReduction"));
        assertTrue(source.contains("uInputHasWeights"));
        assertTrue(source.contains("total += sampleValue"));
        assertTrue(!source.contains("uOriginalSize"));
    }

    @Test
    void darkSceneRaisesTargetAndBrightSceneLowersItWithinBounds() {
        AutoExposureSettings settings = AutoExposureSettings.builder()
                .minExposure(0.25f)
                .maxExposure(4.0f)
                .build();

        float dark = AutoExposurePass.targetExposure(0.01f, settings);
        float middle = AutoExposurePass.targetExposure(0.18f, settings);
        float bright = AutoExposurePass.targetExposure(4.0f, settings);

        assertEquals(4.0f, dark);
        assertEquals(1.0f, middle, 1.0e-6f);
        assertEquals(0.25f, bright);
    }

    @Test
    void exponentialAdaptationIsFrameRateIndependentForEqualElapsedTime() {
        AutoExposureSettings settings = AutoExposureSettings.defaults();
        float sixtyFps = integrate(1.0f, 3.0f, 60, settings);
        float oneTwentyFps = integrate(1.0f, 3.0f, 120, settings);

        assertEquals(sixtyFps, oneTwentyFps, 1.0e-5f);
    }

    @Test
    void brightenAndDarkenUseIndependentSpeeds() {
        AutoExposureSettings settings = AutoExposureSettings.builder()
                .brightenSpeed(0.5f)
                .darkenSpeed(4.0f)
                .build();

        float brightened = AutoExposurePass.adaptExposure(1.0f, 2.0f, 0.1f, settings);
        float darkened = AutoExposurePass.adaptExposure(1.0f, 0.5f, 0.1f, settings);

        assertTrue(brightened - 1.0f < 1.0f - darkened);
    }

    @Test
    void adaptationClampsInitialAndTargetValuesToConfiguredRange() {
        AutoExposureSettings settings = AutoExposureSettings.builder()
                .minExposure(0.5f)
                .maxExposure(2.0f)
                .build();

        assertEquals(2.0f, AutoExposurePass.adaptExposure(100.0f, 100.0f,
                1.0f, settings));
        assertEquals(0.5f, AutoExposurePass.adaptExposure(0.01f, 0.01f,
                1.0f, settings));
    }

    private static float integrate(float previous, float target, int frames,
                                   AutoExposureSettings settings) {
        float value = previous;
        float delta = 1.0f / frames;
        for (int frame = 0; frame < frames; frame++) {
            value = AutoExposurePass.adaptExposure(value, target, delta, settings);
        }
        return value;
    }
}
