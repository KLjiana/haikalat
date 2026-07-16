package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UiRendererTest {
    @Test
    void framebufferScaleComesFromDimensionsInsteadOfMonitorContentScale() {
        UiRenderSnapshot sameSize = UiRenderSnapshot.capture(0,
                960, 600, 960, 600, 1.25, 1.25,
                new UiDisplayList(), new UiBatcher());
        UiRenderSnapshot hidpi = UiRenderSnapshot.capture(1,
                960, 600, 1920, 1200, 1.25, 1.25,
                new UiDisplayList(), new UiBatcher());

        assertEquals(1.0, sameSize.framebufferScaleX());
        assertEquals(1.0, sameSize.framebufferScaleY());
        assertEquals(2.0, hidpi.framebufferScaleX());
        assertEquals(2.0, hidpi.framebufferScaleY());
    }

    @Test
    void emptyOrRejectedSnapshotDoesNotRequireGlContext() {
        try (UiRenderer renderer = new UiRenderer(1)) {
            UiRenderSnapshot empty = UiRenderSnapshot.capture(0,
                    32, 32, 32, 32, 1.0, 1.0,
                    new UiDisplayList(), new UiBatcher());
            renderer.record(empty, new CommandBuffer());
            assertFalse(renderer.isInitialized());

            UiDisplayList tooLarge = new UiDisplayList()
                    .addSolidQuad(new UiScreenRect(0, 0, 1, 1), 0xffffffff,
                            UiBlendMode.PREMULTIPLIED_ALPHA)
                    .addSolidQuad(new UiScreenRect(1, 0, 1, 1), 0xffffffff,
                            UiBlendMode.PREMULTIPLIED_ALPHA);
            UiRenderSnapshot rejected = UiRenderSnapshot.capture(1,
                    32, 32, 32, 32, 1.0, 1.0, tooLarge, new UiBatcher());

            assertThrows(IllegalArgumentException.class,
                    () -> renderer.record(rejected, new CommandBuffer()));
            assertFalse(renderer.isInitialized());
        }
    }
}
