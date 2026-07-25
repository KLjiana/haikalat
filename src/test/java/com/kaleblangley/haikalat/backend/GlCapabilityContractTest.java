package com.kaleblangley.haikalat.backend;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlCapabilityContractTest {
    @Test
    void reportsUnavailableWithoutCurrentContext() {
        GlCapabilityContract.Report report = GlCapabilityContract.inspectCurrent();

        assertFalse(report.contextAvailable());
        assertFalse(report.meetsRequirements());
        assertEquals(EnumSet.allOf(GlCapabilityContract.Requirement.class),
                report.missingRequirements());
        assertThrows(IllegalStateException.class, GlCapabilityContract::requireCurrent);
    }

    @Test
    void acceptsCompleteContractAndKeepsOptionalFeaturesSeparate() {
        GlCapabilityContract.Report report = GlCapabilityContract.evaluate(true, 4, 6, true,
                "vendor", "renderer", "driver",
                EnumSet.allOf(GlCapabilityContract.Requirement.class),
                Set.of(GlCapabilityContract.OptionalFeature.BINDLESS_TEXTURE));

        assertTrue(report.meetsRequirements());
        assertTrue(report.missingRequirements().isEmpty());
        assertEquals(Set.of(GlCapabilityContract.OptionalFeature.BINDLESS_TEXTURE),
                report.optionalFeatures());
        assertTrue(report.summary().contains("4.6 core"));
    }

    @Test
    void reportsAllMissingRequirementsInOneDiagnostic() {
        GlCapabilityContract.Report report = GlCapabilityContract.evaluate(true, 4, 5, false,
                "vendor", "renderer", "driver", Set.of(), Set.of());

        assertEquals(EnumSet.allOf(GlCapabilityContract.Requirement.class),
                report.missingRequirements());
        assertTrue(report.summary().contains("OpenGL 4.6"));
        assertTrue(report.summary().contains("core profile"));
        assertTrue(report.summary().contains("shader storage buffers"));
        assertTrue(report.summary().contains("4.5 compatibility"));
    }

    @Test
    void returnedFeatureSetsAreImmutableSnapshots() {
        GlCapabilityContract.Report report = GlCapabilityContract.evaluate(true, 4, 6, true,
                "vendor", "renderer", "driver",
                EnumSet.allOf(GlCapabilityContract.Requirement.class), Set.of());

        assertThrows(UnsupportedOperationException.class,
                () -> report.missingRequirements().add(
                        GlCapabilityContract.Requirement.COMPUTE_SHADER));
        assertThrows(UnsupportedOperationException.class,
                () -> report.optionalFeatures().add(
                        GlCapabilityContract.OptionalFeature.BINDLESS_TEXTURE));
    }
}
