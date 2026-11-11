package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import org.junit.jupiter.api.Test;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;

/**
 * Decodes the three test-only HDR probe fixtures with the same STB entry point
 * used by {@code Texture2D.fromHdrResource}. This is a JVM-only guard against a
 * malformed RGBE fixture silently making all probes look like the global
 * environment before any GPU test runs.
 */
class ReflectionProbeHdrFixtureTest {
    @Test
    void fixturesDecodeToTheirDeclaredLinearColors() throws IOException {
        assertFixture("probe-warm.hdr", 1.0f, 10.0f / 128.0f, 5.0f / 128.0f);
        assertFixture("probe-cool.hdr", 5.0f / 128.0f, 10.0f / 128.0f, 1.0f);
        assertFixture("probe-neutral.hdr", 0.25f, 0.25f, 0.25f);
    }

    @Test
    void directionalFixtureIsRedTowardPlusZAndDarkTowardMinusX() throws IOException {
        byte[] bytes;
        try (InputStream input = ReflectionProbeHdrFixtureTest.class.getResourceAsStream(
                "/environments/pbr/probe-directional.hdr")) {
            assertNotNull(input, "missing directional HDR fixture");
            bytes = input.readAllBytes();
        }
        ByteBuffer source = memAlloc(bytes.length);
        try {
            source.put(bytes).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer width = stack.mallocInt(1);
                IntBuffer height = stack.mallocInt(1);
                IntBuffer channels = stack.mallocInt(1);
                FloatBuffer image = STBImage.stbi_loadf_from_memory(source, width, height,
                        channels, 4);
                assertNotNull(image, "STB failed to decode probe-directional.hdr: "
                        + STBImage.stbi_failure_reason());
                try {
                    assertEquals(64, width.get(0));
                    assertEquals(32, height.get(0));
                    // Equirect u=0.75, v=0.5 is +Z; u=0 is -X.
                    int plusZ = (16 * 64 + 48) * 4;
                    int minusX = (16 * 64) * 4;
                    assertEquals(4.0f, image.get(plusZ), 0.05f,
                            "+Z must be the red lobe");
                    assertTrue(image.get(minusX) < 0.05f,
                            "-X must be dark for the rotation regression");
                } finally {
                    STBImage.stbi_image_free(image);
                }
            }
        } finally {
            memFree(source);
        }
    }

    private static void assertFixture(String name, float red, float green, float blue)
            throws IOException {
        byte[] bytes;
        try (InputStream input = ReflectionProbeHdrFixtureTest.class.getResourceAsStream(
                "/environments/pbr/" + name)) {
            assertNotNull(input, "missing HDR fixture " + name);
            bytes = input.readAllBytes();
        }
        ByteBuffer source = memAlloc(bytes.length);
        try {
            source.put(bytes).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer width = stack.mallocInt(1);
                IntBuffer height = stack.mallocInt(1);
                IntBuffer channels = stack.mallocInt(1);
                FloatBuffer image = STBImage.stbi_loadf_from_memory(source, width, height,
                        channels, 4);
                assertNotNull(image, "STB failed to decode " + name + ": "
                        + STBImage.stbi_failure_reason());
                try {
                    assertEquals(4, width.get(0), name + " width");
                    assertEquals(2, height.get(0), name + " height");
                    for (int pixel = 0; pixel < width.get(0) * height.get(0); pixel++) {
                        int offset = pixel * 4;
                        assertEquals(red, image.get(offset), 1.0e-4f,
                                name + " red at pixel " + pixel);
                        assertEquals(green, image.get(offset + 1), 1.0e-4f,
                                name + " green at pixel " + pixel);
                        assertEquals(blue, image.get(offset + 2), 1.0e-4f,
                                name + " blue at pixel " + pixel);
                    }
                } finally {
                    STBImage.stbi_image_free(image);
                }
            }
        } finally {
            memFree(source);
        }
    }
}
