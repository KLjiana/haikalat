package com.kaleblangley.haikalat.integration;

import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the test-only striped RGBE environment with the exact decoder the
 * real GL fixture uses ({@code STBImage.stbi_loadf_from_memory}). The shared
 * 4x2 {@code studio-small.hdr} is nearly constant after IBL prefiltering, which
 * made the anisotropic IBL difference invisible; this fixture must keep real
 * azimuthal contrast. This is a JVM fixture sanity check, not a GL test and not
 * a substitute for the GPU assertion.
 */
class AnisotropicEnvironmentFixtureTest {
    private static final String FIXTURE =
            "/environments/pbr/anisotropic-stripes.hdr";
    private static final int EXPECTED_WIDTH = 64;
    private static final int EXPECTED_HEIGHT = 32;

    @Test
    void stripedEnvironmentDecodesWithRealAzimuthalContrast() throws IOException {
        byte[] encoded;
        try (InputStream input = getClass().getResourceAsStream(FIXTURE)) {
            assertNotNull(input, "missing test fixture " + FIXTURE);
            encoded = input.readAllBytes();
        }
        ByteBuffer data = BufferUtils.createByteBuffer(encoded.length);
        data.put(encoded).flip();
        IntBuffer width = BufferUtils.createIntBuffer(1);
        IntBuffer height = BufferUtils.createIntBuffer(1);
        IntBuffer channels = BufferUtils.createIntBuffer(1);
        FloatBuffer pixels;
        STBImage.stbi_set_flip_vertically_on_load_thread(0);
        try {
            pixels = STBImage.stbi_loadf_from_memory(data, width, height, channels, 4);
        } finally {
            STBImage.stbi_set_flip_vertically_on_load_thread(0);
        }
        assertNotNull(pixels, "STB rejected the fixture: " + STBImage.stbi_failure_reason());
        try {
            assertEquals(EXPECTED_WIDTH, width.get(0));
            assertEquals(EXPECTED_HEIGHT, height.get(0));
            assertTrue(channels.get(0) == 3 || channels.get(0) == 4,
                    "RGBE source must report RGB or RGBA channels, got " + channels.get(0));
            double minimum = Double.POSITIVE_INFINITY;
            double maximum = 0.0;
            for (int pixel = 0; pixel < EXPECTED_WIDTH * EXPECTED_HEIGHT; pixel++) {
                int offset = pixel * 4;
                double r = pixels.get(offset);
                double g = pixels.get(offset + 1);
                double b = pixels.get(offset + 2);
                double alpha = pixels.get(offset + 3);
                assertTrue(Double.isFinite(r) && Double.isFinite(g)
                        && Double.isFinite(b) && Double.isFinite(alpha),
                        "fixture pixel " + pixel + " is not finite");
                assertTrue(r >= 0.0 && g >= 0.0 && b >= 0.0 && alpha >= 0.0,
                        "fixture pixel " + pixel + " is negative");
                double luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                minimum = Math.min(minimum, luminance);
                maximum = Math.max(maximum, luminance);
            }
            assertTrue(maximum > 1.0,
                    "fixture must carry real HDR radiance, got max " + maximum);
            assertTrue(maximum > minimum * 20.0,
                    "fixture must keep azimuthal contrast, got min " + minimum
                            + " max " + maximum);
        } finally {
            STBImage.stbi_image_free(pixels);
        }
    }
}
