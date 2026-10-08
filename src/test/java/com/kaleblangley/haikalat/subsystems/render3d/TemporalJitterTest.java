package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
