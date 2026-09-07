package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PbrBrdfMathTest {
    @Test
    void dielectricAndMetallicLimitsMatchFrozenContract() {
        assertEquals(0.04f, PbrBrdfMath.DIELECTRIC_F0, 1.0e-6f);
        assertEquals(0.0f, PbrBrdfMath.diffuseWeight(1.0f, 0.04f), 1.0e-6f);
        assertTrue(PbrBrdfMath.diffuseWeight(0.0f, 0.04f) > 0.9f);
        float whiteLambert = PbrBrdfMath.diffuseFromIrradiance((float) Math.PI,
                1.0f, 0.0f, 0.0f);
        assertEquals(1.0f, whiteLambert, 1.0e-6f,
                "PI-scaled irradiance must produce unit white Lambert response");
        assertEquals(0.0f, PbrBrdfMath.diffuseFromIrradiance((float) Math.PI,
                1.0f, 1.0f, 0.04f), 1.0e-6f,
                "fully metallic surfaces have no diffuse IBL term");
    }

    @Test
    void roughnessAndGrazingEndpointsRemainFinite() {
        for (float roughness : new float[]{0.0f, 0.045f, 1.0f}) {
            for (float nDotV : new float[]{0.0f, 1.0e-5f, 0.5f, 1.0f}) {
                float result = PbrBrdfMath.directScalar(0.7f, nDotV, 0.8f, 0.6f, roughness);
                assertTrue(Float.isFinite(result));
                assertTrue(result >= 0.0f);
            }
        }
        assertEquals(0.0f, PbrBrdfMath.directScalar(0.0f, 1.0f, 1.0f, 1.0f, 0.5f));
    }

    @Test
    void shadowOnlyRemovesDirectAndAoOnlyScalesIndirect() {
        assertEquals(8.0f, PbrBrdfMath.compose(5, 2, 1, 0, 1), 1.0e-6f);
        assertEquals(3.0f, PbrBrdfMath.compose(5, 2, 1, 1, 1), 1.0e-6f);
        assertEquals(6.0f, PbrBrdfMath.compose(5, 2, 1, 0, 0), 1.0e-6f);
    }

    @Test
    void pointAndSpotFalloffIsFiniteInverseSquareAndContinuousAtRange() {
        float near = PbrBrdfMath.rangeInverseSquareAttenuation(1.0f, 10.0f);
        float farther = PbrBrdfMath.rangeInverseSquareAttenuation(2.0f, 10.0f);
        assertTrue(near > farther, "radiance must decrease with distance");
        assertTrue(near / farther > 3.0f,
                "distance term must retain the inverse-square contribution");

        float justInside = PbrBrdfMath.rangeInverseSquareAttenuation(9.999f, 10.0f);
        assertTrue(justInside >= 0.0f && justInside < 1.0e-8f);
        assertEquals(0.0f, PbrBrdfMath.rangeInverseSquareAttenuation(10.0f, 10.0f), 0.0f);
        assertEquals(0.0f, PbrBrdfMath.rangeInverseSquareAttenuation(12.0f, 10.0f), 0.0f);
        assertTrue(Float.isFinite(PbrBrdfMath.rangeInverseSquareAttenuation(0.0f, 10.0f)));
    }
}
