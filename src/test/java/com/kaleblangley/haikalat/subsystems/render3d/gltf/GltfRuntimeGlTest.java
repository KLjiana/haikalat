package com.kaleblangley.haikalat.subsystems.render3d.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAssetException;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAssetLoader;
import com.kaleblangley.haikalat.core.assets.gltf.LoadedGltfScene;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;

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
                    .load(AssetRef.of("/gltf/minimal.gltf"));
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
    void uploadsEmbeddedMaskedRadioPngAsSrgbTextureVariant() {
        ResourceLocator classpath = ResourceLocator.classpath(getClass());
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            LoadedGltfScene loaded = new GltfAssetLoader(classpath)
                    .load(AssetRef.of("/radio.gltf"));
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
        String dualRole = classpath.readString(AssetRef.of("/radio.gltf"))
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
        String opaque = classpath.readString(AssetRef.of("/radio.gltf"))
                .replace("\"alphaMode\":\"MASK\"", "\"alphaMode\":\"OPAQUE\"");
        java.nio.file.Files.writeString(temporaryDirectory.resolve("radio-faults.gltf"), opaque);
        LoadedGltfScene loaded = new GltfAssetLoader(classpath.addRoot(temporaryDirectory))
                .load(AssetRef.of("radio-faults.gltf"));
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            for (GltfSceneAsset.UploadStage expected : GltfSceneAsset.UploadStage.values()) {
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
    void gltfSceneProducesDistinctPbrPixelsThroughHdrAndAces() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            LoadedGltfScene loaded = new GltfAssetLoader(ResourceLocator.classpath(getClass()))
                    .load(AssetRef.of("/gltf/minimal.gltf"));
            GltfRuntimeLibrary library = GltfRuntimeLibrary.create();
            GltfSceneAsset asset = GltfSceneAsset.upload(loaded, library);
            try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                    "/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality())) {
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
    void runtimeUploadRestoresPrewarmedDeviceVertexArrayState() throws Exception {
        ResourceLocator classpath = ResourceLocator.classpath(getClass());
        String opaque = classpath.readString(AssetRef.of("/radio.gltf"))
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

    private static int rgbEnergy(ByteBuffer pixel) {
        return Byte.toUnsignedInt(pixel.get(0)) + Byte.toUnsignedInt(pixel.get(1))
                + Byte.toUnsignedInt(pixel.get(2));
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder().dimensions(64, 64)
                .title("glTF runtime test").visible(false).build();
    }
}
