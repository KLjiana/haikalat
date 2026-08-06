package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.subsystems.resources.ResourceGeneration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneCharacterRuntimeDiagnosticsTest {
    @Test
    void snapshotsAreImmutableAndExposeTransitionState() {
        List<String> windows = new java.util.ArrayList<>(List.of("hit"));
        SceneCharacterRuntimeDiagnostics diagnostics = new SceneCharacterRuntimeDiagnostics(
                "player", ResourceGeneration.INITIAL, "attack", 0.12f, 0.25f,
                "idle", 0.4f, "exit-time", windows);
        windows.clear();
        assertTrue(diagnostics.transitioning());
        assertTrue(diagnostics.activeWindows().contains("hit"));
        assertThrows(UnsupportedOperationException.class,
                () -> diagnostics.activeWindows().add("combo"));
    }

    @Test
    void rejectsOutOfRangeTransitionWeight() {
        assertThrows(IllegalArgumentException.class, () ->
                new SceneCharacterRuntimeDiagnostics("player", ResourceGeneration.INITIAL,
                        "idle", 0.0f, 0.0f, "", 1.1f, "", List.of()));
    }
}
