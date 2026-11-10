package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.subsystems.render3d.ao.GtaoAllocationPlan;

import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GtaoAllocationPlanTest {
    @Test void deviceLimitRejectsNarrowOversizeAndInvalidExtentsWithoutGl() {
        GtaoAllocationPlan.validateDeviceExtent(16384, 1, 16384);
        assertThrows(IllegalArgumentException.class, () -> GtaoAllocationPlan.validateDeviceExtent(16385, 1, 16384));
        assertThrows(IllegalArgumentException.class, () -> GtaoAllocationPlan.validateDeviceExtent(1, 16385, 16384));
        assertThrows(IllegalArgumentException.class, () -> GtaoAllocationPlan.validateDeviceExtent(0, 1, 16384));
        assertThrows(IllegalArgumentException.class, () -> GtaoAllocationPlan.validateDeviceExtent(1, 1, 0));
    }

    @Test void budgetsUseActualAoFormatsAndCeilHalfExtents() {
        var settings = GtaoSettings.defaults().withEnabled(true);
        var plan = GtaoAllocationPlan.prepare(97, 65, settings, true, 0, false);
        assertEquals(96 + 49L * 33 * 8, plan.privateBytes());
        assertEquals(97L * 65 + 49L * 33 * 6, plan.graphBytes());
        assertEquals(plan.privateBytes() + plan.graphBytes(), plan.activeBytes());
        assertEquals(37_324_896, GtaoAllocationPlan.prepare(3840, 2160, settings, true, 0, false).activeBytes());
        assertEquals(70_502_496, GtaoAllocationPlan.prepare(3840, 2160, settings, false, 0, false).activeBytes());
    }

    @Test void budgetsIncludeRetainedOwnersAndRejectBeforeGlAllocation() {
        var enabled = GtaoSettings.defaults().withEnabled(true);
        var plan = GtaoAllocationPlan.prepare(97, 65, enabled, true, 1000, false);
        assertEquals(plan.activeBytes() + 1000, plan.peakBytes());
        assertEquals(1000, GtaoAllocationPlan.prepare(97, 65, enabled, true, 1000, true).peakBytes());
        assertEquals(0, GtaoAllocationPlan.prepare(97, 65, GtaoSettings.disabled(), true, 1000, false).activeBytes());
        assertThrows(IllegalArgumentException.class, () -> GtaoAllocationPlan.prepare(16384, 16384, enabled, true, 0, false));
        assertThrows(IllegalArgumentException.class, () -> GtaoAllocationPlan.prepare(97, 65, enabled, true, GtaoAllocationPlan.TRANSACTION_LIMIT, false));
    }

    @Test void changingTemporalModeCountsOnlyNewTargetsAtPeak() {
        var temporal = GtaoSettings.defaults().withEnabled(true);
        var stateless = new GtaoSettings(true, temporal.quality(), temporal.radius(), temporal.strength(),
                temporal.thickness(), false, temporal.historyWeight(), temporal.depthRejectionThreshold());
        var old = GtaoAllocationPlan.prepare(97, 65, temporal, true, 0, false);
        long sharedTargets = GtaoAllocationPlan.compatibleBytes(97, 65, stateless, true, temporal, true);
        var candidate = GtaoAllocationPlan.prepare(97, 65, stateless, true, old.activeBytes(), sharedTargets);
        assertEquals(old.graphBytes(), sharedTargets);
        assertEquals(old.activeBytes() + candidate.privateBytes() + 49L * 33 * 4, candidate.peakBytes());
    }
}
