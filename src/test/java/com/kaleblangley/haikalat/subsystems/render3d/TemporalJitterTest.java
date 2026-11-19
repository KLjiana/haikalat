package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraUniforms;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.TemporalJitter;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the jitter sign and unit conversion against the actual projection math. */
class TemporalJitterTest {
    private static final float EPSILON = 1.0e-6f;

    @Test
    void uvOffsetMatchesProjectedPointDelta() {
        int width = 1280;
        int height = 720;
        Matrix4f[] projections = {
                new Matrix4f().perspective((float) Math.toRadians(60.0f), width / (float) height, 0.1f, 100.0f),
                new Matrix4f().ortho(-8, 8, -4, 4, 0.1f, 100.0f),
                new Matrix4f().frustum(-0.08f, 0.12f, -0.05f, 0.07f, 0.1f, 100.0f)
        };
        for (Matrix4f stable : projections) for (float depth : new float[]{0.125f, 5.0f, 60.0f})
                for (int frame = 0; frame < 8; frame++) {
            Matrix4f jittered = new Matrix4f(stable);
            TemporalJitter.applyProjection(jittered, width, height, AntiAliasingMode.TAA, frame);

            Matrix4f uniformProjection = new Matrix4f(stable);
            CameraUniforms.applyTemporalJitter(uniformProjection, width, height, AntiAliasingMode.TAA, frame);
            assertEquals(jittered, uniformProjection, "raster uniforms and successful-frame projection agree");
            Vector4f world = new Vector4f(0.01f, -0.02f, -depth, 1.0f);
            Vector4f stableClip = stable.transform(new Vector4f(world));
            Vector4f jitteredClip = jittered.transform(new Vector4f(world));
            assertEquals(stableClip.z, jitteredClip.z, "jitter preserves clip depth");
            assertEquals(stableClip.w, jitteredClip.w, "jitter preserves perspective division");
            float stableU = stableClip.x / stableClip.w * 0.5f + 0.5f;
            float jitteredU = jitteredClip.x / jitteredClip.w * 0.5f + 0.5f;
            float stableV = stableClip.y / stableClip.w * 0.5f + 0.5f;
            float jitteredV = jitteredClip.y / jitteredClip.w * 0.5f + 0.5f;

            Vector2f expected = TemporalJitter.uvOffset(AntiAliasingMode.TAA, frame,
                    width, height, new Vector2f());
            assertEquals(expected.x, jitteredU - stableU, EPSILON, "u offset frame " + frame);
            assertEquals(expected.y, jitteredV - stableV, EPSILON, "v offset frame " + frame);
        }
    }

    @Test
    void noJitterWhenDisabled() {
        Matrix4f projection = new Matrix4f().perspective(1.0f, 1.0f, 0.1f, 100.0f);
        Matrix4f before = new Matrix4f(projection);
        TemporalJitter.applyProjection(projection, 1280, 720, AntiAliasingMode.NONE, 3);
        assertEquals(before, projection);
        Vector2f uv = TemporalJitter.uvOffset(AntiAliasingMode.FXAA, 3, 1280, 720, null);
        assertEquals(0.0f, uv.x);
        assertEquals(0.0f, uv.y);
    }

    @Test
    void reducedSequenceSharesTheTaaPatternAndWrapsDeterministically() {
        assertEquals(4, TemporalJitter.REDUCED_PHASE_COUNT);
        Matrix4f stable = new Matrix4f().perspective((float) Math.toRadians(60.0f),
                16.0f / 9.0f, 0.1f, 100.0f);
        Vector2f[] expected = {
                new Vector2f(-0.25f, -0.25f),
                new Vector2f(0.25f, -0.25f),
                new Vector2f(-0.25f, 0.25f),
                new Vector2f(0.25f, 0.25f)
        };
        for (int phase = 0; phase < TemporalJitter.REDUCED_PHASE_COUNT; phase++) {
            Vector2f pixels = TemporalJitter.reducedOffsetPixels(phase, new Vector2f());
            assertEquals(expected[phase].x, pixels.x, EPSILON, "phase " + phase + " x");
            assertEquals(expected[phase].y, pixels.y, EPSILON, "phase " + phase + " y");
            assertEquals(expected[phase], pixels,
                    "the reduced phase " + phase + " must be the documented pattern");
            assertEquals(pixels, TemporalJitter.reducedOffsetPixels(phase
                    + TemporalJitter.REDUCED_PHASE_COUNT, new Vector2f()),
                    "phases wrap at the sequence length");

            Matrix4f reduced = new Matrix4f(stable);
            TemporalJitter.applyReducedProjection(reduced, 1280, 720, phase);
            Matrix4f taa = new Matrix4f(stable);
            TemporalJitter.applyProjection(taa, 1280, 720, AntiAliasingMode.TAA, phase);
            assertEquals(taa, reduced,
                    "the reduced sequence must reuse the native TAA phase pattern");
        }
        // The sequence must visit four distinct sub-pixel positions; a single
        // phase or a repeated position would not average any spatial offsets.
        java.util.Set<Vector2f> distinct = new java.util.HashSet<>();
        for (int phase = 0; phase < TemporalJitter.REDUCED_PHASE_COUNT; phase++) {
            distinct.add(TemporalJitter.reducedOffsetPixels(phase, new Vector2f()));
        }
        assertEquals(TemporalJitter.REDUCED_PHASE_COUNT, distinct.size());
    }

    @Test
    void reducedUvOffsetMatchesTheProjectedDeltaAndUsesTheReducedExtent() {
        int reducedWidth = 800;
        int reducedHeight = 600;
        int outputWidth = 1600;
        int outputHeight = 1200;
        Matrix4f[] projections = {
                new Matrix4f().perspective((float) Math.toRadians(60.0f),
                        reducedWidth / (float) reducedHeight, 0.1f, 100.0f),
                new Matrix4f().ortho(-8, 8, -6, 6, 0.1f, 100.0f),
                new Matrix4f().frustum(-0.08f, 0.12f, -0.05f, 0.07f, 0.1f, 100.0f)
        };
        for (Matrix4f stable : projections) {
            for (int phase = 0; phase < TemporalJitter.REDUCED_PHASE_COUNT; phase++) {
                Matrix4f jittered = new Matrix4f(stable);
                TemporalJitter.applyReducedProjection(jittered, reducedWidth, reducedHeight,
                        phase);
                Vector4f world = new Vector4f(0.01f, -0.02f, -5.0f, 1.0f);
                Vector4f stableClip = stable.transform(new Vector4f(world));
                Vector4f jitteredClip = jittered.transform(new Vector4f(world));
                assertEquals(stableClip.z, jitteredClip.z, "jitter preserves clip depth");
                assertEquals(stableClip.w, jitteredClip.w, "jitter preserves clip w");

                Vector2f expected = TemporalJitter.reducedUvOffset(phase, reducedWidth,
                        reducedHeight, new Vector2f());
                float stableU = stableClip.x / stableClip.w * 0.5f + 0.5f;
                float jitteredU = jitteredClip.x / jitteredClip.w * 0.5f + 0.5f;
                float stableV = stableClip.y / stableClip.w * 0.5f + 0.5f;
                float jitteredV = jitteredClip.y / jitteredClip.w * 0.5f + 0.5f;
                assertEquals(expected.x, jitteredU - stableU, EPSILON,
                        "phase " + phase + " u displacement");
                assertEquals(expected.y, jitteredV - stableV, EPSILON,
                        "phase " + phase + " v displacement");

                // The displacement is a quarter of a *reduced* pixel, not of an
                // output pixel; normalizing by the output extent would halve it
                // at this 2x spatial ratio.
                assertEquals(0.25f / reducedWidth, Math.abs(expected.x), EPSILON);
                assertEquals(0.25f / reducedHeight, Math.abs(expected.y), EPSILON);
                assertTrue(Math.abs(expected.x) > Math.abs(0.25f / outputWidth),
                        "the displacement must be normalized by the reduced extent");
            }
        }
    }
}
