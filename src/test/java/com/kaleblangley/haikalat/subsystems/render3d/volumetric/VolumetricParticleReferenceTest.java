package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent double-precision contract for the local particle medium.
 *
 * <p>The expected constants were computed from the documented formula in a
 * separate scratch calculation (not by {@link VolumetricParticleReference}) so
 * a change to the reference and its expected values cannot move together. This
 * test is GL-free; the guarded {@code VolumetricParticleGlTest} reuses the same
 * reference for pixel comparison.</p>
 */
class VolumetricParticleReferenceTest {
    private static final double EXACT = 1.0e-9;
    private static final double SCATTER_TOLERANCE = 1.0e-6;

    private static VolumetricParticleSettings settings() {
        return settings(48, 0.05f, 1.0f);
    }

    private static VolumetricParticleSettings settings(int steps, float stepSize, float weight) {
        return new VolumetricParticleSettings(true, 4.0f, 0.2f, 0.8f,
                new Vector3f(0.0f), new Vector3f(1.5f), steps, stepSize,
                new Vector3f(0.3f, -1.0f, 0.2f), new Vector3f(3.5f, 3.2f, 3.0f),
                0.2f, VolumetricParticleSettings.FogBlend.OCCLUDING, weight, 1.0f);
    }

    private static VolumetricParticleReference.Ray centerRay() {
        return new VolumetricParticleReference.Ray(0.0, 0.0, 8.0,
                0.0, 0.0, -1.0, 1.0e30);
    }

    private static VolumetricParticleReference.Ray parallelRay(double offsetY,
                                                               double maxDistance) {
        return new VolumetricParticleReference.Ray(0.0, offsetY, 8.0,
                0.0, 0.0, -1.0, maxDistance);
    }

    @Test
    void analyticOpticalDepthMatchesTheFixedClosedForms() {
        VolumetricParticleSettings settings = settings();
        assertEquals(6.399999999999999,
                VolumetricParticleReference.analyticOpticalDepth(settings, centerRay()), EXACT);
        assertEquals(3.1999999999999997,
                VolumetricParticleReference.analyticOpticalDepth(settings,
                        parallelRay(0.0, 8.0)), EXACT);
        assertEquals(3.1176914536239657,
                VolumetricParticleReference.analyticOpticalDepth(settings,
                        parallelRay(0.75, 1.0e30)), EXACT);
        assertEquals(0.4976640000000008,
                VolumetricParticleReference.analyticOpticalDepth(settings,
                        parallelRay(1.2, 1.0e30)), EXACT);
        assertEquals(0.0,
                VolumetricParticleReference.analyticOpticalDepth(settings,
                        parallelRay(2.0, 1.0e30)), 0.0,
                "a ray that misses the ellipsoid has exactly zero optical depth");
        assertEquals(3.1999999999999997,
                VolumetricParticleReference.analyticOpticalDepth(settings,
                        new VolumetricParticleReference.Ray(0.0, 0.0, 0.0,
                                0.0, 0.0, -1.0, 1.0e30)), EXACT,
                "a camera inside the body integrates from the eye to the exit");
    }

    @Test
    void quadratureConvergesMonotonicallyToTheAnalyticDepth() {
        VolumetricParticleSettings settings = settings();
        double analytic = VolumetricParticleReference.analyticOpticalDepth(settings, centerRay());
        double[] budgetErrors = new double[4];
        int[] budgets = {8, 16, 32, 48};
        for (int index = 0; index < budgets.length; index++) {
            VolumetricParticleSettings budget = settings(budgets[index], 0.05f, 1.0f);
            double quadrature = VolumetricParticleReference.quadratureOpticalDepth(budget,
                    centerRay());
            budgetErrors[index] = Math.abs(quadrature - analytic);
            assertTrue(budgetErrors[index] <= 1.4e-3,
                    "budget " + budgets[index] + " must stay inside the documented bound");
        }
        for (int index = 1; index < budgetErrors.length; index++) {
            assertTrue(budgetErrors[index] < budgetErrors[index - 1],
                    "a larger step budget must reduce the quadrature error: "
                            + budgetErrors[index] + " !< " + budgetErrors[index - 1]);
        }
        assertEquals(6.401367187499998,
                VolumetricParticleReference.quadratureOpticalDepth(settings(8, 0.05f, 1.0f),
                        centerRay()), EXACT);
        assertEquals(6.400001054928625,
                VolumetricParticleReference.quadratureOpticalDepth(settings(48, 0.05f, 1.0f),
                        centerRay()), EXACT);

        double previousError = Double.POSITIVE_INFINITY;
        for (float stepSize : new float[]{0.5f, 0.25f, 0.1f, 0.05f}) {
            VolumetricParticleSettings setting = settings(64, stepSize, 1.0f);
            double error = Math.abs(VolumetricParticleReference.quadratureOpticalDepth(setting,
                    centerRay()) - analytic);
            assertTrue(error <= 2.4e-3, "step size " + stepSize + " must stay bounded");
            assertTrue(error < previousError,
                    "a smaller target step must reduce the quadrature error");
            previousError = error;
        }
    }

    @Test
    void phaseMatchesTheFixedHenyeyGreensteinSamplesAndStaysBounded() {
        VolumetricParticleSettings settings = settings();
        VolumetricParticleSettings isotropic = new VolumetricParticleSettings(true, 4.0f, 0.2f,
                0.8f, new Vector3f(), new Vector3f(1.5f), 48, 0.05f,
                new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f), 0.0f,
                VolumetricParticleSettings.FogBlend.OCCLUDING, 1.0f, 1.0f);
        assertEquals(0.07957747154594767,
                VolumetricParticleReference.phase(isotropic, 0.37), 1.0e-12,
                "isotropic scattering is the 1/(4 pi) constant");
        assertEquals(0.04420970603012939, VolumetricParticleReference.phase(settings, -1.0), 1.0e-8);
        assertEquals(0.061146891371181726, VolumetricParticleReference.phase(settings, -0.3), 1.0e-8);
        assertEquals(0.07202965900360357, VolumetricParticleReference.phase(settings, 0.0), 1.0e-8);
        assertEquals(0.09922987419975385, VolumetricParticleReference.phase(settings, 0.5), 1.0e-8);
        assertEquals(0.14920776063089783, VolumetricParticleReference.phase(settings, 1.0), 1.0e-8);

        VolumetricParticleSettings forward = new VolumetricParticleSettings(true, 4.0f, 0.2f,
                0.8f, new Vector3f(), new Vector3f(1.5f), 48, 0.05f,
                new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f), 0.7f,
                VolumetricParticleSettings.FogBlend.OCCLUDING, 1.0f, 1.0f);
        assertEquals(1.5031298880916741, VolumetricParticleReference.phase(forward, 1.0), 1.0e-8);
        VolumetricParticleSettings floored = new VolumetricParticleSettings(true, 4.0f, 0.2f,
                0.8f, new Vector3f(), new Vector3f(1.5f), 48, 0.05f,
                new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f), 0.9f,
                VolumetricParticleSettings.FogBlend.OCCLUDING, 1.0f, 1.0f);
        assertEquals(15.119712194361124, VolumetricParticleReference.phase(floored, 1.0), 1.0e-8,
                "the denominator floor must keep the forward lobe bounded");
        assertTrue(Double.isFinite(VolumetricParticleReference.phase(floored, -1.0)));
    }

    @Test
    void transmittanceAndSingleScatterMatchTheFixedSamples() {
        VolumetricParticleSettings settings = settings();
        assertEquals(0.001661557273173937,
                VolumetricParticleReference.transmittance(settings, 6.4), 1.0e-15);

        VolumetricParticleReference.Contribution contribution =
                VolumetricParticleReference.contributionFor(settings, centerRay(), 6.4);
        assertEquals(0.001661557273173937, contribution.transmittance(), 1.0e-15);
        assertEquals(0.2253617444797094, contribution.red(), SCATTER_TOLERANCE);
        assertEquals(0.20604502659461585, contribution.green(), SCATTER_TOLERANCE);
        assertEquals(0.19316720955403663, contribution.blue(), SCATTER_TOLERANCE);

        double[] background = {0.05, 0.10, 0.15};
        double[] occluding = VolumetricParticleReference.occluding(settings, centerRay(),
                background, true);
        assertEquals(0.2254448223433681, occluding[0], SCATTER_TOLERANCE);
        assertEquals(0.20621118232193325, occluding[1], SCATTER_TOLERANCE);
        assertEquals(0.19341644314501272, occluding[2], SCATTER_TOLERANCE);

        VolumetricParticleSettings additive = new VolumetricParticleSettings(true, 4.0f, 0.2f,
                0.8f, new Vector3f(), new Vector3f(1.5f), 48, 0.05f,
                new Vector3f(0.3f, -1.0f, 0.2f), new Vector3f(3.5f, 3.2f, 3.0f), 0.2f,
                VolumetricParticleSettings.FogBlend.ADDITIVE, 1.0f, 1.0f);
        double[] additivePixels = VolumetricParticleReference.additive(additive, centerRay(),
                background, true);
        assertEquals(0.2753617444797094, additivePixels[0], SCATTER_TOLERANCE);
        assertEquals(0.30604502659461585, additivePixels[1], SCATTER_TOLERANCE);
        assertEquals(0.3431672095540366, additivePixels[2], SCATTER_TOLERANCE);
    }

    @Test
    void densityChangesAreMonotoneAndEnergyBounded() {
        VolumetricParticleReference.Ray ray = parallelRay(0.75, 1.0e30);
        double previousOpticalDepth = -1.0;
        double previousTransmittance = 2.0;
        double previousScatter = -1.0;
        for (float density : new float[]{0.5f, 1.0f, 2.0f, 4.0f, 8.0f}) {
            VolumetricParticleSettings settings = new VolumetricParticleSettings(true, density,
                    0.2f, 0.8f, new Vector3f(), new Vector3f(1.5f), 48, 0.05f,
                    new Vector3f(0.3f, -1.0f, 0.2f), new Vector3f(3.5f, 3.2f, 3.0f), 0.2f,
                    VolumetricParticleSettings.FogBlend.OCCLUDING, 1.0f, 1.0f);
            double opticalDepth = VolumetricParticleReference.analyticOpticalDepth(settings, ray);
            double transmittance = VolumetricParticleReference.transmittance(settings, opticalDepth);
            VolumetricParticleReference.Contribution contribution =
                    VolumetricParticleReference.contributionFor(settings, ray, opticalDepth);
            assertTrue(opticalDepth > previousOpticalDepth, "density must increase the optical depth");
            assertTrue(transmittance < previousTransmittance, "density must decrease transmittance");
            assertTrue(transmittance > 0.0 && transmittance <= 1.0, "transmittance stays in (0,1]");
            assertTrue(contribution.red() > previousScatter, "density must increase the in-scatter");
            double boundRed = settings.lightColorInternal().x
                    * VolumetricParticleReference.phase(settings, 1.0);
            assertTrue(contribution.red() <= boundRed,
                    "single scatter cannot exceed light * phase at unit albedo");
            previousOpticalDepth = opticalDepth;
            previousTransmittance = transmittance;
            previousScatter = contribution.red();
        }

        VolumetricParticleSettings zero = new VolumetricParticleSettings(true, 4.0f, 0.2f, 0.8f,
                new Vector3f(), new Vector3f(1.5f), 48, 0.05f,
                new Vector3f(0.3f, -1.0f, 0.2f), new Vector3f(3.5f, 3.2f, 3.0f), 0.2f,
                VolumetricParticleSettings.FogBlend.OCCLUDING, 0.0f, 1.0f);
        double zeroDepth = VolumetricParticleReference.analyticOpticalDepth(zero, ray);
        assertEquals(1.0, VolumetricParticleReference.transmittance(zero, zeroDepth), 0.0,
                "a zero blend weight leaves the particle medium fully transparent");
        assertEquals(1.0, VolumetricParticleReference.transmittance(settings(), 0.0), 0.0);
        VolumetricParticleReference.Contribution zeroContribution =
                VolumetricParticleReference.contributionFor(zero, ray, zeroDepth);
        assertEquals(0.0, zeroContribution.red(), 0.0);
    }

    @Test
    void depthClippingIsMonotoneAndBounded() {
        VolumetricParticleSettings settings = settings();
        double full = VolumetricParticleReference.analyticOpticalDepth(settings, centerRay());
        double inside = VolumetricParticleReference.analyticOpticalDepth(settings,
                parallelRay(0.0, 8.5));
        assertEquals(5.056790123456791, inside, EXACT);
        assertEquals(3.1999999999999997,
                VolumetricParticleReference.analyticOpticalDepth(settings,
                        parallelRay(0.0, 8.0)), EXACT);
        assertEquals(0.22716049382716036,
                VolumetricParticleReference.analyticOpticalDepth(settings,
                        parallelRay(0.0, 7.0)), EXACT);
        assertTrue(inside > 0.0 && inside < full, "a surface inside the body truncates the march");
        double previous = -1.0;
        for (double maxDistance : new double[]{6.5, 7.0, 8.0, 8.5, 9.5, 1.0e30}) {
            double depth = VolumetricParticleReference.analyticOpticalDepth(settings,
                    parallelRay(0.0, maxDistance));
            assertTrue(depth >= previous, "clipped depth must not decrease");
            assertTrue(depth <= full, "clipped depth cannot exceed the unbounded body");
            previous = depth;
        }
    }

    @Test
    void rayForPixelReconstructsTheDocumentedPerspectiveAndOrthographicRays() {
        int size = 65;
        Matrix4f perspective = new Matrix4f().perspective((float) Math.toRadians(50.0f), 1.0f,
                0.125f, 64.0f);
        Matrix4f view = new Matrix4f().lookAt(new Vector3f(0.0f, 0.0f, 10.0f),
                new Vector3f(0.0f), new Vector3f(0.0f, 1.0f, 0.0f));
        float deviceDepth = deviceDepth(perspective, 12.0f);
        VolumetricParticleReference.Ray perspectiveRay = VolumetricParticleReference.rayForPixel(
                new Matrix4f(perspective).invert(), new Matrix4f(view).invert(),
                size, size, 32, 32, deviceDepth);
        assertEquals(0.0, perspectiveRay.originX(), 1.0e-6);
        assertEquals(0.0, perspectiveRay.originY(), 1.0e-6);
        assertEquals(9.875, perspectiveRay.originZ(), 1.0e-6,
                "the perspective ray origin is the near-plane point, matching the shader");
        assertEquals(0.0, perspectiveRay.directionX(), 1.0e-6);
        assertEquals(0.0, perspectiveRay.directionY(), 1.0e-6);
        assertEquals(-1.0, perspectiveRay.directionZ(), 1.0e-6);
        assertEquals(11.875, perspectiveRay.maxDistance(), 1.0e-3,
                "the surface distance is measured from the shader's near-plane ray origin");

        Matrix4f orthographic = new Matrix4f().ortho(-4.0f, 4.0f, -4.0f, 4.0f, 0.125f, 64.0f);
        VolumetricParticleReference.Ray orthographicRay = VolumetricParticleReference.rayForPixel(
                new Matrix4f(orthographic).invert(), new Matrix4f(view).invert(),
                size, size, 32, 32, deviceDepth(orthographic, 12.0f));
        assertEquals(0.0, orthographicRay.originX(), 1.0e-6);
        assertEquals(0.0, orthographicRay.originY(), 1.0e-6);
        assertEquals(9.875, orthographicRay.originZ(), 1.0e-6);
        assertEquals(-1.0, orthographicRay.directionZ(), 1.0e-6);
        assertEquals(11.875, orthographicRay.maxDistance(), 1.0e-3);

        VolumetricParticleReference.Ray clear = VolumetricParticleReference.rayForPixel(
                new Matrix4f(perspective).invert(), new Matrix4f(view).invert(),
                size, size, 32, 32, 1.0f);
        assertTrue(clear.maxDistance() > 1.0e20, "a cleared depth is treated as no occluder");
    }

    private static float deviceDepth(Matrix4f projection, float viewDistance) {
        Vector4f clip = new Vector4f(0.0f, 0.0f, -viewDistance, 1.0f).mul(projection);
        return clip.z / clip.w * 0.5f + 0.5f;
    }
}
