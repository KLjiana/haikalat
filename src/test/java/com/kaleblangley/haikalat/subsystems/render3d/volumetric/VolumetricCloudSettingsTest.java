package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM contract for the bounded volumetric-cloud configuration.
 *
 * <p>Checks the disabled no-op value, defensive copies, coefficient helpers,
 * every constructor bound, the low-resolution extent rule and the explicit
 * reuse of the #10 {@link CloudShadowSettings} coverage interface. The real-GL
 * behaviour is covered separately by the guarded {@code VolumetricCloudGlTest};
 * this class never creates a GL context.</p>
 */
class VolumetricCloudSettingsTest {
    private static BuilderValues values() {
        return new BuilderValues();
    }

    private static VolumetricCloudSettings settings() {
        return values().build();
    }

    @Test
    void disabledIsAnExactNoOpValueAndCanBeReenabledWithoutRebuilding() {
        VolumetricCloudSettings disabled = VolumetricCloudSettings.disabled();
        assertFalse(disabled.enabled());
        assertFalse(disabled.active());
        assertEquals(CloudShadowSettings.disabled(), disabled.coverageField(),
                "the disabled value must reuse the #10 cloud-shadow defaults");
        VolumetricCloudSettings enabled = disabled.withEnabled(true);
        assertTrue(enabled.active());
        assertEquals(disabled.coverageField(), enabled.coverageField());
        assertEquals(disabled.baseAltitudeMetres(), enabled.baseAltitudeMetres());
        assertEquals(disabled.renderScale(), enabled.renderScale());
        assertEquals(disabled.historyBlend(), enabled.historyBlend());
        assertEquals(disabled.cameraCutAngleDegrees(), enabled.cameraCutAngleDegrees());
        assertFalse(enabled.withEnabled(false).active());
        assertEquals(disabled, disabled.withEnabled(false));
    }

    @Test
    void lowResolutionExtentUsesTheSingleDocumentedRoundingRule() {
        VolumetricCloudSettings half = withRenderScale(settings(), 0.5f);
        assertEquals(32, half.lowResolutionExtent(64));
        assertEquals(1, half.lowResolutionExtent(1), "a low extent never drops below one pixel");
        assertEquals(2, half.lowResolutionExtent(3), "round-half-up on odd extents");
        assertEquals(5, half.lowResolutionExtent(10));
        VolumetricCloudSettings quarter = withRenderScale(settings(), 0.25f);
        assertEquals(16, quarter.lowResolutionExtent(64));
        VolumetricCloudSettings nativeResolution = withRenderScale(settings(), 1.0f);
        assertEquals(64, nativeResolution.lowResolutionExtent(64));
        assertThrows(IllegalArgumentException.class, () -> half.lowResolutionExtent(0));
    }

    @Test
    void vectorComponentsReturnDefensiveCopies() {
        VolumetricCloudSettings settings = settings();
        settings.lightDirection().set(0.0f, 0.0f, 99.0f);
        settings.lightColor().set(99.0f);
        assertEquals(0.0f, settings.lightDirection().x, 0.0f);
        assertEquals(-1.0f, settings.lightDirection().y, 0.0f);
        assertEquals(0.0f, settings.lightDirection().z, 0.0f);
        assertEquals(new Vector3f(1.0f, 1.0f, 1.0f), settings.lightColor());
    }

    @Test
    void cloudscapeIsOptInAndSurvivesSettingsCopies() {
        VolumetricCloudSettings original = settings();
        assertEquals(0.0f, original.shapeDetail());
        assertEquals(new Vector3f(), original.skyLightColor());
        assertEquals(0.0f, original.aerialPerspectiveDistanceMetres());

        Vector3f sky = new Vector3f(0.30f, 0.39f, 0.50f);
        VolumetricCloudSettings styled = original.withCloudscape(0.88f, sky, 11_500.0f);
        sky.zero();
        styled.skyLightColor().zero();
        VolumetricCloudSettings copied = styled.withEnabled(false)
                .withCoverageField(CloudShadowSettings.disabled());
        assertEquals(0.88f, copied.shapeDetail());
        assertEquals(new Vector3f(0.30f, 0.39f, 0.50f), copied.skyLightColor());
        assertEquals(11_500.0f, copied.aerialPerspectiveDistanceMetres());
        assertEquals(original, styled.withCloudscape(0.0f, new Vector3f(), 0.0f));

        for (float invalid : new float[]{-0.01f, 1.01f, Float.NaN}) {
            assertThrows(IllegalArgumentException.class,
                    () -> original.withCloudscape(invalid, sky, 0.0f));
        }
        for (float invalid : new float[]{-1.0f, 200_001.0f, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> original.withCloudscape(0.0f, sky, invalid));
        }
        for (float invalid : new float[]{-0.01f, 16.01f, Float.NaN}) {
            assertThrows(IllegalArgumentException.class,
                    () -> original.withCloudscape(0.0f, new Vector3f(0, invalid, 0), 0.0f));
        }
    }

    @Test
    void cloudModelStrengthsAreIndependentOptInsAndSurviveCopies() {
        VolumetricCloudSettings original = settings().withCloudscape(0.4f, new Vector3f(0.3f), 4000.0f);
        assertEquals(original, original.withCloudModel(0.0f, 0.0f));
        VolumetricCloudSettings shaped = original.withCloudModel(0.9f, 0.0f);
        VolumetricCloudSettings lit = original.withCloudModel(0.0f, 0.8f);
        assertEquals(0.0f, shaped.multipleScatteringStrength());
        assertEquals(0.0f, lit.bodyStrength());
        VolumetricCloudSettings copied = original.withCloudModel(0.9f, 0.8f)
                .withEnabled(false).withCoverageField(CloudShadowSettings.disabled())
                .withCloudscape(0.25f, new Vector3f(), 0.0f);
        assertEquals(0.9f, copied.bodyStrength());
        assertEquals(0.8f, copied.multipleScatteringStrength());
        assertEquals(0.4f, shaped.shapeDetail());
        assertEquals(original.skyLightColor(), lit.skyLightColor());
        assertEquals(original.aerialPerspectiveDistanceMetres(), lit.aerialPerspectiveDistanceMetres());
        for (float invalid : new float[]{-0.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> original.withCloudModel(invalid, 0.0f));
            assertThrows(IllegalArgumentException.class, () -> original.withCloudModel(0.0f, invalid));
        }
    }

    @Test
    void lightDirectionIsNormalizedAndCoefficientHelpersAreConsistent() {
        VolumetricCloudSettings settings = settings();
        assertEquals(1.0f, settings.lightDirection().length(), 1.0e-6f);
        assertEquals(0.02f * (0.05f + 0.95f), settings.extinction(), 1.0e-7f);
        assertEquals(0.95f, settings.scatteringAlbedo(), 1.0e-6f);

        VolumetricCloudSettings vacuum = values().density(0.0f).absorption(0.0f)
                .scattering(0.0f).build();
        assertEquals(0.0f, vacuum.extinction(), 0.0f);
        assertEquals(0.0f, vacuum.scatteringAlbedo(), 0.0f,
                "zero coefficients have no albedo");
        VolumetricCloudSettings oblique = values()
                .lightDirection(new Vector3f(0.3f, -1.0f, 0.2f)).build();
        assertEquals(1.0f, oblique.lightDirection().length(), 1.0e-6f);
    }

    @Test
    void everyComponentIsValidatedAtConstruction() {
        assertRejected(values().coverageField(null));
        assertRejected(values().baseAltitudeMetres(Float.NaN));
        assertRejected(values().baseAltitudeMetres(100_001.0f));
        assertRejected(values().topAltitudeMetres(Float.POSITIVE_INFINITY));
        assertRejected(values().baseAltitudeMetres(1400.0f));
        assertRejected(values().topAltitudeMetres(1000.5f));
        assertRejected(values().topAltitudeMetres(100_001.0f));
        assertRejected(values().density(-0.1f));
        assertRejected(values().density(Float.NaN));
        assertRejected(values().density(65.0f));
        assertRejected(values().absorption(-0.1f));
        assertRejected(values().absorption(Float.POSITIVE_INFINITY));
        assertRejected(values().scattering(65.0f));
        assertRejected(values().scattering(Float.NaN));
        assertRejected(values().lightDirection(new Vector3f()));
        assertRejected(values().lightDirection(new Vector3f(Float.NaN, -1.0f, 0.0f)));
        assertRejected(values().lightColor(new Vector3f(-0.1f, 1.0f, 1.0f)));
        assertRejected(values().lightColor(new Vector3f(17.0f, 1.0f, 1.0f)));
        assertRejected(values().anisotropy(0.95f));
        assertRejected(values().anisotropy(-0.91f));
        assertRejected(values().maximumSteps(0));
        assertRejected(values().maximumSteps(65));
        assertRejected(values().stepSizeMetres(0.0f));
        assertRejected(values().stepSizeMetres(-1.0f));
        assertRejected(values().stepSizeMetres(512.1f));
        assertRejected(values().lightSteps(0));
        assertRejected(values().lightSteps(9));
        assertRejected(values().maximumLightDistanceMetres(0.0f));
        assertRejected(values().maximumLightDistanceMetres(20_001.0f));
        assertRejected(values().renderScale(0.05f));
        assertRejected(values().renderScale(1.1f));
        assertRejected(values().renderScale(Float.NaN));
        assertRejected(values().historyBlend(-0.1f));
        assertRejected(values().historyBlend(1.0f));
        assertRejected(values().exposureThreshold(-0.1f));
        assertRejected(values().exposureThreshold(1.1f));
        assertRejected(values().depthToleranceMetres(0.0f));
        assertRejected(values().depthToleranceMetres(1_001.0f));
        assertRejected(values().cameraCutTranslationMetres(-1.0f));
        assertRejected(values().cameraCutTranslationMetres(10_001.0f));
        assertRejected(values().cameraCutAngleDegrees(-1.0f));
        assertRejected(values().cameraCutAngleDegrees(181.0f));
        values().maximumSteps(64).stepSizeMetres(512.0f).lightSteps(8)
                .maximumLightDistanceMetres(20_000.0f).density(64.0f)
                .absorption(64.0f).scattering(64.0f).anisotropy(0.9f)
                .renderScale(1.0f).historyBlend(0.99f).exposureThreshold(1.0f)
                .depthToleranceMetres(1_000.0f).cameraCutTranslationMetres(10_000.0f)
                .cameraCutAngleDegrees(180.0f).build();
    }

    @Test
    void coverageFieldReusesTheCloudShadowSettingsInterface() {
        VolumetricCloudSettings settings = settings();
        CloudShadowSettings coverage = settings.coverageField();
        assertEquals(0.5f, coverage.coverage(), 0.0f);
        assertEquals(0.4f, coverage.softness(), 0.0f);
        assertEquals(0.0004f, coverage.scale(), 0.0f);
        assertEquals(0.0f, coverage.speed(), 0.0f);

        CloudShadowSettings published = new CloudShadowSettings(true, 0.72f, 0.2f, 0.001f,
                12.0f, 0.65f);
        VolumetricCloudSettings replaced = settings.withCoverageField(published);
        assertSame(published, replaced.coverageField(),
                "the same #10 settings value is shared, not copied into a second cloud field");
        assertEquals(settings.baseAltitudeMetres(), replaced.baseAltitudeMetres());
        assertEquals(settings.historyBlend(), replaced.historyBlend());
        assertEquals(settings.maximumSteps(), replaced.maximumSteps());
    }

    private static void assertRejected(BuilderValues values) {
        assertThrows(IllegalArgumentException.class, values::build,
                "an out-of-range component must fail at construction");
    }

    /** Mutable fixture mirroring the record components and their valid defaults. */
    private static final class BuilderValues {
        private CloudShadowSettings coverageField =
                new CloudShadowSettings(true, 0.5f, 0.4f, 0.0004f, 0.0f, 0.65f);
        private float baseAltitude = 1_000.0f;
        private float topAltitude = 1_400.0f;
        private float density = 0.02f;
        private float absorption = 0.05f;
        private float scattering = 0.95f;
        private Vector3f lightDirection = new Vector3f(0.0f, -1.0f, 0.0f);
        private Vector3f lightColor = new Vector3f(1.0f, 1.0f, 1.0f);
        private float anisotropy = 0.3f;
        private int maximumSteps = 32;
        private float stepSize = 40.0f;
        private int lightSteps = 4;
        private float maximumLightDistance = 2_000.0f;
        private float renderScale = 0.5f;
        private float historyBlend = 0.9f;
        private float exposureThreshold = 0.1f;
        private float depthTolerance = 2.0f;
        private float cameraCutTranslation = 5.0f;
        private float cameraCutAngle = 30.0f;

        BuilderValues coverageField(CloudShadowSettings value) {
            coverageField = value;
            return this;
        }

        BuilderValues baseAltitudeMetres(float value) {
            baseAltitude = value;
            return this;
        }

        BuilderValues topAltitudeMetres(float value) {
            topAltitude = value;
            return this;
        }

        BuilderValues density(float value) {
            density = value;
            return this;
        }

        BuilderValues absorption(float value) {
            absorption = value;
            return this;
        }

        BuilderValues scattering(float value) {
            scattering = value;
            return this;
        }

        BuilderValues lightDirection(Vector3f value) {
            lightDirection = value;
            return this;
        }

        BuilderValues lightColor(Vector3f value) {
            lightColor = value;
            return this;
        }

        BuilderValues anisotropy(float value) {
            anisotropy = value;
            return this;
        }

        BuilderValues maximumSteps(int value) {
            maximumSteps = value;
            return this;
        }

        BuilderValues stepSizeMetres(float value) {
            stepSize = value;
            return this;
        }

        BuilderValues lightSteps(int value) {
            lightSteps = value;
            return this;
        }

        BuilderValues maximumLightDistanceMetres(float value) {
            maximumLightDistance = value;
            return this;
        }

        BuilderValues renderScale(float value) {
            renderScale = value;
            return this;
        }

        BuilderValues historyBlend(float value) {
            historyBlend = value;
            return this;
        }

        BuilderValues exposureThreshold(float value) {
            exposureThreshold = value;
            return this;
        }

        BuilderValues depthToleranceMetres(float value) {
            depthTolerance = value;
            return this;
        }

        BuilderValues cameraCutTranslationMetres(float value) {
            cameraCutTranslation = value;
            return this;
        }

        BuilderValues cameraCutAngleDegrees(float value) {
            cameraCutAngle = value;
            return this;
        }

        VolumetricCloudSettings build() {
            return new VolumetricCloudSettings(true, coverageField, baseAltitude, topAltitude,
                    density, absorption, scattering, lightDirection, lightColor, anisotropy,
                    maximumSteps, stepSize, lightSteps, maximumLightDistance, renderScale,
                    historyBlend, exposureThreshold, depthTolerance, cameraCutTranslation,
                    cameraCutAngle);
        }
    }

    private static VolumetricCloudSettings withRenderScale(VolumetricCloudSettings settings,
                                                           float scale) {
        return new VolumetricCloudSettings(settings.enabled(), settings.coverageField(),
                settings.baseAltitudeMetres(), settings.topAltitudeMetres(), settings.density(),
                settings.absorption(), settings.scattering(), settings.lightDirection(),
                settings.lightColor(), settings.anisotropy(), settings.maximumSteps(),
                settings.stepSizeMetres(), settings.lightSteps(),
                settings.maximumLightDistanceMetres(), scale, settings.historyBlend(),
                settings.exposureThreshold(), settings.depthToleranceMetres(),
                settings.cameraCutTranslationMetres(), settings.cameraCutAngleDegrees());
    }
}
