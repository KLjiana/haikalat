package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutdoorFrameStateTest {
    @Test
    void onlyCommittedCameraBecomesThePreviousTemporalFrame() {
        OutdoorFrameState state = new OutdoorFrameState();
        Camera camera = new Camera(new Vector3f(0.0f, 2.0f, 8.0f));

        assertFalse(state.prepare(camera, 1280, 720, AntiAliasingMode.TAA, 0));
        assertFalse(state.previousCameraValid());
        state.commitSuccessfulFrame();
        assertTrue(state.previousCameraValid());

        camera.setPosition(new Vector3f(0.1f, 2.0f, 8.0f));
        assertFalse(state.prepare(camera, 1280, 720, AntiAliasingMode.TAA, 1));
        assertTrue(state.previousCameraValid());
        state.invalidate();
        assertFalse(state.previousCameraValid());
    }

    @Test
    void largeTranslationAndRotationAreCameraCuts() {
        OutdoorFrameState state = new OutdoorFrameState();
        Camera camera = new Camera(new Vector3f(0.0f, 2.0f, 8.0f));
        state.prepare(camera, 640, 360, AntiAliasingMode.NONE, 0);
        state.commitSuccessfulFrame();

        camera.setPosition(new Vector3f(5.0f, 2.0f, 8.0f));
        assertTrue(state.prepare(camera, 640, 360, AntiAliasingMode.NONE, 1));
        state.commitSuccessfulFrame();
        camera.setYaw(camera.yaw() + 90.0f);
        assertTrue(state.prepare(camera, 640, 360, AntiAliasingMode.NONE, 2));
    }
}
