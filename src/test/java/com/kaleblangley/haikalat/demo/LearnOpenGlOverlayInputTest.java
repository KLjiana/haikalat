package com.kaleblangley.haikalat.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearnOpenGlOverlayInputTest {
    @Test
    void visibleDiagnosticsPanelOwnsUiInteractionMode() {
        assertFalse(LearnOpenGlOverlay.acceptsF1InteractionToggle(true));
        assertTrue(LearnOpenGlOverlay.acceptsF1InteractionToggle(false));
    }
}
