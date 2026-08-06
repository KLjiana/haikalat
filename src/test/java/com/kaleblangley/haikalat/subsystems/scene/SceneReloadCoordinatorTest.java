package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.subsystems.resources.AssetId;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneReloadCoordinatorTest {
    private static AssetId id(String path) {
        return AssetId.of("demo", path);
    }

    @Test
    void coalescesRepeatedGraphNotificationsIntoOneGraphOnlyDecision() {
        SceneReloadCoordinator coordinator = new SceneReloadCoordinator();
        coordinator.submit(id("player.animation-graph.json"),
                SceneReloadCoordinator.ChangeKind.GRAPH_DEFINITION)
                .submit(id("player.animation-graph.json"),
                        SceneReloadCoordinator.ChangeKind.GRAPH_PARAMETERS);

        SceneReloadCoordinator.ReloadDecision decision = coordinator.drain();
        assertTrue(decision.graphOnly());
        assertFalse(decision.requiresPipelineRebuild());
        assertEquals(1, decision.changes().size());
        assertEquals(EnumSet.of(SceneReloadCoordinator.ChangeKind.GRAPH_DEFINITION,
                SceneReloadCoordinator.ChangeKind.GRAPH_PARAMETERS),
                decision.changes().getFirst().changes());
        assertTrue(coordinator.isEmpty());
    }

    @Test
    void topologyDominatesTargetReplacementAndChangesAreSorted() {
        SceneReloadCoordinator coordinator = new SceneReloadCoordinator();
        coordinator.submit(id("z.scene.json"), SceneReloadCoordinator.ChangeKind.TOPOLOGY)
                .submit(id("a.target"), SceneReloadCoordinator.ChangeKind.PRESENTATION_TARGET);

        SceneReloadCoordinator.ReloadDecision decision = coordinator.drain();
        assertTrue(decision.requiresPipelineRebuild());
        assertEquals("demo:a.target", decision.changes().getFirst().asset().toString());
        assertFalse(decision.actions().contains(SceneReloadCoordinator.ReloadAction.REPLACE_TARGET));
    }
}
