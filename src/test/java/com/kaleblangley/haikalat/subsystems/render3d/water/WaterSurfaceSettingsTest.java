package com.kaleblangley.haikalat.subsystems.render3d.water;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM contract for the finite water surface configuration.
 *
 * <p>The expected wave normals, absorption fractions and shore factors come
 * from an independent reference implementation written in this test from the
 * documented formula, never from the production helper.</p>
 */
class WaterSurfaceSettingsTest {
    private static WaterSurfaceSettings enabled() {
        return WaterSurfaceSettings.builder()
                .enabled(true)
                .level(0.0f)
                .bounds(-10.0f, -10.0f, 10.0f, 10.0f)
                .waveWavelength(8.0f)
                .waveSpeed(0.6f)
                .waveAmplitude(0.06f)
                .waveDirectionRadians(0.0f)
                .waterColor(0.02f, 0.12f, 0.16f)
                .absorptionDensity(0.35f)
                .shoreFadeDistance(0.75f)
                .reflectionFallback(0.35f, 0.5f, 0.65f)
                .build();
    }

    @Test
    void disabledValueIsAnExactNoOp() {
        WaterSurfaceSettings disabled = WaterSurfaceSettings.disabled();
        assertFalse(disabled.active());
        Vector3f disabledNormal = disabled.waveNormal(3.0f, -2.0f, 12.5f, new Vector3f());
        assertEquals(0.0f, disabledNormal.x, 0.0f);
        assertEquals(1.0f, disabledNormal.y, 0.0f);
        assertEquals(0.0f, disabledNormal.z, 0.0f);
        assertEquals(0.0f, disabled.absorptionFraction(100.0f));
        assertEquals(0.0f, disabled.shoreFraction(100.0f));
        assertEquals(20.0f, disabled.width(), 0.0f);
        assertEquals(20.0f, disabled.depth(), 0.0f);
    }

    @Test
    void waveNormalMatchesIndependentTwoTrainReference() {
        WaterSurfaceSettings settings = enabled();
        for (float time : new float[]{0.0f, 0.5f, 1.7f, 30.0f}) {
            for (float[] point : new float[][]{{0.0f, 0.0f}, {1.0f, 2.0f},
                    {-3.5f, 0.25f}, {12.0f, -7.0f}}) {
                Vector3f actual = settings.waveNormal(point[0], point[1], time, new Vector3f());
                Vector3f expected = referenceNormal(settings, point[0], point[1], time);
                assertEquals(expected.x, actual.x, 2.0e-6f, "normal x");
                assertEquals(expected.y, actual.y, 2.0e-6f, "normal y");
                assertEquals(expected.z, actual.z, 2.0e-6f, "normal z");
                assertEquals(1.0f, actual.length(), 1.0e-6f);
                assertTrue(actual.y > 0.0f);
            }
        }
    }

    @Test
    void waveNormalsVaryWithTimeOnlyWhenTheWaveMoves() {
        WaterSurfaceSettings still = WaterSurfaceSettings.builder().enabled(true)
                .waveSpeed(0.0f).waveAmplitude(0.1f).build();
        Vector3f stillAtZero = still.waveNormal(1.0f, 1.0f, 0.0f, new Vector3f());
        Vector3f stillAtFour = still.waveNormal(1.0f, 1.0f, 4.0f, new Vector3f());
        assertEquals(stillAtZero, stillAtFour);

        WaterSurfaceSettings moving = WaterSurfaceSettings.builder().enabled(true)
                .waveWavelength(3.0f).waveSpeed(1.2f).waveAmplitude(0.1f).build();
        Vector3f first = moving.waveNormal(0.5f, 0.25f, 0.0f, new Vector3f());
        Vector3f second = moving.waveNormal(0.5f, 0.25f, 0.75f, new Vector3f());
        assertNotEquals(first, second);
        assertEquals(0.0f, moving.waveNormal(0.5f, 0.25f, 0.0f, new Vector3f())
                .distance(first), 0.0f, "same inputs must be deterministic");
    }

    @Test
    void absorptionAndShoreFollowThePublishedReference() {
        WaterSurfaceSettings settings = enabled();
        for (float thickness : new float[]{0.05f, 0.25f, 1.0f, 4.0f, 20.0f}) {
            assertEquals(1.0 - Math.exp(-0.35 * thickness),
                    settings.absorptionFraction(thickness), 2.0e-6);
        }
        assertEquals(0.0f, settings.absorptionFraction(0.0f));
        assertEquals(0.0f, settings.absorptionFraction(-3.0f));
        assertTrue(settings.absorptionFraction(1.0e7f) <= 1.0f);

        assertEquals(0.0f, settings.shoreFraction(0.0f));
        assertEquals(0.0f, settings.shoreFraction(-0.5f));
        assertEquals(0.5f, settings.shoreFraction(0.375f), 1.0e-6f);
        assertEquals(1.0f, settings.shoreFraction(0.75f), 1.0e-6f);
        assertEquals(1.0f, settings.shoreFraction(5.0f), 1.0e-6f);
        assertTrue(settings.shoreFraction(0.2f) < settings.shoreFraction(0.6f));

        WaterSurfaceSettings hardEdge = WaterSurfaceSettings.builder().enabled(true)
                .shoreFadeDistance(0.0f).build();
        assertEquals(0.0f, hardEdge.shoreFraction(0.0f));
        assertEquals(1.0f, hardEdge.shoreFraction(1.0e-4f));

        WaterSurfaceSettings transparent = WaterSurfaceSettings.builder().enabled(true)
                .absorptionDensity(0.0f).build();
        assertEquals(0.0f, transparent.absorptionFraction(10.0f));
    }

    @Test
    void constructionRejectsOutOfRangeGeometryAndAppearance() {
        assertThrows(IllegalArgumentException.class, () -> WaterSurfaceSettings.builder()
                .bounds(2.0f, 0.0f, 1.0f, 4.0f).build(), "inverted X bounds");
        assertThrows(IllegalArgumentException.class, () -> WaterSurfaceSettings.builder()
                .bounds(0.0f, 0.0f, WaterSurfaceSettings.MINIMUM_EXTENT * 0.5f, 4.0f).build());
        assertThrows(IllegalArgumentException.class, () -> WaterSurfaceSettings.builder()
                .bounds(0.0f, 0.0f, WaterSurfaceSettings.MAXIMUM_EXTENT * 2.0f, 4.0f).build());
        assertThrows(IllegalArgumentException.class, () -> WaterSurfaceSettings.builder()
                .level(Float.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> WaterSurfaceSettings.builder()
                .waveWavelength(0.0f).build());
        assertThrows(IllegalArgumentException.class, () -> WaterSurfaceSettings.builder()
                .waveWavelength(WaterSurfaceSettings.MAXIMUM_WAVELENGTH * 2.0f).build());
        assertThrows(IllegalArgumentException.class, () -> WaterSurfaceSettings.builder()
                .waveSpeed(WaterSurfaceSettings.MAXIMUM_WAVE_SPEED + 0.5f).build());
        assertThrows(IllegalArgumentException.class, () -> WaterSurfaceSettings.builder()
                .waveAmplitude(WaterSurfaceSettings.MAXIMUM_WAVE_AMPLITUDE + 0.1f).build());
        assertThrows(IllegalArgumentException.class, () -> WaterSurfaceSettings.builder()
                .waveDirectionRadians(Float.POSITIVE_INFINITY).build());
        assertThrows(IllegalArgumentException.class, () -> WaterSurfaceSettings.builder()
                .absorptionDensity(-1.0f).build());
        assertThrows(IllegalArgumentException.class, () -> WaterSurfaceSettings.builder()
                .shoreFadeDistance(WaterSurfaceSettings.MAXIMUM_SHORE_FADE_DISTANCE + 1.0f)
                .build());
        assertThrows(IllegalArgumentException.class, () -> WaterSurfaceSettings.builder()
                .waterColor(-0.1f, 0.0f, 0.0f).build());
        assertThrows(IllegalArgumentException.class, () -> WaterSurfaceSettings.builder()
                .reflectionFallback(0.0f, Float.NaN, 0.0f).build());
    }

    @Test
    void valueAccessorsAndDirectionCanonicalisationAreDefensive() {
        WaterSurfaceSettings settings = WaterSurfaceSettings.builder()
                .enabled(true).waveDirectionRadians(-(float) (Math.PI * 0.5)).build();
        assertEquals(1.5 * Math.PI, settings.waveDirectionRadians(), 1.0e-5);

        Vector3f color = settings.waterColor();
        color.set(9.0f);
        assertNotEquals(9.0f, settings.waterColor().x);
        Vector3f fallback = settings.reflectionFallback();
        fallback.set(9.0f);
        assertNotEquals(9.0f, settings.reflectionFallback().x);

        Vector3f destination = new Vector3f();
        settings.waterColorTo(destination);
        assertEquals(settings.waterColor(), destination);
        settings.reflectionFallbackTo(destination);
        assertEquals(settings.reflectionFallback(), destination);

        assertFalse(settings.withEnabled(false).active());
        assertTrue(settings.withEnabled(false).waterColor().equals(settings.waterColor()));
    }

    /** Independent implementation of the documented two-train normal formula. */
    private static Vector3f referenceNormal(WaterSurfaceSettings settings, float x, float z,
                                            float time) {
        double twoPi = Math.PI * 2.0;
        double k1 = twoPi / settings.waveWavelength();
        double directionX = Math.cos(settings.waveDirectionRadians());
        double directionZ = Math.sin(settings.waveDirectionRadians());
        double k2 = k1 * 1.7;
        double omega1 = k1 * settings.waveSpeed();
        double omega2 = k2 * settings.waveSpeed();
        double phase1 = k1 * (directionX * x + directionZ * z) - omega1 * time;
        double phase2 = k2 * (-directionZ * x + directionX * z) - omega2 * time + 1.3;
        double gradientX = settings.waveAmplitude() * (k1 * directionX * Math.cos(phase1)
                + 0.5 * k2 * -directionZ * Math.cos(phase2));
        double gradientZ = settings.waveAmplitude() * (k1 * directionZ * Math.cos(phase1)
                + 0.5 * k2 * directionX * Math.cos(phase2));
        return new Vector3f((float) -gradientX, 1.0f, (float) -gradientZ).normalize();
    }
}
