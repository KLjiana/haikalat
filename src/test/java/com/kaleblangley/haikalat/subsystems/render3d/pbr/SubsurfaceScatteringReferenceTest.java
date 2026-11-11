package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings.Profile;
import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings.ThicknessSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixed-input checks for the independent double reference of the forward
 * transmission, thickness proxies, screen-space radius and Poisson kernel.
 * Expected values are evaluated from the closed form in this test, never from
 * the production Java helpers.
 */
class SubsurfaceScatteringReferenceTest {
    private static final double TOLERANCE = 1.0e-12;

    @Test
    void profilePenetrationMatchesTheDocumentedRatios() {
        assertArrayEquals(new double[]{1.0, 0.55, 0.30},
                SubsurfaceScatteringReference.penetration(Profile.SKIN), TOLERANCE);
        assertArrayEquals(new double[]{1.0, 1.0, 1.0},
                SubsurfaceScatteringReference.penetration(Profile.UNIFORM), TOLERANCE);
    }

    @Test
    void thicknessAttenuationIsExponentialPerChannel() {
        double thickness = 0.02;
        double scale = 0.02;
        assertArrayEquals(new double[]{
                        Math.exp(-1.0),
                        Math.exp(-1.0 / 0.55),
                        Math.exp(-1.0 / 0.30)},
                SubsurfaceScatteringReference.attenuation(thickness, scale, Profile.SKIN),
                1.0e-15);
        assertArrayEquals(new double[]{Math.exp(-2.0), Math.exp(-2.0), Math.exp(-2.0)},
                SubsurfaceScatteringReference.attenuation(0.04, 0.02, Profile.UNIFORM),
                1.0e-15);
        assertArrayEquals(new double[]{1.0, 1.0, 1.0},
                SubsurfaceScatteringReference.attenuation(0.0, 0.02, Profile.SKIN),
                0.0);
    }

    @Test
    void attenuationIsMonotoneAndBoundedForTheProductionClamp() {
        double previous = 1.0;
        for (double thickness = 0.0; thickness <= 2.0; thickness += 0.01) {
            double[] attenuation = SubsurfaceScatteringReference.attenuation(
                    thickness, 0.02, Profile.SKIN);
            assertTrue(attenuation[0] >= 0.0 && attenuation[0] <= 1.0);
            assertTrue(attenuation[0] <= previous + TOLERANCE,
                    "attenuation must not increase with thickness");
            previous = attenuation[0];
        }
        assertTrue(SubsurfaceScatteringReference.attenuation(2.0, 0.001, Profile.SKIN)[2]
                < 1.0e-20, "a very thin mean free path absorbs a thick layer completely");
    }

    @Test
    void backLightTransmissionUsesOnlyTheBackFacingCosine() {
        double thickness = 0.02;
        double scale = 0.02;
        double backLit = SubsurfaceScatteringReference.transmissionWeight(
                -0.5, thickness, scale, Profile.SKIN, 0);
        assertEquals(0.5 * Math.exp(-1.0), backLit, 1.0e-15);
        assertEquals(0.0, SubsurfaceScatteringReference.transmissionWeight(
                0.5, thickness, scale, Profile.SKIN, 0), 0.0,
                "a front-facing light must not create a transmission term");
        assertEquals(0.0, SubsurfaceScatteringReference.transmissionWeight(
                0.0, thickness, scale, Profile.SKIN, 1), 0.0,
                "the term is exactly zero at the terminator");
        assertEquals(1.0, SubsurfaceScatteringReference.transmissionWeight(
                -2.0, 0.0, scale, Profile.SKIN, 0), 1.0,
                "the back-facing cosine and zero thickness are clamped to one");
    }

    @Test
    void thicknessSourcesResolveTheSameThreeContracts() {
        assertEquals(0.4, SubsurfaceScatteringReference.thicknessMetres(
                0.4, ThicknessSource.CONSTANT, 0.0, 0.0), TOLERANCE);
        assertEquals(0.3, SubsurfaceScatteringReference.thicknessMetres(
                0.4, ThicknessSource.OCCLUSION, 0.25, 0.0), 1.0e-15);
        assertEquals(0.04, SubsurfaceScatteringReference.thicknessMetres(
                0.4, ThicknessSource.BASE_ALPHA, 0.0, 0.9), 1.0e-15);
        assertEquals(2.0, SubsurfaceScatteringReference.thicknessMetres(
                4.0, ThicknessSource.CONSTANT, 0.0, 0.0), 0.0,
                "the authored thickness is bounded to two metres");
        assertEquals(0.0, SubsurfaceScatteringReference.thicknessMetres(
                0.4, ThicknessSource.OCCLUSION, 2.0, 0.0), 0.0,
                "the occlusion proxy is clamped to [0, 1]");
    }

    @Test
    void effectiveRadiusNarrowsWithThicknessAndStaysBounded() {
        double thin = SubsurfaceScatteringReference.effectiveRadiusMetres(
                0.0, 0.02, Profile.SKIN, 0.5);
        assertEquals(0.5, thin, 1.0e-15);
        double thick = SubsurfaceScatteringReference.effectiveRadiusMetres(
                0.2, 0.02, Profile.SKIN, 0.5);
        assertTrue(thick > 0.0 && thick < thin, "thicker layers must have a narrower halo");
        for (double thickness = 0.0; thickness <= 2.0; thickness += 0.05) {
            double radius = SubsurfaceScatteringReference.effectiveRadiusMetres(
                    thickness, 0.02, Profile.SKIN, 5.0);
            assertTrue(radius >= 0.0 && radius <= 2.0);
        }
    }

    @Test
    void poissonKernelIsSymmetricBoundedAndConsistent() {
        assertEquals(SubsurfaceScatteringReference.POISSON_TAPS.length, 16);
        double center = SubsurfaceScatteringReference.diffusionWeight(0.0, 10.0);
        assertEquals(1.0, center, TOLERANCE);
        assertEquals(1.0 / Math.exp(2.5),
                SubsurfaceScatteringReference.diffusionWeight(10.0, 10.0), 1.0e-15);
        assertEquals(SubsurfaceScatteringReference.diffusionWeight(3.0, 10.0),
                SubsurfaceScatteringReference.diffusionWeight(-3.0, 10.0), 0.0);
        for (double[] tap : SubsurfaceScatteringReference.POISSON_TAPS) {
            double radius = Math.hypot(tap[0], tap[1]);
            assertTrue(radius <= 1.25,
                    "tap " + Arrays.toString(tap) + " must stay inside the bounded disk");
        }
        double sum = SubsurfaceScatteringReference.tapWeightSum(10.0);
        assertTrue(sum > 0.0 && sum < 16.0, "the kernel sum stays bounded");
        assertTrue(Double.isFinite(sum));
    }
}
