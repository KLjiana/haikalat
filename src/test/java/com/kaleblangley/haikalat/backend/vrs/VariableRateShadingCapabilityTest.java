package com.kaleblangley.haikalat.backend.vrs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure extension/entry-point/limit decision contract for feature #42. */
class VariableRateShadingCapabilityTest {
    @Test
    void noContextIsUnavailableWithoutPretendingSupport() {
        VariableRateShadingCapability capability =
                VariableRateShadingCapability.inspectCurrent();
        assertFalse(capability.contextAvailable());
        assertEquals(VariableRateShadingCapability.Status.UNAVAILABLE, capability.status());
        assertFalse(capability.nvImagePathAvailable());
        assertTrue(capability.unsupportedReason().contains("no current OpenGL context"));
        assertTrue(capability.summary().contains("fallback=UNAVAILABLE"));
    }

    @Test
    void completeNvExtensionWithEntryPointsAndLimitsIsTheOnlySupportedPath() {
        VariableRateShadingCapability capability = VariableRateShadingCapability.evaluate(
                true, true, true, false, false, false, 16, 16, 8, 16);
        assertEquals(VariableRateShadingCapability.Status.SUPPORTED_NV_IMAGE,
                capability.status());
        assertTrue(capability.nvImagePathAvailable());
        assertEquals("", capability.unsupportedReason());
        assertTrue(capability.summary().startsWith("variable-rate-shading active=SUPPORTED_NV_IMAGE"));
        assertTrue(capability.summary().contains("GL_NV_shading_rate_image"));
        assertTrue(capability.summary().contains("texel=16x16"));
    }

    @Test
    void advertisedNvExtensionWithoutEntryPointsIsNotSupport() {
        VariableRateShadingCapability capability = VariableRateShadingCapability.evaluate(
                true, true, false, true, false, false, 0, 0, 0, 0);
        assertEquals(VariableRateShadingCapability.Status.UNAVAILABLE, capability.status());
        assertFalse(capability.nvImagePathAvailable());
        assertTrue(capability.unsupportedReason().contains("entry points are missing"));
        assertTrue(capability.summary().contains("GL_NV_primitive_shading_rate"));
    }

    @Test
    void advertisedNvExtensionWithInvalidLimitsIsNotSupport() {
        VariableRateShadingCapability capability = VariableRateShadingCapability.evaluate(
                true, true, true, false, false, false, 16, 0, 8, 16);
        assertEquals(VariableRateShadingCapability.Status.UNAVAILABLE, capability.status());
        assertFalse(capability.nvImagePathAvailable());
        assertTrue(capability.unsupportedReason().contains("limits are invalid"));
    }

    @Test
    void extOnlyIsDetectedButExplicitlyNotImplemented() {
        VariableRateShadingCapability capability = VariableRateShadingCapability.evaluate(
                true, false, false, false, true, true, 0, 0, 0, 0);
        assertEquals(VariableRateShadingCapability.Status.EXT_DETECTED_NOT_IMPLEMENTED,
                capability.status());
        assertFalse(capability.nvImagePathAvailable());
        assertTrue(capability.unsupportedReason().contains("EXT path is not implemented"));
        assertTrue(capability.unsupportedReason().contains("GL_NV_shading_rate_image is absent"));
    }

    @Test
    void extAdvertisedWithoutEntryPointsExplainsTheMissingSymbols() {
        VariableRateShadingCapability capability = VariableRateShadingCapability.evaluate(
                true, false, false, false, true, false, 0, 0, 0, 0);
        assertEquals(VariableRateShadingCapability.Status.UNAVAILABLE, capability.status());
        assertTrue(capability.unsupportedReason().contains("entry points are missing"));
        assertTrue(capability.unsupportedReason().contains("glFramebufferShadingRateEXT"));
    }

    @Test
    void absentExtensionsAreReportedAsUnavailable() {
        VariableRateShadingCapability capability = VariableRateShadingCapability.evaluate(
                true, false, false, false, false, false, 0, 0, 0, 0);
        assertEquals(VariableRateShadingCapability.Status.UNAVAILABLE, capability.status());
        assertTrue(capability.unsupportedReason().contains("neither GL_NV_shading_rate_image"));
    }

    @Test
    void evaluateClampsNegativeLimitsAndUnavailableIsAllZero() {
        VariableRateShadingCapability capability = VariableRateShadingCapability.evaluate(
                true, true, false, false, false, false, -4, -8, -1, -2);
        assertEquals(0, capability.texelWidth());
        assertEquals(0, capability.texelHeight());
        assertEquals(0, capability.paletteSize());
        assertEquals(0, capability.maxCoarseSamples());

        VariableRateShadingCapability empty = VariableRateShadingCapability.unavailable();
        assertFalse(empty.contextAvailable());
        assertEquals(VariableRateShadingCapability.Status.UNAVAILABLE, empty.status());
        assertEquals(0, empty.paletteSize());
    }

    @Test
    void uninitialisedCapturedPaletteEntriesAreRestoredWithAValidDefault() {
        int fullRate = VariableRateShadingRate.ONE_BY_ONE.glValue();
        int coarse = VariableRateShadingRate.TWO_BY_TWO.glValue();
        assertArrayEquals(new int[]{fullRate, fullRate},
                VariableRateShadingState.sanitizePaletteForRestore(new int[]{0, 0}),
                "zero is not a valid shading-rate enumerant and must not be written back");
        assertArrayEquals(new int[]{fullRate, coarse},
                VariableRateShadingState.sanitizePaletteForRestore(new int[]{999, coarse}),
                "already-valid entries must be preserved exactly");
    }

    @Test
    void rateMappingMatchesTheNvRateVocabularyAndFootprints() {
        assertEquals(1, VariableRateShadingRate.ONE_BY_ONE.pixelCount());
        assertEquals(16, VariableRateShadingRate.FOUR_BY_FOUR.pixelCount());
        assertEquals("2x2", VariableRateShadingRate.TWO_BY_TWO.footprint());
        assertTrue(VariableRateShadingRate.ONE_BY_ONE.fullRate());
        assertFalse(VariableRateShadingRate.TWO_BY_TWO.fullRate());
        assertEquals(7, VariableRateShadingRate.values().length);
        assertEquals(0, VariableRateShadingRate.ONE_BY_ONE.widthPixels() - 1);
        assertEquals(4, VariableRateShadingRate.TWO_BY_FOUR.heightPixels());
        for (VariableRateShadingRate rate : VariableRateShadingRate.values()) {
            assertTrue(rate.glValue() != 0, rate.name());
        }
        assertThrows(IllegalStateException.class,
                () -> VariableRateShadingState.begin(0, new int[]{1, 2}));
    }
}
