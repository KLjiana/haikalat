package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies GTAO history allocation failure cannot retire the active pair. */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class GtaoHistoryResizeGlTest {
    @Test
    void injectedGtaoAllocationFailureLeavesActivePairUntouched() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(64, 48).title("GTAO resize candidate").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GtaoHistory history = new GtaoHistory(16, 12);
            try {
                int oldRead = history.readFramebuffer().id();
                System.setProperty("haikalat.test.failGtaoResizeAllocation", "true");
                try {
                    assertThrows(IllegalStateException.class,
                            () -> history.prepareResize(24, 20));
                } finally {
                    System.clearProperty("haikalat.test.failGtaoResizeAllocation");
                }
                assertEquals(oldRead, history.readFramebuffer().id());
                assertEquals(16, history.readFramebuffer().width());
                assertEquals(12, history.readFramebuffer().height());

                GtaoHistory.ResizeCandidate candidate = history.prepareResize(24, 20);
                try {
                    assertEquals(oldRead, history.readFramebuffer().id());
                    history.commitResize(candidate);
                } finally {
                    candidate.close();
                }
                assertEquals(24, history.readFramebuffer().width());
                assertEquals(20, history.readFramebuffer().height());
                GlDebug.checkError("injectedGtaoAllocationFailureLeavesActivePairUntouched");
            } finally {
                history.close();
            }
        }
    }
}
