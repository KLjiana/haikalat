package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Construction failures must not leak the two compute programs.
 *
 * <p>Both the pre-allocation budget rejection and the post-program storage
 * rejection paths are exercised, and live PROGRAM resources are counted through
 * the backend tracking ring.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ClusteredLightingResourcesLifecycleGlTest {
    @Test
    void rejectedConstructionDoesNotLeakComputePrograms() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(32, 32).title("ClusteredResourcesLifecycle").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            long before = livePrograms();

            for (int attempt = 0; attempt < 8; attempt++) {
                assertThrows(IllegalStateException.class, () -> new ClusteredLightingResources(
                        3840, 2160, ClusteredLightingSettings.builder()
                                .memoryBudgetBytes(1024L * 1024L).build()));
            }
            long afterBudget = livePrograms();
            assertEquals(before, afterBudget,
                    "budget rejection must not leave compute programs alive");

            // Force the storage stage to fail after both programs were linked:
            // a 8192x8192 extent with 8px tiles and 256 slices overflows the
            // single-buffer byte limit while a huge budget passes the precheck.
            for (int attempt = 0; attempt < 4; attempt++) {
                assertThrows(IllegalStateException.class, () -> new ClusteredLightingResources(
                        8192, 8192, ClusteredLightingSettings.builder()
                                .tileSize(8).zSlices(256)
                                .memoryBudgetBytes(1L << 40).build()));
            }
            assertEquals(afterBudget, livePrograms(),
                    "storage failure must close both linked compute programs");
        }
    }

    private static long livePrograms() {
        return GlDebug.resources().liveResources().stream()
                .filter(resource -> resource.kind().equals("PROGRAM"))
                .count();
    }
}
