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
}
