package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PbrEnvironmentSettingsTest {
    @Test
    void qualityPresetsAreDeterministicAndValidateTopology() {
        assertEquals(16, PbrEnvironmentSettings.quality("test").environmentSize());
        assertEquals(512, PbrEnvironmentSettings.quality("default").environmentSize());
        assertThrows(IllegalArgumentException.class, () -> new PbrEnvironmentSettings(
                15, 8, 16, 16, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> PbrEnvironmentSettings.quality("fast"));
    }
}
