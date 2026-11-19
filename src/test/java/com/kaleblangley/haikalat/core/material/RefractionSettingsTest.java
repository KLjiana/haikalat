package com.kaleblangley.haikalat.core.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bounds and the explicit default-off path of the single-layer refraction settings. */
class RefractionSettingsTest {
    @Test
    void disabledSettingsKeepTheExactNoOpPath() {
        RefractionSettings disabled = RefractionSettings.disabled();

        assertFalse(disabled.enabled());
        assertFalse(disabled.active());
        assertEquals(1.5f, disabled.ior(), 0.0f);
        assertEquals(0.15f, disabled.thickness(), 0.0f);
        assertEquals(0.0f, disabled.roughness(), 0.0f);
        assertEquals(1.0f, disabled.transmission(), 0.0f);
        assertEquals(0.0f, disabled.reflection(), 0.0f);
        assertFalse(disabled.withEnabled(false).active());
    }

    @Test
    void enabledSettingsCarryTheAuthoredValues() {
        RefractionSettings settings = RefractionSettings.of(1.33f, 0.4f, 0.25f, 0.8f, 0.2f);

        assertTrue(settings.active());
        assertEquals(1.33f, settings.ior(), 0.0f);
        assertEquals(0.4f, settings.thickness(), 0.0f);
        assertEquals(0.25f, settings.roughness(), 0.0f);
        assertEquals(0.8f, settings.transmission(), 0.0f);
        assertEquals(0.2f, settings.reflection(), 0.0f);
    }

    @Test
    void withMethodsKeepTheOtherComponents() {
        RefractionSettings base = RefractionSettings.of(1.5f, 0.3f, 0.1f, 0.9f, 0.1f);

        assertEquals(new RefractionSettings(true, 2.0f, 0.3f, 0.1f, 0.9f, 0.1f),
                base.withIor(2.0f));
        assertEquals(new RefractionSettings(true, 1.5f, 1.5f, 0.1f, 0.9f, 0.1f),
                base.withThickness(1.5f));
        assertEquals(new RefractionSettings(true, 1.5f, 0.3f, 0.5f, 0.9f, 0.1f),
                base.withRoughness(0.5f));
        assertEquals(new RefractionSettings(true, 1.5f, 0.3f, 0.1f, 0.4f, 0.1f),
                base.withTransmission(0.4f));
        assertEquals(new RefractionSettings(true, 1.5f, 0.3f, 0.1f, 0.9f, 0.6f),
                base.withReflection(0.6f));
        assertEquals(false, base.withEnabled(false).enabled());
    }

    @Test
    void iorIsBoundedToThePhysicalRange() {
        assertEquals(1.0f, RefractionSettings.of(1.0f, 0.0f, 0.0f, 0.0f, 0.0f).ior(), 0.0f);
        assertEquals(2.5f, RefractionSettings.of(2.5f, 0.0f, 0.0f, 0.0f, 0.0f).ior(), 0.0f);
        assertThrows(IllegalArgumentException.class,
                () -> RefractionSettings.of(0.99f, 0.1f, 0.0f, 1.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> RefractionSettings.of(2.51f, 0.1f, 0.0f, 1.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> RefractionSettings.of(Float.NaN, 0.1f, 0.0f, 1.0f, 0.0f));
    }

    @Test
    void thicknessAndUnitWeightsAreBounded() {
        assertEquals(0.0f, RefractionSettings.of(1.5f, 0.0f, 0.0f, 0.0f, 0.0f)
                .thickness(), 0.0f);
        assertEquals(2.0f, RefractionSettings.of(1.5f, 2.0f, 1.0f, 1.0f, 1.0f)
                .thickness(), 0.0f);
        assertThrows(IllegalArgumentException.class,
                () -> RefractionSettings.of(1.5f, -0.01f, 0.0f, 1.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> RefractionSettings.of(1.5f, 2.01f, 0.0f, 1.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> RefractionSettings.of(1.5f, Float.POSITIVE_INFINITY, 0.0f, 1.0f, 0.0f));
        for (float invalid : new float[]{-0.01f, 1.01f, Float.NaN}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RefractionSettings.of(1.5f, 0.1f, invalid, 1.0f, 0.0f));
            assertThrows(IllegalArgumentException.class,
                    () -> RefractionSettings.of(1.5f, 0.1f, 0.0f, invalid, 0.0f));
            assertThrows(IllegalArgumentException.class,
                    () -> RefractionSettings.of(1.5f, 0.1f, 0.0f, 1.0f, invalid));
        }
    }

    @Test
    void publishedBoundsMatchTheShaderContract() {
        assertEquals(0.25f, RefractionSettings.MAXIMUM_SCREEN_OFFSET_UV, 0.0f);
        assertEquals(0.02f, RefractionSettings.MAXIMUM_ROUGHNESS_SPREAD_UV, 0.0f);
    }
}
