package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent double-precision reference for the smoke integration scheme.
 *
 * <p>Fixed expected values were produced by a separate scratch calculation in
 * a different language (see the technical card), not by calling
 * {@link SmokeSimulationReference}. The cases pin the documented conservation,
 * exponential dissipation, upwind advection, closed-boundary, injection-window
 * and stability behaviour; the guarded {@code SmokeSimulationGlTest} reuses the
 * same reference for field comparison.</p>
 */
class SmokeSimulationReferenceTest {
    private static final double EXACT = 1.0e-9;

    private static SmokeSimulationSettings settings(int grid, float halfExtent, float dt,
                                                    Vector3f wind, float buoyancy,
                                                    float maximumTemperature,
                                                    float densityDissipation,
                                                    List<SmokeSimulationSettings.Source> sources) {
        return new SmokeSimulationSettings(true, grid, grid, grid, new Vector3f(),
                new Vector3f(halfExtent), dt, wind, buoyancy, maximumTemperature,
                densityDissipation, 0.0f, 8, sources);
    }

    private static void fill(SmokeSimulationReference.Grid grid, double density,
                             double temperature) {
        for (int z = 0; z < grid.nz(); z++) {
            for (int y = 0; y < grid.ny(); y++) {
                for (int x = 0; x < grid.nx(); x++) {
                    grid.set(x, y, z, density, temperature);
                }
            }
        }
    }

    private static double centerOfMassY(SmokeSimulationReference.Grid grid,
                                        SmokeSimulationSettings settings) {
        Vector3f minimum = settings.gridMinimum();
        Vector3f cell = settings.cellSize();
        double weighted = 0.0;
        double total = 0.0;
        for (int z = 0; z < grid.nz(); z++) {
            for (int y = 0; y < grid.ny(); y++) {
                for (int x = 0; x < grid.nx(); x++) {
                    double density = grid.density(x, y, z);
                    weighted += density * (minimum.y + (y + 0.5) * cell.y);
                    total += density;
                }
            }
        }
        return weighted / total;
    }

    @Test
    void uniformDissipationFollowsTheExactExponential() {
        SmokeSimulationSettings settings = settings(8, 4.0f, 0.1f, new Vector3f(), 0.0f, 4.0f,
                2.0f, List.of());
        SmokeSimulationReference.Grid grid = SmokeSimulationReference.empty(settings);
        fill(grid, 1.0, 0.0);
        assertEquals(512.0, grid.totalDensity(), EXACT);
        SmokeSimulationReference.step(grid, settings, 0);
        assertEquals(419.1901443266459, grid.totalDensity(), 1.0e-9);
        SmokeSimulationReference.step(grid, settings, 1);
        SmokeSimulationReference.step(grid, settings, 2);
        SmokeSimulationReference.step(grid, settings, 3);
        assertEquals(230.05642688553382, grid.totalDensity(), 1.0e-9);
        assertEquals(0.0, grid.maximumTemperature(), 0.0);
    }

    @Test
    void deltaAdvectionPreservesMassAndMatchesTheFixedDistribution() {
        SmokeSimulationSettings settings = settings(8, 4.0f, 0.1f, new Vector3f(1.0f, 0.0f, 0.0f),
                0.0f, 4.0f, 0.0f, List.of());
        SmokeSimulationReference.Grid grid = SmokeSimulationReference.empty(settings);
        grid.set(3, 4, 4, 1.0, 0.0);
        SmokeSimulationReference.step(grid, settings, 0);
        assertEquals(0.8999999985098839, grid.density(3, 4, 4), 1.0e-12);
        assertEquals(0.10000000149011612, grid.density(4, 4, 4), 1.0e-12);
        SmokeSimulationReference.step(grid, settings, 1);
        SmokeSimulationReference.step(grid, settings, 2);
        assertEquals(0.7289999963790178, grid.density(3, 4, 4), 1.0e-12);
        assertEquals(0.24300000281631945, grid.density(4, 4, 4), 1.0e-12);
        assertEquals(0.027000000759959227, grid.density(5, 4, 4), 1.0e-12);
        assertEquals(0.0010000000447034842, grid.density(6, 4, 4), 1.0e-12);
        assertEquals(0.0, grid.density(7, 4, 4), 0.0);
        assertEquals(1.0, grid.totalDensity(), 1.0e-12);
    }

    @Test
    void closedBoundaryConservesMassUnderOutwardWind() {
        SmokeSimulationSettings settings = settings(8, 4.0f, 0.1f, new Vector3f(2.0f, 0.0f, 0.0f),
                0.0f, 4.0f, 0.0f, List.of());
        SmokeSimulationReference.Grid grid = SmokeSimulationReference.empty(settings);
        SmokeSimulationReference.seedSphere(grid, settings, 0.0, 0.0, 0.0, 2.0, 1.0, 0.0);
        double initial = grid.totalDensity();
        assertTrue(initial > 0.0);
        for (long step = 0; step < 20; step++) {
            SmokeSimulationReference.step(grid, settings, step);
        }
        assertEquals(initial, grid.totalDensity(), initial * 1.0e-12,
                "closed faces must conserve total density exactly");
    }

    @Test
    void buoyancyRaisesThePlumeAndKeepsMassBounded() {
        SmokeSimulationSettings settings = settings(8, 4.0f, 0.1f, new Vector3f(), 1.0f, 4.0f,
                0.0f, List.of());
        SmokeSimulationReference.Grid grid = SmokeSimulationReference.empty(settings);
        SmokeSimulationReference.seedSphere(grid, settings, 0.0, -2.0, 0.0, 1.5, 1.0, 4.0);
        double initialMass = grid.totalDensity();
        double initialHeight = centerOfMassY(grid, settings);
        for (long step = 0; step < 20; step++) {
            SmokeSimulationReference.step(grid, settings, step);
        }
        assertEquals(initialMass, grid.totalDensity(), initialMass * 1.0e-12);
        assertTrue(centerOfMassY(grid, settings) > initialHeight + 0.05,
                "buoyancy must lift the density plume");
        assertTrue(grid.maximumTemperature() <= settings.maximumTemperature() + 1.0e-12);
    }

    @Test
    void injectionMatchesTheFixedSourceWeightSum() {
        SmokeSimulationSettings.Source source = new SmokeSimulationSettings.Source(
                new Vector3f(), 1.5f, 2.0f, 1.0f, 0.0f, 10.0f);
        SmokeSimulationSettings settings = settings(8, 4.0f, 0.05f, new Vector3f(), 0.0f, 4.0f,
                0.0f, List.of(source));
        SmokeSimulationReference.Grid grid = SmokeSimulationReference.empty(settings);
        SmokeSimulationReference.step(grid, settings, 0);
        assertEquals(0.3555555608537463, grid.totalDensity(), 1.0e-12);
        SmokeSimulationReference.step(grid, settings, 1);
        SmokeSimulationReference.step(grid, settings, 2);
        assertEquals(1.066666682561239, grid.totalDensity(), 1.0e-12);
        assertEquals(0.06666666766007741, grid.maximumTemperature(), 1.0e-12);
    }

    @Test
    void burstWindowStopsInjectionAndDissipationDecaysTheRetainedMass() {
        SmokeSimulationSettings.Source source = new SmokeSimulationSettings.Source(
                new Vector3f(), 1.5f, 2.0f, 1.0f, 0.0f, 0.2f);
        SmokeSimulationSettings settings = settings(8, 4.0f, 0.1f, new Vector3f(), 0.0f, 4.0f,
                1.0f, List.of(source));
        SmokeSimulationReference.Grid grid = SmokeSimulationReference.empty(settings);
        for (long step = 0; step < 2; step++) {
            SmokeSimulationReference.step(grid, settings, step);
        }
        double injected = grid.totalDensity();
        assertTrue(injected > 0.0);
        SmokeSimulationReference.step(grid, settings, 2);
        SmokeSimulationReference.step(grid, settings, 3);
        assertEquals(injected * Math.exp(-2.0 * 0.1f), grid.totalDensity(), injected * 1.0e-12);
    }

    @Test
    void inactiveSourceWindowsAddNothing() {
        SmokeSimulationSettings.Source source = new SmokeSimulationSettings.Source(
                new Vector3f(), 1.5f, 2.0f, 1.0f, 1.0f, 2.0f);
        SmokeSimulationSettings settings = settings(8, 4.0f, 0.1f, new Vector3f(), 0.0f, 4.0f,
                0.0f, List.of(source));
        SmokeSimulationReference.Grid grid = SmokeSimulationReference.empty(settings);
        for (long step = 0; step < 10; step++) {
            SmokeSimulationReference.step(grid, settings, step);
        }
        assertEquals(0.0, grid.totalDensity(), 0.0,
                "steps at times below startSeconds must not inject");
        SmokeSimulationReference.step(grid, settings, 10);
        assertTrue(grid.totalDensity() > 0.0);
    }

    @Test
    void nearCflAdvectionStaysFiniteAndBounded() {
        SmokeSimulationSettings settings = settings(8, 4.0f, 0.1f, new Vector3f(0.0f, 9.0f, 0.0f),
                0.0f, 4.0f, 0.0f, List.of());
        assertTrue(settings.timeStepSeconds() <= settings.maximumStableTimeStepSeconds());
        SmokeSimulationReference.Grid grid = SmokeSimulationReference.empty(settings);
        SmokeSimulationReference.seedSphere(grid, settings, 0.0, -2.0, 0.0, 2.0, 1.0, 0.0);
        double initial = grid.totalDensity();
        double peak = grid.maximumDensity();
        for (long step = 0; step < 200; step++) {
            SmokeSimulationReference.step(grid, settings, step);
        }
        assertEquals(initial, grid.totalDensity(), initial * 1.0e-10);
        assertTrue(grid.maximumDensity() <= initial + 1.0e-12,
                "closed upwind advection cannot exceed the conserved total mass");
        assertTrue(grid.maximumDensity() >= peak,
                "the closed lid accumulates density instead of leaking it");
        for (int z = 0; z < grid.nz(); z++) {
            for (int y = 0; y < grid.ny(); y++) {
                for (int x = 0; x < grid.nx(); x++) {
                    double density = grid.density(x, y, z);
                    assertTrue(Double.isFinite(density) && density >= 0.0);
                }
            }
        }
    }
}
