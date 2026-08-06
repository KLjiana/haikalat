package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.subsystems.resources.AssetId;
import com.kaleblangley.haikalat.subsystems.resources.ResourceGeneration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SerializedSceneDiagnosticsTest {
    @Test
    void characterSnapshotCarriesGraphAndParameterIdentity() {
        AssetId scene = AssetId.of("demo", "scene.json");
        SceneDefinition definition = new SceneDefinition(scene,
                new SceneDefinition.CameraDefinition("camera",
                        new SceneDefinition.ProjectionDefinition("perspective", 60.0f, 0.1f, 100.0f)),
                List.of(new SceneDefinition.NodeDefinition("camera", null,
                        SceneDefinition.TransformDefinition.identity(), null, null)));
        SceneBuildPlan plan = new SceneBuildPlan(scene, ResourceGeneration.INITIAL, definition,
                java.util.Map.of(), List.of());
        SceneAssetSnapshot snapshot = new SceneAssetSnapshot(0, 0, 0, 0, 0, 0, 0,
                java.util.Map.of(), List.of(), List.of(), null, null);
        SerializedSceneDiagnostics diagnostics = SerializedSceneDiagnostics.from(plan, snapshot);
        assertEquals(scene, diagnostics.scene());
        assertEquals(0, diagnostics.characters().size());
    }
}
