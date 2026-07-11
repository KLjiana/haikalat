package com.kaleblangley.haikalat.integration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoResourceContractTest {
    private static final Path DEMO_RESOURCES = Path.of("src", "demo", "resources", "demo");

    @Test
    void instancedShadersMatchTheirBatchBaseAttributeLocations() throws IOException {
        String projViewShader = Files.readString(DEMO_RESOURCES.resolve("instanced_projview.vert"));
        String sceneShader = Files.readString(DEMO_RESOURCES.resolve("instanced_scene.vert"));

        assertTrue(projViewShader.contains("layout (location = 3) in mat4 aInstanceMatrix;"),
                "Builtin meshes reserve attribute 2 for normals, so demo instances start at 3");
        assertTrue(sceneShader.contains("layout (location = 3) in mat4 aInstanceMatrix;"),
                "All demos reserve attribute 2 for mesh normals");
    }

    @Test
    void groundUsesRadiansAndLiesOnTheXzPlane() throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(DEMO_RESOURCES.resolve("learnopengl.properties"))) {
            properties.load(reader);
        }
        String[] rotation = properties.getProperty("object.ground.rotation").split(",");
        float rotateX = Float.parseFloat(rotation[0]);

        assertEquals(-(float) Math.PI * 0.5f, rotateX, 1.0e-5f);
        assertEquals("0.0,-1.4,-3.0", properties.getProperty("object.ground.position"));
    }

    @Test
    void litShadersUseTheSelectedShadowDirectionalLightIndex() throws IOException {
        String colorShader = Files.readString(DEMO_RESOURCES.resolve("color_mvp.frag"));
        String texturedShader = Files.readString(DEMO_RESOURCES.resolve("textured_mvp.frag"));

        for (String shader : java.util.List.of(colorShader, texturedShader)) {
            assertTrue(shader.contains("uniform int uDirectionalShadowLightIndex;"));
            assertTrue(shader.contains("i == uDirectionalShadowLightIndex"));
            assertTrue(!shader.contains("i == 0 ? 1.0 - shadowFactor"),
                    "Shadowing must not be hard-coded to the first directional light");
        }
    }
}
