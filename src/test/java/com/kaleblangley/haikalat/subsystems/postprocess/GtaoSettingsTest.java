package com.kaleblangley.haikalat.subsystems.postprocess;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GtaoSettingsTest {
    @Test
    void defaultsAreDisabledAndFactoriesRemainImmutable() {
        GtaoSettings defaults = GtaoSettings.defaults();
        assertFalse(defaults.enabled());
        assertEquals(GtaoQuality.MEDIUM, defaults.quality());
        assertEquals(true, defaults.withEnabled(true).enabled());
        assertFalse(defaults.enabled());
        assertFalse(GtaoSettings.disabled().enabled());
        assertEquals(GtaoQuality.HIGH, GtaoSettings.quality(GtaoQuality.HIGH).quality());
        assertEquals(true, GtaoSettings.quality(GtaoQuality.HIGH).enabled());
    }

    @Test
    void validatesFiniteAndBoundedInputs() {
        assertThrows(IllegalArgumentException.class, () -> new GtaoSettings(
                true, GtaoQuality.LOW, 0.0f, 1.0f, 0.0f, true, 0.9f, 0.02f));
        assertThrows(IllegalArgumentException.class, () -> new GtaoSettings(
                true, GtaoQuality.LOW, 101.0f, 1.0f, 0.0f, true, 0.9f, 0.02f));
        assertThrows(IllegalArgumentException.class, () -> new GtaoSettings(
                true, GtaoQuality.LOW, 1.0f, 1.0f, 0.0f, true, 0.9f, 1.1f));
        assertThrows(IllegalArgumentException.class, () -> new GtaoSettings(
                true, GtaoQuality.LOW, 1.0f, 1.0f, 1.1f, true, 0.9f, 0.02f));
    }

    @Test
    void qualityPreservesTheFixedBoundedBudgets() {
        assertEquals(2, GtaoQuality.LOW.directions());
        assertEquals(4, GtaoQuality.LOW.stepsPerDirection());
        assertEquals(4, GtaoQuality.MEDIUM.directions());
        assertEquals(4, GtaoQuality.MEDIUM.stepsPerDirection());
        assertEquals(6, GtaoQuality.HIGH.directions());
        assertEquals(6, GtaoQuality.HIGH.stepsPerDirection());
    }

    @Test
    void estimateShaderExposesDeterministicTemporalFramePhase() throws IOException {
        try (InputStream stream = GtaoSettingsTest.class.getResourceAsStream(
                "/shaders/postprocess/gtao-estimate.frag")) {
            if (stream == null) throw new IOException("gtao-estimate.frag is missing");
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            org.junit.jupiter.api.Assertions.assertTrue(
                    source.contains("uniform float uFramePhase"));
            org.junit.jupiter.api.Assertions.assertTrue(
                    source.contains("float(slice) + 0.5 + uFramePhase"));
        }
    }
}
