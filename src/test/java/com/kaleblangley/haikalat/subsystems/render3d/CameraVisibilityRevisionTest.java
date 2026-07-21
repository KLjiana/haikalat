package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CameraVisibilityRevisionTest {
    @Test
    void revisionChangesOnlyWhenViewOrProjectionChanges() {
        Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 5.0f));
        long initial = camera.visibilityRevision();

        camera.setPosition(new Vector3f(0.0f, 0.0f, 5.0f));
        camera.setYaw(camera.yaw());
        camera.setPitch(camera.pitch());
        camera.processKeyboard(Camera.Movement.FORWARD, 0.0f);
        camera.processMouseMovement(0.0f, 0.0f);
        camera.processMouseScroll(0.0f);
        camera.setMovementSpeed(100.0f);
        assertEquals(initial, camera.visibilityRevision());

        camera.setPosition(new Vector3f(1.0f, 0.0f, 5.0f));
        long moved = camera.visibilityRevision();
        assertNotEquals(initial, moved);

        camera.processMouseMovement(5.0f, 2.0f);
        long rotated = camera.visibilityRevision();
        assertNotEquals(moved, rotated);

        camera.processMouseScroll(1.0f);
        assertNotEquals(rotated, camera.visibilityRevision());
    }
}
