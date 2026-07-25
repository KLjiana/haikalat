package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalShadowMathTest {
    @Test
    void pointAtlasBuildsSixCenteredFaceMatricesAndTiles() {
        PointShadowAtlas atlas = new PointShadowAtlas(new LocalShadowSettings(256, 0.1f, 0.002f));
        SceneLight light = SceneLight.shadowedPoint(new Vector3f(1.0f, 2.0f, 3.0f),
                new Vector3f(1.0f), 4.0f, 12.0f);
        List<Matrix4f> matrices = atlas.faceMatrices(light);
        Vector3f[] directions = {
                new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0),
                new Vector3f(0, 1, 0), new Vector3f(0, -1, 0),
                new Vector3f(0, 0, 1), new Vector3f(0, 0, -1)
        };
        assertEquals(6, matrices.size());
        assertEquals(768, atlas.width());
        assertEquals(512, atlas.height());
        for (int face = 0; face < matrices.size(); face++) {
            Vector4f clip = matrices.get(face).transform(new Vector4f(
                    new Vector3f(light.position()).fma(1.0f, directions[face]), 1.0f));
            assertEquals(0.0f, clip.x / clip.w, 1.0e-5f);
            assertEquals(0.0f, clip.y / clip.w, 1.0e-5f);
            assertEquals(face % 3 * 256, atlas.viewportX(face));
            assertEquals(face / 3 * 256, atlas.viewportY(face));
        }
        assertThrows(IllegalArgumentException.class, () -> atlas.viewportX(6));
        assertThrows(IllegalArgumentException.class,
                () -> atlas.faceMatrices(SceneLight.shadowedDirectional(
                        new Vector3f(0, -1, 0), new Vector3f(1), 1.0f)));
    }

    @Test
    void spotMatrixCentersTheOuterConeDirection() {
        SpotShadowMap map = new SpotShadowMap(new LocalShadowSettings(512, 0.05f, 0.003f));
        SceneLight light = SceneLight.shadowedSpot(new Vector3f(2.0f, 3.0f, 4.0f),
                new Vector3f(-1.0f, -1.0f, -1.0f), new Vector3f(1.0f),
                8.0f, 20.0f, 0.2f, 0.5f);
        Vector4f clip = map.lightSpaceMatrix(light).transform(new Vector4f(
                new Vector3f(light.position()).fma(2.0f, light.direction()), 1.0f));
        assertEquals(0.0f, clip.x / clip.w, 1.0e-5f);
        assertEquals(0.0f, clip.y / clip.w, 1.0e-5f);
        assertTrue(clip.z / clip.w > -1.0f && clip.z / clip.w < 1.0f);
    }

    @Test
    void cascadePlanUsesContiguousPracticalSplitsAndStableTexelCenters() {
        DirectionalCascadePlan plan = DirectionalCascadePlan.create(
                new Vector3f(), new Vector3f(0.0f, 0.0f, -1.0f),
                new Vector3f(0.0f, 0.0f, -1.0f), (float) Math.toRadians(60.0),
                16.0f / 9.0f, 0.1f, 100.0f, 3, 0.65f, 2048);
        assertEquals(3, plan.cascades().size());
        assertEquals(0.1f, plan.cascades().get(0).nearDistance(), 1.0e-6f);
        assertEquals(100.0f, plan.cascades().get(2).farDistance(), 1.0e-5f);
        for (int index = 1; index < plan.cascades().size(); index++) {
            assertEquals(plan.cascades().get(index - 1).farDistance(),
                    plan.cascades().get(index).nearDistance(), 1.0e-6f);
            assertTrue(plan.cascades().get(index).farDistance()
                    > plan.cascades().get(index - 1).farDistance());
            assertTrue(plan.cascades().get(index).lightSpaceMatrix().isFinite());
        }
        assertEquals(0, plan.select(1.0f));
        assertEquals(2, plan.select(1000.0f));

        float subTexelMove = plan.cascades().get(0).texelWorldSize() * 0.25f;
        DirectionalCascadePlan shifted = DirectionalCascadePlan.create(
                new Vector3f(subTexelMove, 0.0f, 0.0f),
                new Vector3f(0.0f, 0.0f, -1.0f), new Vector3f(0.0f, 0.0f, -1.0f),
                (float) Math.toRadians(60.0), 16.0f / 9.0f,
                0.1f, 100.0f, 3, 0.65f, 2048);
        assertEquals(plan.cascades().get(0).lightSpaceMatrix().m30(),
                shifted.cascades().get(0).lightSpaceMatrix().m30(), 1.0e-6f);
        assertEquals(plan.cascades().get(0).lightSpaceMatrix().m31(),
                shifted.cascades().get(0).lightSpaceMatrix().m31(), 1.0e-6f);
    }
}
