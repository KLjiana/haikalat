package com.kaleblangley.haikalat.subsystems.render3d.water;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JVM contract for the bounded underwater-volume settings value. */
class UnderwaterVolumeSettingsTest {
    @Test
    void disabledIsAnExactNoOpValue() {
        UnderwaterVolumeSettings disabled = UnderwaterVolumeSettings.disabled();
        assertFalse(disabled.active());
        assertEquals(0.0f, disabled.absorptionDensity());
        assertEquals(UnderwaterVolumeSettings.DEFAULT_DISTANCE_METRES,
                disabled.maximumDistanceMetres());
        assertFalse(disabled.withEnabled(false).active());
        assertTrue(disabled.withEnabled(true).active());
    }

    @Test
    void builderRoundTripsAndCopiesTheColourDefensively() {
        Vector3f scatter = new Vector3f(0.04f, 0.18f, 0.31f);
        UnderwaterVolumeSettings settings = UnderwaterVolumeSettings.builder()
                .enabled(true)
                .absorptionDensity(0.42f)
                .scatterColor(scatter)
                .maximumDistanceMetres(37.5f)
                .build();
        scatter.set(9.0f, 9.0f, 9.0f);
        assertTrue(settings.active());
        assertEquals(0.42f, settings.absorptionDensity());
        assertEquals(37.5f, settings.maximumDistanceMetres());
        assertEquals(0.04f, settings.scatterColor().x);
        Vector3f first = settings.scatterColor();
        Vector3f second = settings.scatterColor();
        assertNotSame(first, second, "every read must copy the colour");
        first.set(8.0f, 8.0f, 8.0f);
        assertEquals(0.04f, settings.scatterColor().x,
                "mutating a returned colour must not change the value");
        Vector3f destination = new Vector3f();
        settings.scatterColorTo(destination);
        assertEquals(new Vector3f(0.04f, 0.18f, 0.31f), destination);
    }

    @Test
    void withCopiesChangeOnlyTheSelectedField() {
        UnderwaterVolumeSettings base = UnderwaterVolumeSettings.builder()
                .enabled(true)
                .absorptionDensity(0.3f)
                .scatterColor(0.1f, 0.2f, 0.3f)
                .maximumDistanceMetres(12.0f)
                .build();
        assertEquals(0.7f, base.withAbsorptionDensity(0.7f).absorptionDensity());
        assertEquals(new Vector3f(0.1f, 0.2f, 0.3f),
                base.withAbsorptionDensity(0.7f).scatterColor());
        assertFalse(base.withEnabled(false).active());
        assertEquals(0.3f, base.withEnabled(false).absorptionDensity());
        assertEquals(0.9f, base.withScatterColor(0.9f, 0.0f, 0.0f).scatterColor().x);
        assertEquals(45.0f, base.withMaximumDistanceMetres(45.0f).maximumDistanceMetres());
        assertEquals(12.0f, base.maximumDistanceMetres(),
                "copies must not mutate the original");
    }

    @Test
    void invalidRangesAndNonFiniteValuesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> UnderwaterVolumeSettings.builder().absorptionDensity(-0.1f).build());
        assertThrows(IllegalArgumentException.class,
                () -> UnderwaterVolumeSettings.builder()
                        .absorptionDensity(UnderwaterVolumeSettings.MAXIMUM_ABSORPTION_DENSITY
                                + 0.01f).build());
        assertThrows(IllegalArgumentException.class,
                () -> UnderwaterVolumeSettings.builder().absorptionDensity(Float.NaN).build());
        assertThrows(IllegalArgumentException.class,
                () -> UnderwaterVolumeSettings.builder()
                        .absorptionDensity(Float.POSITIVE_INFINITY).build());
        assertThrows(IllegalArgumentException.class,
                () -> UnderwaterVolumeSettings.builder()
                        .maximumDistanceMetres(UnderwaterVolumeSettings.MINIMUM_DISTANCE_METRES
                                - 0.01f).build());
        assertThrows(IllegalArgumentException.class,
                () -> UnderwaterVolumeSettings.builder()
                        .maximumDistanceMetres(UnderwaterVolumeSettings.MAXIMUM_DISTANCE_METRES
                                + 0.01f).build());
        assertThrows(IllegalArgumentException.class,
                () -> UnderwaterVolumeSettings.builder()
                        .maximumDistanceMetres(Float.NaN).build());
        assertThrows(IllegalArgumentException.class,
                () -> UnderwaterVolumeSettings.builder()
                        .scatterColor(-0.1f, 0.0f, 0.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> UnderwaterVolumeSettings.builder()
                        .scatterColor(Float.NaN, 0.0f, 0.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> UnderwaterVolumeSettings.builder()
                        .scatterColor(UnderwaterVolumeSettings.MAXIMUM_COLOR_COMPONENT + 0.1f,
                                0.0f, 0.0f).build());
        assertThrows(NullPointerException.class,
                () -> UnderwaterVolumeSettings.builder().scatterColor((Vector3f) null).build());
        assertThrows(NullPointerException.class,
                () -> new UnderwaterVolumeSettings(true, 0.2f, null, 10.0f));
    }
}
