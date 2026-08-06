package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.subsystems.resources.AssetId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bounded CPU soak for the v0.21 content/reload control path. */
class SerializedSceneStabilityTest {
    @Test
    void runsThreeThousandSixHundredFramesAndOneHundredReloadsWithoutLosingActive() {
        String active = "idle";
        int reloads = 0;
        SceneReloadCoordinator coordinator = new SceneReloadCoordinator();
        AssetId graph = AssetId.of("demo", "player.animation-graph.json");

        for (int frame = 0; frame < 3_600; frame++) {
            if (frame % 36 == 0) {
                coordinator.submit(graph, SceneReloadCoordinator.ChangeKind.GRAPH_PARAMETERS);
                SceneReloadCoordinator.ReloadDecision decision = coordinator.drain();
                assertTrue(decision.graphOnly());
            }
            if (frame % 36 == 0 && reloads < 100) {
                try (SceneReloadTransaction<String> transaction =
                             SceneReloadTransaction.begin(active)) {
                    transaction.stage("generation-" + reloads).commit();
                    active = transaction.active();
                }
                reloads++;
            }
        }

        assertEquals(100, reloads);
        assertEquals("generation-99", active);
    }
}
