package com.kaleblangley.haikalat.demo.ui;

import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputCollector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiDemoOptionsTest {
    @Test
    void deterministicModeProvidesHiddenFiniteTypedDefaults() {
        UiDemoOptions options = UiDemoOptions.parse("--deterministic");

        assertTrue(options.deterministic());
        assertTrue(options.hidden());
        assertFalse(options.vsync());
        assertEquals(UiDemoOptions.DEFAULT_DETERMINISTIC_FRAMES, options.maximumFrames());
        assertEquals(UiDemoOptions.ScriptMode.BUILTIN, options.scriptMode());
    }

    @Test
    void parsesAndSortsResizeContentScaleAndExplicitScript() {
        UiDemoOptions options = UiDemoOptions.parse("--deterministic", "--frames=10",
                "--resize=5:800x500", "--resize=2:640X360",
                "--content-scale=1.25x1.5", "--script=none");

        assertEquals(2, options.resizeSteps().size());
        assertEquals(new UiDemoOptions.ResizeStep(2, 640, 360), options.resizeSteps().get(0));
        assertEquals(new UiDemoOptions.ResizeStep(5, 800, 500), options.resizeSteps().get(1));
        assertEquals(1.25f, options.contentScale().x());
        assertEquals(1.5f, options.contentScale().y());
        assertEquals(UiDemoOptions.ScriptMode.NONE, options.scriptMode());
    }

    @Test
    void rejectsAmbiguousOrUnverifiableArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> UiDemoOptions.parse("--frames=0"));
        assertThrows(IllegalArgumentException.class,
                () -> UiDemoOptions.parse("--deterministic", "--frames=5",
                        "--resize=4:640x360"));
        assertThrows(IllegalArgumentException.class,
                () -> UiDemoOptions.parse("--resize=2:640x360", "--resize=2:800x600"));
        assertThrows(IllegalArgumentException.class,
                () -> UiDemoOptions.parse("--content-scale=0x1"));
        assertThrows(IllegalArgumentException.class,
                () -> UiDemoOptions.parse("--unknown"));
    }

    @Test
    void contentScaleAdapterPreservesIncreasingSequenceAndMapsLogicalSpace() {
        WindowInputCollector platform = new WindowInputCollector();
        platform.windowSize(1_000, 900);
        platform.framebufferSize(1_000, 900);
        platform.contentScale(1.0f, 1.0f);
        platform.cursorPosition(500.0, 450.0);
        platform.focused(true);
        platform.cursorInside(true);

        UiDemoInput adapter = new UiDemoInput();
        UiDemoOptions.ContentScale scale = new UiDemoOptions.ContentScale(1.25f, 1.5f);
        var first = adapter.adapt(platform.snapshot(), scale);
        var second = adapter.adapt(platform.snapshot(), scale);

        assertEquals(800, first.windowWidth());
        assertEquals(600, first.windowHeight());
        assertEquals(1_000, first.framebufferWidth());
        assertEquals(900, first.framebufferHeight());
        assertEquals(400.0, first.cursorX(), 0.0001);
        assertEquals(300.0, first.cursorY(), 0.0001);
        assertTrue(second.sequence() > first.sequence());
    }
}
