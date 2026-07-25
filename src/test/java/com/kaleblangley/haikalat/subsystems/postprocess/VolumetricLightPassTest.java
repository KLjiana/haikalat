package com.kaleblangley.haikalat.subsystems.postprocess;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolumetricLightPassTest {
    @Test
    void settingsAreDefensiveAndPhaseIsFiniteAndDirectional() {
        Vector3f position = new Vector3f(1.0f, 2.0f, 3.0f);
        VolumetricLightSettings settings = new VolumetricLightSettings(32, 8.0f, 0.4f,
                0.5f, 5.0f, 0.95f, 0.8f, 3.0f, position,
                new Vector3f(0.0f, 0.0f, -2.0f), new Vector3f(1.0f, 0.8f, 0.6f));
        position.zero();

        assertEquals(1.0f, settings.lightPosition().x, 0.0f);
        assertEquals(1.0f, settings.lightDirection().length(), 1.0e-6f);
        assertTrue(VolumetricLightPass.phase(1.0f, 0.5f)
                > VolumetricLightPass.phase(-1.0f, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> new VolumetricLightSettings(
                129, 8.0f, 0.4f, 0.0f, 5.0f, 0.95f, 0.8f, 3.0f,
                new Vector3f(), new Vector3f(0.0f, 0.0f, -1.0f), new Vector3f(1.0f)));
        assertThrows(IllegalArgumentException.class,
                () -> VolumetricLightPass.phase(2.0f, 0.0f));
    }
}
