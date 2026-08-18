package com.kaleblangley.haikalat.demo.pbr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Render3dV023DemoTest {
    @Test
    void interactiveDefaultsKeepTheWindowOpenAtProductionQuality() {
        Render3dV023Demo.Options options = Render3dV023Demo.Options.parse(new String[0]);

        assertAll(
                () -> assertFalse(options.hidden()),
                () -> assertEquals(-1, options.maximumFrames()),
                () -> assertEquals(1280, options.width()),
                () -> assertEquals(720, options.height()),
                () -> assertEquals("default", options.environmentQuality()),
                () -> assertFalse(options.verify()));
    }

    @Test
    void hiddenVerificationAcceptsFiniteResizeAndTestQuality() {
        Render3dV023Demo.Options options = Render3dV023Demo.Options.parse(new String[]{
                "--hidden", "--frames=12", "--size=640x360", "--resize=6:800x450",
                "--environment-quality=test", "--verify"
        });

        assertAll(
                () -> assertTrue(options.hidden()),
                () -> assertEquals(12, options.maximumFrames()),
                () -> assertEquals(640, options.width()),
                () -> assertEquals(360, options.height()),
                () -> assertEquals(6, options.resizeFrame()),
                () -> assertEquals(800, options.resizeWidth()),
                () -> assertEquals(450, options.resizeHeight()),
                () -> assertEquals("test", options.environmentQuality()),
                () -> assertTrue(options.verify()));
    }

    @Test
    void hiddenModeHasABoundedDefaultAndRejectsInvalidAutomationArguments() {
        assertEquals(12, Render3dV023Demo.Options.parse(
                new String[]{"--deterministic"}).maximumFrames());
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> Render3dV023Demo.Options.parse(new String[]{"--frames=0"})),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> Render3dV023Demo.Options.parse(
                                new String[]{"--frames=4", "--resize=4:800x450"})),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> Render3dV023Demo.Options.parse(
                                new String[]{"--environment-quality=ultra"})),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> Render3dV023Demo.Options.parse(new String[]{"--unknown"})));
    }
}
