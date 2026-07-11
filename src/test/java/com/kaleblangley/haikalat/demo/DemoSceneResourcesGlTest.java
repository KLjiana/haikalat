package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.MaterialDef;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.core.assets.SceneAssetConfig;
import com.kaleblangley.haikalat.core.assets.ShaderAsset;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class DemoSceneResourcesGlTest {
    @Test
    void closesOwnedResourcesAndCleansUpPartialLoadFailure() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            ResourceLocator locator = ResourceLocator.classpath(LearnOpenGlDemo.class);
            SceneAssetConfig baseline = SceneAssetConfig.load(locator, "/demo/learnopengl.properties");
            DemoSceneResources resources = DemoSceneResources.load(locator, baseline);
            ShaderProgram modelShader = resources.shader("model");
            List<Mesh> modelMeshes = resources.meshes("pyramid");

            resources.close();
            resources.close();

            assertTrue(resources.isClosed());
            assertTrue(modelShader.isClosed());
            assertTrue(modelMeshes.stream().allMatch(Mesh::isClosed));

            SceneAssetConfig partialFailure = partialFailureConfig();
            AtomicReference<DemoSceneResources> allocated = new AtomicReference<>();
            assertThrows(IllegalStateException.class,
                    () -> DemoSceneResources.load(locator, partialFailure, allocated::set));
            assertTrue(allocated.get().isClosed(), "Partially loaded resources must be closed on failure");
        }
    }

    @Test
    void mapsEveryModelMeshToOneSceneRenderer() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            Mesh first = Mesh.from(BuiltinMeshData.coloredTriangle("first"));
            Mesh second = Mesh.from(BuiltinMeshData.coloredTriangle("second"));
            ShaderProgram shader = ShaderProgram.fromSources("""
                    #version 330 core
                    layout (location = 0) in vec3 aPos;
                    void main() { gl_Position = vec4(aPos, 1.0); }
                    """, """
                    #version 330 core
                    out vec4 FragColor;
                    void main() { FragColor = vec4(1.0); }
                    """);
            Material material = Material.builder(shader).build();
            try {
                Scene scene = new Scene(new Camera());
                int added = LearnOpenGlDemo.addModelMeshes(scene, List.of(first, second), material,
                        (matrix, frame) -> matrix.identity(), true);

                assertEquals(2, added);
                assertEquals(2, scene.renderers().size());
                assertTrue(scene.renderers().stream().allMatch(renderer -> renderer.castShadows()));
            } finally {
                material.close();
                shader.close();
                second.close();
                first.close();
            }
        }
    }

    private static SceneAssetConfig partialFailureConfig() {
        return new SceneAssetConfig(
                Map.of("valid", ShaderAsset.of("/demo/model_scene.vert", "/demo/lit_scene.frag")),
                Map.of("wall", new SceneAssetConfig.TextureDef(AssetRef.of("/wall.png"), false)),
                Map.of("invalid", new MaterialDef("valid", List.of(
                        new MaterialDef.TextureBinding(0, "uTexture", "wall", "unsupportedSampler")),
                        BlendMode.OPAQUE, true)),
                Map.of(), Map.of(), Map.of());
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder()
                .dimensions(32, 32)
                .title("Demo resource GL test")
                .visible(false)
                .build();
    }
}
