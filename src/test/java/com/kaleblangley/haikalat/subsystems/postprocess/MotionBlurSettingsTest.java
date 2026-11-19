package com.kaleblangley.haikalat.subsystems.postprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionBlurSettingsTest {
    @Test
    void disabledClosedShutterAndZeroRadiusAreExactNoOps() {
        assertFalse(MotionBlurSettings.disabled().active());
        assertFalse(MotionBlurSettings.of(0.0f, 16.0f).active());
        assertFalse(MotionBlurSettings.builder().enabled(true)
                .shutterFraction(0.5f).maxRadiusPixels(0.0f).build().active());
        assertTrue(MotionBlurSettings.of(0.5f, 16.0f).active());
        assertFalse(MotionBlurSettings.of(0.5f, 16.0f).withEnabled(false).active());
    }

    @Test
    void validatesShutterAndRadiusBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new MotionBlurSettings(true, -0.1f, 16.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new MotionBlurSettings(true, 1.1f, 16.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new MotionBlurSettings(true, Float.NaN, 16.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new MotionBlurSettings(true, Float.POSITIVE_INFINITY, 16.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new MotionBlurSettings(true, 0.5f, -0.1f));
        assertThrows(IllegalArgumentException.class,
                () -> new MotionBlurSettings(true, 0.5f, 64.1f));
        assertThrows(IllegalArgumentException.class,
                () -> new MotionBlurSettings(true, 0.5f, Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> MotionBlurSettings.builder().maxRadiusPixels(Float.NaN).build());
    }

    @Test
    void boundariesAreAccepted() {
        assertFalse(MotionBlurSettings.of(0.0f, 16.0f).active());
        assertTrue(MotionBlurSettings.of(1.0f, 64.0f).active());
        assertEquals(0.0f, MotionBlurSettings.of(0.0f, 0.0f).shutterFraction());
        assertEquals(64.0f, MotionBlurSettings.of(1.0f, 64.0f).maxRadiusPixels());
    }

    @Test
    void withersPreserveTheOtherParameters() {
        MotionBlurSettings settings = MotionBlurSettings.of(0.5f, 16.0f);
        assertEquals(new MotionBlurSettings(true, 0.75f, 16.0f),
                settings.withShutterFraction(0.75f));
        assertEquals(new MotionBlurSettings(true, 0.5f, 24.0f),
                settings.withMaxRadiusPixels(24.0f));
        assertFalse(settings.withEnabled(false).active());
    }
}
