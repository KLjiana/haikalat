package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporalFrameStateTest {
    @Test
    void previousOnlyAdvancesOnSuccessfulCommit() {
        TemporalFrameState state = new TemporalFrameState();
        TemporalFrameState.FrameParameters first = parameters(0, 0.0f, 0.0f, 0.0f);
        state.prepare(first);
        assertNull(state.previous());
        state.discardFrame();
        assertNull(state.previous(), "a failed frame must not publish previous state");

        state.prepare(first);
        state.commitSuccessfulFrame();
        assertNotNull(state.previous());

        TemporalFrameState.FrameParameters second = parameters(1, 0.1f, 0.0f, 0.0f);
        state.prepare(second);
        state.commitSuccessfulFrame();
        assertTrue(state.previous().frameSequence() == 1);
    }

    @Test
    void largePositionJumpLooksLikeCameraCut() {
        TemporalFrameState.FrameParameters before = parameters(0, 0.0f, 0.0f, 0.0f);
        TemporalFrameState.FrameParameters after = parameters(1, 100.0f, 0.0f, 0.0f);
        assertTrue(TemporalFrameState.looksLikeCameraCut(before, after));
        assertFalse(TemporalFrameState.looksLikeCameraCut(before,
                parameters(1, 0.01f, 0.0f, 0.0f)));
    }

    private static TemporalFrameState.FrameParameters parameters(long sequence,
                                                                 float x, float y, float z) {
        Matrix4f projection = new Matrix4f().perspective(1.0f, 1.0f, 0.1f, 100.0f);
        Matrix4f view = new Matrix4f().lookAt(x, y, z, x, y, z - 1.0f, 0.0f, 1.0f, 0.0f);
        Matrix4f viewProjection = new Matrix4f(projection).mul(view);
        return new TemporalFrameState.FrameParameters(sequence, 64, 64, 0.1f, 100.0f,
                x, y, z, 0.0f, 0.0f, -1.0f, 0.0f, 0.0f,
                projection, projection, new Matrix4f(projection).invert(), view,
                viewProjection, new Matrix4f(viewProjection).invert(), sequence);
    }
}
