package com.kaleblangley.haikalat.subsystems.postprocess.denoise;

import com.kaleblangley.haikalat.subsystems.postprocess.TemporalDenoiseSettings;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-layer contract for the temporal-denoise input/enable boundary.
 *
 * <p>Pixel correctness is proved by the real-GL test; this JVM case pins the
 * fail-closed constructor state and the bounded shader shape so an accidentally
 * unbounded loop or a missing uniform is caught without a GL context.</p>
 */
class TemporalDenoiseContractTest {
    @Test
    void disabledOrInvalidConstructionFailsBeforeAnyResourceExists() {
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalDenoisePass(TemporalDenoiseSettings.disabled(), 16, 16));
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalDenoisePass(TemporalDenoiseSettings.defaults(), 0, 16));
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalDenoisePass(TemporalDenoiseSettings.defaults(), 16, -1));
    }

    @Test
    void inputsCarryDefensiveMatrixCopiesAndRequireTheSignal() {
        Matrix4f inverseProjection = new Matrix4f().identity();
        Matrix4f inverseView = new Matrix4f().identity();
        Matrix4f stableViewProjection = new Matrix4f().identity();
        TemporalDenoiseInputs inputs = new TemporalDenoiseInputs(11, 12, 13, 0, 0,
                inverseProjection, inverseView, stableViewProjection);
        inverseProjection.translate(1.0f, 2.0f, 3.0f);
        assertEquals(new Matrix4f().identity(), inputs.inverseProjection());
        assertFalse(inputs.usesVelocity());

        TemporalDenoiseInputs velocity = new TemporalDenoiseInputs(11, 12, 13, 14, 15,
                new Matrix4f(), new Matrix4f(), new Matrix4f());
        assertTrue(velocity.usesVelocity());

        TemporalDenoiseInputs jittered = new TemporalDenoiseInputs(11, 12, 13, 0, 0,
                0.25f, -0.125f, new Matrix4f(), new Matrix4f(), new Matrix4f());
        assertEquals(0.25f, jittered.jitterUvX(), 0.0f);
        assertEquals(-0.125f, jittered.jitterUvY(), 0.0f);
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalDenoiseInputs(11, 12, 13, 0, 0,
                        Float.NaN, 0.0f, new Matrix4f(), new Matrix4f(),
                        new Matrix4f()));
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalDenoiseInputs(11, 12, 13, 0, 0,
                        0.0f, Float.POSITIVE_INFINITY, new Matrix4f(),
                        new Matrix4f(), new Matrix4f()));

        assertThrows(IllegalArgumentException.class,
                () -> new TemporalDenoiseInputs(0, 12, 13, 0, 0,
                        new Matrix4f(), new Matrix4f(), new Matrix4f()));
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalDenoiseInputs(11, 0, 13, 0, 0,
                        new Matrix4f(), new Matrix4f(), new Matrix4f()));
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalDenoiseInputs(11, 12, 0, 0, 0,
                        new Matrix4f(), new Matrix4f(), new Matrix4f()));
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalDenoiseInputs(11, 12, 13, 14, 0,
                        new Matrix4f(), new Matrix4f(), new Matrix4f()),
                "velocity without previous depth would silently skip depth rejection");
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalDenoiseInputs(11, 12, 13, 0, 15,
                        new Matrix4f(), new Matrix4f(), new Matrix4f()));
        assertThrows(NullPointerException.class,
                () -> new TemporalDenoiseInputs(11, 12, 13, 0, 0,
                        null, new Matrix4f(), new Matrix4f()));
        assertThrows(NullPointerException.class,
                () -> new TemporalDenoiseInputs(11, 12, 13, 0, 0,
                        new Matrix4f(), null, new Matrix4f()));
        assertThrows(NullPointerException.class,
                () -> new TemporalDenoiseInputs(11, 12, 13, 0, 0,
                        new Matrix4f(), new Matrix4f(), null));
    }

    @Test
    void productionShaderKeepsTheBoundedResolveAndMomentShape() throws IOException {
        String source;
        try (var stream = TemporalDenoiseContractTest.class.getResourceAsStream(
                "/shaders/postprocess/temporal-denoise.frag")) {
            assertNotNull(stream);
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(source.contains("#version 460 core"));
        assertTrue(source.contains("#define TDN_MAX_NEIGHBORHOOD_RADIUS 4"),
                "the neighbourhood loop bound must match the settings maximum");
        assertTrue(source.contains("uNeighborhoodRadius"));
        assertTrue(source.contains("uDepthRejectionThreshold"));
        assertTrue(source.contains("uNormalRejectionThreshold"));
        assertTrue(source.contains("uReactiveThreshold"));
        assertTrue(source.contains("uMaxReprojectionPixels"));
        assertTrue(source.contains("uHistoryMoments"));
        assertTrue(source.contains("historySigma"),
                "history moments must feed the clamp sigma, not only the write-back");
        assertTrue(source.contains("sigma = max(currentSigma, historySigma)"));
        assertTrue(source.contains("uPreviousViewProjection"));
        assertTrue(source.contains("uUseVelocity"));
        assertTrue(source.contains("uCurrentJitter"));
        assertTrue(source.contains("uPreviousJitter"));
        assertTrue(source.contains("texelFetch(uHistoryColor"));
        assertTrue(source.contains("texelFetch(uHistoryState"));
        assertTrue(source.contains("outState = vec4(currentDepth, currentOct.x, currentOct.y, 1.0)"));
        assertTrue(source.contains("outMoments = vec2(outMean, outM2)"));
        assertFalse(source.contains("while ("),
                "an unbounded loop would break the cost contract");
        assertFalse(source.contains("gl_FragCoord.xy ="),
                "the shader must not write its fragment coordinate");
        assertFalse(source.contains("imageStore"),
                "the denoiser must not use a second unordered write path");
    }

    @Test
    void settingsBoundsMatchTheShaderShape() {
        assertEquals(4, TemporalDenoiseSettings.MAXIMUM_NEIGHBORHOOD_RADIUS);
        assertEquals(64, TemporalDenoiseSettings.MAXIMUM_HISTORY_LENGTH);
        assertEquals(0.9f, TemporalDenoiseSettings.defaults().historyWeight(), 1.0e-6f);
    }
}
