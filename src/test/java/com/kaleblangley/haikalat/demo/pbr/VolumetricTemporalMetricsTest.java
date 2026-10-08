package com.kaleblangley.haikalat.demo.pbr;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VolumetricTemporalMetricsTest {
    private static float[] pixel(float value){return new float[]{value,value,value,1};}
    private static final VolumetricTemporalMetrics.Roi ONE=new VolumetricTemporalMetrics.Roi(1,1,0,0,1,1);
    @Test void absoluteRecoveryCountsNegativeGhostsAndRejectsAnInvisibleTestLight() {
        var r=VolumetricTemporalMetrics.recovery(pixel(2),pixel(1),pixel(.9f),pixel(1),ONE,1e-6,.05);
        assertEquals(3,r.beforeAbsoluteRgbEnergy());assertEquals(.1,r.residualRatio(),1e-7);assertFalse(r.passed());
        var absent=VolumetricTemporalMetrics.recovery(pixel(1),pixel(1),pixel(1),pixel(1),ONE,1e-6,.05);
        assertNull(absent.residualRatio());assertFalse(absent.passed());
    }
    @Test void populationVarianceIsPerPixelSoOppositeFlickerCannotCancelAtRoiMean() {
        var roi=new VolumetricTemporalMetrics.Roi(2,1,0,0,2,1);
        float[] a={0,0,0,1,2,2,2,1},b={2,2,2,1,0,0,0,1},ref={1,1,1,1,1,1,1,1};
        var s=VolumetricTemporalMetrics.stability(List.of(a,b),ref,roi,.01,.02,.005);
        assertEquals(1,s.maximumRelativeStdDev(),1e-7);assertEquals(0,s.roiMeanRelativeStdDev(),1e-7);
        assertFalse(s.perPixelDiagnosticPassed());assertTrue(s.passed(),"ROI gate and local flicker diagnostic have distinct scopes");
    }
    @Test void darkPixelsUseAbsoluteVariationAndNonfiniteSamplesFail() {
        var s=VolumetricTemporalMetrics.stability(List.of(pixel(0),pixel(.008f)),pixel(.002f),ONE,.01,.02,.005);
        assertEquals(1,s.darkPixels());assertEquals(.004,s.maximumDarkAbsoluteStdDev(),1e-7);assertTrue(s.passed());
        var invalid=VolumetricTemporalMetrics.stability(List.of(pixel(0),pixel(Float.NaN)),pixel(.002f),ONE,.01,.02,.005);
        assertEquals(1,invalid.nonfiniteValues());assertFalse(invalid.passed());
        assertThrows(IllegalArgumentException.class,()->new VolumetricTemporalMetrics.Roi(1,1,1,0,1,1));
    }
}
