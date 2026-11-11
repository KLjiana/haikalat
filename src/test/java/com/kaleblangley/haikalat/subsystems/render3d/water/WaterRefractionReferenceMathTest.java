package com.kaleblangley.haikalat.subsystems.render3d.water;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixed-input contract for the independent double refraction reference.
 *
 * <p>The oblique example is worked by hand from the documented formula:
 * a 60-degree perspective camera at {@code (0, 4, 0)} with the view translated
 * so it looks along {@code -Z}, a flat normal at {@code (0, 1, 0)}, four metres
 * of water column, IOR 1.333 and one UV of strength produce
 * {@code (0, -0.11139303)} UV.  The normal-incidence, monotonicity, clamp,
 * foreground and edge-fallback cases pin the remaining branches so a later
 * "fix" cannot silently change the shader contract.</p>
 */
class WaterRefractionReferenceMathTest {
    private static final Matrix4f PROJECTION = new Matrix4f()
            .perspective((float) Math.toRadians(60.0), 1.0f, 0.1f, 100.0f);
    private static final float THICKNESS = 4.0f;

    @Test
    void normalIncidenceProducesNoOffset() {
        Matrix4f view = new Matrix4f().lookAt(new Vector3f(0.0f, 5.0f, 0.0f),
                new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(0.0f, 0.0f, -1.0f));
        Vector3f camera = new Vector3f(0.0f, 5.0f, 0.0f);
        Vector3f world = new Vector3f(0.0f, 0.0f, 0.0f);
        Vector2f centre = WaterRefractionReferenceMath.projectToUv(PROJECTION, view,
                new Vector3d(world));
        assertNotNull(centre);
        assertEquals(0.5f, centre.x, 1.0e-6f);
        assertEquals(0.5f, centre.y, 1.0e-6f);

        WaterRefractionReferenceMath.Offset offset = WaterRefractionReferenceMath.offset(
                PROJECTION, view, new Vector3d(world), new Vector3d(0.0, 1.0, 0.0), camera,
                centre.x, centre.y, 1.333, 3.0, 1.0, 1.0, 0.5);
        assertEquals(0.0, offset.x(), 1.0e-6);
        assertEquals(0.0, offset.y(), 1.0e-6);
        assertEquals(3.0, offset.travel(), 1.0e-6);
        assertEquals(centre.x, offset.shiftedX(), 1.0e-6);
        assertEquals(centre.y, offset.shiftedY(), 1.0e-6);
        assertEquals(WaterRefractionReferenceMath.FallbackReason.NONE, offset.fallback());
    }

    @Test
    void windowDepthEncodingMatchesTheProjectionAndRoundTrips() {
        Matrix4f view = new Matrix4f().translate(0.0f, -4.0f, 0.0f);
        assertEquals(0.0, WaterRefractionReferenceMath.deviceDepth(PROJECTION,
                0.0, 0.0, -0.1), 1.0e-6,
                "a point on the near plane encodes window depth zero");
        assertEquals(1.0, WaterRefractionReferenceMath.deviceDepth(PROJECTION,
                0.0, 0.0, -100.0), 1.0e-6,
                "a point on the far plane encodes window depth one");

        Vector4f clip = new Vector4f(0.2f, -0.3f, -10.0f, 1.0f).mul(PROJECTION);
        double expected = clip.z / clip.w * 0.5 + 0.5;
        assertEquals(expected, WaterRefractionReferenceMath.deviceDepth(PROJECTION,
                0.2, -0.3, -10.0), 1.0e-7,
                "window depth must be the projected NDC z mapped to [0,1]");

        Vector3d world = new Vector3d(0.3, 0.0, -20.0);
        Vector2f uv = WaterRefractionReferenceMath.projectToUv(PROJECTION, view, world);
        assertNotNull(uv);
        Vector3f viewSpace = new Vector3f((float) world.x, (float) world.y, (float) world.z)
                .mulPosition(view);
        double depth = WaterRefractionReferenceMath.deviceDepth(PROJECTION,
                viewSpace.x, viewSpace.y, viewSpace.z);
        assertTrue(depth > 0.0 && depth < 1.0,
                "a visible fixture point must encode a window depth in (0,1), got " + depth);
        Vector3d roundTrip = WaterRefractionReferenceMath.unproject(PROJECTION, view,
                uv.x, uv.y, depth);
        assertNotNull(roundTrip);
        assertEquals(world.x, roundTrip.x, 1.0e-3, "round-trip x");
        assertEquals(world.y, roundTrip.y, 1.0e-3, "round-trip y");
        assertEquals(world.z, roundTrip.z, 1.0e-3, "round-trip z");
    }

    @Test
    void fixedObliqueExampleMatchesTheHandComputedOffset() {
        Matrix4f view = new Matrix4f().translate(0.0f, -4.0f, 0.0f);
        Vector3f camera = new Vector3f(0.0f, 4.0f, 0.0f);
        Vector3f world = new Vector3f(0.0f, 0.0f, -20.0f);
        Vector2f centre = WaterRefractionReferenceMath.projectToUv(PROJECTION, view,
                new Vector3d(world));
        assertNotNull(centre);
        assertEquals(0.5f, centre.x, 1.0e-6f);
        assertEquals(0.32679492f, centre.y, 1.0e-6f);

        WaterRefractionReferenceMath.Offset offset = WaterRefractionReferenceMath.offset(
                PROJECTION, view, new Vector3d(world), new Vector3d(0.0, 1.0, 0.0), camera,
                centre.x, centre.y, 1.333, THICKNESS, 1.0, 1.0, 0.5);
        assertEquals(0.0, offset.x(), 1.0e-6);
        assertEquals(-0.11139303, offset.y(), 1.0e-5);
        assertEquals(5.90497359, offset.travel(), 1.0e-4);
        assertEquals(centre.x + offset.x(), offset.shiftedX(), 1.0e-7);
        assertEquals(centre.y + offset.y(), offset.shiftedY(), 1.0e-7);
        assertEquals(WaterRefractionReferenceMath.FallbackReason.NONE, offset.fallback());
        assertTrue(offset.length() * 128.0 > 14.0,
                "the fixed example must move the lookup by more than fourteen 128px texels");
    }

    @Test
    void thicknessStrengthAndClampAreMonotonicAndBounded() {
        Matrix4f view = new Matrix4f().translate(0.0f, -4.0f, 0.0f);
        Vector3f camera = new Vector3f(0.0f, 4.0f, 0.0f);
        Vector3d world = new Vector3d(0.0, 0.0, -20.0);
        Vector3d normal = new Vector3d(0.0, 1.0, 0.0);
        Vector2f centre = WaterRefractionReferenceMath.projectToUv(PROJECTION, view, world);
        assertNotNull(centre);
        double centreX = centre.x;
        double centreY = centre.y;

        double previous = 0.0;
        for (double thickness : new double[]{0.0, 0.25, 1.0, 4.0}) {
            WaterRefractionReferenceMath.Offset offset = WaterRefractionReferenceMath.offset(
                    PROJECTION, view, world, normal, camera, centreX, centreY,
                    1.333, thickness, 1.0, 1.0, 0.5);
            assertTrue(offset.length() >= previous,
                    "offset must not shrink as the water column grows");
            previous = offset.length();
        }

        WaterRefractionReferenceMath.Offset zeroScale = WaterRefractionReferenceMath.offset(
                PROJECTION, view, world, normal, camera, centreX, centreY,
                1.333, THICKNESS, 0.0, 1.0, 0.5);
        assertEquals(0.0, zeroScale.length(), 0.0);
        WaterRefractionReferenceMath.Offset zeroStrength = WaterRefractionReferenceMath.offset(
                PROJECTION, view, world, normal, camera, centreX, centreY,
                1.333, THICKNESS, 1.0, 0.0, 0.5);
        assertEquals(0.0, zeroStrength.length(), 0.0);

        WaterRefractionReferenceMath.Offset single = WaterRefractionReferenceMath.offset(
                PROJECTION, view, world, normal, camera, centreX, centreY,
                1.333, THICKNESS, 1.0, 1.0, 0.5);
        WaterRefractionReferenceMath.Offset doubled = WaterRefractionReferenceMath.offset(
                PROJECTION, view, world, normal, camera, centreX, centreY,
                1.333, THICKNESS, 1.0, 2.0, 0.5);
        assertEquals(single.length() * 2.0, doubled.length(), 1.0e-6);

        WaterRefractionReferenceMath.Offset clamped = WaterRefractionReferenceMath.offset(
                PROJECTION, view, world, normal, camera, centreX, centreY,
                1.333, 1.0e5, 8.0, 2.0, 0.02);
        assertEquals(0.02, clamped.length(), 1.0e-9);
        assertEquals(WaterRefractionReferenceMath.FallbackReason.NONE, clamped.fallback());
    }

    @Test
    void foregroundPredicateUsesTheBiasAgainstTheWaterDistance() {
        assertFalse(WaterRefractionReferenceMath.foregroundRejected(10.0, 9.96, 0.05));
        assertTrue(WaterRefractionReferenceMath.foregroundRejected(10.0, 9.90, 0.05));
        assertFalse(WaterRefractionReferenceMath.foregroundRejected(10.0, 12.0, 0.05));
        assertTrue(WaterRefractionReferenceMath.foregroundRejected(10.0, 9.94, 0.05));
    }

    @Test
    void offscreenLookupFallsBackToTheConfiguredBackground() {
        Matrix4f view = new Matrix4f().translate(0.0f, -4.0f, 0.0f);
        Vector3f camera = new Vector3f(0.0f, 4.0f, 0.0f);
        Vector3d world = new Vector3d(0.0, 0.0, -8.66);
        Vector2f centre = WaterRefractionReferenceMath.projectToUv(PROJECTION, view, world);
        assertNotNull(centre);
        WaterRefractionReferenceMath.Offset offset = WaterRefractionReferenceMath.offset(
                PROJECTION, view, world, new Vector3d(0.0, 1.0, 0.0), camera,
                centre.x, centre.y, 1.333, THICKNESS, 1.0, 1.0, 0.5);
        assertEquals(WaterRefractionReferenceMath.FallbackReason.OUTSIDE_SCREEN,
                offset.fallback());
        assertTrue(offset.shiftedY() < 0.0);

        float[] source = new float[4 * 4 * 4];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int index = (y * 4 + x) * 4;
                source[index] = 0.1f + x * 0.1f;
                source[index + 1] = 0.2f + y * 0.1f;
                source[index + 2] = 0.3f;
                source[index + 3] = 1.0f;
            }
        }
        WaterRefractionReferenceMath.Evaluation outOfBounds =
                new WaterRefractionReferenceMath.Evaluation(true,
                        world, new Vector3d(0.0, 1.0, 0.0),
                        THICKNESS, centre.x, centre.y, offset,
                        WaterRefractionReferenceMath.FallbackReason.OUTSIDE_SCREEN);
        WaterRefractionSettings unrefracted = WaterRefractionSettings.builder()
                .enabled(true).offscreenFallback(
                        WaterRefractionSettings.OffscreenFallback.UNREFRACTED)
                .offscreenFallbackColor(0.9f, 0.1f, 0.9f).build();
        Vector3f centreSample = WaterRefractionReferenceMath.sampleBilinear(source, 4, 4,
                centre.x, centre.y);
        Vector3f kept = WaterRefractionReferenceMath.sampleBackground(outOfBounds,
                unrefracted, source, 4, 4);
        assertEquals(centreSample.x, kept.x, 1.0e-6f);
        assertEquals(centreSample.y, kept.y, 1.0e-6f);
        assertEquals(centreSample.z, kept.z, 1.0e-6f);

        WaterRefractionSettings tinted = WaterRefractionSettings.builder()
                .enabled(true).offscreenFallback(
                        WaterRefractionSettings.OffscreenFallback.TINT)
                .offscreenFallbackColor(0.9f, 0.1f, 0.9f).build();
        Vector3f tint = WaterRefractionReferenceMath.sampleBackground(outOfBounds,
                tinted, source, 4, 4);
        assertEquals(0.9f, tint.x, 0.0f);
        assertEquals(0.1f, tint.y, 0.0f);
        assertEquals(0.9f, tint.z, 0.0f);
    }

    @Test
    void bilinearSamplingClampsBothEdges() {
        float[] source = new float[4 * 4 * 4];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int index = (y * 4 + x) * 4;
                source[index] = x * 0.25f;
                source[index + 1] = y * 0.25f;
                source[index + 2] = 0.5f;
                source[index + 3] = 1.0f;
            }
        }
        assertColour(0.0f, 0.0f, 0.5f,
                WaterRefractionReferenceMath.sampleBilinear(source, 4, 4, 0.0, 0.0), 1.0e-6f);
        assertColour(0.75f, 0.75f, 0.5f,
                WaterRefractionReferenceMath.sampleBilinear(source, 4, 4, 1.0, 1.0), 1.0e-6f);
        assertColour(0.0f, 0.0f, 0.5f,
                WaterRefractionReferenceMath.sampleBilinear(source, 4, 4, -1.0, -1.0), 1.0e-6f);
        assertColour(0.375f, 0.375f, 0.5f,
                WaterRefractionReferenceMath.sampleBilinear(source, 4, 4, 0.5, 0.5), 1.0e-6f);
    }

    @Test
    void refractionDegeneratesToTheIncidentDirectionAtUnitIor() {
        Vector3d incident = new Vector3d(0.3, -0.8, -0.5).normalize();
        Vector3d normal = new Vector3d(0.1, 1.0, 0.05).normalize();
        Vector3d refracted = WaterRefractionReferenceMath.refract(incident, normal, 1.0);
        assertNotNull(refracted);
        assertEquals(incident.x, refracted.x, 1.0e-9);
        assertEquals(incident.y, refracted.y, 1.0e-9);
        assertEquals(incident.z, refracted.z, 1.0e-9);
        assertNull(WaterRefractionReferenceMath.refract(new Vector3d(0.0, 0.0, 0.0),
                normal, 1.5));
    }

    private static void assertColour(float red, float green, float blue, Vector3f actual,
                                     float tolerance) {
        assertEquals(red, actual.x, tolerance, "red");
        assertEquals(green, actual.y, tolerance, "green");
        assertEquals(blue, actual.z, tolerance, "blue");
    }
}
