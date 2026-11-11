package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.subsystems.postprocess.ScreenDistortion;
import com.kaleblangley.haikalat.subsystems.vfx.VfxDistortion;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VfxDistortionProjectorTest {
    private static final float NEAR = 0.1f;
    private static final float FAR = 100.0f;
    private static final float FIELD_OF_VIEW = (float) Math.toRadians(90.0);

    private final Matrix4f projection = new Matrix4f()
            .perspective(FIELD_OF_VIEW, 1.0f, NEAR, FAR);
    private final Matrix4f view = new Matrix4f();

    @Test
    void projectsCentreRadiusAndDepthIntoBoundedUv() {
        VfxDistortion source = distortion(1L, new Vector3f(0.0f, 0.0f, -5.0f), 1.0f);

        List<ScreenDistortion> projected = VfxDistortionProjector.project(
                List.of(source), projection, view);

        assertEquals(1, projected.size());
        ScreenDistortion contribution = projected.get(0);
        assertEquals(0.5f, contribution.centerUv().x, 1.0e-6f);
        assertEquals(0.5f, contribution.centerUv().y, 1.0e-6f);
        // 90 degree vertical FOV at five metres: the half-height is five metres,
        // so a one metre radius covers 1 / (2 * 5) of the UV height.
        assertEquals(0.1f, contribution.extentUv().x, 1.0e-5f);
        assertEquals(0.1f, contribution.extentUv().y, 1.0e-5f);
        assertEquals(expectedDepth(5.0f), contribution.effectDepth(), 1.0e-5f);
        assertEquals(new Vector2f(0.1f, 0.0f), contribution.uvOffset());
        assertEquals(1.0f, contribution.mask());
        assertEquals(0.35f, contribution.ring());
        assertEquals(0.4f, contribution.haze());
        assertEquals(2.0f, contribution.phase());
    }

    @Test
    void convertsWorldThicknessToBoundedNdcTolerance() {
        VfxDistortion source = new VfxDistortion(1L, 0, new Vector3f(0.0f, 0.0f, -5.0f),
                1.0f, new Vector2f(), 1.0f, 1.0f, 0.0f, 0.0f, 0.0f);

        ScreenDistortion contribution = VfxDistortionProjector.project(
                List.of(source), projection, view).get(0);

        float expected = expectedDepth(6.0f) - expectedDepth(5.0f);
        assertEquals(expected, contribution.depthThickness(), 1.0e-5f);
        assertTrue(contribution.depthThickness() > 0.0f);
        assertTrue(contribution.depthThickness() < 0.5f);
    }

    @Test
    void zeroThicknessKeepsZeroTolerance() {
        VfxDistortion source = new VfxDistortion(1L, 0, new Vector3f(0.0f, 0.0f, -5.0f),
                1.0f, new Vector2f(), 1.0f, 0.0f, 0.0f, 0.0f, 0.0f);

        ScreenDistortion contribution = VfxDistortionProjector.project(
                List.of(source), projection, view).get(0);

        assertEquals(0.0f, contribution.depthThickness());
    }

    @Test
    void cullsBehindCameraAndOffscreenCentresInsteadOfClamping() {
        VfxDistortion behind = distortion(1L, new Vector3f(0.0f, 0.0f, 2.0f), 1.0f);
        VfxDistortion offscreen = distortion(2L, new Vector3f(20.0f, 0.0f, -5.0f), 1.0f);
        VfxDistortion visible = distortion(3L, new Vector3f(0.0f, 0.0f, -5.0f), 1.0f);

        List<ScreenDistortion> projected = VfxDistortionProjector.project(
                List.of(behind, offscreen, visible), projection, view);

        assertEquals(1, projected.size());
        assertEquals(3L, projected.get(0).effectId());
    }

    @Test
    void offsetsCentreWithTheViewportAspect() {
        VfxDistortion source = distortion(1L, new Vector3f(1.0f, 0.0f, -5.0f), 1.0f);

        ScreenDistortion contribution = VfxDistortionProjector.project(
                List.of(source), projection, view).get(0);

        // 90 degree FOV and one metre at five metres: ndc x = 0.2, uv x = 0.6.
        assertEquals(0.6f, contribution.centerUv().x, 1.0e-5f);
        assertEquals(0.5f, contribution.centerUv().y, 1.0e-5f);
    }

    @Test
    void clampsAnOversizedScreenExtentToTheDataBound() {
        VfxDistortion source = distortion(1L, new Vector3f(0.0f, 0.0f, -0.2f), 5.0f);

        ScreenDistortion contribution = VfxDistortionProjector.project(
                List.of(source), projection, view).get(0);

        assertEquals(ScreenDistortion.MAXIMUM_EXTENT_UV, contribution.extentUv().x, 1.0e-6f);
        assertEquals(ScreenDistortion.MAXIMUM_EXTENT_UV, contribution.extentUv().y, 1.0e-6f);
    }

    @Test
    void rejectsDuplicateContributionKeys() {
        VfxDistortion first = distortion(1L, new Vector3f(0.0f, 0.0f, -5.0f), 1.0f);
        VfxDistortion duplicate = new VfxDistortion(1L, 0, new Vector3f(0.0f, 0.0f, -4.0f),
                1.0f, new Vector2f(), 1.0f, 0.0f, 0.0f, 0.0f, 0.0f);

        assertThrows(IllegalArgumentException.class,
                () -> VfxDistortionProjector.project(List.of(first, duplicate), projection, view));
    }

    @Test
    void emptyInputProducesAnEmptyList() {
        assertTrue(VfxDistortionProjector.project(List.of(), projection, view).isEmpty());
    }

    /** Independent perspective-depth formula for z != 0 in view space. */
    private static float expectedDepth(float distance) {
        float z = -distance;
        float m22 = -(FAR + NEAR) / (FAR - NEAR);
        float m23 = -2.0f * FAR * NEAR / (FAR - NEAR);
        float ndcZ = (m22 * z + m23) / -z;
        return ndcZ * 0.5f + 0.5f;
    }

    private static VfxDistortion distortion(long effectId, Vector3f center, float radius) {
        return new VfxDistortion(effectId, 0, center, radius, new Vector2f(0.1f, 0.0f),
                1.0f, 0.0f, 0.35f, 0.4f, 2.0f);
    }
}
