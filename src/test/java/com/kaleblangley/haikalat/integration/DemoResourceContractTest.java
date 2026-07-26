package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.core.assets.LoadedModel;
import com.kaleblangley.haikalat.core.assets.ObjModelLoader;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoResourceContractTest {
    private static final Path DEMO_RESOURCES = Path.of("src", "demo", "resources");
    private static final Path INSTANCING_SHADERS = DEMO_RESOURCES.resolve("shaders/instancing");
    private static final Path SCENE_SHADERS = DEMO_RESOURCES.resolve("shaders/scene");
    private static final Path SCENE_CONFIGURATIONS = DEMO_RESOURCES.resolve("scenes/configurations");

    @Test
    void instancedShadersMatchTheirBatchBaseAttributeLocations() throws IOException {
        String projViewShader = Files.readString(INSTANCING_SHADERS.resolve("instanced-projview.vert"));
        String sceneShader = Files.readString(INSTANCING_SHADERS.resolve("instanced-scene.vert"));

        String expectedLayout = "layout (location = " + BuiltinMeshData.INSTANCE_ATTRIBUTE_BASE
                + ") in mat4 aInstanceMatrix;";
        assertTrue(projViewShader.contains(expectedLayout),
                "Builtin meshes reserve attribute 2 for normals, so demo instances start at 3");
        assertTrue(sceneShader.contains(expectedLayout),
                "All demos reserve attribute 2 for mesh normals");
    }

    @Test
    void groundUsesRadiansAndLiesOnTheXzPlane() throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(SCENE_CONFIGURATIONS.resolve("learnopengl.properties"))) {
            properties.load(reader);
        }
        String[] rotation = properties.getProperty("object.ground.rotation").split(",");
        float rotateX = Float.parseFloat(rotation[0]);

        assertEquals(-(float) Math.PI * 0.5f, rotateX, 1.0e-5f);
        assertEquals("0.0,-1.4,-3.0", properties.getProperty("object.ground.position"));
    }

    @Test
    void litShadersUseTheSelectedShadowDirectionalLightIndex() throws IOException {
        String shader = Files.readString(SCENE_SHADERS.resolve("lit-scene.frag"));

        assertTrue(shader.contains("uniform int uDirectionalShadowLightIndex;"));
        assertTrue(shader.contains("i == uDirectionalShadowLightIndex"));
        assertTrue(!shader.contains("i == 0 ? 1.0 - shadowFactor"),
                "Shadowing must not be hard-coded to the first directional light");
    }

    @Test
    void packagedObjBaselineHasRenderablePositionNormalUvData() throws IOException {
        Path modelPath = DEMO_RESOURCES.resolve("models/obj/baseline-pyramid.obj");
        LoadedModel loaded = ObjModelLoader.parse(Files.readString(modelPath), modelPath.toString());
        MeshData mesh = loaded.firstMesh();

        assertEquals(8 * Float.BYTES, mesh.layout().strideBytes());
        assertEquals(java.util.List.of(0, 1, 2), mesh.layout().attributes().stream()
                .map(attribute -> attribute.index()).toList());
        assertTrue(mesh.indices().length >= 3 && mesh.indices().length % 3 == 0);
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            int normal = vertex * 8 + 3;
            float x = mesh.vertices()[normal];
            float y = mesh.vertices()[normal + 1];
            float z = mesh.vertices()[normal + 2];
            assertTrue(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z));
            assertTrue(x * x + y * y + z * z > 0.9f, "OBJ normals must be non-zero and normalized");
        }

        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(SCENE_CONFIGURATIONS.resolve("learnopengl.properties"))) {
            properties.load(reader);
        }
        assertEquals("/models/obj/baseline-pyramid.obj", properties.getProperty("model.pyramid.path"));
        assertEquals("pyramid", properties.getProperty("object.pyramid.model"));
    }
}
