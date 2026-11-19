package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the GL-free probe description contract: bounded influence values,
 * defensive copies, the disabled no-op and the quality-to-prefilter mapping.
 */
class ReflectionProbeSettingsTest {
    private static final float EPSILON = 1.0e-6f;

    @Test
    void boxSettingsNormalizeTheirOwnStateAndExposeCopies() {
        Vector3f position = new Vector3f(2.0f, 3.0f, 4.0f);
        Vector3f half = new Vector3f(0.5f, 1.0f, 1.5f);
        ReflectionProbeSettings settings = ReflectionProbeSettings.box("warm", position, half,
                0.75f, ReflectionProbeSettings.Quality.TEST, 2.0f, 0.5f,
                "/environments/pbr/probe-warm.hdr");
        position.set(99.0f);
        half.set(99.0f);
        assertEquals(new Vector3f(2.0f, 3.0f, 4.0f), settings.position());
        assertEquals(new Vector3f(0.5f, 1.0f, 1.5f), settings.halfExtents());
        assertNotSame(settings.position(), settings.position(),
                "position accessor must not expose the internal vector");
        assertTrue(settings.active());
        assertEquals(ReflectionProbeSettings.Shape.BOX, settings.shape());
        assertEquals(2.0f, settings.intensity(), EPSILON);
        assertEquals(0.5f, settings.rotationRadians(), EPSILON);
        assertEquals(0.75f, settings.blendDistance(), EPSILON);
    }

    @Test
    void sphereSettingsUseTheRadiusAndIgnoreHalfExtentsForSelection() {
        ReflectionProbeSettings settings = ReflectionProbeSettings.sphere("cool",
                new Vector3f(1.0f, 0.0f, 0.0f), 2.0f, 0.5f,
                ReflectionProbeSettings.Quality.LOW, 1.0f, 0.0f,
                "/environments/pbr/probe-cool.hdr");
        assertEquals(ReflectionProbeSettings.Shape.SPHERE, settings.shape());
        assertEquals(2.0f, settings.radius(), EPSILON);
        assertEquals(2.0f, settings.halfExtents().x, EPSILON);
        assertTrue(settings.active());
    }

    @Test
    void disabledAndZeroIntensityProbesAreInactiveWithoutLosingTheirDescription() {
        ReflectionProbeSettings enabled = ReflectionProbeSettings.box("warm",
                new Vector3f(), new Vector3f(1.0f), 0.5f,
                ReflectionProbeSettings.Quality.TEST, 1.0f, 0.0f,
                "/environments/pbr/probe-warm.hdr");
        ReflectionProbeSettings disabled = enabled.disabledCopy();
        assertFalse(disabled.active());
        assertFalse(disabled.enabled());
        assertEquals(enabled.name(), disabled.name());
        assertEquals(enabled.hdrResource(), disabled.hdrResource());
        ReflectionProbeSettings muted = new ReflectionProbeSettings("muted", true, new Vector3f(),
                ReflectionProbeSettings.Shape.BOX, new Vector3f(1.0f), 1.0f, 0.5f,
                ReflectionProbeSettings.Quality.TEST, 0.0f, 0.0f,
                "/environments/pbr/probe-warm.hdr");
        assertFalse(muted.active());
    }

    @Test
    void qualityTiersAreCoarsePowerOfTwoPrefilters() {
        for (ReflectionProbeSettings.Quality quality : ReflectionProbeSettings.Quality.values()) {
            PbrEnvironmentSettings settings = quality.environmentSettings();
            assertEquals(settings.prefilteredSize(),
                    Integer.highestOneBit(settings.prefilteredSize()));
            assertEquals(settings.environmentSize(),
                    Integer.highestOneBit(settings.environmentSize()));
            assertTrue(settings.prefilterSamples() > 0);
        }
        assertTrue(ReflectionProbeSettings.Quality.HIGH.environmentSettings().prefilteredSize()
                > ReflectionProbeSettings.Quality.TEST.environmentSettings().prefilteredSize());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankNamesAreRejected(String name) {
        assertThrows(IllegalArgumentException.class, () -> ReflectionProbeSettings.box(name,
                new Vector3f(), new Vector3f(1.0f), 0.5f,
                ReflectionProbeSettings.Quality.TEST, 1.0f, 0.0f,
                "/environments/pbr/probe-warm.hdr"));
    }

    @Test
    void invalidInfluenceValuesAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> ReflectionProbeSettings.box("probe",
                new Vector3f(Float.NaN, 0.0f, 0.0f), new Vector3f(1.0f), 0.5f,
                ReflectionProbeSettings.Quality.TEST, 1.0f, 0.0f, "hdr"));
        assertThrows(IllegalArgumentException.class, () -> ReflectionProbeSettings.box("probe",
                new Vector3f(), new Vector3f(0.0f, 1.0f, 1.0f), 0.5f,
                ReflectionProbeSettings.Quality.TEST, 1.0f, 0.0f, "hdr"));
        assertThrows(IllegalArgumentException.class, () -> ReflectionProbeSettings.box("probe",
                new Vector3f(), new Vector3f(1.0f), -0.5f,
                ReflectionProbeSettings.Quality.TEST, 1.0f, 0.0f, "hdr"));
        assertThrows(IllegalArgumentException.class, () -> ReflectionProbeSettings.sphere("probe",
                new Vector3f(), 0.0f, 0.5f,
                ReflectionProbeSettings.Quality.TEST, 1.0f, 0.0f, "hdr"));
        assertThrows(IllegalArgumentException.class, () -> ReflectionProbeSettings.sphere("probe",
                new Vector3f(), 1.0f, Float.POSITIVE_INFINITY,
                ReflectionProbeSettings.Quality.TEST, 1.0f, 0.0f, "hdr"));
        assertThrows(IllegalArgumentException.class, () -> ReflectionProbeSettings.sphere("probe",
                new Vector3f(), 1.0f, 0.5f,
                ReflectionProbeSettings.Quality.TEST, -1.0f, 0.0f, "hdr"));
        assertThrows(IllegalArgumentException.class, () -> ReflectionProbeSettings.sphere("probe",
                new Vector3f(), 1.0f, 0.5f,
                ReflectionProbeSettings.Quality.TEST, 1.0f, Float.NaN, "hdr"));
        assertThrows(IllegalArgumentException.class, () -> ReflectionProbeSettings.sphere("probe",
                new Vector3f(), 1.0f, 0.5f,
                ReflectionProbeSettings.Quality.TEST, 1.0f, 0.0f, " "));
    }
}
