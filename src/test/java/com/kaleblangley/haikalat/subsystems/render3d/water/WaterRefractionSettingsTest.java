package com.kaleblangley.haikalat.subsystems.render3d.water;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM contract for the opt-in screen-space water refraction configuration.
 *
 * <p>Guards the disabled no-op value, the bounded constructor validation and
 * the defensive colour accessors; the numeric refraction model is covered by
 * {@link WaterRefractionReferenceMathTest}.</p>
 */
class WaterRefractionSettingsTest {
    @Test
    void disabledValueIsAnExactNoOp() {
        WaterRefractionSettings disabled = WaterRefractionSettings.disabled();
        assertFalse(disabled.active());
        assertEquals(1.333f, disabled.ior(), 0.0f);
        assertEquals(1.0f, disabled.strength(), 0.0f);
        assertEquals(0.02f, disabled.maximumScreenOffset(), 0.0f);
        assertEquals(1.0f, disabled.thicknessScale(), 0.0f);
        assertEquals(0.05f, disabled.foregroundRejectionBias(), 0.0f);
        assertEquals(WaterRefractionSettings.OffscreenFallback.UNREFRACTED,
                disabled.offscreenFallback());
        assertEquals(new Vector3f(0.02f, 0.12f, 0.16f), disabled.offscreenFallbackColor());
        assertFalse(disabled.withEnabled(false).active());
    }

    @Test
    void builderStoresBoundedValuesAndWithEnabledPreservesThem() {
        WaterRefractionSettings settings = WaterRefractionSettings.builder()
                .enabled(true)
                .ior(1.5f)
                .strength(1.25f)
                .maximumScreenOffset(0.1f)
                .thicknessScale(2.0f)
                .foregroundRejectionBias(0.2f)
                .offscreenFallback(WaterRefractionSettings.OffscreenFallback.TINT)
                .offscreenFallbackColor(0.1f, 0.2f, 0.3f)
                .build();
        assertTrue(settings.active());
        assertEquals(1.5f, settings.ior(), 0.0f);
        assertEquals(1.25f, settings.strength(), 0.0f);
        assertEquals(0.1f, settings.maximumScreenOffset(), 0.0f);
        assertEquals(2.0f, settings.thicknessScale(), 0.0f);
        assertEquals(0.2f, settings.foregroundRejectionBias(), 0.0f);
        assertEquals(WaterRefractionSettings.OffscreenFallback.TINT,
                settings.offscreenFallback());

        WaterRefractionSettings disabled = settings.withEnabled(false);
        assertFalse(disabled.active());
        assertEquals(settings.ior(), disabled.ior(), 0.0f);
        assertEquals(settings.strength(), disabled.strength(), 0.0f);
        assertEquals(settings.maximumScreenOffset(), disabled.maximumScreenOffset(), 0.0f);
        assertEquals(settings.thicknessScale(), disabled.thicknessScale(), 0.0f);
        assertEquals(settings.foregroundRejectionBias(), disabled.foregroundRejectionBias(), 0.0f);
        assertEquals(settings.offscreenFallback(), disabled.offscreenFallback());
        assertEquals(settings.offscreenFallbackColor(), disabled.offscreenFallbackColor());
    }

    @Test
    void fallbackModeCarriesTheDocumentedShaderValue() {
        assertEquals(0, WaterRefractionSettings.OffscreenFallback.UNREFRACTED.shaderValue());
        assertEquals(1, WaterRefractionSettings.OffscreenFallback.TINT.shaderValue());
    }

    @Test
    void constructionRejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> WaterRefractionSettings.builder()
                .ior(0.99f).build());
        assertThrows(IllegalArgumentException.class, () -> WaterRefractionSettings.builder()
                .ior(WaterRefractionSettings.MAXIMUM_IOR + 0.1f).build());
        assertThrows(IllegalArgumentException.class, () -> WaterRefractionSettings.builder()
                .ior(Float.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> WaterRefractionSettings.builder()
                .strength(-0.01f).build());
        assertThrows(IllegalArgumentException.class, () -> WaterRefractionSettings.builder()
                .strength(WaterRefractionSettings.MAXIMUM_STRENGTH + 0.1f).build());
        assertThrows(IllegalArgumentException.class, () -> WaterRefractionSettings.builder()
                .strength(Float.POSITIVE_INFINITY).build());
        assertThrows(IllegalArgumentException.class, () -> WaterRefractionSettings.builder()
                .maximumScreenOffset(-0.01f).build());
        assertThrows(IllegalArgumentException.class, () -> WaterRefractionSettings.builder()
                .maximumScreenOffset(WaterRefractionSettings.MAXIMUM_SCREEN_OFFSET + 0.1f)
                .build());
        assertThrows(IllegalArgumentException.class, () -> WaterRefractionSettings.builder()
                .maximumScreenOffset(Float.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> WaterRefractionSettings.builder()
                .thicknessScale(-0.01f).build());
        assertThrows(IllegalArgumentException.class, () -> WaterRefractionSettings.builder()
                .thicknessScale(WaterRefractionSettings.MAXIMUM_THICKNESS_SCALE + 0.1f).build());
        assertThrows(IllegalArgumentException.class, () -> WaterRefractionSettings.builder()
                .foregroundRejectionBias(-0.01f).build());
        assertThrows(IllegalArgumentException.class, () -> WaterRefractionSettings.builder()
                .foregroundRejectionBias(
                        WaterRefractionSettings.MAXIMUM_FOREGROUND_REJECTION_BIAS + 0.1f).build());
        assertThrows(NullPointerException.class, () -> WaterRefractionSettings.builder()
                .offscreenFallback(null).build());
        assertThrows(IllegalArgumentException.class, () -> WaterRefractionSettings.builder()
                .offscreenFallbackColor(-0.1f, 0.0f, 0.0f).build());
        assertThrows(IllegalArgumentException.class, () -> WaterRefractionSettings.builder()
                .offscreenFallbackColor(0.0f, Float.NaN, 0.0f).build());
        assertThrows(IllegalArgumentException.class, () -> WaterRefractionSettings.builder()
                .offscreenFallbackColor(
                        WaterRefractionSettings.MAXIMUM_COLOR_COMPONENT + 0.1f, 0.0f, 0.0f)
                .build());
    }

    @Test
    void colourAccessorsAndBuilderInputsAreDefensive() {
        Vector3f source = new Vector3f(0.1f, 0.2f, 0.3f);
        WaterRefractionSettings settings = WaterRefractionSettings.builder()
                .enabled(true).offscreenFallbackColor(source.x, source.y, source.z).build();
        source.set(9.0f);
        assertNotEquals(9.0f, settings.offscreenFallbackColor().x);

        Vector3f returned = settings.offscreenFallbackColor();
        returned.set(9.0f);
        assertNotEquals(9.0f, settings.offscreenFallbackColor().x);

        Vector3f destination = new Vector3f();
        settings.offscreenFallbackColorTo(destination);
        assertEquals(settings.offscreenFallbackColor(), destination);
    }
}
