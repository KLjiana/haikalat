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

        assertTrue(projViewShader.contains("layout (location = 2) in mat4 aInstanceMatrix;"),
                "MinimalDemo and AsyncDemo upload instance matrices from attribute 2");
        assertTrue(sceneShader.contains("layout (location = 3) in mat4 aInstanceMatrix;"),
                "LearnOpenGlDemo reserves attribute 2 for mesh normals");
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
}
