package com.kaleblangley.haikalat.subsystems.render3d;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaaSettingsTest {
    @Test
    void defaultsAreValidAndWeightIsBelowOne() {
        TaaSettings settings = TaaSettings.defaults();
        assertTrue(settings.historyWeight() < 1.0f);
        assertTrue(settings.depthAbsoluteTolerance() >= 0.0f);
        assertTrue(settings.depthRelativeTolerance() >= 0.0f);
    }

    @Test
    void depthToleranceUsesMaxOfAbsoluteAndRelative() {
        TaaSettings settings = new TaaSettings(0.9f, 0.01f, 0.02f, 1.0f, true, 1);
        assertEquals(0.01f, settings.depthTolerance(0.1f), 1.0e-6f);
        assertEquals(0.2f, settings.depthTolerance(10.0f), 1.0e-6f);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaaSettings(1.0f, 0.01f, 0.01f, 1.0f, true, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new TaaSettings(0.9f, -1.0f, 0.01f, 1.0f, true, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new TaaSettings(0.9f, 0.0f, 0.0f, 1.0f, true, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new TaaSettings(0.9f, 0.01f, 0.01f, 1.5f, true, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new TaaSettings(0.9f, 0.01f, 0.01f, 1.0f, true, 3));
    }
}
