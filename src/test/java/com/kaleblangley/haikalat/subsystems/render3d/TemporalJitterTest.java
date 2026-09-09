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
        for (int frame = 0; frame < 8; frame++) {
            Matrix4f stable = new Matrix4f().perspective((float) Math.toRadians(60.0f),
                    width / (float) height, 0.1f, 100.0f);
            Matrix4f jittered = new Matrix4f(stable);
            TemporalJitter.applyProjection(jittered, width, height, AntiAliasingMode.TAA, frame);

            Vector4f world = new Vector4f(0.0f, 0.0f, -5.0f, 1.0f);
            Vector4f stableClip = stable.transform(new Vector4f(world));
            Vector4f jitteredClip = jittered.transform(new Vector4f(world));
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
