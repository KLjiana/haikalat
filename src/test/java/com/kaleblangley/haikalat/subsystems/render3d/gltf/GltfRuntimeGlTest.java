package com.kaleblangley.haikalat.subsystems.render3d.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAssetException;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAssetLoader;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAssetLimits;
import com.kaleblangley.haikalat.core.assets.gltf.GltfLoadOptions;
import com.kaleblangley.haikalat.core.assets.gltf.LoadedGltfScene;
import com.kaleblangley.haikalat.core.assets.gltf.SceneSelection;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.animation.AnimationPlayer;
import com.kaleblangley.haikalat.subsystems.animation.AnimationGraph;
import com.kaleblangley.haikalat.subsystems.animation.ClipMotion;
import com.kaleblangley.haikalat.subsystems.animation.AnimationSignal;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.scene.GltfGpuAssetCache;
import com.kaleblangley.haikalat.subsystems.scene.SceneUploadBudget;
import com.kaleblangley.haikalat.subsystems.resources.AssetId;
import com.kaleblangley.haikalat.subsystems.resources.ResourceGeneration;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.testing.SkinnedGltfFixture;
import com.kaleblangley.haikalat.testing.MorphGltfFixture;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL30.GL_VERTEX_ARRAY_BINDING;

@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class GltfRuntimeGlTest {
    @TempDir java.nio.file.Path temporaryDirectory;

    @Test
    void uploadsInstantiatesAndProtectsLibraryLifetime() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            LoadedGltfScene loaded = new GltfAssetLoader(ResourceLocator.classpath(GltfRuntimeGlTest.class))
                    .load(AssetRef.of("/fixtures/gltf/minimal.gltf"));
            GltfRuntimeLibrary library = GltfRuntimeLibrary.create();
            GltfSceneAsset asset = GltfSceneAsset.upload(loaded, library);
            try {
                assertEquals(1, library.activeAssetCount());
                assertEquals(1, asset.uniqueMeshCount());
                assertEquals(1, asset.instantiate(new Matrix4f().translation(0, 0, -2), true).size());
                assertThrows(IllegalStateException.class, library::close);
            } finally {
                asset.close();
                asset.close();
                library.close();
            }
            assertTrue(asset.isClosed());
            assertTrue(library.isClosed());
            assertThrows(IllegalStateException.class,
                    () -> asset.instantiate(new Matrix4f(), true));
        }
    }

    @Test
    void uploadsCpuDecodedRgba8PixelsWithoutReenteringImageDecoder() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            Texture2D texture = Texture2D.fromRgba8(1, 1,
                    new byte[] {(byte) 0xff, 0x40, 0x20, (byte) 0xff},
                    TextureColorSpace.SRGB);
            try {
                assertEquals(1, texture.width());
                assertEquals(1, texture.height());
                assertEquals(TextureColorSpace.SRGB, texture.colorSpace());
                assertEquals(GL_NO_ERROR, glGetError());
            } finally {
                texture.close();
            }
        }
    }

    @Test
    void gpuAssetCacheSharesExactGenerationAndRetiresOnLastLease() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            LoadedGltfScene loaded = new GltfAssetLoader(ResourceLocator.classpath(getClass()))
                    .load(AssetRef.of("/fixtures/gltf/minimal.gltf"));
            GltfRuntimeLibrary library = GltfRuntimeLibrary.create();
            GltfGpuAssetCache cache = new GltfGpuAssetCache(library);
            AssetId id = AssetId.of("test", "fixtures/gltf/minimal.gltf");
            GltfGpuAssetCache.Lease first = cache.acquire(id, ResourceGeneration.INITIAL,
                    "default", loaded);
            GltfGpuAssetCache.Lease second = cache.acquire(id, ResourceGeneration.INITIAL,
                    "default", loaded);
            GltfSceneAsset shared = first.asset();
            try {
                assertEquals(1, cache.entryCount());
                assertEquals(2, cache.activeLeaseCount());
                assertTrue(shared == second.asset());
                first.close();
                assertEquals(1, cache.activeLeaseCount());
                assertFalse(shared.isClosed());
            } finally {
                second.close();
                cache.close();
                library.close();
            }
            assertTrue(shared.isClosed());
        }
    }

    @Test
    void stagedGpuRequestsCoalesceAndRespectOneStepPumpBudget() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            LoadedGltfScene loaded = new GltfAssetLoader(ResourceLocator.classpath(getClass()))
                    .load(AssetRef.of("/fixtures/gltf/minimal.gltf"));
            GltfRuntimeLibrary library = GltfRuntimeLibrary.create();
            GltfGpuAssetCache cache = new GltfGpuAssetCache(library);
            AssetId id = AssetId.of("test", "fixtures/gltf/minimal.gltf");
            var first = cache.request(id, ResourceGeneration.INITIAL, "default", loaded);
            var second = cache.request(id, ResourceGeneration.INITIAL, "default", loaded);
            try {
                assertEquals(1, cache.pendingUploadCount());
                assertFalse(first.isDone());
                int calls = 0;
                while (!first.isDone()) {
                    assertTrue(cache.pump(new SceneUploadBudget(1, 1_000_000_000L)) <= 1);
                    assertTrue(++calls < 10);
                }
                GltfGpuAssetCache.Lease firstLease = first.join();
                GltfGpuAssetCache.Lease secondLease = second.join();
                assertTrue(firstLease.asset() == secondLease.asset());
                assertEquals(2, cache.activeLeaseCount());
                firstLease.close();
                secondLease.close();
                assertEquals(0, cache.entryCount());
            } finally {
                cache.close();
                library.close();
            }
        }
    }

    @Test
    void animatedSkinUpdatesForwardAndShadowPassesAndProtectsAssetLifetime() throws Exception {
        java.nio.file.Files.writeString(temporaryDirectory.resolve("animated-skin.gltf"),
                SkinnedGltfFixture.document());
        LoadedGltfScene loaded = new GltfAssetLoader(ResourceLocator.classpath(getClass())
                .addRoot(temporaryDirectory)).load(AssetRef.of("animated-skin.gltf"));

        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            GltfRuntimeLibrary library = GltfRuntimeLibrary.create();
            GltfSceneAsset asset = GltfSceneAsset.upload(loaded, library);
            GltfSceneInstance instance = asset.instantiateAnimated(
                    new Matrix4f().translation(-0.5f, -0.5f, 0.0f), true);
            try {
                assertEquals(1, instance.animationCount());
                assertEquals(java.util.List.of("lift"), instance.animationNames());
                assertEquals(1, instance.objects().size());
                assertEquals(0.0f, instance.jointPaletteMatrix(0, 1).m31(), 1.0e-5f);
                assertThrows(IllegalStateException.class, asset::close,
                        "the asset owns meshes and materials used by the live instance");

                Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 3.0f)));
                instance.objects().forEach(scene::add);
                scene.addLight(SceneLight.shadowedDirectional(
                        new Vector3f(0.0f, 0.0f, -1.0f), new Vector3f(1.0f), 3.0f));
                try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                        "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality())) {
                    RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                            RenderSettings.builder()
                                    .toneMappingMode(ToneMappingMode.ACES)
                                    .bloomSettings(BloomSettings.disabled())
                                    .vsync(false)
                                    .build(), environment);
                    try {
                        pipeline.build();
                        instance.seek(0.0f);
                        pipeline.execute(device);
                        ByteBuffer bindFrame = readFrame(window);

                        instance.seek(1.0f);
                        assertEquals(1.0f, instance.jointPaletteMatrix(0, 1).m31(), 1.0e-5f,
                                "the animated tip joint should move one unit from its bind pose");
                        pipeline.execute(device);
                        ByteBuffer animatedFrame = readFrame(window);

                        assertEquals(1, pipeline.lastShadowCasterDrawCount(),
                                "the skinned primitive must traverse the directional shadow pass");
                        assertTrue(frameRgbEnergy(animatedFrame) > 20,
                                "the skinned PBR primitive must produce visible pixels");
                        assertTrue(changedRgbPixels(bindFrame, animatedFrame) > 8,
                                "GPU skinning must visibly move the weighted triangle");
                        assertEquals(GL_NO_ERROR, glGetError());
                    } finally {
                        pipeline.close();
                    }
                }
            } finally {
                instance.close();
                assertTrue(instance.isClosed());
                asset.close();
                library.close();
            }
        }
    }

    @Test
    void morphWeightsArePerInstanceAndDeformForwardAndShadowPasses() throws Exception {
        java.nio.file.Files.writeString(temporaryDirectory.resolve("morph.gltf"),
                MorphGltfFixture.document());
        LoadedGltfScene loaded = new GltfAssetLoader(ResourceLocator.classpath(getClass())
                .addRoot(temporaryDirectory)).load(AssetRef.of("morph.gltf"));

        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            GltfRuntimeLibrary library = GltfRuntimeLibrary.create();
            GltfSceneAsset asset = GltfSceneAsset.upload(loaded, library);
            GltfSceneInstance first = asset.instantiateAnimated(new Matrix4f(), true);
            GltfSceneInstance second = asset.instantiateAnimated(new Matrix4f(), true);
            try {
                assertEquals(2, asset.morphTargetBufferCount());
                assertEquals(1_152L, asset.morphTargetGpuBytes());
                assertEquals(3, first.morphWeightBufferCount());
                assertEquals(48L, first.morphWeightGpuBytes());
                first.setMorphWeight(0, 0, 0.75f);
                assertEquals(0.75f, first.morphWeight(0, 0), 1.0e-6f);
                assertEquals(0.0f, second.morphWeight(0, 0), 1.0e-6f,
                        "instances must not share mutable morph weights");

                Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 3.0f)));
                first.objects().forEach(scene::add);
                scene.addLight(SceneLight.shadowedDirectional(
                        new Vector3f(0.0f, 0.0f, -1.0f), new Vector3f(1.0f), 3.0f));
                try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                        "/environments/pbr/studio-small.hdr",
                        PbrEnvironmentSettings.testQuality())) {
                    RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                            RenderSettings.builder()
                                    .toneMappingMode(ToneMappingMode.ACES)
                                    .bloomSettings(BloomSettings.disabled())
                                    .vsync(false)
                                    .build(), environment);
                    try {
                        pipeline.build();
                        first.play(0, com.kaleblangley.haikalat.subsystems.animation
                                .AnimationPlayer.LoopMode.ONCE).seek(0.0f);
                        pipeline.execute(device);
                        ByteBuffer baseFrame = readFrame(window);

                        first.seek(1.0f);
                        pipeline.execute(device);
                        ByteBuffer morphedFrame = readFrame(window);

                        assertEquals(3, pipeline.lastShadowCasterDrawCount(),
                                "non-skinned and skinned morph nodes must traverse shadow");
                        assertTrue(frameRgbEnergy(morphedFrame) > 20);
                        assertTrue(changedRgbPixels(baseFrame, morphedFrame) > 8,
                                "GPU morph must visibly deform the PBR primitive");
                        assertEquals(GL_NO_ERROR, glGetError());
                    } finally {
                        pipeline.close();
                    }
                }
            } finally {
                second.close();
                first.close();
                asset.close();
                library.close();
            }
        }
    }

    @Test
    void uploadsEmbeddedMaskedRadioPngAsSrgbTextureVariant() {
        ResourceLocator classpath = ResourceLocator.classpath(getClass());
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            LoadedGltfScene loaded = new GltfAssetLoader(classpath)
                    .load(AssetRef.of("/scenes/gltf/radio.gltf"));
            try (GltfRuntimeLibrary library = GltfRuntimeLibrary.create()) {
                GltfSceneAsset asset = GltfSceneAsset.upload(loaded, library);
                try {
                    assertEquals(8, asset.uniqueMeshCount());
                    assertEquals(1, asset.uniqueTextureCount());
                    assertEquals(8, asset.instantiate(new Matrix4f(), false).size());
                    GltfAssetException failure = assertThrows(GltfAssetException.class,
                            () -> asset.instantiate(new Matrix4f(), true));
                    assertTrue(failure.getMessage().contains("castShadows=false"));
                } finally {
                    asset.close();
                }
            }
        }
    }

    @Test
    void maskMaterialInUnselectedSceneDoesNotBlockOpaqueShadowInstantiation() throws Exception {
        String positions = java.util.Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(36).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putFloat(0).putFloat(0).putFloat(0)
                        .putFloat(1).putFloat(0).putFloat(0)
                        .putFloat(0).putFloat(1).putFloat(0).array());
        java.nio.file.Files.writeString(temporaryDirectory.resolve("unused-mask.gltf"), """
                {"asset":{"version":"2.0"},"scene":0,
                 "scenes":[{"name":"opaque","nodes":[0]},{"name":"masked","nodes":[1]}],
                 "nodes":[{"mesh":0},{"mesh":1}],
                 "buffers":[{"byteLength":36,"uri":"data:application/octet-stream;base64,%s"}],
                 "bufferViews":[{"buffer":0,"byteLength":36}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"}],
                 "materials":[{}, {"alphaMode":"MASK","alphaCutoff":0.5}],
                 "meshes":[
                   {"primitives":[{"attributes":{"POSITION":0},"material":0}]},
                   {"primitives":[{"attributes":{"POSITION":0},"material":1}]}]}
                """.formatted(positions));
        LoadedGltfScene loaded = new GltfAssetLoader(ResourceLocator.classpath(getClass())
                .addRoot(temporaryDirectory)).load(AssetRef.of("unused-mask.gltf"));
        assertTrue(loaded.nodes().getFirst().reachable());
        assertFalse(loaded.nodes().get(1).reachable());

        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            try (GltfRuntimeLibrary library = GltfRuntimeLibrary.create()) {
                GltfSceneAsset asset = GltfSceneAsset.upload(loaded, library);
                try {
                    assertEquals(1, asset.instantiate(new Matrix4f(), true).size());
                    assertEquals(GL_NO_ERROR, glGetError());
                } finally {
                    asset.close();
                }
            }
        }
    }

    @Test
    void sameEncodedImageCreatesDistinctSrgbAndLinearTextureVariants() throws Exception {
        ResourceLocator classpath = ResourceLocator.classpath(getClass());
        String dualRole = classpath.readString(AssetRef.of("/scenes/gltf/radio.gltf"))
                .replace("\"alphaMode\":\"MASK\"", "\"alphaMode\":\"OPAQUE\"")
                .replace("\"baseColorTexture\":{\"index\":0}",
                        "\"baseColorTexture\":{\"index\":0},\"metallicRoughnessTexture\":{\"index\":0}");
        java.nio.file.Files.writeString(temporaryDirectory.resolve("radio-dual-role.gltf"), dualRole);
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            LoadedGltfScene loaded = new GltfAssetLoader(classpath.addRoot(temporaryDirectory))
                    .load(AssetRef.of("radio-dual-role.gltf"));
            try (GltfRuntimeLibrary library = GltfRuntimeLibrary.create()) {
                GltfSceneAsset asset = GltfSceneAsset.upload(loaded, library);
                try {
                    assertEquals(2, asset.uniqueTextureCount(),
                            "one encoded image needs independent sRGB and linear GPU variants");
                } finally {
                    asset.close();
                }
            }
        }
    }

    @Test
    void injectedUploadFailuresReleaseEveryOwnedStageAndLibraryLease() throws Exception {
        ResourceLocator classpath = ResourceLocator.classpath(getClass());
        String opaque = classpath.readString(AssetRef.of("/scenes/gltf/radio.gltf"))
                .replace("\"alphaMode\":\"MASK\"", "\"alphaMode\":\"OPAQUE\"");
        java.nio.file.Files.writeString(temporaryDirectory.resolve("radio-faults.gltf"), opaque);
        LoadedGltfScene loaded = new GltfAssetLoader(classpath.addRoot(temporaryDirectory))
                .load(AssetRef.of("radio-faults.gltf"));
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            for (GltfSceneAsset.UploadStage expected : GltfSceneAsset.UploadStage.values()) {
                if (expected == GltfSceneAsset.UploadStage.MORPH) continue;
                try (GltfRuntimeLibrary library = GltfRuntimeLibrary.create()) {
                    GltfAssetException failure = assertThrows(GltfAssetException.class,
                            () -> GltfSceneAsset.upload(loaded, library, (stage, index) -> {
                                if (stage == expected) throw new IllegalStateException(
                                        "injected " + stage + "[" + index + "] failure");
                            }));
                    assertEquals(GltfAssetException.Phase.UPLOAD, failure.phase());
                    assertEquals(0, library.activeAssetCount());
                    assertEquals(GL_NO_ERROR, glGetError(), "cleanup after " + expected);

                    GltfSceneAsset recovered = GltfSceneAsset.upload(loaded, library);
                    recovered.close();
                    assertEquals(0, library.activeAssetCount());
                }
            }
        }
    }

    @Test
    void injectedMorphUploadFailureRollsBackBuffersAndLibraryLease() throws Exception {
        java.nio.file.Files.writeString(temporaryDirectory.resolve("morph-fault.gltf"),
                MorphGltfFixture.document());
        LoadedGltfScene loaded = new GltfAssetLoader(ResourceLocator.classpath(getClass())
                .addRoot(temporaryDirectory)).load(AssetRef.of("morph-fault.gltf"));
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            try (GltfRuntimeLibrary library = GltfRuntimeLibrary.create()) {
                assertThrows(GltfAssetException.class,
                        () -> GltfSceneAsset.upload(loaded, library, (stage, index) -> {
                            if (stage == GltfSceneAsset.UploadStage.MORPH) {
                                throw new IllegalStateException("injected morph failure");
                            }
                        }));
                assertEquals(0, library.activeAssetCount());
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void gltfSceneProducesDistinctPbrPixelsThroughHdrAndAces() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            LoadedGltfScene loaded = new GltfAssetLoader(ResourceLocator.classpath(getClass()))
                    .load(AssetRef.of("/fixtures/gltf/minimal.gltf"));
            GltfRuntimeLibrary library = GltfRuntimeLibrary.create();
            GltfSceneAsset asset = GltfSceneAsset.upload(loaded, library);
            try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                    "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality())) {
                Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 3.0f)));
                asset.instantiate(new Matrix4f().translation(-0.4f, -0.4f, 0.0f), false)
                        .forEach(scene::add);
                scene.addLight(SceneLight.directional(new Vector3f(0.0f, 0.0f, -1.0f),
                        new Vector3f(1.0f), 3.0f));
                RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                        RenderSettings.builder()
                                .toneMappingMode(ToneMappingMode.ACES)
                                .bloomSettings(BloomSettings.disabled())
                                .vsync(false)
                                .build(), environment);
                try {
                    pipeline.build();
                    pipeline.execute(device);
                    ByteBuffer center = readPixel(window.width() / 2, window.height() / 2);
                    ByteBuffer corner = readPixel(1, 1);
                    int centerEnergy = rgbEnergy(center);
                    int cornerEnergy = rgbEnergy(corner);
                    assertTrue(centerEnergy > 20, "glTF PBR object must produce visible energy");
                    assertTrue(Math.abs(centerEnergy - cornerEnergy) > 5,
                            "glTF object pixel must differ from environment background");
                    assertEquals(GL_NO_ERROR, glGetError());
                } finally {
                    pipeline.close();
                }
            } finally {
                asset.close();
                library.close();
            }
        }
    }

    @Test
    void crouchWalkRigidActionsProduceVisiblePbrPixels() {
        LoadedGltfScene loaded = new GltfAssetLoader(ResourceLocator.classpath(getClass())
                .addRoot(Path.of("src/demo/resources")))
                .load(AssetRef.of("scenes/gltf/crouch_walk.glb"),
                        new GltfLoadOptions(new SceneSelection.Default(), false,
                                GltfAssetLimits.defaults()));
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                     "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality());
                 GltfRuntimeLibrary library = GltfRuntimeLibrary.create();
                 GltfSceneAsset asset = GltfSceneAsset.upload(loaded, library);
                 GltfSceneInstance instance = asset.instantiateAnimated(
                         new Matrix4f().scale(0.55f), false)) {
                instance.playCombined(
                        IntStream.range(0, instance.animationCount()).boxed().toList(),
                        AnimationPlayer.LoopMode.LOOP).seek(1.0f / 12.0f);
                Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.2f, 7.0f)));
                instance.objects().forEach(scene::add);
                scene.addLight(SceneLight.directional(new Vector3f(0.0f, 0.0f, -1.0f),
                        new Vector3f(1.0f), 3.0f));
                RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                        RenderSettings.builder()
                                .toneMappingMode(ToneMappingMode.ACES)
                                .bloomSettings(BloomSettings.disabled())
                                .vsync(false)
                                .build(), environment);
                try {
                    pipeline.build();
                    pipeline.execute(device);
                    assertEquals(instance.objects().size(),
                            pipeline.lastVisibilityStatistics().forwardVisible());
                    ByteBuffer frame = readFrame(window);
                    assertTrue(pixelsDifferentFromCorner(frame, window.width()) > 100,
                            "the animated crouch_walk model must differ from the background");
                    assertEquals(GL_NO_ERROR, glGetError());
                } finally {
                    pipeline.close();
                }
            }
        }
    }

    @Test
    void gltfInstanceBridgesGraphParametersTriggersAndTransitionSignals() throws Exception {
        java.nio.file.Files.writeString(temporaryDirectory.resolve("graph-skin.gltf"),
                SkinnedGltfFixture.document());
        java.nio.file.Files.writeString(temporaryDirectory.resolve("graph-skin.animation.json"),
                "{\"animations\":{\"lift\":{\"markers\":["
                        + "{\"name\":\"hit_start\",\"timeSeconds\":0.2},"
                        + "{\"name\":\"hit_end\",\"timeSeconds\":0.3}]}}}");
        LoadedGltfScene loaded = new GltfAssetLoader(ResourceLocator.classpath(getClass())
                .addRoot(temporaryDirectory)).loadWithSidecar(AssetRef.of("graph-skin.gltf"));
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            try (GltfRuntimeLibrary library = GltfRuntimeLibrary.create();
                 GltfSceneAsset asset = GltfSceneAsset.upload(loaded, library);
                 GltfSceneInstance instance = asset.instantiateAnimated(
                         new Matrix4f(), false)) {
                AnimationGraph graph = AnimationGraph.builder("combat", instance.animationSkeleton())
                        .booleanParameter("grounded", true)
                        .floatParameter("speed", 0.0f)
                        .triggerParameter("attack")
                        .triggerParameter("hit")
                        .triggerParameter("dodge")
                        .triggerParameter("combo")
                        .state("idle", new ClipMotion(instance.animationClip(0)),
                                AnimationPlayer.LoopMode.LOOP)
                        .state("attack", new ClipMotion(instance.animationClip(0)),
                                AnimationPlayer.LoopMode.LOOP)
                        .state("hit", new ClipMotion(instance.animationClip(0)),
                                AnimationPlayer.LoopMode.ONCE)
                        .state("dodge", new ClipMotion(instance.animationClip(0)),
                                AnimationPlayer.LoopMode.ONCE)
                        .state("combo", new ClipMotion(instance.animationClip(0)),
                                AnimationPlayer.LoopMode.ONCE)
                        .entry("idle")
                        .transition("idle", "attack",
                                AnimationGraph.TransitionSpec.builder()
                                        .duration(0.1f)
                                        .when(AnimationGraph.Condition.trigger("attack"))
                                        .build())
                        .anyTransition("hit", AnimationGraph.TransitionSpec.builder()
                                .when(AnimationGraph.Condition.trigger("hit")).build())
                        .anyTransition("dodge", AnimationGraph.TransitionSpec.builder()
                                .when(AnimationGraph.Condition.trigger("dodge")).build())
                        .anyTransition("combo", AnimationGraph.TransitionSpec.builder()
                                .when(AnimationGraph.Condition.trigger("combo")).build())
                        .build();
                instance.attachAnimationGraph(graph);
                assertTrue(instance.hasAnimationController());
                assertEquals("idle", instance.currentAnimationState());
                instance.drainEvents();
                instance.setBoolean("grounded", true)
                        .setFloat("speed", 2.5f)
                        .fireTrigger("attack")
                        .update(0.05f);
                assertTrue(instance.drainEvents().stream().anyMatch(signal ->
                        signal.type() == AnimationSignal.Type.TRANSITION_START));
                instance.update(0.1f);
                assertEquals("attack", instance.currentAnimationState());
                assertTrue(instance.drainEvents().stream().anyMatch(signal ->
                        signal.type() == AnimationSignal.Type.TRANSITION_COMPLETE));
                instance.update(0.1f);
                assertEquals(java.util.List.of("hit"), instance.activeAnimationWindows());
                instance.update(0.1f);
                assertTrue(instance.activeAnimationWindows().isEmpty());
                instance.fireTrigger("hit").update(0.0f);
                assertEquals("hit", instance.currentAnimationState());
                instance.fireTrigger("dodge").update(0.0f);
                assertEquals("dodge", instance.currentAnimationState());
                instance.fireTrigger("combo").update(0.0f);
                assertEquals("combo", instance.currentAnimationState());
            }
        }
    }

    @Test
    void runtimeUploadRestoresPrewarmedDeviceVertexArrayState() throws Exception {
        ResourceLocator classpath = ResourceLocator.classpath(getClass());
        String opaque = classpath.readString(AssetRef.of("/scenes/gltf/radio.gltf"))
                .replace("\"alphaMode\":\"MASK\"", "\"alphaMode\":\"OPAQUE\"");
        java.nio.file.Files.writeString(temporaryDirectory.resolve("radio-state.gltf"), opaque);
        LoadedGltfScene loaded = new GltfAssetLoader(classpath.addRoot(temporaryDirectory))
                .load(AssetRef.of("radio-state.gltf"));
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            try (Mesh probe = Mesh.from(loaded.primitives().getFirst().mesh());
                 GltfRuntimeLibrary library = GltfRuntimeLibrary.create()) {
                device.execute(device.createCommandBuffer().bindMesh(probe));
                assertEquals(probe.vertexArray().id(), glGetInteger(GL_VERTEX_ARRAY_BINDING));

                GltfSceneAsset asset = GltfSceneAsset.upload(loaded, library);
                try {
                    assertEquals(probe.vertexArray().id(), glGetInteger(GL_VERTEX_ARRAY_BINDING),
                            "runtime asset creation must restore the VAO cached by the active device");
                    device.execute(device.createCommandBuffer().bindMesh(probe));
                    assertEquals(probe.vertexArray().id(), glGetInteger(GL_VERTEX_ARRAY_BINDING));
                    assertEquals(GL_NO_ERROR, glGetError());
                } finally {
                    asset.close();
                }
            }
        }
    }

    private static ByteBuffer readPixel(int x, int y) {
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        return pixel;
    }

    private static ByteBuffer readFrame(GlfwWindow window) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(window.width() * window.height() * 4);
        glReadPixels(0, 0, window.width(), window.height(), GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static int changedRgbPixels(ByteBuffer left, ByteBuffer right) {
        int changed = 0;
        for (int offset = 0; offset < left.capacity(); offset += 4) {
            int delta = Math.abs(Byte.toUnsignedInt(left.get(offset))
                    - Byte.toUnsignedInt(right.get(offset)))
                    + Math.abs(Byte.toUnsignedInt(left.get(offset + 1))
                    - Byte.toUnsignedInt(right.get(offset + 1)))
                    + Math.abs(Byte.toUnsignedInt(left.get(offset + 2))
                    - Byte.toUnsignedInt(right.get(offset + 2)));
            if (delta > 6) changed++;
        }
        return changed;
    }

    private static int pixelsDifferentFromCorner(ByteBuffer frame, int width) {
        int cornerOffset = (width + 1) * 4;
        int backgroundRed = Byte.toUnsignedInt(frame.get(cornerOffset));
        int backgroundGreen = Byte.toUnsignedInt(frame.get(cornerOffset + 1));
        int backgroundBlue = Byte.toUnsignedInt(frame.get(cornerOffset + 2));
        int distinct = 0;
        for (int offset = 0; offset < frame.capacity(); offset += 4) {
            int delta = Math.abs(Byte.toUnsignedInt(frame.get(offset)) - backgroundRed)
                    + Math.abs(Byte.toUnsignedInt(frame.get(offset + 1)) - backgroundGreen)
                    + Math.abs(Byte.toUnsignedInt(frame.get(offset + 2)) - backgroundBlue);
            if (delta > 12) distinct++;
        }
        return distinct;
    }

    private static int frameRgbEnergy(ByteBuffer pixels) {
        int energy = 0;
        for (int offset = 0; offset < pixels.capacity(); offset += 4) {
            energy += Byte.toUnsignedInt(pixels.get(offset));
            energy += Byte.toUnsignedInt(pixels.get(offset + 1));
            energy += Byte.toUnsignedInt(pixels.get(offset + 2));
        }
        return energy;
    }

    private static int rgbEnergy(ByteBuffer pixel) {
        return Byte.toUnsignedInt(pixel.get(0)) + Byte.toUnsignedInt(pixel.get(1))
                + Byte.toUnsignedInt(pixel.get(2));
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder().dimensions(64, 64)
                .title("glTF runtime test").visible(false).build();
    }
}
