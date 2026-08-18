package com.kaleblangley.haikalat.subsystems.render3d;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DirectionalCascadeSettingsTest {
    @Test
    void atlasLayoutsCoverTwoThroughFourCascades() {
        assertEquals(1024, new DirectionalCascadeSettings(2, 2048, 0.5f, 0.05f).tileSize());
        DirectionalCascadeSettings three = new DirectionalCascadeSettings(3, 4096, 0.5f, 0.05f);
        assertEquals(2, three.columns());
        assertEquals(2, three.rows());
        assertEquals(2048, three.tileSize());
        assertThrows(IllegalArgumentException.class,
                () -> new DirectionalCascadeSettings(5, 4096, 0.5f, 0.05f));
    }
}
