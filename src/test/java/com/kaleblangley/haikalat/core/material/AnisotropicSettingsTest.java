package com.kaleblangley.haikalat.core.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the bounded anisotropic material input contract: inert zero-strength
 * default, construction bounds, and the rotation/strength wrappers. The shader
 * math oracle lives in the test-only {@code AnisotropicReferenceMath}.
 */
class AnisotropicSettingsTest {
    @Test
    void disabledSettingsAreInertZeroValues() {
        AnisotropicSettings disabled = AnisotropicSettings.disabled();

        assertFalse(disabled.active());
        assertEquals(0.0f, disabled.strength());
        assertEquals(0.0f, disabled.rotation());
        assertEquals(AnisotropicSettings.disabled(),
                AnisotropicSettings.disabled().withStrength(0.0f),
                "withStrength(0) must stay the inert default");
    }

    @Test
    void factoryAndWithersPreserveTheOtherComponent() {
        AnisotropicSettings settings = AnisotropicSettings.of(0.75f, 1.25f);

        assertTrue(settings.active());
        assertEquals(0.75f, settings.strength());
        assertEquals(1.25f, settings.rotation());
        assertEquals(0.0f, AnisotropicSettings.ofStrength(0.75f).rotation(),
                "ofStrength must default to the mesh tangent direction");
        assertEquals(new AnisotropicSettings(0.4f, 1.25f),
                settings.withStrength(0.4f));
        assertEquals(new AnisotropicSettings(0.75f, -2.0f),
                settings.withRotation(-2.0f));
        assertFalse(settings.withStrength(0.0f).active(),
                "zero strength must deactivate the anisotropic path");
        assertEquals(1.25f, settings.withStrength(0.0f).rotation(),
                "disabling must keep the authored rotation for later re-enable");
    }

    @Test
    void rejectsNonFiniteStrengthAndOutOfRangeStrength() {
        for (float strength : new float[]{-0.001f, 1.001f, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new AnisotropicSettings(strength, 0.0f),
                    "strength " + strength + " must be rejected");
        }
        assertFalse(AnisotropicSettings.of(0.0f, 0.0f).active());
        assertEquals(1.0f, AnisotropicSettings.of(1.0f, 0.0f).strength(),
                "full strength is valid; the shader floors the minor axis");
    }

    @Test
    void rejectsNonFiniteRotationAndKeepsPeriodicValues() {
        for (float rotation : new float[]{Float.NaN, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new AnisotropicSettings(0.5f, rotation),
                    "rotation " + rotation + " must be rejected");
        }
        assertEquals(-7.5f, AnisotropicSettings.of(0.5f, -7.5f).rotation(),
                "finite rotations are periodic, not range-clamped");
        assertEquals(100.0f, AnisotropicSettings.of(0.5f, 100.0f).rotation());
    }

    @Test
    void shaderFloorConstantMatchesTheSettingsContract() {
        assertEquals(0.045f * 0.045f, AnisotropicSettings.MINIMUM_ALPHA, 1.0e-9f,
                "the per-axis alpha floor must stay the square of the base roughness floor");
        assertEquals(1.0f, AnisotropicSettings.MAXIMUM_STRENGTH);
    }
}
