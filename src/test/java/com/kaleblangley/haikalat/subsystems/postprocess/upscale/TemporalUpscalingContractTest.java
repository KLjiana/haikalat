package com.kaleblangley.haikalat.subsystems.postprocess.upscale;

import com.kaleblangley.haikalat.subsystems.postprocess.TemporalUpscalingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalUpscalingSource;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM contract for the temporal-upscaling records, the pass constructor guard
 * and the shader source declarations that bound the runtime path.
 */
class TemporalUpscalingContractTest {
    private static final TemporalUpscalingSettings SETTINGS =
            TemporalUpscalingSettings.defaults();
    private static final Matrix4f IDENTITY = new Matrix4f();

    @Test
    void passRequiresActiveSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalUpscalingPass(TemporalUpscalingSettings.disabled(), 32, 32));
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalUpscalingPass(SETTINGS, 0, 32));
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalUpscalingPass(SETTINGS, 32, -1));
    }

    @Test
    void inputsRejectIncompleteOrInvalidContracts() {
        assertThrows(IllegalArgumentException.class,
                () -> inputs(0, 2, 0, 0, 0, 0, 16, 16, 0.0f, 0.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> inputs(1, 0, 0, 0, 0, 0, 16, 16, 0.0f, 0.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> inputs(1, 2, 3, 0, 0, 0, 16, 16, 0.0f, 0.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> inputs(1, 2, 0, 4, 0, 0, 16, 16, 0.0f, 0.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> inputs(1, 2, 0, 0, 0, 0, 0, 16, 0.0f, 0.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> inputs(1, 2, 0, 0, 0, 0, 16, -1, 0.0f, 0.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> inputs(1, 2, 0, 0, 0, 0, 16, 16, Float.NaN, 0.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> inputs(1, 2, 0, 0, 0, 0, 16, 16, 0.0f, Float.NaN, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> inputs(1, 2, 0, 0, 0, 0, 16, 16, 0.0f, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> inputs(1, 2, 0, 0, 0, 0, 16, 16, 0.0f, 0.0f, -1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> inputs(1, 2, 0, 0, 0, 0, 16, 16, 0.0f, 0.0f, Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalUpscalingInputs(1, 2, 0, 0, 0, -1, 16, 16,
                        0.0f, 0.0f, 1.0f, IDENTITY, IDENTITY, IDENTITY));
        assertThrows(NullPointerException.class,
                () -> new TemporalUpscalingInputs(1, 2, 0, 0, 0, 0, 16, 16,
                        0.0f, 0.0f, 1.0f, null, IDENTITY, IDENTITY));
        assertThrows(NullPointerException.class,
                () -> new TemporalUpscalingInputs(1, 2, 0, 0, 0, 0, 16, 16,
                        0.0f, 0.0f, 1.0f, IDENTITY, null, IDENTITY));
        assertThrows(NullPointerException.class,
                () -> new TemporalUpscalingInputs(1, 2, 0, 0, 0, 0, 16, 16,
                        0.0f, 0.0f, 1.0f, IDENTITY, IDENTITY, null));

        TemporalUpscalingInputs valid = inputs(1, 2, 3, 4, 5, 6, 16, 16, 0.25f, -0.25f, 2.0f);
        assertTrue(valid.usesVelocity());
        assertTrue(valid.hasReactiveMask());
        assertTrue(valid.hasUiMask());
    }

    @Test
    void inputRecordCopiesMatricesDefensively() {
        Matrix4f inverseProjection = new Matrix4f().translation(1.0f, 2.0f, 3.0f);
        Matrix4f inverseView = new Matrix4f().translation(4.0f, 5.0f, 6.0f);
        Matrix4f stableViewProjection = new Matrix4f().translation(7.0f, 8.0f, 9.0f);
        TemporalUpscalingInputs inputs = new TemporalUpscalingInputs(1, 2, 0, 0, 0, 0,
                16, 16, 0.0f, 0.0f, 1.0f, inverseProjection, inverseView, stableViewProjection);
        inverseProjection.zero();
        inverseView.zero();
        stableViewProjection.zero();
        assertArrayEquals(new Matrix4f().translation(1.0f, 2.0f, 3.0f).get(new float[16]),
                inputs.inverseProjection().get(new float[16]), 0.0f);
        assertArrayEquals(new Matrix4f().translation(4.0f, 5.0f, 6.0f).get(new float[16]),
                inputs.inverseView().get(new float[16]), 0.0f);
        assertArrayEquals(new Matrix4f().translation(7.0f, 8.0f, 9.0f).get(new float[16]),
                inputs.stableViewProjection().get(new float[16]), 0.0f);
    }

    @Test
    void sourceRejectsIncompleteOrInvalidContracts() {
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalUpscalingSource(0, 2, 16, 16));
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalUpscalingSource(1, 0, 16, 16));
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalUpscalingSource(1, 2, 3, 0, 16, 16));
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalUpscalingSource(1, 2, 0, 4, 16, 16));
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalUpscalingSource(1, 2, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalUpscalingSource(1, 2, 0, 0, 0, -1, 16, 16));

        TemporalUpscalingSource valid = new TemporalUpscalingSource(1, 2, 16, 16);
        assertThrows(IllegalStateException.class,
                () -> valid.validateAgainst(TemporalUpscalingSettings.disabled(), 64, 64));
        assertEquals(0, valid.velocityTexture());
        assertEquals(0, valid.uiMaskTexture());
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalUpscalingSource(1, 2, 80, 80)
                        .validateAgainst(SETTINGS, 64, 64));
        valid.validateAgainst(SETTINGS, 64, 64);
        valid.validateAgainst(SETTINGS, 16, 16);
    }

    @Test
    void shaderSourcesDeclareTheBoundedContract() throws IOException {
        String resolve = Files.readString(Path.of("src", "main", "resources", "shaders",
                "postprocess", "temporal-upscale.frag"));
        assertTrue(resolve.contains("#define TUS_MAX_NEIGHBORHOOD_RADIUS 3"),
                "shader neighbourhood bound must match the largest quality radius");
        for (String declaration : new String[]{
                "uniform sampler2D uColor;",
                "uniform sampler2D uDepth;",
                "uniform sampler2D uVelocity;",
                "uniform sampler2D uPreviousDepth;",
                "uniform sampler2D uReactive;",
                "uniform sampler2D uUiMask;",
                "uniform sampler2D uHistoryColor;",
                "uniform sampler2D uHistoryState;",
                "uniform mat4 uPreviousViewProjection;",
                "uniform mat4 uPreviousInverseProjection;",
                "uniform vec2 uCurrentJitter;",
                "uniform int uHistoryValid;",
                "uniform int uUseVelocity;",
                "uniform int uHasReactive;",
                "uniform int uHasUiMask;",
                "uniform float uHistoryBlend;",
                "uniform float uReactiveThreshold;",
                "uniform float uExposureThreshold;",
                "uniform float uExposure;",
                "uniform float uMaxReprojectionPixels;"}) {
            assertTrue(resolve.contains(declaration), "missing shader declaration: " + declaration);
        }
        assertTrue(resolve.contains("layout(location = 0) out vec4 outColor;"));
        assertTrue(resolve.contains("layout(location = 1) out vec4 outState;"));
        assertTrue(resolve.contains("outState = vec4(currentDepth, uExposure, 0.0, 1.0);"),
                "the state attachment must carry the depth and exposure used next frame");

        String sharpen = Files.readString(Path.of("src", "main", "resources", "shaders",
                "postprocess", "temporal-upscale-sharpen.frag"));
        assertTrue(sharpen.contains("uniform float uSharpness;"));
        assertTrue(sharpen.contains("uniform sampler2D uUiMask;"));
        long resolvedTaps = countOccurrences(sharpen, "texelFetch(uResolved");
        assertEquals(5, resolvedTaps,
                "the bounded sharpen must be a 5-tap cross, got " + resolvedTaps);
    }

    private static long countOccurrences(String source, String needle) {
        long count = 0;
        int index = 0;
        while ((index = source.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static TemporalUpscalingInputs inputs(int color, int depth, int velocity,
                                                  int previousDepth, int reactive, int uiMask,
                                                  int width, int height, float jitterX,
                                                  float jitterY, float exposure) {
        return new TemporalUpscalingInputs(color, depth, velocity, previousDepth, reactive,
                uiMask, width, height, jitterX, jitterY, exposure, IDENTITY, IDENTITY,
                IDENTITY);
    }
}
