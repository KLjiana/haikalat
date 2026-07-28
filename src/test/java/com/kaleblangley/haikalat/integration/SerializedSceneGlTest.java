package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfRuntimeLibrary;
import com.kaleblangley.haikalat.subsystems.resources.AssetId;
import com.kaleblangley.haikalat.subsystems.resources.ResourceCatalog;
import com.kaleblangley.haikalat.subsystems.resources.ResourceSource;
import com.kaleblangley.haikalat.subsystems.scene.GltfGpuAssetCache;
import com.kaleblangley.haikalat.subsystems.scene.SceneAssetService;
import com.kaleblangley.haikalat.subsystems.scene.SceneBuildPlan;
import com.kaleblangley.haikalat.subsystems.scene.SceneHandle;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.opengl.GL;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class SerializedSceneGlTest {
    @TempDir
    Path directory;

    @Test
    void cpuPlanPublishesOnlyAfterCompleteGpuSceneExists() throws Exception {
        Files.createDirectories(directory.resolve("scenes/gltf"));
        Files.writeString(directory.resolve("scenes/gltf/minimal.gltf"),
                Files.readString(Path.of("src/test/resources/fixtures/gltf/minimal.gltf")),
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("scenes/showcase.scene.json"), """
                {"format":"haikalat.scene","version":1,
                 "camera":{"node":"camera","projection":{
                   "type":"perspective","fovYDegrees":60,"near":0.1,"far":100}},
                 "nodes":[
                   {"id":"camera","transform":{"translation":[0,0,3]}},
                   {"id":"model","renderable":{"type":"gltf",
                     "asset":"./gltf/minimal.gltf","castShadows":false}}
                 ]}
                """, StandardCharsets.UTF_8);
        ResourceCatalog catalog = ResourceCatalog.builder()
                .mount("demo", ResourceSource.directory("demo", directory))
                .build();
        try (SceneAssetService service = new SceneAssetService(catalog)) {
            SceneHandle handle = service.open(
                    AssetId.of("demo", "scenes/showcase.scene.json"));
            SceneBuildPlan plan = service.loadPlan(
                    AssetId.of("demo", "scenes/showcase.scene.json")).join();
            try (GlfwWindow window = GlTestSupport.hiddenWindow()) {
                window.bindContext();
                GL.createCapabilities();
                GltfRuntimeLibrary library = GltfRuntimeLibrary.create();
                GltfGpuAssetCache cache = new GltfGpuAssetCache(library);
                try {
                    for (int i = 0; i < 20 && !handle.hasCurrent(); i++) {
                        service.pumpUploads(cache, new com.kaleblangley.haikalat.subsystems.scene
                                .SceneUploadBudget(1, 1_000_000_000L));
                        service.applyReadyScenes(candidate -> {
                            assertEquals(1, candidate.scene().renderers().size());
                            return true;
                        });
                    }
                    assertTrue(handle.hasCurrent());
                    assertEquals(1, handle.current().scene().renderers().size());
                    assertEquals(1, cache.activeLeaseCount());

                    var firstVersion = handle.current();
                    Files.writeString(directory.resolve("scenes/showcase.scene.json"),
                            "{\"format\":\"haikalat.scene\",\"version\":1}",
                            StandardCharsets.UTF_8);
                    service.reload(plan.sceneId());
                    assertThrows(java.util.concurrent.CompletionException.class,
                            () -> service.loadPlan(plan.sceneId()).join());
                    assertSame(firstVersion, handle.current());
                    assertEquals(1, cache.activeLeaseCount());

                    Files.writeString(directory.resolve("scenes/showcase.scene.json"), """
                            {"format":"haikalat.scene","version":1,
                             "camera":{"node":"camera","projection":{
                               "type":"perspective","fovYDegrees":55,"near":0.1,"far":100}},
                             "nodes":[
                               {"id":"camera","transform":{"translation":[0,0,4]}},
                               {"id":"model","renderable":{"type":"gltf",
                                 "asset":"./gltf/minimal.gltf","castShadows":false}}
                             ]}
                            """, StandardCharsets.UTF_8);
                    service.reload(plan.sceneId());
                    SceneBuildPlan replacement = service.loadPlan(plan.sceneId()).join();
                    for (int i = 0; i < 20 && handle.current().generation()
                            .equals(plan.generation()); i++) {
                        service.pumpUploads(cache, new com.kaleblangley.haikalat.subsystems.scene
                                .SceneUploadBudget(1, 1_000_000_000L));
                        service.applyReadyScenes(candidate -> true);
                    }
                    assertTrue(handle.current().generation().value()
                            > plan.generation().value());
                    assertNotSame(firstVersion, handle.current());
                    assertTrue(firstVersion.isClosed());
                    assertEquals(1, cache.entryCount(),
                            "unchanged glTF generation should remain shared across scene reload");
                    assertEquals(1, cache.activeLeaseCount());
                } finally {
                    handle.close();
                    assertEquals(0, cache.activeLeaseCount());
                    cache.close();
                    library.close();
                }
            }
        }
    }
}
