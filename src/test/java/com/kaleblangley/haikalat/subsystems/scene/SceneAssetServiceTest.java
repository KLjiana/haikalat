package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.subsystems.resources.AssetId;
import com.kaleblangley.haikalat.subsystems.resources.ResourceCatalog;
import com.kaleblangley.haikalat.subsystems.resources.ResourceSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneAssetServiceTest {
    @TempDir
    Path directory;

    @Test
    void coalescesSameGenerationAndBuildsCpuOnlyPlan() throws Exception {
        Files.createDirectories(directory.resolve("scenes/gltf"));
        Files.writeString(directory.resolve("scenes/gltf/minimal.gltf"),
                Files.readString(Path.of("src/test/resources/fixtures/gltf/minimal.gltf")),
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("scenes/showcase.scene.json"), """
                {
                  "format":"haikalat.scene",
                  "version":1,
                  "camera":{"node":"camera","projection":{
                    "type":"perspective","fovYDegrees":60,"near":0.1,"far":100}},
                  "nodes":[
                    {"id":"camera"},
                    {"id":"hero","renderable":{
                      "type":"gltf","asset":"./gltf/minimal.gltf",
                      "animated":false,"castShadows":false}}
                  ]
                }
                """, StandardCharsets.UTF_8);
        ResourceCatalog catalog = ResourceCatalog.builder()
                .mount("demo", ResourceSource.directory("demo", directory))
                .build();
        AssetId sceneId = AssetId.of("demo", "scenes/showcase.scene.json");

        try (SceneAssetService service = new SceneAssetService(catalog)) {
            CompletableFuture<SceneBuildPlan> first = service.loadPlan(sceneId);
            CompletableFuture<SceneBuildPlan> second = service.loadPlan(sceneId);
            assertSame(first, second);
            SceneBuildPlan plan = first.join();

            assertEquals(sceneId, plan.sceneId());
            assertEquals(1, plan.gltfAssets().size());
            assertEquals(1, plan.instances().size());
            assertEquals(sceneId, plan.definition().source());
            assertTrue(service.reverseDependencies()
                    .get(AssetId.of("demo", "scenes/gltf/minimal.gltf"))
                    .contains(sceneId));
            assertEquals(0, service.snapshot().pendingCpuRequests());
            assertEquals(0, service.snapshot().pendingUploadRequests());
            assertTrue(service.snapshot().handles().isEmpty());
            assertEquals(0L, service.snapshot().generations().get(sceneId).value());
        }
    }

    @Test
    void dependencyInvalidationAdvancesSceneGeneration() throws Exception {
        Files.writeString(directory.resolve("scene.scene.json"), """
                {"format":"haikalat.scene","version":1,
                 "camera":{"node":"camera","projection":{
                   "type":"perspective","fovYDegrees":60,"near":0.1,"far":10}},
                 "nodes":[{"id":"camera"}]}
                """, StandardCharsets.UTF_8);
        ResourceCatalog catalog = ResourceCatalog.builder()
                .mount("demo", ResourceSource.directory("demo", directory))
                .build();
        AssetId scene = AssetId.of("demo", "scene.scene.json");

        try (SceneAssetService service = new SceneAssetService(catalog)) {
            SceneBuildPlan plan = service.loadPlan(scene).join();
            assertEquals(0L, plan.generation().value());
            assertEquals(1, service.invalidate(scene).size());
            assertEquals(1L, service.currentGeneration(scene).value());
        }
    }

    @Test
    void managedHandleReschedulesAfterInvalidationWithoutRetainingStaleUploadState() throws Exception {
        Files.writeString(directory.resolve("scene.scene.json"), """
                {"format":"haikalat.scene","version":1,
                 "camera":{"node":"camera","projection":{
                   "type":"perspective","fovYDegrees":60,"near":0.1,"far":10}},
                 "nodes":[{"id":"camera"}]}
                """, StandardCharsets.UTF_8);
        ResourceCatalog catalog = ResourceCatalog.builder()
                .mount("demo", ResourceSource.directory("demo", directory))
                .build();
        AssetId scene = AssetId.of("demo", "scene.scene.json");

        try (SceneAssetService service = new SceneAssetService(catalog)) {
            SceneHandle handle = service.open(scene);
            SceneBuildPlan first = service.loadPlan(scene).join();
            assertEquals(0L, first.generation().value());

            service.reload(scene);
            SceneBuildPlan second = service.loadPlan(scene).join();
            assertEquals(1L, second.generation().value());
            assertEquals(SceneHandle.Status.LOADING, handle.status());
            handle.close();
        }
    }

    @Test
    void externalGltfDependencyInvalidationPropagatesToScene() throws Exception {
        Files.createDirectories(directory.resolve("gltf"));
        Files.write(directory.resolve("gltf/triangle.bin"), Base64.getDecoder().decode(
                "AAAAAAAAAAAAAAAAAACAPwAAAAAAAAAAAAAAAAAAgD8AAAAA"));
        Files.writeString(directory.resolve("gltf/external.gltf"), """
                {
                  "asset":{"version":"2.0"},
                  "scene":0,
                  "scenes":[{"nodes":[0]}],
                  "nodes":[{"mesh":0}],
                  "buffers":[{"byteLength":36,"uri":"triangle.bin"}],
                  "bufferViews":[{"buffer":0,"byteLength":36}],
                  "accessors":[{"bufferView":0,"componentType":5126,
                    "count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]}],
                  "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("scene.scene.json"), """
                {"format":"haikalat.scene","version":1,
                 "camera":{"node":"camera","projection":{
                   "type":"perspective","fovYDegrees":60,"near":0.1,"far":10}},
                 "nodes":[
                   {"id":"camera"},
                   {"id":"triangle","renderable":{
                     "type":"gltf","asset":"./gltf/external.gltf","animated":false}}
                 ]}
                """, StandardCharsets.UTF_8);
        ResourceCatalog catalog = ResourceCatalog.builder()
                .mount("demo", ResourceSource.directory("demo", directory))
                .build();
        AssetId scene = AssetId.of("demo", "scene.scene.json");
        AssetId binary = AssetId.of("demo", "gltf/triangle.bin");

        try (SceneAssetService service = new SceneAssetService(catalog)) {
            service.loadPlan(scene).join();
            assertTrue(service.reverseDependencies().get(binary).contains(scene));
            assertTrue(service.invalidate(binary).contains(scene));
            assertEquals(1L, service.currentGeneration(binary).value());
            assertEquals(1L, service.currentGeneration(scene).value());
        }
    }

    @Test
    void recordsBoundedStructuredCpuFailureDiagnostics() throws Exception {
        Files.writeString(directory.resolve("broken.scene.json"), "{}",
                StandardCharsets.UTF_8);
        ResourceCatalog catalog = ResourceCatalog.builder()
                .mount("demo", ResourceSource.directory("demo", directory))
                .build();
        AssetId scene = AssetId.of("demo", "broken.scene.json");

        try (SceneAssetService service = new SceneAssetService(catalog)) {
            assertThrows(java.util.concurrent.CompletionException.class,
                    () -> service.loadPlan(scene).join());
            SceneAssetSnapshot snapshot = service.snapshot();
            assertEquals("PARSE_SCENE", snapshot.lastFailurePhase());
            assertEquals(scene, snapshot.lastFailureAsset());
            assertEquals(1, snapshot.recentFailures().size());
            assertEquals("PARSE_SCENE", snapshot.recentFailures().getFirst().phase());
        }
    }
}
