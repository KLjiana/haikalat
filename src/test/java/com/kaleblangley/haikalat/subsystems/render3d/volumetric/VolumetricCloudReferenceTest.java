package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixed-input contract for the independent volumetric-cloud reference.
 *
 * <p>The expected constants were computed from the documented formulas in a
 * separate scratch calculation (not by calling {@link VolumetricCloudReference}),
 * so the double reference and the GLSL stages cannot drift together unnoticed.
 * Case groups: shared #10 mask, vertical profile and its exact integral, closed
 * form and quadrature optical depth, phase/energy bounds and the full bounded
 * light march. This class never creates a GL context.</p>
 */
class VolumetricCloudReferenceTest {
    private static final double MASK_AT_CENTER = 0.7796666058656434;
    private static final double MASK_AT_EDGE = 0.33482689740841876;
    private static final double MASK_AT_HALF = 0.49906432024539166;
    private static final double EPSILON = 1.0e-6;

    private static VolumetricCloudSettings settings() {
        return new VolumetricCloudSettings(true,
                new CloudShadowSettings(true, 0.5f, 0.4f, 0.0004f, 0.0f, 0.65f),
                1_000.0f, 1_400.0f, 0.02f, 0.05f, 0.95f,
                new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f), 0.3f,
                32, 40.0f, 4, 2_000.0f, 1.0f, 0.0f, 0.1f, 2.0f, 5.0f, 30.0f);
    }

    private static VolumetricCloudSettings withWind(VolumetricCloudSettings settings,
                                                    float speed) {
        CloudShadowSettings coverage = settings.coverageField();
        return new VolumetricCloudSettings(settings.enabled(),
                new CloudShadowSettings(coverage.enabled(), coverage.coverage(),
                        coverage.softness(), coverage.scale(), speed, coverage.strength()),
                settings.baseAltitudeMetres(), settings.topAltitudeMetres(), settings.density(),
                settings.absorption(), settings.scattering(), settings.lightDirection(),
                settings.lightColor(), settings.anisotropy(), settings.maximumSteps(),
                settings.stepSizeMetres(), settings.lightSteps(),
                settings.maximumLightDistanceMetres(), settings.renderScale(),
                settings.historyBlend(), settings.exposureThreshold(),
                settings.depthToleranceMetres(), settings.cameraCutTranslationMetres(),
                settings.cameraCutAngleDegrees());
    }

    private static VolumetricCloudReference.Ray verticalRay() {
        return new VolumetricCloudReference.Ray(1500.5, 5000.0, 0.5,
                0.0, -1.0, 0.0, VolumetricCloudReference.NO_SURFACE);
    }

    @Test
    void sharedCloudMaskMatchesThePinnedFieldAndWindOffset() {
        VolumetricCloudSettings settings = settings();
        assertEquals(MASK_AT_CENTER, VolumetricCloudReference.mask(settings, 1500.5, 0.5, 0.0),
                EPSILON, "pinned three-octave mask value");
        assertEquals(MASK_AT_EDGE, VolumetricCloudReference.mask(settings, 1412.5, -300.5, 0.0),
                EPSILON, "pinned soft-coverage value");
        assertEquals(MASK_AT_HALF, VolumetricCloudReference.mask(settings, -961.5, 983.5, 0.0),
                EPSILON, "pinned transition value");

        VolumetricCloudSettings windy = withWind(settings, 20.0f);
        double moved = VolumetricCloudReference.mask(windy, 1500.5, 0.5, 30.0);
        double staticAtOffset = VolumetricCloudReference.mask(settings, 1500.5 + 0.8 * 600.0,
                0.5 + 0.6 * 600.0, 0.0);
        assertEquals(staticAtOffset, moved, EPSILON,
                "the wind offset must be the same world-xz translation used by the #10 mask");

        assertEquals(0.0, VolumetricCloudReference.maskHash(0.0, 0.0), EPSILON);
        assertEquals(0.038120745183618965,
                VolumetricCloudReference.maskHash(1.5, 2.5), EPSILON);
    }

    @Test
    void verticalProfileAndItsExactIntegralMatchTheClosedForm() {
        VolumetricCloudSettings settings = settings();
        assertEquals(0.0, VolumetricCloudReference.profile(settings, 999.0), 0.0,
                "below the layer the density is exactly zero");
        assertEquals(0.0, VolumetricCloudReference.profile(settings, 1000.0), 0.0);
        assertEquals(0.5, VolumetricCloudReference.profile(settings, 1050.0), EPSILON);
        assertEquals(1.0, VolumetricCloudReference.profile(settings, 1100.0), 0.0);
        assertEquals(1.0, VolumetricCloudReference.profile(settings, 1200.0), 0.0);
        assertEquals(0.5, VolumetricCloudReference.profile(settings, 1350.0), EPSILON);
        assertEquals(0.0, VolumetricCloudReference.profile(settings, 1400.0), 0.0);
        assertEquals(0.0, VolumetricCloudReference.profile(settings, 1401.0), 0.0,
                "above the layer the density is exactly zero");

        assertEquals(300.0, VolumetricCloudReference.profileIntegral(settings, 1000.0, 1400.0),
                EPSILON, "the full-slab integral is exactly 0.75 * thickness");
        assertEquals(281.25, VolumetricCloudReference.profileIntegral(settings, 1050.0, 1350.0),
                EPSILON);
        assertEquals(50.0, VolumetricCloudReference.profileIntegral(settings, 1000.0, 1100.0),
                EPSILON);
        assertEquals(100.0, VolumetricCloudReference.profileIntegral(settings, 1100.0, 1200.0),
                EPSILON);
        assertEquals(0.0, VolumetricCloudReference.profileIntegral(settings, 1400.0, 1500.0), 0.0);
    }

    @Test
    void opticalDepthAndTransmittanceFollowTheClosedFormAndAreMonotonic() {
        VolumetricCloudSettings settings = settings();
        double analytic = settings.extinction() * MASK_AT_CENTER * 300.0;
        assertEquals(4.677999635193861, analytic, EPSILON);
        assertEquals(analytic, VolumetricCloudReference.verticalOpticalDepth(settings,
                verticalRay(), MASK_AT_CENTER), EPSILON);
        assertEquals(0.009297593877063559, Math.exp(-analytic), EPSILON);

        VolumetricCloudSettings dense = new VolumetricCloudSettings(true,
                settings.coverageField(), 1_000.0f, 1_400.0f, 0.04f, 0.05f, 0.95f,
                new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f), 0.3f,
                32, 40.0f, 4, 2_000.0f, 1.0f, 0.0f, 0.1f, 2.0f, 5.0f, 30.0f);
        double doubled = VolumetricCloudReference.verticalOpticalDepth(dense, verticalRay(),
                MASK_AT_CENTER);
        assertEquals(2.0 * analytic, doubled, 1.0e-6,
                "optical depth must be linear in density");
        assertTrue(Math.exp(-doubled) < Math.exp(-analytic),
                "denser clouds must transmit less");
    }

    @Test
    void quadratureConvergesToTheClosedFormWithinTheDocumentedBound() {
        VolumetricCloudSettings settings = settings();
        double analytic = VolumetricCloudReference.verticalOpticalDepth(settings,
                verticalRay(), MASK_AT_CENTER);
        double fine = VolumetricCloudReference.quadratureOpticalDepth(settings, verticalRay(), 0.0);
        assertEquals(4.68049456833263, fine, 1.0e-6, "pinned midpoint quadrature value");
        assertTrue(Math.abs(fine - analytic) / analytic < 1.0e-3, "ten midpoint steps must stay within 0.1% of the closed form, fine=" + fine + " analytic=" + analytic);

        VolumetricCloudSettings coarse = new VolumetricCloudSettings(true,
                settings.coverageField(), 1_000.0f, 1_400.0f, 0.02f, 0.05f, 0.95f,
                new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f), 0.3f,
                32, 200.0f, 4, 2_000.0f, 1.0f, 0.0f, 0.1f, 2.0f, 5.0f, 30.0f);
        double twoSteps = VolumetricCloudReference.quadratureOpticalDepth(coarse,
                verticalRay(), 0.0);
        assertEquals(6.237332846925147, twoSteps, 1.0e-6,
                "two midpoint steps pin the coarser quadrature");
        assertTrue(Math.abs(twoSteps - analytic) > Math.abs(fine - analytic),
                "more steps must move the quadrature toward the closed form");
    }

    @Test
    void phaseAndSingleScatterStayInsideTheDocumentedEnergyBound() {
        VolumetricCloudSettings settings = settings();
        assertEquals(0.07957747154594767, VolumetricCloudReference.phase(0.0, 0.0), EPSILON,
                "isotropic phase is 1 / (4 pi)");
        assertEquals(0.03296108288885406, VolumetricCloudReference.phase(0.3, -1.0), EPSILON);

        VolumetricCloudReference.Contribution contribution = VolumetricCloudReference.march(
                settings, verticalRay(), 0.0);
        assertEquals(0.015513774321947563, contribution.red(), 1.0e-6,
                "pinned vertical single-scatter value");
        assertEquals(contribution.red(), contribution.green(), 0.0);
        assertEquals(contribution.red(), contribution.blue(), 0.0);
        assertEquals(0.00927442591526445, contribution.transmittance(), 1.0e-6,
                "pinned vertical transmittance");

        double bound = settings.lightColorInternal().x
                * VolumetricCloudReference.phase(settings.anisotropy(), -1.0)
                * settings.scatteringAlbedo();
        assertTrue(contribution.red() > 0.0);
        assertTrue(contribution.red() < bound,
                "single scatter must stay below light * phase * albedo");
        assertTrue(contribution.transmittance() > 0.0 && contribution.transmittance() <= 1.0);

        VolumetricCloudReference.Contribution belowHorizon = VolumetricCloudReference.march(
                new VolumetricCloudSettings(true, settings.coverageField(),
                        1_000.0f, 1_400.0f, 0.02f, 0.05f, 0.95f,
                        new Vector3f(0.0f, 1.0f, 0.0f), new Vector3f(1.0f), 0.3f,
                        32, 40.0f, 4, 2_000.0f, 1.0f, 0.0f, 0.1f, 2.0f, 5.0f, 30.0f),
                verticalRay(), 0.0);
        assertEquals(0.0, belowHorizon.red(), 1.0e-30,
                "a light below the layer contributes no direct in-scatter");
        assertEquals(contribution.transmittance(), belowHorizon.transmittance(), 1.0e-12,
                "the light direction must not change transmittance");
    }

    @Test
    void lightMarchIsBoundedAndMatchesTheClosedFormAtTheLayerMidpoint() {
        VolumetricCloudSettings settings = settings();
        double tau = VolumetricCloudReference.lightTau(settings, 1500.5, 1200.0, 0.5, 0.0,
                0.0, 1.0, 0.0);
        assertEquals(2.3389998175969304, tau, 1.0e-6,
                "light optical depth matches extinction * mask * profile integral");
        double bottom = VolumetricCloudReference.lightTau(settings, 1500.5, 1000.0, 0.5, 0.0,
                0.0, 1.0, 0.0);
        assertEquals(4.677999635193861, bottom, 1.0e-6);
        assertTrue(bottom > tau, "the light path lengthens toward the layer base");

        double capped = VolumetricCloudReference.lightTau(new VolumetricCloudSettings(true,
                        settings.coverageField(), 1_000.0f, 1_400.0f, 0.02f, 0.05f, 0.95f,
                        new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f), 0.3f,
                        32, 40.0f, 4, 300.0f, 1.0f, 0.0f, 0.1f, 2.0f, 5.0f, 30.0f),
                1500.5, 1000.0, 0.5, 0.0, 0.0, 1.0, 0.0);
        assertTrue(capped < bottom,
                "maximumLightDistanceMetres must cap the light march");
    }

    @Test
    void uniformMaskHelpersMatchTheClosedFormAndTheSampledField() {
        VolumetricCloudSettings settings = settings();
        VolumetricCloudReference.Ray ray = verticalRay();
        double maskValue = VolumetricCloudReference.mask(settings, 1500.5, 0.5, 0.0);
        assertEquals(300.0, VolumetricCloudReference.visibleProfileIntegral(settings, ray),
                EPSILON, "the visible profile integral is the full-slab closed form");
        assertEquals(maskValue, VolumetricCloudReference.effectiveMask(settings, ray, 0.0),
                EPSILON, "a vertical ray sees one constant mask value");

        VolumetricCloudReference.Contribution sampled = VolumetricCloudReference.march(
                settings, ray, 0.0);
        VolumetricCloudReference.Contribution uniform = VolumetricCloudReference
                .marchWithUniformMask(settings, ray, 0.0, maskValue);
        assertEquals(sampled.red(), uniform.red(), 1.0e-9,
                "the uniform-mask march must match the sampled march for a constant mask");
        assertEquals(sampled.transmittance(), uniform.transmittance(), 1.0e-9);
        assertEquals(maskValue, VolumetricCloudReference.fittedMask(settings, ray,
                Math.exp(-settings.extinction() * maskValue
                        * VolumetricCloudReference.visibleProfileIntegral(settings, ray))),
                EPSILON, "the effective-mask fit inverts the closed-form optical depth");
        assertEquals(maskValue, VolumetricCloudReference.fittedMask(settings, ray,
                sampled.transmittance()), 1.0e-3,
                "the fitted mask stays within the midpoint quadrature error");

        VolumetricCloudReference.Ray missing = new VolumetricCloudReference.Ray(
                1500.5, 500.0, 0.5, 0.0, -1.0, 0.0, VolumetricCloudReference.NO_SURFACE);
        assertEquals(0.0, VolumetricCloudReference.visibleProfileIntegral(settings, missing), 0.0);
        assertEquals(0.0, VolumetricCloudReference.fittedMask(settings, missing, 1.0), 0.0);
        VolumetricCloudReference.Contribution empty = VolumetricCloudReference
                .marchWithUniformMask(settings, missing, 0.0, maskValue);
        assertEquals(0.0, empty.red(), 0.0);
        assertEquals(1.0, empty.transmittance(), 0.0);
    }

    @Test
    void cameraCutAndExposureRulesUseTheDocumentedThresholds() {
        assertFalse(VolumetricCloudReference.cameraCut(0.0, 0.0, 0.0, 0.0, 0.0, 4.9, 0.0, 0.0,
                -1.0, 0.0, 0.0, -1.0, 5.0, 30.0),
                "a translation below the limit is not a cut");
        assertTrue(VolumetricCloudReference.cameraCut(0.0, 0.0, 0.0, 0.0, 0.0, 6.0,
                0.0, 0.0, -1.0, 0.0, 0.0, -1.0, 5.0, 30.0));
        assertTrue(VolumetricCloudReference.cameraCut(0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                        0.0, 0.0, -1.0, Math.sin(Math.toRadians(40.0)), 0.0,
                        -Math.cos(Math.toRadians(40.0)), 5.0, 30.0),
                "a rotation above the limit is a cut");

        assertTrue(VolumetricCloudReference.exposureAccepted(1.0f, 1.05f, 0.1f));
        assertTrue(VolumetricCloudReference.exposureAccepted(1.0f, 0.95f, 0.1f));
        assertFalse(VolumetricCloudReference.exposureAccepted(1.0f, 1.2f, 0.1f));
        assertTrue(VolumetricCloudReference.exposureAccepted(1.0f, 2.0f, 1.0f),
                "threshold 1 accepts any change up to 2x");
        assertFalse(VolumetricCloudReference.exposureAccepted(1.0f, 0.0f, 1.0f));
        assertFalse(VolumetricCloudReference.exposureAccepted(Float.NaN, 1.0f, 1.0f));
    }
}
