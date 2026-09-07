package com.kaleblangley.haikalat.subsystems.render3d;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OutdoorVolumeMathTest {
    @Test
    void beerLambertReferenceIsMonotonicAndFinite() {
        assertEquals(1.0f, OutdoorVolumeMath.transmittance(0.0f, 20.0f), 1.0e-6f);
        assertEquals((float) Math.exp(-2.0), OutdoorVolumeMath.transmittance(0.1f, 20.0f), 1.0e-6f);
        assertTrue(OutdoorVolumeMath.transmittance(0.2f, 20.0f)
                < OutdoorVolumeMath.transmittance(0.1f, 20.0f));
        assertEquals(1.0f - (float) Math.exp(-2.0),
                OutdoorVolumeMath.uniformSegmentScattering(0.1f, 20.0f), 1.0e-6f);
    }

    @Test
    void phaseIsForwardPeakedAndPositive() {
        float forward = OutdoorVolumeMath.henyeyGreenstein(1.0f, 0.6f);
        float side = OutdoorVolumeMath.henyeyGreenstein(0.0f, 0.6f);
        float backward = OutdoorVolumeMath.henyeyGreenstein(-1.0f, 0.6f);
        assertTrue(forward > side);
        assertTrue(side > backward);
        assertTrue(backward > 0.0f);
    }

    @Test
    void invalidReferenceInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OutdoorVolumeMath.transmittance(-1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> OutdoorVolumeMath.transmittance(Float.NaN, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> OutdoorVolumeMath.henyeyGreenstein(0.0f, 0.95f));
    }
}
