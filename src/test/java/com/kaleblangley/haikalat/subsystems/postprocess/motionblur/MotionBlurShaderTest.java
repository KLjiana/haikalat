package com.kaleblangley.haikalat.subsystems.postprocess.motionblur;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the raster-space sampling contract of the production motion-blur
 * shader.  The shared colour, velocity and validity buffers are all indexed by
 * the current raster frame; the velocity value is already jitter-free.  A
 * reintroduced stable-coordinate shift would silently soften TAA frames, so it
 * must fail this test rather than only a graph-order check.
 */
class MotionBlurShaderTest {
    @Test
    void samplesEveryInputAtTheSameRasterCoordinate() throws IOException {
        String source;
        try (var stream = MotionBlurShaderTest.class.getResourceAsStream(
                "/shaders/postprocess/motion-blur.frag")) {
            assertNotNull(stream);
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertFalse(source.contains("uJitter"),
                "the production pass must not consume a jitter uniform");
        assertFalse(source.contains("stableUv"),
                "the production pass must not reconstruct in stable coordinates");
        assertTrue(source.contains("texture(uVelocity, rasterUv)"),
                "velocity must be sampled at the current raster coordinate");
        assertTrue(source.contains("texture(uValidity, rasterUv)"),
                "validity must be sampled at the current raster coordinate");
        assertTrue(source.contains("rasterUv + motionUv * fraction"),
                "gather taps must be rooted at the current raster coordinate");
    }
}
