package com.kaleblangley.haikalat.subsystems.render3d;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VolumetricAllocationPlanTest {
    @Test void rejectsOversizedAndRetiringPlusCandidatePeaksWithoutNeedingGl() {
        var fog = VolumetricGenerationGlTest.fog(.2f);
        var plan = VolumetricAllocationPlan.prepare(1920,1080,fog,1028,1,false,0);
        assertTrue(plan.privateBytes() > 0); assertTrue(plan.colorBytes() > 0);
        assertEquals(plan.activeBytes(),plan.peakBytes());
        assertThrows(IllegalArgumentException.class, () -> VolumetricAllocationPlan.prepare(32768,32768,fog,1028,4,true,0));
        assertThrows(IllegalArgumentException.class, () -> VolumetricAllocationPlan.prepare(1920,1080,fog,1028,1,false,384L*1024*1024));
        var off = VolumetricAllocationPlan.prepare(1920,1080,VolumetricFogSettings.disabled(),1028,1,false,plan.activeBytes());
        assertEquals(0,off.activeBytes()); assertEquals(plan.activeBytes(),off.peakBytes());
    }
}
