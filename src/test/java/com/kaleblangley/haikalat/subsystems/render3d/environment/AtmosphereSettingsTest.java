package com.kaleblangley.haikalat.subsystems.render3d.environment;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM contract for the physical atmosphere configuration.
 *
 * <p>These cases pin the documented units, the bounded LUT/sample tiers and the
 * construction-time rejection of non-physical or unbounded input.  Numerical
 * scattering expectations live in {@link AtmosphereReferenceTest}.</p>
 */
class AtmosphereSettingsTest {
    @Test
    void earthDefaultsArePhysicalAndBounded() {
        AtmosphereSettings settings = AtmosphereSettings.earth();
        assertEquals(AtmosphereSettings.EARTH_RADIUS_METERS, settings.planetRadiusMeters());
        assertEquals(AtmosphereSettings.EARTH_ATMOSPHERE_THICKNESS_METERS,
                settings.atmosphereThicknessMeters());
        assertEquals(6_471_000.0f, settings.atmosphereTopRadiusMeters());
        Vector3f beta = settings.rayleighScatteringPerMeter();
        assertTrue(beta.x > 0.0f && beta.y > beta.x && beta.z > beta.y);
        assertEquals(8_000.0f, settings.rayleighScaleHeightMeters());
        assertEquals(3.996e-6f, settings.mieScatteringPerMeter());
        assertEquals(0.444e-6f, settings.mieAbsorptionPerMeter());
        assertEquals(1_200.0f, settings.mieScaleHeightMeters());
        assertEquals(0.76f, settings.mieAnisotropy());
        assertEquals(1_361.0f, settings.sunIntensityWattsPerSquareMetre());
        assertEquals(0.02f, settings.exposure());
        assertEquals(256, settings.transmittanceLutWidth());
        assertEquals(64, settings.transmittanceLutHeight());
        assertEquals(32, settings.skyViewLutWidth());
        assertEquals(32, settings.skyViewLutHeight());
        assertEquals(64, settings.transmittanceSamples());
        assertEquals(64, settings.skyViewSamples());
        assertEquals(1.0f, settings.sunDirection().length(), 1.0e-6f);
        assertEquals(60.0,
                Math.toDegrees(AtmosphereReference.sunElevationRadians(settings.sunDirection())),
                1.0e-3);
        assertEquals(0.0,
                Math.toDegrees(AtmosphereReference.sunAzimuthRadians(settings.sunDirection())),
                1.0e-3);
    }

    @Test
    void qualityTiersIncreaseWorkInsideTheValidatedBounds() {
        AtmosphereSettings low = AtmosphereSettings.earth(AtmosphereSettings.Quality.LOW);
        AtmosphereSettings medium = AtmosphereSettings.earth(AtmosphereSettings.Quality.MEDIUM);
        AtmosphereSettings high = AtmosphereSettings.earth(AtmosphereSettings.Quality.HIGH);
        assertTrue(low.transmittanceLutWidth() < medium.transmittanceLutWidth());
        assertTrue(medium.transmittanceLutWidth() < high.transmittanceLutWidth());
        assertTrue(low.skyViewLutWidth() < medium.skyViewLutWidth());
        assertTrue(medium.skyViewLutWidth() < high.skyViewLutWidth());
        assertTrue(low.transmittanceSamples() < medium.transmittanceSamples());
        assertTrue(medium.transmittanceSamples() < high.transmittanceSamples());
        assertTrue(low.skyViewSamples() < medium.skyViewSamples());
        assertTrue(medium.skyViewSamples() < high.skyViewSamples());
        for (AtmosphereSettings settings : new AtmosphereSettings[]{low, medium, high}) {
            assertTrue(settings.transmittanceLutWidth() % 8 == 0);
            assertTrue(settings.transmittanceLutHeight() % 8 == 0);
            assertTrue(settings.skyViewLutWidth() % 8 == 0);
            assertTrue(settings.skyViewLutHeight() % 8 == 0);
            assertTrue(settings.transmittanceLutWidth()
                    <= AtmosphereSettings.MAX_TRANSMITTANCE_LUT_EXTENT);
            assertTrue(settings.skyViewLutWidth() <= AtmosphereSettings.MAX_SKY_VIEW_LUT_EXTENT);
            assertTrue(settings.transmittanceSamples()
                    <= AtmosphereSettings.MAX_TRANSMITTANCE_SAMPLES);
            assertTrue(settings.skyViewSamples() <= AtmosphereSettings.MAX_SKY_VIEW_SAMPLES);
        }
        assertThrows(NullPointerException.class,
                () -> AtmosphereSettings.earth((AtmosphereSettings.Quality) null));
    }

    @Test
    void rejectsNonPhysicalOrUnboundedInput() {
        Values values = new Values();
        assertRejected(values, v -> v.radius = Float.NaN);
        assertRejected(values, v -> v.radius = 1_000.0f);
        assertRejected(values, v -> v.radius = Float.POSITIVE_INFINITY);
        assertRejected(values, v -> v.thickness = 0.0f);
        assertRejected(values, v -> v.thickness = 10_000_000.0f);
        assertRejected(values, v -> v.thickness = v.radius + 1.0f);
        assertRejected(values, v -> v.rayleigh = new Vector3f(Float.NaN, 1.0e-6f, 1.0e-6f));
        assertRejected(values, v -> v.rayleigh = new Vector3f(-1.0e-6f, 1.0e-6f, 1.0e-6f));
        assertRejected(values, v -> v.rayleigh = new Vector3f(1.0f, 1.0f, 1.0f));
        assertRejected(values, v -> v.rayleighScaleHeight = 0.0f);
        assertRejected(values, v -> v.rayleighScaleHeight = v.thickness + 1.0f);
        assertRejected(values, v -> v.mieScattering = -1.0e-6f);
        assertRejected(values, v -> v.mieAbsorption = Float.NaN);
        assertRejected(values, v -> v.mieScaleHeight = 0.0f);
        assertRejected(values, v -> v.mieScaleHeight = v.thickness + 1.0f);
        assertRejected(values, v -> v.anisotropy = -0.91f);
        assertRejected(values, v -> v.anisotropy = 0.91f);
        assertRejected(values, v -> v.sky = new Vector3f());
        assertRejected(values, v -> v.sky = new Vector3f(Float.NaN, -0.5f, 0.0f));
        assertRejected(values, v -> v.sunIntensity = -1.0f);
        assertRejected(values, v -> v.sunIntensity = Float.NaN);
        assertRejected(values, v -> v.exposure = -0.01f);
        assertRejected(values, v -> v.exposure = Float.NaN);
        assertRejected(values, v -> v.sunAngularRadius = 0.0f);
        assertRejected(values, v -> v.sunAngularRadius = 0.25f);
        assertRejected(values, v -> v.sunDiscIntensity = -1.0f);
        assertRejected(values, v -> v.transmittanceWidth = 0);
        assertRejected(values, v -> v.transmittanceWidth = 12);
        assertRejected(values, v -> v.transmittanceWidth = 2048);
        assertRejected(values, v -> v.transmittanceHeight = 4);
        assertRejected(values, v -> v.skyViewWidth = 0);
        assertRejected(values, v -> v.skyViewWidth = 520);
        assertRejected(values, v -> v.skyViewHeight = 12);
        assertRejected(values, v -> v.transmittanceSamples = 3);
        assertRejected(values, v -> v.transmittanceSamples = 257);
        assertRejected(values, v -> v.skyViewSamples = 3);
        assertRejected(values, v -> v.skyViewSamples = 129);
        assertThrows(NullPointerException.class, () -> new Values().build(v -> v.rayleigh = null));
        assertThrows(NullPointerException.class, () -> new Values().build(v -> v.sky = null));
    }

    @Test
    void vectorsAreDefensivelyCopiedAndSunDirectionIsNormalized() {
        Vector3f rayleigh = new Vector3f(5.802e-6f, 13.558e-6f, 33.1e-6f);
        Values values = new Values();
        values.rayleigh = rayleigh;
        values.sky = new Vector3f(0.0f, -2.0f, 0.0f);
        AtmosphereSettings settings = values.build(v -> { });
        rayleigh.zero();
        values.sky.zero();
        assertEquals(5.802e-6f, settings.rayleighScatteringPerMeter().x);
        assertEquals(1.0f, settings.sunDirection().length(), 1.0e-6f);
        assertEquals(-1.0f, settings.sunDirection().y, 1.0e-6f);

        settings.rayleighScatteringPerMeter().zero();
        settings.sunDirection().zero();
        assertEquals(33.1e-6f, settings.rayleighScatteringPerMeter().z);
        assertEquals(-1.0f, settings.sunDirection().y, 1.0e-6f);
        assertEquals(5.802e-6f, settings.rayleighScatteringInternal().x);
    }

    @Test
    void withSunDirectionAndExposureKeepTheOtherPhysicalFields() {
        AtmosphereSettings base = AtmosphereSettings.earth();
        AtmosphereSettings moved = base.withSunDirection(new Vector3f(0.0f, -0.5f, -0.8660254f));
        assertEquals(1.0f, moved.sunDirection().length(), 1.0e-6f);
        assertEquals(30.0,
                Math.toDegrees(AtmosphereReference.sunElevationRadians(moved.sunDirection())),
                1.0e-3);
        assertEquals(base.planetRadiusMeters(), moved.planetRadiusMeters());
        assertEquals(base.rayleighScatteringPerMeter(), moved.rayleighScatteringPerMeter());
        assertEquals(base.transmittanceLutWidth(), moved.transmittanceLutWidth());

        AtmosphereSettings brighter = base.withExposure(0.04f);
        assertEquals(0.04f, brighter.exposure());
        assertEquals(base.exposure(), 0.02f);
        assertEquals(base.sunDirection(), brighter.sunDirection());
        assertNotEquals(base, brighter);
        assertThrows(IllegalArgumentException.class, () -> base.withExposure(-1.0f));
        assertThrows(NullPointerException.class,
                () -> base.withSunDirection((org.joml.Vector3fc) null));

        AtmosphereSettings largerDisc = base.withSunAngularRadius(0.05f)
                .withSunDiscIntensity(0.0f);
        assertEquals(0.05f, largerDisc.sunAngularRadiusRadians());
        assertEquals(0.0f, largerDisc.sunDiscIntensity());
        assertThrows(IllegalArgumentException.class, () -> base.withSunAngularRadius(0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> base.withSunAngularRadius(AtmosphereSettings.MAX_SUN_ANGULAR_RADIUS + 0.01f));
        assertThrows(IllegalArgumentException.class, () -> base.withSunDiscIntensity(-1.0f));
    }

    private static void assertRejected(Values ignored, java.util.function.Consumer<Values> mutation) {
        assertThrows(IllegalArgumentException.class, () -> new Values().build(mutation));
    }

    private static final class Values {
        float radius = AtmosphereSettings.EARTH_RADIUS_METERS;
        float thickness = AtmosphereSettings.EARTH_ATMOSPHERE_THICKNESS_METERS;
        Vector3f rayleigh = new Vector3f(5.802e-6f, 13.558e-6f, 33.1e-6f);
        float rayleighScaleHeight = 8_000.0f;
        float mieScattering = 3.996e-6f;
        float mieAbsorption = 0.444e-6f;
        float mieScaleHeight = 1_200.0f;
        float anisotropy = 0.76f;
        Vector3f sky = new Vector3f(0.0f, -0.8660254f, -0.5f);
        float sunIntensity = 1_361.0f;
        float exposure = 0.02f;
        float sunAngularRadius = 0.00465f;
        float sunDiscIntensity = 200.0f;
        int transmittanceWidth = 256;
        int transmittanceHeight = 64;
        int skyViewWidth = 32;
        int skyViewHeight = 32;
        int transmittanceSamples = 64;
        int skyViewSamples = 32;

        AtmosphereSettings build(java.util.function.Consumer<Values> mutation) {
            mutation.accept(this);
            return new AtmosphereSettings(radius, thickness, rayleigh, rayleighScaleHeight,
                    mieScattering, mieAbsorption, mieScaleHeight, anisotropy, sky, sunIntensity,
                    exposure, sunAngularRadius, sunDiscIntensity, transmittanceWidth,
                    transmittanceHeight, skyViewWidth, skyViewHeight, transmittanceSamples,
                    skyViewSamples);
        }
    }
}
