package com.kaleblangley.haikalat.subsystems.render3d;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GtaoPassesTest {
    @Test
    void temporalPhaseRotatesAndNonTemporalPhaseStaysFixed() {
        assertEquals(0.0f, GtaoPasses.samplePhase(0, true));
        assertEquals(0.125f, GtaoPasses.samplePhase(1, true));
        assertEquals(0.875f, GtaoPasses.samplePhase(7, true));
        assertEquals(0.0f, GtaoPasses.samplePhase(8, true));
        assertEquals(0.0f, GtaoPasses.samplePhase(31, false));
    }

    @Test
    void staticCameraKeepsTemporalPhaseStable() {
        assertEquals(0.0f, GtaoPasses.samplePhaseForMotion(1, true, false));
        assertEquals(0.0f, GtaoPasses.samplePhaseForMotion(7, true, false));
        assertEquals(0.375f, GtaoPasses.samplePhaseForMotion(3, true, true));
        assertEquals(0.0f, GtaoPasses.samplePhaseForMotion(3, false, true));
    }
}
