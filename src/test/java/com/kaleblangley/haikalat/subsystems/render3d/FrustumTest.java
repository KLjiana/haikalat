package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.mesh.Bounds3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrustumTest {
    @Test
    void classifiesInsideIntersectingOutsideAndTangentForAllClipPlanes() {
        Frustum frustum = new Frustum().set(new Matrix4f());

        assertEquals(Frustum.Classification.INSIDE,
                frustum.classify(Bounds3f.of(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f)));
        assertEquals(Frustum.Classification.INTERSECTING,
                frustum.classify(Bounds3f.of(0.5f, -0.2f, -0.2f, 1.5f, 0.2f, 0.2f)));
        assertEquals(Frustum.Classification.INTERSECTING,
                frustum.classify(Bounds3f.of(1, 0, 0, 1, 0, 0)));

        Bounds3f[] outside = {
                Bounds3f.of(-3, 0, 0, -2, 0, 0), Bounds3f.of(2, 0, 0, 3, 0, 0),
                Bounds3f.of(0, -3, 0, 0, -2, 0), Bounds3f.of(0, 2, 0, 0, 3, 0),
                Bounds3f.of(0, 0, -3, 0, 0, -2), Bounds3f.of(0, 0, 2, 0, 0, 3)
        };
        for (Bounds3f bounds : outside) {
            assertEquals(Frustum.Classification.OUTSIDE, frustum.classify(bounds));
        }
        assertEquals(Frustum.Classification.INTERSECTING,
                frustum.classify(Bounds3f.unbounded()));
    }

    @Test
    void stableCameraProjectionIgnoresTemporalJitterAndRespondsToAspect() {
        Camera camera = new Camera(new Vector3f(0, 0, 0));
        Matrix4f stable = CameraProjection.stable(camera, 1280, 720, new Matrix4f());
        Matrix4f view = camera.getViewMatrix(new Matrix4f());
        Frustum stableFrustum = new Frustum().set(stable.mul(view, new Matrix4f()));
        Bounds3f edgeProbe = Bounds3f.of(2.9f, -0.1f, -5.1f, 3.1f, 0.1f, -4.9f);
        Frustum.Classification expected = stableFrustum.classify(edgeProbe);

        for (int frame = 0; frame < 32; frame++) {
            Matrix4f shaderProjection = CameraProjection.stable(camera, 1280, 720, new Matrix4f());
            CameraUniforms.applyTemporalJitter(shaderProjection, 1280, 720,
                    AntiAliasingMode.TAA, frame);
            // visibility 每帧重新使用 stableProjection，而不是 shaderProjection。
            Frustum visibility = new Frustum().set(CameraProjection.stable(camera, 1280, 720,
                    new Matrix4f()).mul(view, new Matrix4f()));
            assertEquals(expected, visibility.classify(edgeProbe));
            assertNotEquals(shaderProjection, stable);
        }

        Frustum narrow = new Frustum().set(CameraProjection.stable(camera, 720, 1280,
                new Matrix4f()).mul(view, new Matrix4f()));
        assertNotEquals(stableFrustum.classify(edgeProbe), narrow.classify(edgeProbe));
    }

    @Test
    void rejectsDegenerateOrNonFinitePlaneExtraction() {
        assertThrows(IllegalArgumentException.class,
                () -> new Frustum().set(new Matrix4f().zero()));
        assertThrows(IllegalArgumentException.class,
                () -> new Frustum().set(new Matrix4f().m00(Float.NaN)));
    }

    @Test
    void cameraAtPositiveZRejectsWorldBoundsFarToTheSide() {
        Camera camera = new Camera(new Vector3f(0, 0, 5));
        Matrix4f clip = CameraProjection.stable(camera, 32, 32, new Matrix4f())
                .mul(camera.getViewMatrix(new Matrix4f()), new Matrix4f());
        Frustum frustum = new Frustum().set(clip);
        WorldBounds bounds = new WorldBounds();
        BoundsTransforms.world(Bounds3f.of(-0.5f, -0.5f, 0.0f, 0.5f, 0.5f, 0.0f),
                new Matrix4f().translate(10, 0, 0), bounds);

        assertEquals(Frustum.Classification.OUTSIDE, frustum.classify(bounds));
    }
}
