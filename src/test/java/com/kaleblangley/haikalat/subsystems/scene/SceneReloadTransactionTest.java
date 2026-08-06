package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.subsystems.resources.AssetId;
import com.kaleblangley.haikalat.subsystems.resources.ResourceGeneration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneReloadTransactionTest {
    @Test
    void failedCandidateRetainsActiveAndCommittedCandidateIsOneShot() {
        SceneReloadTransaction<String> failed = SceneReloadTransaction.begin("active");
        failed.stage("candidate").fail(new IllegalArgumentException("bad graph"));
        assertEquals("active", failed.active());
        assertEquals(SceneReloadTransaction.Status.FAILED, failed.status());
        assertEquals("bad graph", failed.failure().getMessage());
        assertThrows(IllegalStateException.class, () -> failed.stage("later"));

        SceneReloadTransaction<String> committed = SceneReloadTransaction.begin("old");
        assertEquals("new", committed.stage("new").commit());
        assertEquals("new", committed.active());
        assertEquals(SceneReloadTransaction.Status.COMMITTED, committed.status());
        assertThrows(IllegalStateException.class, committed::commit);
    }

    @Test
    void dependencyDiagnosticsAreBoundedAndExplicit() {
        AssetId scene = AssetId.of("demo", "scene.json");
        SceneDependencyDiagnostics healthy = SceneDependencyDiagnostics.success(scene,
                ResourceGeneration.INITIAL, List.of(scene));
        assertTrue(healthy.healthy());
        SceneDependencyDiagnostics broken = SceneDependencyDiagnostics.failure(scene,
                ResourceGeneration.INITIAL, List.of(scene), List.of(AssetId.of("demo", "graph.json")),
                "MISSING_GRAPH", "graph sidecar is unavailable");
        assertFalse(broken.healthy());
        assertEquals("MISSING_GRAPH", broken.failureCode());
    }
}
