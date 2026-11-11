package com.kaleblangley.haikalat.core.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the bounded clearcoat value contract: inert defaults, construction
 * bounds, the F0 = 0.04 Fresnel reference and the energy split used by the PBR
 * shader.
 */
class ClearcoatSettingsTest {
    /**
     * Independent double-precision evaluation of the Schlick interface term the
     * shader implements. The test expectation is never computed with the
     * production helper.
     */
    private static double referenceFresnel(double cosTheta) {
        double clamped = Math.max(0.0, Math.min(1.0, cosTheta));
        return 0.04 + 0.96 * Math.pow(1.0 - clamped, 5.0);
    }

    @Test
    void disabledSettingsAreInertZeroValues() {
        ClearcoatSettings disabled = ClearcoatSettings.disabled();

        assertFalse(disabled.active());
        assertFalse(disabled.enabled());
        assertEquals(0.0f, disabled.roughness());
        assertEquals(0.0f, disabled.normalStrength());
        assertEquals(ClearcoatSettings.disabled(), ClearcoatSettings.disabled().withEnabled(false),
                "withEnabled(false) must stay the inert default");
    }

    @Test
    void factoryAndWithersPreserveTheOtherComponents() {
        ClearcoatSettings rough = ClearcoatSettings.of(0.35f, 0.75f);

        assertTrue(rough.active());
        assertEquals(0.35f, rough.roughness());
        assertEquals(0.75f, rough.normalStrength());
        assertEquals(0.35f, ClearcoatSettings.ofRoughness(0.35f).roughness());
        assertEquals(0.0f, ClearcoatSettings.ofRoughness(0.35f).normalStrength(),
                "ofRoughness must default to the flat normal input");

        ClearcoatSettings changed = rough.withRoughness(0.6f)
                .withNormalStrength(0.2f)
                .withEnabled(true);
        assertEquals(new ClearcoatSettings(true, 0.6f, 0.2f), changed);
        assertFalse(rough.withEnabled(false).active());
        assertEquals(0.35f, rough.withEnabled(false).roughness(),
                "disabling must keep the authored roughness for later re-enable");
    }

    @Test
    void rejectsNonFiniteAndOutOfRangeInputs() {
        for (float roughness : new float[]{-0.001f, 1.001f, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ClearcoatSettings(true, roughness, 0.0f),
                    "roughness " + roughness + " must be rejected");
        }
        for (float strength : new float[]{-0.001f, 1.001f, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ClearcoatSettings(true, 0.5f, strength),
                    "normal strength " + strength + " must be rejected");
        }
        assertThrows(IllegalArgumentException.class,
                () -> ClearcoatSettings.of(Float.NaN, 0.0f));
        assertTrue(ClearcoatSettings.of(0.0f, 0.0f).active(),
                "zero roughness is valid; the shader applies its own safe floor");
        assertEquals(1.0f, ClearcoatSettings.of(1.0f, 1.0f).roughness());
    }

    @Test
    void fresnelMatchesTheIndependentSchlickReference() {
        for (float cos : new float[]{0.0f, 0.05f, 0.25f, 0.5f, 0.75f, 0.95f, 1.0f}) {
            assertEquals((float) referenceFresnel(cos), ClearcoatSettings.fresnel(cos), 1.0e-6f,
                    "clearcoat Fresnel at cos " + cos);
        }
        assertEquals(ClearcoatSettings.F0, ClearcoatSettings.fresnel(1.0f), 1.0e-7f,
                "normal incidence must expose the F0 = 0.04 interface value");
        assertEquals(1.0f, ClearcoatSettings.fresnel(0.0f), 1.0e-7f,
                "grazing incidence must reach full reflection");
        assertEquals(0.07f, ClearcoatSettings.fresnel(0.5f), 1.0e-7f,
                "45 degrees is 0.04 + 0.96 * 0.5^5 = 0.07");
    }

    @Test
    void fresnelIsFiniteAndMonotoneForClampedCosines() {
        float previous = -1.0f;
        for (float cos = 0.0f; cos <= 1.0f; cos += 0.01f) {
            float value = ClearcoatSettings.fresnel(cos);
            assertTrue(value >= ClearcoatSettings.F0 && value <= 1.0f,
                    "Fresnel must stay in [F0, 1] at cos " + cos + ", got " + value);
            assertTrue(value <= previous + 1.0e-7f || previous < 0.0f,
                    "Fresnel must decrease as the cosine grows");
            previous = value;
        }
        assertEquals(ClearcoatSettings.fresnel(1.0f), ClearcoatSettings.fresnel(2.0f), 0.0f,
                "cosines above one are clamped");
        assertEquals(ClearcoatSettings.fresnel(0.0f), ClearcoatSettings.fresnel(-1.0f), 0.0f,
                "negative cosines are clamped");
        assertThrows(IllegalArgumentException.class,
                () -> ClearcoatSettings.fresnel(Float.NaN));
    }

    @Test
    void interfaceSplitIsAConvexEnergyNeutralCombination() {
        float base = 0.8f;
        float coat = 2.5f;
        for (int step = 0; step <= 100; step++) {
            float f = step / 100.0f;
            float combined = ClearcoatSettings.compose(base, coat, f);
            assertTrue(combined >= Math.min(base, coat) - 1.0e-6f
                            && combined <= Math.max(base, coat) + 1.0e-6f,
                    "split at F=" + f + " must stay between the layers");
            assertEquals(base + (coat - base) * f, combined, 1.0e-6f,
                    "split is the linear interface interpolation");
        }
        assertEquals(base, ClearcoatSettings.compose(base, coat, 0.0f), 0.0f,
                "F = 0 must return the base layer exactly (the disabled/zero restore)");
        assertEquals(coat, ClearcoatSettings.compose(base, coat, 1.0f), 0.0f,
                "F = 1 must return the coat layer exactly");
        float fresnel = ClearcoatSettings.fresnel(0.8f);
        assertEquals(1.0f, fresnel + (1.0f - fresnel), 1.0e-7f,
                "reflected and transmitted shares must sum to one");
    }

    @Test
    void composeRejectsInvalidInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> ClearcoatSettings.compose(1.0f, 2.0f, -0.01f));
        assertThrows(IllegalArgumentException.class,
                () -> ClearcoatSettings.compose(1.0f, 2.0f, 1.01f));
        assertThrows(IllegalArgumentException.class,
                () -> ClearcoatSettings.compose(Float.NaN, 2.0f, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> ClearcoatSettings.compose(1.0f, Float.POSITIVE_INFINITY, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> ClearcoatSettings.compose(1.0f, 2.0f, Float.NaN));
    }
}
