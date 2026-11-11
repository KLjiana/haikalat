package com.kaleblangley.haikalat.subsystems.render3d.planar;

import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanarReflectionSettingsTest {
    private static final float EPSILON = 1.0e-6f;

    @Test
    void disabledValueIsTheExactNoOpAndBuilderDefaultsMatchIt() {
        PlanarReflectionSettings disabled = PlanarReflectionSettings.disabled();
        assertFalse(disabled.enabled());
        assertFalse(disabled.active());
        assertEquals(1, PlanarReflectionSettings.MAXIMUM_PLANES);
        assertEquals(disabled, PlanarReflectionSettings.builder().build());
        assertNotEquals(disabled, disabled.withEnabled(true));
        assertTrue(disabled.withEnabled(true).active());
    }

    @Test
    void normalizesThePlaneNormalAndComparesByValue() {
        PlanarReflectionSettings two = PlanarReflectionSettings.builder()
                .enabled(true)
                .plane(1.0f, 2.0f, 3.0f, 0.0f, 2.0f, 0.0f)
                .build();
        PlanarReflectionSettings one = PlanarReflectionSettings.builder()
                .enabled(true)
                .plane(1.0f, 2.0f, 3.0f, 0.0f, 1.0f, 0.0f)
                .build();
        assertEquals(one, two);
        assertEquals(1.0f, two.planeNormal().length(), EPSILON);
        assertEquals(1.0f, two.planeNormal().y, EPSILON);
        assertEquals(0.0f, two.planeNormal().x, EPSILON);
        assertEquals(1.0f, two.planePoint().x, EPSILON);
        assertNotEquals(one, PlanarReflectionSettings.builder()
                .enabled(true).planeY(1.0f).build());
    }

    @Test
    void writesThePlaneEquationAndTheOffsetClipPlane() {
        PlanarReflectionSettings settings = PlanarReflectionSettings.builder()
                .enabled(true)
                .plane(0.0f, 2.0f, 0.0f, 0.0f, 1.0f, 0.0f)
                .nearPlaneOffset(0.25f)
                .build();
        Vector4f plane = settings.planeEquation(new Vector4f());
        assertEquals(0.0f, plane.x, EPSILON);
        assertEquals(1.0f, plane.y, EPSILON);
        assertEquals(0.0f, plane.z, EPSILON);
        assertEquals(-2.0f, plane.w, EPSILON);

        Vector4f clip = settings.clipPlane(new Vector4f());
        assertEquals(1.0f, clip.y, EPSILON);
        assertEquals(-1.75f, clip.w, EPSILON);

        PlanarReflectionSettings tilted = PlanarReflectionSettings.builder()
                .enabled(true)
                .plane(1.0f, 2.0f, 3.0f, 0.0f, 0.0f, 2.0f)
                .nearPlaneOffset(0.5f)
                .build();
        Vector4f tiltedPlane = tilted.planeEquation(new Vector4f());
        assertEquals(0.0f, tiltedPlane.x, EPSILON);
        assertEquals(0.0f, tiltedPlane.y, EPSILON);
        assertEquals(1.0f, tiltedPlane.z, EPSILON);
        assertEquals(-3.0f, tiltedPlane.w, EPSILON);
        assertEquals(-2.5f, tilted.clipPlane(new Vector4f()).w, EPSILON);
    }

    @Test
    void scalesAndCapsTheReflectionExtent() {
        PlanarReflectionSettings full = PlanarReflectionSettings.builder()
                .enabled(true).planeY(0.0f).build();
        assertEquals(1920, full.renderWidth(1920, 1080));
        assertEquals(1080, full.renderHeight(1920, 1080));

        PlanarReflectionSettings half = new PlanarReflectionSettings(true,
                new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(0.0f, 1.0f, 0.0f),
                0.5f, 2048, 1, 0.0f);
        assertEquals(960, half.renderWidth(1920, 1080));
        assertEquals(540, half.renderHeight(1920, 1080));

        PlanarReflectionSettings capped = new PlanarReflectionSettings(true,
                new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(0.0f, 1.0f, 0.0f),
                0.5f, 512, 1, 0.0f);
        assertEquals(512, capped.renderWidth(1920, 1080));
        assertEquals(288, capped.renderHeight(1920, 1080));

        PlanarReflectionSettings asymmetric = new PlanarReflectionSettings(true,
                new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(0.0f, 1.0f, 0.0f),
                1.0f, 1000, 1, 0.0f);
        assertEquals(1000, asymmetric.renderWidth(1920, 1080));
        assertEquals(563, asymmetric.renderHeight(1920, 1080));

        PlanarReflectionSettings tiny = new PlanarReflectionSettings(true,
                new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(0.0f, 1.0f, 0.0f),
                0.001f, 1024, 1, 0.0f);
        assertEquals(1, tiny.renderWidth(10, 10));
        assertEquals(1, tiny.renderHeight(10, 10));
        assertThrows(IllegalArgumentException.class, () -> full.renderWidth(0, 10));
        assertThrows(IllegalArgumentException.class, () -> full.renderHeight(10, -1));
    }

    @Test
    void rejectsInvalidValuesAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionSettings.builder().plane(0, 0, 0, 0, 0, 0).build());
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionSettings.builder().plane(Float.NaN, 0, 0, 0, 1, 0).build());
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionSettings.builder().plane(0, 0, 0, Float.POSITIVE_INFINITY, 1, 0)
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionSettings.builder().resolutionScale(0.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionSettings.builder().resolutionScale(1.01f).build());
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionSettings.builder().resolutionScale(Float.NaN).build());
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionSettings.builder().maxResolution(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionSettings.builder()
                        .maxResolution(PlanarReflectionSettings.MAXIMUM_RESOLUTION + 1).build());
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionSettings.builder().updateIntervalFrames(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionSettings.builder()
                        .updateIntervalFrames(
                                PlanarReflectionSettings.MAXIMUM_UPDATE_INTERVAL_FRAMES + 1)
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionSettings.builder().nearPlaneOffset(-0.1f).build());
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionSettings.builder().nearPlaneOffset(Float.NaN).build());
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionSettings.builder().plane(1.0e7f, 0, 0, 0, 1, 0).build());
    }

    @Test
    void accessorsReturnDefensiveCopies() {
        PlanarReflectionSettings settings = PlanarReflectionSettings.builder()
                .enabled(true).plane(1.0f, 2.0f, 3.0f, 0.0f, 2.0f, 0.0f)
                .build();
        Vector3f point = settings.planePoint();
        point.x = 99.0f;
        Vector3f normal = settings.planeNormal();
        normal.y = 99.0f;
        assertEquals(1.0f, settings.planePoint().x, EPSILON);
        assertEquals(1.0f, settings.planeNormal().y, EPSILON);
        Vector3f target = new Vector3f();
        settings.planePointTo(target);
        assertEquals(1.0f, target.x, EPSILON);
        settings.planeNormalTo(target);
        assertEquals(1.0f, target.y, EPSILON);
    }
}
