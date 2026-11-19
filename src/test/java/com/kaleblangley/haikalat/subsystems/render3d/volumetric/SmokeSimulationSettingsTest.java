package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration contract for the bounded smoke simulation.
 *
 * <p>Locks the disabled no-op value, every bounded rejection, the exact upwind
 * CFL stability limit and defensive copying. The numerical integration itself
 * is covered by {@code SmokeSimulationReferenceTest}; the real-GL consumer is
 * covered by the guarded {@code SmokeSimulationGlTest}.</p>
 */
class SmokeSimulationSettingsTest {
    private static final float TOLERANCE = 1.0e-5f;

    private static SmokeSimulationSettings settings(int grid, float halfExtent, float dt,
                                                    Vector3f wind, float buoyancy,
                                                    List<SmokeSimulationSettings.Source> sources) {
        return new SmokeSimulationSettings(true, grid, grid, grid, new Vector3f(),
                new Vector3f(halfExtent), dt, wind, buoyancy, 4.0f, 1.0f, 2.0f, 4, sources);
    }

    private static SmokeSimulationSettings base() {
        return settings(32, 4.0f, 1.0f / 60.0f, new Vector3f(0.5f, 0.0f, 0.25f),
                1.0f, List.of());
    }

    @Test
    void disabledIsTheExactNoOpValue() {
        SmokeSimulationSettings disabled = SmokeSimulationSettings.disabled();
        assertFalse(disabled.enabled());
        assertFalse(disabled.active());
        assertTrue(disabled.sources().isEmpty());
        assertTrue(disabled.withEnabled(true).enabled());
        assertTrue(disabled.withEnabled(true).active());
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeSimulation(disabled),
                "disabled settings must not create a simulation");
    }

    @Test
    void productionQualityTiersAreAccepted() {
        for (int grid : new int[]{32, 48, 64}) {
            SmokeSimulationSettings tier = settings(grid, 4.0f, 1.0f / 60.0f,
                    new Vector3f(0.5f, 0.0f, 0.25f), 1.0f, List.of());
            assertEquals(grid, tier.gridWidth());
            assertTrue(tier.maximumStableTimeStepSeconds() >= tier.timeStepSeconds(),
                    "the documented quality tier must satisfy its own CFL bound");
            assertEquals(2L * (long) grid * grid * grid * 8L, tier.estimatedBytes());
        }
    }

    @Test
    void gridBoundsAndTimeStepAreBounded() {
        Vector3f zero = new Vector3f();
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeSimulationSettings(true, 7, 32, 32, zero, new Vector3f(4.0f),
                        1.0f / 60.0f, zero, 0.0f, 4.0f, 0.0f, 0.0f, 4, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeSimulationSettings(true, 32, 65, 32, zero, new Vector3f(4.0f),
                        1.0f / 60.0f, zero, 0.0f, 4.0f, 0.0f, 0.0f, 4, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeSimulationSettings(true, 32, 32, 32, zero,
                        new Vector3f(0.0f, 4.0f, 4.0f), 1.0f / 60.0f, zero, 0.0f, 4.0f,
                        0.0f, 0.0f, 4, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeSimulationSettings(true, 32, 32, 32, zero, new Vector3f(4.0f),
                        Float.NaN, zero, 0.0f, 4.0f, 0.0f, 0.0f, 4, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeSimulationSettings(true, 32, 32, 32, zero, new Vector3f(4.0f),
                        0.0f, zero, 0.0f, 4.0f, 0.0f, 0.0f, 4, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeSimulationSettings(true, 32, 32, 32, zero, new Vector3f(4.0f),
                        SmokeSimulationSettings.MAXIMUM_TIME_STEP + 1.0e-3f, zero, 0.0f, 4.0f,
                        0.0f, 0.0f, 4, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeSimulationSettings(true, 32, 32, 32, zero, new Vector3f(4.0f),
                        1.0f / 60.0f, zero, -1.0f, 4.0f, 0.0f, 0.0f, 4, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeSimulationSettings(true, 32, 32, 32, zero, new Vector3f(4.0f),
                        1.0f / 60.0f, zero, 0.0f, 0.0f, 0.0f, 0.0f, 4, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeSimulationSettings(true, 32, 32, 32, zero, new Vector3f(4.0f),
                        1.0f / 60.0f, zero, 0.0f, 4.0f, -0.1f, 0.0f, 4, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeSimulationSettings(true, 32, 32, 32, zero, new Vector3f(4.0f),
                        1.0f / 60.0f, zero, 0.0f, 4.0f, 0.0f, 0.0f, 9, List.of()));
    }

    @Test
    void cflViolatingStepIsRejectedAndTheLimitIsAccepted() {
        Vector3f wind = new Vector3f(2.0f, 0.0f, 1.0f);
        SmokeSimulationSettings stable = settings(32, 4.0f, 1.0f / 60.0f, wind, 2.0f, List.of());
        float limit = stable.maximumStableTimeStepSeconds();
        assertTrue(limit < SmokeSimulationSettings.MAXIMUM_TIME_STEP);
        assertThrows(IllegalArgumentException.class,
                () -> settings(32, 4.0f, limit * 1.05f, wind, 2.0f, List.of()),
                "a step above the explicit upwind CFL bound must be rejected");
        SmokeSimulationSettings atLimit = settings(32, 4.0f, limit, wind, 2.0f, List.of());
        assertTrue(atLimit.timeStepSeconds() <= atLimit.maximumStableTimeStepSeconds());
    }

    @Test
    void sourcesAreBoundedByCountGeometryAndWindows() {
        SmokeSimulationSettings.Source inside = new SmokeSimulationSettings.Source(
                new Vector3f(0.0f, 0.0f, 0.0f), 1.0f, 2.0f, 1.0f, 0.0f, 10.0f);
        SmokeSimulationSettings valid = settings(32, 4.0f, 1.0f / 60.0f, new Vector3f(),
                1.0f, List.of(inside));
        assertEquals(1, valid.sources().size());
        assertTrue(inside.activeAt(0.0));
        assertTrue(inside.activeAt(9.999));
        assertFalse(inside.activeAt(10.0), "the source window is half-open");
        assertFalse(inside.activeAt(-0.1));

        SmokeSimulationSettings.Source outside = new SmokeSimulationSettings.Source(
                new Vector3f(3.5f, 0.0f, 0.0f), 1.0f, 1.0f, 0.0f, 0.0f, 10.0f);
        assertThrows(IllegalArgumentException.class,
                () -> settings(32, 4.0f, 1.0f / 60.0f, new Vector3f(), 1.0f, List.of(outside)),
                "a source sphere must lie inside the grid bounds");
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeSimulationSettings.Source(new Vector3f(), 0.0f, 1.0f, 0.0f,
                        0.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeSimulationSettings.Source(new Vector3f(), 1.0f, -1.0f, 0.0f,
                        0.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeSimulationSettings.Source(new Vector3f(), 1.0f, 1.0f, 0.0f,
                        1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeSimulationSettings.Source(new Vector3f(), 1.0f, 1.0f, 0.0f,
                        0.0f, 2.0f * SmokeSimulationSettings.MAXIMUM_SOURCE_TIME));
        List<SmokeSimulationSettings.Source> tooMany = List.of(inside, inside, inside, inside,
                inside);
        assertThrows(IllegalArgumentException.class,
                () -> settings(32, 4.0f, 1.0f / 60.0f, new Vector3f(), 1.0f, tooMany));
    }

    @Test
    void vectorsAndSourcesAreDefensivelyCopied() {
        SmokeSimulationSettings.Source source = new SmokeSimulationSettings.Source(
                new Vector3f(1.0f, 2.0f, 3.0f), 1.0f, 2.0f, 1.0f, 0.0f, 10.0f);
        Vector3f wind = new Vector3f(0.5f, 0.0f, 0.25f);
        SmokeSimulationSettings settings = settings(32, 4.0f, 1.0f / 60.0f, wind,
                1.0f, List.of(source));
        wind.y = 99.0f;
        assertEquals(0.0f, settings.windVelocity().y, 0.0f,
                "the constructor must copy the caller's wind vector");
        Vector3f center = settings.boundsCenter();
        center.x = 99.0f;
        assertEquals(0.0f, settings.boundsCenter().x, 0.0f);
        Vector3f accessorWind = settings.windVelocity();
        accessorWind.y = 99.0f;
        assertEquals(0.0f, settings.windVelocity().y, 0.0f);
        Vector3f sourceCenter = settings.sources().get(0).center();
        sourceCenter.z = 99.0f;
        assertEquals(3.0f, settings.sources().get(0).center().z, 0.0f);
        assertThrows(UnsupportedOperationException.class,
                () -> settings.sources().add(source));
        assertThrows(IllegalArgumentException.class,
                () -> settings(32, 4.0f, 1.0f / 60.0f, new Vector3f(Float.NaN, 0.0f, 0.0f),
                        1.0f, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> settings(32, 4.0f, 1.0f / 60.0f,
                        new Vector3f(SmokeSimulationSettings.MAXIMUM_WIND_SPEED + 1.0f, 0.0f,
                                0.0f), 1.0f, List.of()));
    }

    @Test
    void cellGeometryAndStabilityHelpersAreConsistent() {
        SmokeSimulationSettings settings = settings(32, 4.0f, 1.0f / 60.0f,
                new Vector3f(1.0f, 0.0f, 0.0f), 2.0f, List.of());
        Vector3f cell = settings.cellSize();
        assertEquals(0.25f, cell.x, TOLERANCE);
        assertEquals(0.25f, cell.y, TOLERANCE);
        assertEquals(0.25f, cell.z, TOLERANCE);
        assertEquals(0.25f, settings.minimumCellSize(), TOLERANCE);
        assertEquals(-4.0f, settings.gridMinimum().x, TOLERANCE);
        assertEquals(32 * 32 * 32, settings.cellCount());
        double rate = 1.0 / 0.25 + (2.0 * 4.0) / 0.25;
        assertEquals(1.0 / rate, settings.maximumStableTimeStepSeconds(), 1.0e-5f);
        assertEquals(settings.withEnabled(true), settings, "record equality is value-based");
        assertEquals(settings.withEnabled(false).gridWidth(), settings.gridWidth());
    }
}
