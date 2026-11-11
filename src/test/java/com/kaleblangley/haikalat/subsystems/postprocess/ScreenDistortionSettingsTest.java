package com.kaleblangley.haikalat.subsystems.postprocess;

import org.joml.Vector2f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenDistortionSettingsTest {
    @Test
    void defaultsAreDisabledAndContributeNothing() {
        ScreenDistortionSettings settings = ScreenDistortionSettings.disabled();

        assertFalse(settings.active());
        assertNull(settings.source());
        assertTrue(settings.contributions().isEmpty());
        assertEquals(0.02f, settings.maximumOffsetUv());
        assertEquals(4, settings.maximumContributions());
        assertEquals(0.05f, settings.edgeFeatherUv());
    }

    @Test
    void enabledRequiresASource() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenDistortionSettings(true, 0.02f, 4, 0.05f, null));
        assertThrows(NullPointerException.class, () -> ScreenDistortionSettings.of(null));
        assertThrows(IllegalArgumentException.class,
                () -> ScreenDistortionSettings.builder().enabled(true).build());
    }

    @Test
    void parametersStayInsideBoundedRanges() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenDistortionSettings(false, 0.0f, 4, 0.05f, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenDistortionSettings(false, 0.11f, 4, 0.05f, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenDistortionSettings(false, Float.NaN, 4, 0.05f, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenDistortionSettings(false, 0.02f, 0, 0.05f, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenDistortionSettings(false, 0.02f,
                        ScreenDistortion.MAXIMUM_CONTRIBUTIONS + 1, 0.05f, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenDistortionSettings(false, 0.02f, 4, -0.01f, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenDistortionSettings(false, 0.02f, 4, 0.26f, null));
    }

    @Test
    void builderAndWithersPreserveTheProducer() {
        ScreenDistortionSource source = () -> List.of(distortion());
        ScreenDistortionSettings settings = ScreenDistortionSettings.builder()
                .enabled(true)
                .maximumOffsetUv(0.05f)
                .maximumContributions(2)
                .edgeFeatherUv(0.0f)
                .source(source)
                .build();

        assertTrue(settings.active());
        assertEquals(1, settings.contributions().size());
        assertEquals(0.05f, settings.maximumOffsetUv());
        assertEquals(2, settings.maximumContributions());
        assertEquals(0.0f, settings.edgeFeatherUv());

        ScreenDistortionSettings paused = settings.withEnabled(false);
        assertFalse(paused.active());
        assertEquals(source, paused.source());
        assertEquals(1, paused.contributions().size());

        ScreenDistortionSettings resized = settings.withMaximumContributions(1)
                .withMaximumOffsetUv(0.08f).withEdgeFeatherUv(0.02f);
        assertEquals(1, resized.maximumContributions());
        assertEquals(0.08f, resized.maximumOffsetUv());
        assertEquals(0.02f, resized.edgeFeatherUv());
        assertEquals(source, resized.source());
    }

    @Test
    void ofEnablesWithConservativeDefaults() {
        ScreenDistortionSource source = List::of;
        ScreenDistortionSettings settings = ScreenDistortionSettings.of(source);

        assertTrue(settings.active());
        assertEquals(source, settings.source());
        assertEquals(0.02f, settings.maximumOffsetUv());
        assertEquals(4, settings.maximumContributions());
        assertEquals(0.05f, settings.edgeFeatherUv());
    }

    private static ScreenDistortion distortion() {
        return new ScreenDistortion(1L, 0, new Vector2f(0.5f, 0.5f), new Vector2f(0.5f),
                new Vector2f(0.05f, 0.0f), 1.0f, 0.5f, 0.01f, 0.0f, 0.0f, 0.0f);
    }
}
