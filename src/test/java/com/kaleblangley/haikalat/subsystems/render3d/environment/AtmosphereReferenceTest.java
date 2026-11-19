package com.kaleblangley.haikalat.subsystems.render3d.environment;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Numerical contract for the independent double-precision atmosphere reference.
 *
 * <p>Expected phase values are analytic; the optical-depth/transmittance
 * expectations use the closed-form radial integral for a zenith ray; the two
 * single-scattering samples were produced by a separate double-precision Python
 * implementation of the same documented equations with the same uniform
 * quadrature.  The GL test uses this class as its oracle, so these values pin
 * the oracle itself.</p>
 */
class AtmosphereReferenceTest {
    private static final double EPSILON = 1.0e-12;

    @Test
    void phaseFunctionsMatchAnalyticValues() {
        assertEquals(3.0 / (16.0 * Math.PI), AtmosphereReference.rayleighPhase(0.0), EPSILON);
        assertEquals(3.0 / (16.0 * Math.PI) * 1.25,
                AtmosphereReference.rayleighPhase(0.5), EPSILON);
        assertEquals(3.0 / (8.0 * Math.PI), AtmosphereReference.rayleighPhase(1.0), EPSILON);
        assertEquals(0.016963626397647751,
                AtmosphereReference.miePhaseHenyeyGreenstein(0.0, 0.76), EPSILON);
        assertEquals(0.045467672308637234,
                AtmosphereReference.miePhaseHenyeyGreenstein(0.5, 0.76), EPSILON);
        assertEquals(2.4315338527928536,
                AtmosphereReference.miePhaseHenyeyGreenstein(1.0, 0.76), EPSILON);
    }

    @Test
    void phaseFunctionsIntegrateToUnityOverTheSphere() {
        assertEquals(1.0, sphereIntegral(0.0, true), 1.0e-7);
        assertEquals(1.0, sphereIntegral(0.76, false), 1.0e-6);
        for (double g : new double[]{-0.9, -0.5, 0.0, 0.5, 0.9}) {
            double integral = sphereIntegral(g, false);
            assertTrue(Double.isFinite(integral));
            assertEquals(1.0, integral, 2.0e-5, "Henyey-Greenstein g=" + g);
        }
    }

    @Test
    void zenithOpticalDepthMatchesTheClosedFormRadialIntegral() {
        AtmosphereSettings settings = AtmosphereSettings.earth();
        double expectedRayleigh = settings.rayleighScaleHeightMeters()
                * (1.0 - Math.exp(-settings.atmosphereThicknessMeters()
                / settings.rayleighScaleHeightMeters()));
        double expectedMie = settings.mieScaleHeightMeters()
                * (1.0 - Math.exp(-settings.atmosphereThicknessMeters()
                / settings.mieScaleHeightMeters()));
        double[] depth = AtmosphereReference.opticalDepth(settings,
                settings.planetRadiusMeters(), 1.0, 8192);
        assertEquals(expectedRayleigh, depth[0], 1.0e-2);
        assertEquals(expectedMie, depth[1], 1.0e-2);
    }

    @Test
    void zenithTransmittanceMatchesTheFixedReferenceSample() {
        AtmosphereSettings settings = AtmosphereSettings.earth();
        double[] transmittance = AtmosphereReference.transmittance(settings,
                settings.planetRadiusMeters(), 1.0, 8192);
        assertEquals(0.94957211648649986, transmittance[0], 1.0e-6);
        assertEquals(0.89244395546486333, transmittance[1], 1.0e-6);
        assertEquals(0.7632825783777385, transmittance[2], 1.0e-6);
    }

    @Test
    void transmittanceIsBoundedAndMonotonicTowardsTheHorizon() {
        AtmosphereSettings settings = AtmosphereSettings.earth();
        double previous = Double.POSITIVE_INFINITY;
        for (double mu : new double[]{1.0, 0.75, 0.5, 0.25, 0.1, 0.05, 0.01}) {
            double[] values = AtmosphereReference.transmittance(settings,
                    settings.planetRadiusMeters(), mu, 4096);
            assertTrue(values[0] > values[1] && values[1] > values[2],
                    "blue is attenuated most at mu=" + mu);
            assertTrue(values[2] < previous, "transmittance decreases with air mass");
            previous = values[2];
        }
        double[] horizon = AtmosphereReference.transmittance(settings,
                settings.planetRadiusMeters(), 0.01, 4096);
        assertTrue(horizon[2] < horizon[0] * 0.05);
        assertEquals(0.0, AtmosphereReference.transmittance(settings,
                settings.planetRadiusMeters(), 0.0, 64)[0], 0.0);
    }

    @Test
    void sunDirectionAndHorizonGeometryFollowTheEngineConvention() {
        AtmosphereSettings settings = AtmosphereSettings.earth();
        assertEquals(60.0,
                Math.toDegrees(AtmosphereReference.sunElevationRadians(settings.sunDirection())),
                1.0e-3);
        assertEquals(0.0,
                Math.toDegrees(AtmosphereReference.sunAzimuthRadians(settings.sunDirection())),
                1.0e-3);
        var east = new Vector3f(-0.8660254f, -0.5f, 0.0f);
        assertEquals(30.0, Math.toDegrees(AtmosphereReference.sunElevationRadians(east)),
                1.0e-3);
        assertEquals(90.0, Math.toDegrees(AtmosphereReference.sunAzimuthRadians(east)),
                1.0e-3);

        double dip = AtmosphereReference.horizonDipRadians(
                settings.planetRadiusMeters(), 1_000.0);
        assertEquals(0.017716698486771397, dip, 1.0e-12);
        assertEquals(0.0, AtmosphereReference.horizonDipRadians(
                settings.planetRadiusMeters(), 0.0), 0.0);
        assertTrue(dip > 0.0);
        // A sun exactly on the local horizontal is hidden for a ground observer;
        // an observer at 1 km may still see a sun this far below the horizontal.
        double horizonElevation = -dip;
        assertTrue(Math.toDegrees(horizonElevation) < -1.0);
        assertTrue(Math.toDegrees(horizonElevation) > -1.1);
    }

    @Test
    void zenithSingleScatteringMatchesTheFixedReferenceSample() {
        AtmosphereSettings settings = AtmosphereSettings.earth();
        double[] radiance = AtmosphereReference.singleScattering(settings, 0.0, 0.0, 0.0,
                2048, 512);
        assertEquals(7.79271703399, radiance[0], 7.79271703399 * 1.0e-3);
        assertEquals(15.0912311239, radiance[1], 15.0912311239 * 1.0e-3);
        assertEquals(29.3716149501, radiance[2], 29.3716149501 * 1.0e-3);
        assertTrue(radiance[2] > radiance[1] && radiance[1] > radiance[0]);
        assertTrue(AtmosphereReference.luminance(radiance) > 0.0);
    }

    @Test
    void horizonSingleScatteringMatchesTheFixedReferenceSampleAndScatteringRelations() {
        AtmosphereSettings settings = AtmosphereSettings.earth();
        double[] horizon = AtmosphereReference.singleScattering(settings, 0.0,
                Math.PI * 0.5, 0.0, 2048, 512);
        assertEquals(73.0120233138, horizon[0], 73.0120233138 * 1.0e-3);
        assertEquals(81.0066400978, horizon[1], 81.0066400978 * 1.0e-3);
        assertEquals(71.7476044709, horizon[2], 71.7476044709 * 1.0e-3);
        double[] zenith = AtmosphereReference.singleScattering(settings, 0.0, 0.0, 0.0,
                2048, 512);
        assertTrue(AtmosphereReference.luminance(horizon)
                > AtmosphereReference.luminance(zenith) * 2.0);
        assertTrue(zenith[2] / zenith[0] > horizon[2] / horizon[0] * 1.5,
                "the zenith must be substantially bluer than the horizon");

        double[] highSun = AtmosphereReference.singleScattering(settings, 0.0, 0.0, 0.0,
                1024, 256);
        AtmosphereSettings lowSunSettings = settings.withSunDirection(
                sunDirection(20.0, 0.0));
        double[] lowSun = AtmosphereReference.singleScattering(lowSunSettings, 0.0, 0.0, 0.0,
                1024, 256);
        assertTrue(AtmosphereReference.luminance(highSun)
                > AtmosphereReference.luminance(lowSun) * 1.3,
                "lowering the sun must dim the zenith");
        AtmosphereSettings highSunSettings = settings;
        double[] highHorizon = AtmosphereReference.singleScattering(highSunSettings, 0.0,
                Math.PI * 0.5, 0.0, 1024, 256);
        double[] lowHorizon = AtmosphereReference.singleScattering(lowSunSettings, 0.0,
                Math.PI * 0.5, 0.0, 1024, 256);
        assertTrue(AtmosphereReference.luminance(lowHorizon)
                > AtmosphereReference.luminance(highHorizon) * 1.2,
                "a lower sun brightens the sun-side horizon");
    }

    private static double sphereIntegral(double g, boolean rayleigh) {
        int samples = 20_000;
        double sum = 0.0;
        double step = 2.0 / samples;
        for (int index = 0; index < samples; index++) {
            double cosine = -1.0 + (index + 0.5) * step;
            sum += (rayleigh ? AtmosphereReference.rayleighPhase(cosine)
                    : AtmosphereReference.miePhaseHenyeyGreenstein(cosine, g)) * step;
        }
        return 2.0 * Math.PI * sum;
    }

    private static Vector3f sunDirection(double elevationDegrees, double azimuthDegrees) {
        double elevation = Math.toRadians(elevationDegrees);
        double azimuth = Math.toRadians(azimuthDegrees);
        return new Vector3f((float) (-Math.cos(elevation) * Math.sin(azimuth)),
                (float) -Math.sin(elevation),
                (float) (-Math.cos(elevation) * Math.cos(azimuth)));
    }
}
