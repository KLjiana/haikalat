package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderFrameContextTest {
    @Test
    void invalidationMapsEachRevisionToExactlyOneDomain() {
        SceneRevisionSnapshot base = snapshot(7, 11, 13, 17, 19, 23, 29);

        assertEquals(List.of(FrameInvalidation.Domain.MEMBERSHIP),
                changed(base, snapshot(7, 12, 13, 17, 19, 23, 29)));
        assertEquals(List.of(FrameInvalidation.Domain.TRANSFORM_MODEL),
                changed(base, snapshot(7, 11, 14, 17, 19, 23, 29)));
        assertEquals(List.of(FrameInvalidation.Domain.LIGHTING),
                changed(base, snapshot(7, 11, 13, 18, 19, 23, 29)));
        assertEquals(List.of(FrameInvalidation.Domain.MATERIAL_RENDER_STATE),
                changed(base, snapshot(7, 11, 13, 17, 20, 23, 29)));
        assertEquals(List.of(FrameInvalidation.Domain.CAMERA),
                changed(base, snapshot(7, 11, 13, 17, 19, 24, 29)));
        assertEquals(List.of(FrameInvalidation.Domain.TOPOLOGY_SETTINGS),
                changed(base, snapshot(7, 11, 13, 17, 19, 23, 30)));
        assertFalse(FrameInvalidation.between(base, base).any());
        assertEquals(FrameInvalidation.ALL.reasons(),
                FrameInvalidation.between(null, base).reasons());
    }

    @Test
    void newSceneGenerationInvalidatesOnlySceneOwnedDomainsWhenLocalRevisionsMatch() {
        SceneRevisionSnapshot previous = snapshot(7, 11, 13, 17, 19, 23, 29);
        SceneRevisionSnapshot replacement = snapshot(8, 11, 13, 17, 19, 23, 29);

        FrameInvalidation invalidation = FrameInvalidation.between(previous, replacement);

        assertEquals(List.of(FrameInvalidation.Domain.MEMBERSHIP,
                        FrameInvalidation.Domain.TRANSFORM_MODEL,
                        FrameInvalidation.Domain.LIGHTING,
                        FrameInvalidation.Domain.MATERIAL_RENDER_STATE),
                invalidation.reasons());
        assertFalse(invalidation.invalidated(FrameInvalidation.Domain.CAMERA));
        assertFalse(invalidation.invalidated(FrameInvalidation.Domain.TOPOLOGY_SETTINGS));
    }

    @Test
    void contextFreezesCameraAndLightsAndComparesWithLastSuccessfulFrame() {
        Camera camera = new Camera(new Vector3f(1.0f, 2.0f, 5.0f));
        SceneLight originalLight = SceneLight.directional(
                new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f), 2.0f);
        Scene scene = new Scene(camera).addLight(originalLight);

        RenderFrameContext first = RenderFrameContext.capture(
                scene, camera, 320, 180, 1.0f / 60.0f, 0, 0L, 1L, null);
        RenderFrameContext stable = RenderFrameContext.capture(
                scene, camera, 320, 180, 1.0f / 60.0f, 1, 1L, 1L, first);
        assertFalse(stable.invalidation().any());

        camera.setPosition(new Vector3f(9.0f, 8.0f, 7.0f));
        originalLight.color().set(0.25f, 0.5f, 0.75f);
        scene.setLight(0, SceneLight.directional(
                new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(0.5f), 3.0f));
        RenderFrameContext changed = RenderFrameContext.capture(
                scene, camera, 320, 180, 1.0f / 30.0f, 2, 2L, 1L, stable);

        assertEquals(new Vector3f(1.0f, 2.0f, 5.0f), first.camera().position());
        assertEquals(new Vector3f(1.0f), first.lights().get(0).color());
        assertTrue(changed.invalidation().invalidated(FrameInvalidation.Domain.CAMERA));
        assertTrue(changed.invalidation().invalidated(FrameInvalidation.Domain.LIGHTING));
        assertFalse(changed.invalidation().invalidated(FrameInvalidation.Domain.MEMBERSHIP));

        RenderFrameContext resized = RenderFrameContext.capture(
                scene, camera, 640, 360, 1.0f / 60.0f, 3, 3L, 2L, changed);
        assertEquals(List.of(FrameInvalidation.Domain.TOPOLOGY_SETTINGS),
                resized.invalidation().reasons());
    }

    @Test
    void sceneGenerationIsStableAndProcessUnique() {
        Scene first = new Scene(new Camera());
        Scene second = new Scene(new Camera());
        assertEquals(first.generation(), first.generation());
        assertNotEquals(first.generation(), second.generation());
    }

    @Test
    void contextRejectsInvalidFrameInputs() {
        Scene scene = new Scene(new Camera());
        assertThrows(IllegalArgumentException.class, () -> RenderFrameContext.capture(
                scene, scene.camera(), 0, 1, 0.0f, 0, 0L, 1L, null));
        assertThrows(IllegalArgumentException.class, () -> RenderFrameContext.capture(
                scene, scene.camera(), 1, 1, Float.NaN, 0, 0L, 1L, null));
    }

    private static List<FrameInvalidation.Domain> changed(SceneRevisionSnapshot previous,
                                                           SceneRevisionSnapshot current) {
        return FrameInvalidation.between(previous, current).reasons();
    }

    private static SceneRevisionSnapshot snapshot(long generation, long membership,
                                                   long transform, long lighting,
                                                   long material, long camera, long topology) {
        return new SceneRevisionSnapshot(generation, membership, transform, lighting,
                material, camera, topology);
    }
}
