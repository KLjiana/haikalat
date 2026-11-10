package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.subsystems.render3d.scene.RenderFrameContext;
import com.kaleblangley.haikalat.subsystems.vfx.VfxLight;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VfxLightBridgeTest {
    @Test
    void stableIdsDoNotCollideWithSceneLightsAndSurviveValueUpdates() {
        Scene scene = new Scene(new Camera(new Vector3f()));
        scene.addLight(SceneLight.point(new Vector3f(1.0f, 0.0f, 0.0f),
                new Vector3f(1.0f), 2.0f, 5.0f));
        scene.addLight(SceneLight.point(new Vector3f(-1.0f, 0.0f, 0.0f),
                new Vector3f(0.0f, 1.0f, 0.0f), 2.0f, 5.0f));
        long highestSceneId = scene.lightEntries().stream()
                .mapToLong(SceneLightEntry::stableId).max().orElseThrow();
        VfxLight first = light(1L, 0, 0.0f, 5);
        VfxLight second = light(2L, 0, 1.0f, 3);

        VfxLightBridge bridge = new VfxLightBridge();
        VfxLightBridge.Result initial = bridge.merge(scene.lightEntries(),
                List.of(first, second), 8, 4, true, true, 1L);
        assertEquals(2, initial.extraEntries().size());
        long firstId = initial.extraEntries().get(0).stableId();
        long secondId = initial.extraEntries().get(1).stableId();
        assertTrue(firstId > highestSceneId);
        assertTrue(secondId > highestSceneId);
        assertNotEquals(firstId, secondId);
        assertEquals(2, scene.lightEntries().size(), "user scene lights must not be mutated");
        assertEquals(2, initial.diagnostics().selected());

        VfxLightBridge.Result repeated = bridge.merge(scene.lightEntries(),
                List.of(first, second), 8, 4, true, true, 2L);
        assertEquals(firstId, repeated.extraEntries().get(0).stableId());
        assertEquals(secondId, repeated.extraEntries().get(1).stableId());
        assertEquals(initial.extraEntries().get(0).revision(),
                repeated.extraEntries().get(0).revision());

        VfxLight moved = light(1L, 0, 2.0f, 5);
        VfxLightBridge.Result changed = bridge.merge(scene.lightEntries(),
                List.of(moved, second), 8, 4, true, true, 3L);
        assertEquals(firstId, changed.extraEntries().get(0).stableId());
        assertTrue(changed.extraEntries().get(0).revision()
                > repeated.extraEntries().get(0).revision());
        assertEquals(repeated.extraEntries().get(1).revision(),
                changed.extraEntries().get(1).revision());
    }

    @Test
    void capacityUsesPriorityAndRemainingLocalLightBudgetDeterministically() {
        Scene scene = new Scene(new Camera(new Vector3f()));
        scene.addLight(SceneLight.point(new Vector3f(0.0f), new Vector3f(1.0f), 1.0f, 4.0f));
        List<VfxLight> requested = List.of(light(1L, 0, 0.0f, 1),
                light(2L, 0, 0.0f, 9), light(3L, 0, 0.0f, 5));

        VfxLightBridge bridge = new VfxLightBridge();
        VfxLightBridge.Result two = bridge.merge(scene.lightEntries(), requested,
                8, 2, true, true, 1L);
        assertEquals(2, two.diagnostics().selected());
        assertEquals(1, two.diagnostics().culled());
        assertEquals("capacity-culled", two.diagnostics().reason());
        assertEquals(9.0f, two.extraEntries().get(0).light().intensity(), 1.0e-6f);
        assertEquals(5.0f, two.extraEntries().get(1).light().intensity(), 1.0e-6f);

        VfxLightBridge.Result remaining = bridge.merge(scene.lightEntries(), requested,
                1, 4, true, true, 2L);
        assertEquals(0, remaining.diagnostics().selected());
        assertEquals(3, remaining.diagnostics().culled());
        assertEquals(0, remaining.diagnostics().capacity());
    }

    @Test
    void hdrGateAndDirectLightGateReportDistinctReasons() {
        Scene scene = new Scene(new Camera(new Vector3f()));
        List<VfxLight> requested = List.of(light(1L, 0, 0.0f, 1));
        VfxLightBridge bridge = new VfxLightBridge();

        VfxLightBridge.Result gated = bridge.merge(scene.lightEntries(), requested,
                8, 4, false, true, 1L);
        assertTrue(gated.extraEntries().isEmpty());
        assertEquals("hdr-vfx-disabled", gated.diagnostics().reason());
        assertEquals(1, gated.diagnostics().culled());

        VfxLightBridge.Result directOff = bridge.merge(scene.lightEntries(), requested,
                8, 4, true, false, 2L);
        assertEquals(1, directOff.diagnostics().selected());
        assertEquals("direct-lighting-disabled", directOff.diagnostics().reason());

        VfxLightBridge.Result empty = bridge.merge(scene.lightEntries(), List.of(),
                8, 4, true, true, 3L);
        assertEquals("no-active-light", empty.diagnostics().reason());
    }

    @Test
    void duplicateOrOversizedEffectLightInputIsRejectedBeforePublication() {
        Scene scene = new Scene(new Camera(new Vector3f()));
        VfxLightBridge bridge = new VfxLightBridge();
        VfxLight duplicate = light(1L, 0, 0.0f, 1);
        assertThrows(IllegalArgumentException.class, () -> bridge.merge(scene.lightEntries(),
                List.of(duplicate, duplicate), 8, 4, true, true, 1L));
        List<VfxLight> oversized = java.util.stream.IntStream.rangeClosed(
                        0, VfxLightBridge.MAXIMUM_REQUESTED_LIGHTS)
                .mapToObj(index -> light(index + 1L, 0, 0.0f, 1))
                .toList();
        assertThrows(IllegalArgumentException.class, () -> bridge.merge(scene.lightEntries(),
                oversized, 1024, 4, true, true, 1L));
    }

    @Test
    void frameContextFreezesExtraLightsAndRejectsIdentityCollisions() {
        Scene scene = new Scene(new Camera(new Vector3f()));
        scene.addLight(SceneLight.point(new Vector3f(0.0f), new Vector3f(1.0f), 1.0f, 4.0f));
        long sceneId = scene.lightEntries().get(0).stableId();
        SceneLightEntry extra = new SceneLightEntry(1_000_000L,
                SceneLight.point(new Vector3f(1.0f, 0.0f, 0.0f), new Vector3f(1.0f), 3.0f, 5.0f),
                ShadowLightHints.DEFAULT, LightVolumeHints.DISABLED, 4L);
        RenderFrameContext context = RenderFrameContext.capture(scene, scene.camera(),
                64, 64, 1.0f / 60.0f, 0, 0L, 0L, null, List.of(extra));
        assertEquals(2, context.lightEntries().size());
        assertEquals(1_000_000L, context.lightEntries().get(1).stableId());
        assertEquals(4L, context.lightEntries().get(1).revision());

        SceneLightEntry collision = new SceneLightEntry(sceneId,
                SceneLight.point(new Vector3f(1.0f), new Vector3f(1.0f), 1.0f, 2.0f),
                ShadowLightHints.DEFAULT, LightVolumeHints.DISABLED, 0L);
        assertThrows(IllegalStateException.class, () -> RenderFrameContext.capture(scene,
                scene.camera(), 64, 64, 1.0f / 60.0f, 0, 0L, 0L, null, List.of(collision)));
    }

    @Test
    void sceneOverCapacityStillFailsInsteadOfDroppingUserLights() {
        Scene scene = new Scene(new Camera(new Vector3f()));
        scene.addLight(SceneLight.point(new Vector3f(0.0f), new Vector3f(1.0f), 1.0f, 4.0f));
        scene.addLight(SceneLight.point(new Vector3f(1.0f), new Vector3f(1.0f), 1.0f, 4.0f));
        ClusteredLightingSettings settings = ClusteredLightingSettings.builder()
                .maxLocalLights(1).build();
        assertThrows(IllegalStateException.class, () -> FrameLightTable.build(
                scene.lightEntries(), new Matrix4f(), settings));
    }

    @Test
    void repeatedSceneAndBridgeAllocationsNeverCollide() {
        for (int iteration = 0; iteration < 8; iteration++) {
            Scene scene = new Scene(new Camera(new Vector3f()));
            scene.addLight(SceneLight.point(new Vector3f(0.0f), new Vector3f(1.0f), 1.0f, 4.0f));
            long sceneId = scene.lightEntries().get(0).stableId();
            VfxLightBridge bridge = new VfxLightBridge();
            VfxLightBridge.Result result = bridge.merge(scene.lightEntries(),
                    List.of(light(iteration + 1L, 0, 0.0f, 1)), 8, 4, true, true, 1L);
            long effectId = result.extraEntries().get(0).stableId();
            assertTrue(effectId > sceneId,
                    "iteration " + iteration + " scene=" + sceneId + " effect=" + effectId);
        }
    }

    private static VfxLight light(long effectId, int index, float x, int priority) {
        return new VfxLight(effectId, index, new Vector3f(x, 1.0f, 0.0f),
                new Vector3f(0.4f, 0.8f, 1.0f), priority, 4.0f, priority);
    }
}
