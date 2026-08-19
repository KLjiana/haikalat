package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowLightSchedulerTest {
    @Test
    void sceneAssignsStableProcessIdsAndSetPreservesIdentity() {
        Scene first = new Scene(new Camera())
                .addLight(point(0.0f), ShadowLightHints.priority(7));
        Scene second = new Scene(new Camera()).addLight(point(1.0f));
        long id = first.lightEntries().getFirst().stableId();
        long revision = first.lightEntries().getFirst().revision();

        first.setLight(0, point(2.0f));
        first.setShadowLightHints(0, ShadowLightHints.priority(9));

        assertEquals(id, first.lightEntries().getFirst().stableId());
        assertEquals(revision + 2, first.lightEntries().getFirst().revision());
        assertEquals(9, first.lightEntries().getFirst().hints().priority());
        assertNotEquals(id, second.lightEntries().getFirst().stableId());
    }

    @Test
    void settingsFactoriesAndBoundsAreLocked() {
        LocalShadowPipelineSettings legacy = LocalShadowPipelineSettings.legacyDefaults();
        LocalShadowPipelineSettings balanced = LocalShadowPipelineSettings.balanced();

        assertEquals(1, legacy.maxPointLights());
        assertEquals(1, legacy.maxSpotLights());
        assertEquals(ShadowSelectionMode.SCENE_ORDER, legacy.selectionMode());
        assertFalse(legacy.cacheStaticTiles());
        assertEquals(2, balanced.maxPointLights());
        assertEquals(4, balanced.maxSpotLights());
        assertTrue(balanced.cacheStaticTiles());
        assertThrows(IllegalArgumentException.class, () -> settings(3, 4, 1.15f));
        assertThrows(IllegalArgumentException.class, () -> settings(2, 5, 1.15f));
        assertThrows(IllegalArgumentException.class, () -> settings(2, 4, 0.99f));
        assertThrows(IllegalArgumentException.class, () -> new LocalShadowPipelineSettings(
                LocalShadowSettings.defaults(), LocalShadowSettings.defaults(), 1, 1,
                ShadowSelectionMode.SCENE_ORDER, ShadowFilterMode.HARD,
                Float.NaN, 1.0f, true));
    }

    @Test
    void balancedPlanSelectsOneDirectionalTwoPointAndFourSpotWithStableSlots() {
        Scene scene = new Scene(new Camera());
        scene.addLight(SceneLight.shadowedDirectional(new Vector3f(-1, -1, -1),
                new Vector3f(1), 1.0f));
        for (int index = 0; index < 3; index++) {
            scene.addLight(point(index), ShadowLightHints.priority(10 - index));
        }
        for (int index = 0; index < 4; index++) {
            scene.addLight(spot(index), ShadowLightHints.priority(index));
        }
        LocalShadowPipelineSettings settings = LocalShadowPipelineSettings.balanced();
        ShadowLightScheduler scheduler = new ShadowLightScheduler();

        ShadowFramePlan first = plan(scheduler, scene, settings);
        ShadowFramePlan second = plan(scheduler, scene, settings);

        assertTrue(first.directional().isPresent());
        assertEquals(2, first.points().size());
        assertEquals(4, first.spots().size());
        assertEquals(first.points().stream().map(PointShadowSlotPlan::slot).toList(),
                second.points().stream().map(PointShadowSlotPlan::slot).toList());
        assertEquals(first.spots().stream().map(SpotShadowSlotPlan::slot).toList(),
                second.spots().stream().map(SpotShadowSlotPlan::slot).toList());
        assertEquals(7, first.decisions().stream()
                .filter(value -> value.status() == ShadowDecision.Status.SELECTED).count());
        assertTrue(first.decisions().stream().anyMatch(value ->
                value.type() == LightType.POINT
                        && value.status() == ShadowDecision.Status.LOWER_PRIORITY));
    }

    @Test
    void sceneOrderTieBreakAndReplacementHysteresisAreDeterministic() {
        Scene scene = new Scene(new Camera())
                .addLight(point(0.0f), ShadowLightHints.priority(0))
                .addLight(point(1.0f), ShadowLightHints.priority(0));
        LocalShadowPipelineSettings one = new LocalShadowPipelineSettings(
                LocalShadowSettings.defaults(), LocalShadowSettings.defaults(), 1, 0,
                ShadowSelectionMode.SCENE_ORDER, ShadowFilterMode.PCF_3X3,
                0.0f, 1.15f, true);
        ShadowLightScheduler scheduler = new ShadowLightScheduler();

        long firstId = plan(scheduler, scene, one).points().getFirst().entry().stableId();
        scene.setShadowLightHints(1, ShadowLightHints.priority(1));
        long replacementId = plan(scheduler, scene, one).points().getFirst().entry().stableId();

        assertEquals(scene.lightEntries().getFirst().stableId(), firstId);
        assertEquals(scene.lightEntries().get(1).stableId(), replacementId,
                "higher content priority must replace an incumbent regardless of hysteresis");
    }

    @Test
    void invalidNearRangeAndShaderOverflowReceiveExplicitTerminalReasons() {
        Scene scene = new Scene(new Camera());
        for (int index = 0; index < LightingBinder.MAX_POINT_LIGHTS; index++) {
            scene.addLight(point(index));
        }
        scene.addLight(point(99));
        LocalShadowSettings invalidForLight = new LocalShadowSettings(128, 6.0f, 0.001f);
        LocalShadowPipelineSettings settings = new LocalShadowPipelineSettings(
                invalidForLight, LocalShadowSettings.defaults(), 2, 0,
                ShadowSelectionMode.SCENE_ORDER, ShadowFilterMode.HARD,
                0.0f, 1.0f, true);

        ShadowFramePlan plan = plan(new ShadowLightScheduler(), scene, settings);

        assertTrue(plan.decisions().stream().anyMatch(value ->
                value.status() == ShadowDecision.Status.INVALID_NEAR_FAR_RANGE));
        assertTrue(plan.decisions().stream().anyMatch(value ->
                value.status() == ShadowDecision.Status.OUTSIDE_SHADER_LIMIT));
    }

    @Test
    void atlasLayoutsAreBoundedNonOverlappingAndStd140OffsetsStayLocked() {
        PointShadowAtlas points = new PointShadowAtlas(new LocalShadowSettings(64, 0.1f, 0.0f), 2);
        SpotShadowAtlas spots = new SpotShadowAtlas(new LocalShadowSettings(64, 0.1f, 0.0f), 4);
        assertEquals(192, points.width());
        assertEquals(256, points.height());
        assertEquals(128, spots.width());
        assertEquals(128, spots.height());
        Set<String> pointRects = new HashSet<>();
        for (int slot = 0; slot < 2; slot++) for (int face = 0; face < 6; face++) {
            ShadowTileRect tile = points.faceTile(slot, face);
            assertTrue(pointRects.add(tile.x() + ":" + tile.y()));
            assertTrue(tile.maxU() <= 1.0f && tile.maxV() <= 1.0f);
        }
        Set<String> spotRects = new HashSet<>();
        for (int slot = 0; slot < 4; slot++) {
            ShadowTileRect tile = spots.tile(slot);
            assertTrue(spotRects.add(tile.x() + ":" + tile.y()));
        }
        assertEquals(0, ShadowSamplingBlock.POINT_META_OFFSET);
        assertEquals(32, ShadowSamplingBlock.POINT_MATRIX_OFFSET);
        assertEquals(800, ShadowSamplingBlock.POINT_RECT_OFFSET);
        assertEquals(992, ShadowSamplingBlock.SPOT_META_OFFSET);
        assertEquals(1056, ShadowSamplingBlock.SPOT_MATRIX_OFFSET);
        assertEquals(1312, ShadowSamplingBlock.SPOT_RECT_OFFSET);
        assertEquals(1376, ShadowSamplingBlock.QUALITY_OFFSET);
        assertEquals(1392, ShadowSamplingBlock.BLOCK_SIZE_BYTES);
    }

    @Test
    void filterPresetsExposeExactKernelContracts() {
        assertEquals(0, ShadowFilterMode.HARD.kernelRadius());
        assertEquals(1, ShadowFilterMode.HARD.sampleCount());
        assertEquals(1, ShadowFilterMode.PCF_3X3.kernelRadius());
        assertEquals(9, ShadowFilterMode.PCF_3X3.sampleCount());
        assertEquals(2, ShadowFilterMode.PCF_5X5.kernelRadius());
        assertEquals(25, ShadowFilterMode.PCF_5X5.sampleCount());
    }

    private static ShadowFramePlan plan(ShadowLightScheduler scheduler, Scene scene,
                                        LocalShadowPipelineSettings settings) {
        PointShadowAtlas points = settings.maxPointLights() == 0 ? null
                : new PointShadowAtlas(settings.point(), settings.maxPointLights());
        SpotShadowAtlas spots = settings.maxSpotLights() == 0 ? null
                : new SpotShadowAtlas(settings.spot(), settings.maxSpotLights());
        return scheduler.plan(scene.lightEntries(), camera(), 1280, 720, settings,
                points, spots, DirectionalShadowMap.defaults(),
                DirectionalCascadeSettings.disabled());
    }

    private static ExternalCamera camera() {
        Matrix4f view = new Matrix4f().lookAt(new Vector3f(0, 1, 8),
                new Vector3f(0, 0, 0), new Vector3f(0, 1, 0));
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60),
                16.0f / 9.0f, 0.1f, 100.0f);
        return ExternalCamera.of(view, projection, new Vector3f(0, 1, 8), 0.0f);
    }

    private static SceneLight point(float x) {
        return SceneLight.shadowedPoint(new Vector3f(x, 1.5f, 2.0f),
                new Vector3f(1), 8.0f, 5.0f);
    }

    private static SceneLight spot(float x) {
        return SceneLight.shadowedSpot(new Vector3f(x - 1.5f, 3, 3),
                new Vector3f(0, -0.6f, -1), new Vector3f(1),
                10.0f, 8.0f, 0.2f, 0.55f);
    }

    private static LocalShadowPipelineSettings settings(int points, int spots, float threshold) {
        return new LocalShadowPipelineSettings(LocalShadowSettings.defaults(),
                LocalShadowSettings.defaults(), points, spots,
                ShadowSelectionMode.CAMERA_IMPORTANCE, ShadowFilterMode.PCF_3X3,
                0.0f, threshold, true);
    }
}
