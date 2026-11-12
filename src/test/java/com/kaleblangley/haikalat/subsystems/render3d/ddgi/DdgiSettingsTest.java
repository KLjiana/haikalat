package com.kaleblangley.haikalat.subsystems.render3d.ddgi;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM contract for the bounded DDGI configuration.
 *
 * <p>Covers constructor rejection, the exact disabled no-op, derived grid
 * geometry, the border clamp and defensive radiance copies. The probe
 * weighting/update math lives in {@link DdgiProbeReferenceTest}.</p>
 */
class DdgiSettingsTest {
    private static DdgiSettings enabled() {
        return DdgiSettings.builder()
                .enabled(true)
                .intensity(1.25f)
                .surfaceAlbedo(0.7f)
                .skyRadiance(0.10f, 0.12f, 0.16f)
                .gridOrigin(-4.0f, 0.0f, -6.0f)
                .spacingMetres(2.0f)
                .probes(3, 2, 2)
                .updateBudget(3)
                .hysteresis(0.5f)
                .maximumRayDistance(9.0f)
                .raySteps(20)
                .depthFaceResolution(8)
                .borderFadeMetres(0.75f)
                .maximumIrradiance(24.0f)
                .build();
    }

    @Test
    void disabledValueIsAnExactNoOp() {
        DdgiSettings disabled = DdgiSettings.disabled();
        assertFalse(disabled.active());
        assertFalse(disabled.enabled());
        assertEquals(disabled, DdgiSettings.disabled());
        assertEquals(disabled, disabled.withEnabled(false));
        assertEquals(disabled.hashCode(), DdgiSettings.disabled().hashCode());

        DdgiSettings enabled = disabled.withEnabled(true);
        assertTrue(enabled.active());
        assertEquals(disabled.surfaceAlbedo(), enabled.surfaceAlbedo(), 0.0f);
        assertEquals(disabled.spacingMetres(), enabled.spacingMetres(), 0.0f);
        assertEquals(disabled.probeCount(), enabled.probeCount());
        assertEquals(disabled.skyRadiance(), enabled.skyRadiance());
    }

    @Test
    void builderRoundTripPreservesValuesAndDerivedGridGeometry() {
        DdgiSettings settings = enabled();
        assertTrue(settings.active());
        assertEquals(1.25f, settings.intensity(), 0.0f);
        assertEquals(0.7f, settings.surfaceAlbedo(), 0.0f);
        assertEquals(12, settings.probeCount());
        assertEquals(6.0f, settings.gridWidth(), 0.0f);
        assertEquals(4.0f, settings.gridHeight(), 0.0f);
        assertEquals(4.0f, settings.gridDepth(), 0.0f);
        assertEquals(2.0f, settings.gridMaximumX(), 0.0f);
        assertEquals(4.0f, settings.gridMaximumY(), 0.0f);
        assertEquals(-2.0f, settings.gridMaximumZ(), 0.0f);
        assertEquals(12 * DdgiSettings.IRRADIANCE_TILE_WIDTH,
                settings.irradianceAtlasWidth());
        assertEquals(12 * DdgiSettings.FACE_COUNT * 8, settings.probeDepthAtlasWidth());
        assertEquals(8, settings.probeDepthAtlasHeight());
        assertEquals(DdgiSettings.IRRADIANCE_TILE_HEIGHT, DdgiSettings.IRRADIANCE_FACE_RESOLUTION
                * DdgiSettings.IRRADIANCE_TILE_ROWS);

        DdgiSettings copy = settings.toBuilder().build();
        assertEquals(settings, copy);
        assertEquals(settings.hashCode(), copy.hashCode());
    }

    @Test
    void containsUsesInclusiveGridBounds() {
        DdgiSettings settings = enabled();
        assertTrue(settings.contains(-4.0f, 0.0f, -6.0f), "lower corner must be inside");
        assertTrue(settings.contains(2.0f, 4.0f, -2.0f), "upper corner must be inside");
        assertTrue(settings.contains(0.0f, 2.0f, -4.0f));
        assertFalse(settings.contains(-4.001f, 2.0f, -4.0f));
        assertFalse(settings.contains(2.001f, 2.0f, -4.0f));
        assertFalse(settings.contains(0.0f, -0.001f, -4.0f));
        assertFalse(settings.contains(Float.NaN, 2.0f, -4.0f));
    }

    @Test
    void borderWeightFadesInsideAndIsExactlyZeroOutside() {
        DdgiSettings settings = enabled();
        assertEquals(0.0f, settings.borderWeight(-4.5f, 2.0f, -4.0f), 0.0f);
        assertEquals(0.0f, settings.borderWeight(-4.0f, 2.0f, -4.0f), 0.0f,
                "the exact border has zero fade weight");
        assertEquals(0.5f, settings.borderWeight(-3.625f, 2.0f, -4.0f), 1.0e-6f,
                "half a fade distance inside is a half weight");
        assertEquals(1.0f, settings.borderWeight(-3.0f, 2.0f, -3.0f), 1.0e-6f);
        assertEquals(0.0f, settings.borderWeight(2.0f, 4.0f, -2.0f), 0.0f,
                "the exact upper corner has zero fade distance");
    }

    @Test
    void radianceGetterAndToMethodAreDefensiveCopies() {
        DdgiSettings settings = enabled();
        Vector3f first = settings.skyRadiance();
        first.set(99.0f, 99.0f, 99.0f);
        assertEquals(0.10f, settings.skyRadiance().x, 1.0e-6f);
        Vector3f target = new Vector3f();
        settings.skyRadianceTo(target);
        assertEquals(0.12f, target.y, 1.0e-6f);
        target.set(1.0f, 1.0f, 1.0f);
        assertEquals(0.16f, settings.skyRadiance().z, 1.0e-6f);
    }

    @Test
    void invalidScalarAndGridValuesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> builder().intensity(DdgiSettings.MAXIMUM_INTENSITY + 0.01f).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().intensity(-0.01f).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().surfaceAlbedo(DdgiSettings.MAXIMUM_ALBEDO + 0.01f).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().skyRadiance(DdgiSettings.MAXIMUM_RADIANCE_COMPONENT + 0.01f,
                        0.0f, 0.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().skyRadiance(-0.01f, 0.0f, 0.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().gridOrigin(DdgiSettings.MAXIMUM_ABSOLUTE_ORIGIN + 1.0f,
                        0.0f, 0.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().spacingMetres(DdgiSettings.MINIMUM_SPACING * 0.5f).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().spacingMetres(Float.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> builder().probes(0, 1, 1).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().probes(DdgiSettings.MAXIMUM_GRID_AXIS + 1, 1, 1).build());
        assertThrows(IllegalArgumentException.class, () -> builder().probes(8, 8, 2).build(),
                "64 * 2 probes exceed the bounded capacity");
        assertThrows(IllegalArgumentException.class, () -> builder().updateBudget(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().updateBudget(DdgiSettings.MAXIMUM_UPDATE_BUDGET + 1).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().hysteresis(DdgiSettings.MAXIMUM_HYSTERESIS + 0.01f).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().raySteps(DdgiSettings.MINIMUM_RAY_STEPS - 1).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().raySteps(DdgiSettings.MAXIMUM_RAY_STEPS + 1).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().depthFaceResolution(
                        DdgiSettings.MINIMUM_DEPTH_FACE_RESOLUTION - 1).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().depthFaceResolution(
                        DdgiSettings.MAXIMUM_DEPTH_FACE_RESOLUTION + 1).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().borderFadeMetres(-0.01f).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().maximumIrradiance(DdgiSettings.MAXIMUM_IRRADIANCE + 1.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().maximumRayDistance(DdgiSettings.MINIMUM_RAY_DISTANCE - 0.01f).build());
    }

    @Test
    void rayDistanceMustReachAtLeastOneSpacing() {
        assertThrows(IllegalArgumentException.class,
                () -> builder().spacingMetres(4.0f).maximumRayDistance(3.5f).build());
        DdgiSettings exact = builder().spacingMetres(4.0f).maximumRayDistance(4.0f).build();
        assertEquals(4.0f, exact.maximumRayDistance(), 0.0f);
    }

    private static DdgiSettings.Builder builder() {
        return DdgiSettings.builder()
                .enabled(true)
                .gridOrigin(0.0f, 0.0f, 0.0f)
                .spacingMetres(2.0f)
                .probes(1, 1, 1)
                .maximumRayDistance(4.0f);
    }
}
