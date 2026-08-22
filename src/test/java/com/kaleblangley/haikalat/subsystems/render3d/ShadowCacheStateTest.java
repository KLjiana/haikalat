package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowCacheStateTest {
    @Test
    void failedDirtyViewReportsFrameFailureWithoutInvalidatingCleanViews() {
        Scene scene = new Scene(new Camera()).addLight(SceneLight.shadowedDirectional(
                new Vector3f(-1.0f, -1.0f, -1.0f), new Vector3f(1.0f), 1.0f));
        RenderFrameContext context = RenderFrameContext.capture(scene, scene.camera(),
                320, 180, 0.0f, 0, 0, 0L, null);
        ShadowCacheState cache = new ShadowCacheState();
        LocalShadowPipelineSettings settings = LocalShadowPipelineSettings.balanced();
        DirectionalCascadeSettings cascades = new DirectionalCascadeSettings(2, 256, 0.5f, 0.0f);

        ShadowFramePlan initial = plan(scene, new Matrix4f().identity(), new Matrix4f().identity());
        ShadowFramePlan first = cache.prepare(initial, context, scene, settings, cascades);
        assertTrue(first.directional().orElseThrow().dirtyTiles().stream()
                .allMatch(Boolean::booleanValue));
        cache.frameSucceeded();

        ShadowFramePlan moved = plan(scene, new Matrix4f().translation(0.25f, 0.0f, 0.0f),
                new Matrix4f().identity());
        ShadowFramePlan dirty = cache.prepare(moved, context, scene, settings, cascades);
        assertTrue(dirty.directional().orElseThrow().dirtyTiles().getFirst());
        assertFalse(dirty.directional().orElseThrow().dirtyTiles().get(1));
        cache.frameFailed();

        ShadowFramePlan recovery = cache.prepare(moved, context, scene, settings, cascades);
        ShadowFramePlan.DirectionalPlan directional = recovery.directional().orElseThrow();
        assertTrue(directional.dirtyTiles().getFirst());
        assertEquals(ShadowFramePlan.MissReason.FRAME_FAILURE,
                directional.missReasons().getFirst());
        assertFalse(directional.dirtyTiles().get(1));
        assertEquals(ShadowFramePlan.MissReason.NONE, directional.missReasons().get(1));
    }

    private static ShadowFramePlan plan(Scene scene, Matrix4f first, Matrix4f second) {
        SceneLightEntry entry = scene.lightEntries().getFirst();
        List<Matrix4f> matrices = List.of(new Matrix4f(first), new Matrix4f(second));
        List<ShadowTileRect> tiles = List.of(tile(0), tile(1));
        ShadowFramePlan.DirectionalPlan directional = new ShadowFramePlan.DirectionalPlan(
                entry, 0, 1.0f, matrices, new float[]{0.5f, 1.0f},
                new float[]{1.0f, 1.0f}, tiles,
                List.of(false, false), List.of(ShadowFramePlan.MissReason.NONE,
                        ShadowFramePlan.MissReason.NONE));
        return new ShadowFramePlan(Optional.of(directional), List.of(), List.of(), List.of(),
                0, 0, 0, ShadowFilterMode.PCF_3X3, 0, 0);
    }

    private static ShadowTileRect tile(int index) {
        return new ShadowTileRect(index * 64, 0, 64, 64,
                index * 0.5f, 0.0f, (index + 1) * 0.5f, 1.0f);
    }
}
