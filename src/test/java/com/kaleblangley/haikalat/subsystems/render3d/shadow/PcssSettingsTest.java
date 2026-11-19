package com.kaleblangley.haikalat.subsystems.render3d.shadow;

import com.kaleblangley.haikalat.subsystems.render3d.ShadowSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcssSettingsTest {
    @Test
    void defaultsAreDisabledAndTheEnabledCopyStaysImmutable() {
        PcssSettings defaults = PcssSettings.disabled();
        assertFalse(defaults.enabled());
        assertFalse(defaults.active());

        PcssSettings enabled = defaults.withEnabled(true);
        assertTrue(enabled.enabled());
        assertTrue(enabled.active());
        assertFalse(defaults.enabled(), "withEnabled must not mutate the shared disabled value");
        assertEquals(16.0f, PcssSettings.MAX_LIGHT_WORLD_SIZE);
        assertEquals(48, PcssSettings.MAX_TOTAL_TAPS);
    }

    @Test
    void zeroLightSizeAndDisabledFlagSelectTheExistingPath() {
        PcssSettings zeroLight = new PcssSettings(true, 0.0f, 4, 16, 8, 16, 0.001f);
        assertTrue(zeroLight.enabled());
        assertFalse(zeroLight.active(), "a zero light size must keep the disabled hard/PCF path");
        assertFalse(PcssSettings.disabled().withEnabled(false).active());
    }

    @Test
    void validatesTheBoundedControls() {
        assertThrows(IllegalArgumentException.class,
                () -> new PcssSettings(true, -0.01f, 4, 16, 8, 16, 0.001f));
        assertThrows(IllegalArgumentException.class,
                () -> new PcssSettings(true, Float.NaN, 4, 16, 8, 16, 0.001f));
        assertThrows(IllegalArgumentException.class,
                () -> new PcssSettings(true, PcssSettings.MAX_LIGHT_WORLD_SIZE + 0.01f,
                        4, 16, 8, 16, 0.001f));
        assertThrows(IllegalArgumentException.class,
                () -> new PcssSettings(true, 1.0f, 0, 16, 8, 16, 0.001f));
        assertThrows(IllegalArgumentException.class,
                () -> new PcssSettings(true, 1.0f,
                        PcssSettings.MAX_BLOCKER_RADIUS_TEXELS + 1, 16, 8, 16, 0.001f));
        assertThrows(IllegalArgumentException.class,
                () -> new PcssSettings(true, 1.0f, 4, 0, 8, 16, 0.001f));
        assertThrows(IllegalArgumentException.class,
                () -> new PcssSettings(true, 1.0f, 4,
                        PcssSettings.MAX_BLOCKER_TAPS + 1, 8, 16, 0.001f));
        assertThrows(IllegalArgumentException.class,
                () -> new PcssSettings(true, 1.0f, 4, 16, 0, 16, 0.001f));
        assertThrows(IllegalArgumentException.class,
                () -> new PcssSettings(true, 1.0f, 4, 16,
                        PcssSettings.MAX_FILTER_RADIUS_TEXELS + 1, 16, 0.001f));
        assertThrows(IllegalArgumentException.class,
                () -> new PcssSettings(true, 1.0f, 4, 16, 8, 0, 0.001f));
        assertThrows(IllegalArgumentException.class,
                () -> new PcssSettings(true, 1.0f, 4, 16, 8,
                        PcssSettings.MAX_FILTER_TAPS + 1, 0.001f));
        assertThrows(IllegalArgumentException.class,
                () -> new PcssSettings(true, 1.0f, 4, 32, 8, 17, 0.001f),
                "the combined blocker+filter tap budget must be enforced");
        assertThrows(IllegalArgumentException.class,
                () -> new PcssSettings(true, 1.0f, 4, 16, 8, 16, -0.001f));
        assertThrows(IllegalArgumentException.class,
                () -> new PcssSettings(true, 1.0f, 4, 16, 8, 16, Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new PcssSettings(true, 1.0f, 4, 16, 8, 16,
                        PcssSettings.MAX_DEPTH_BIAS + 0.001f));
    }

    @Test
    void legacyShadowSettingsRemainDisabledAndTheNewComponentIsPreserved() {
        ShadowSettings legacyFour = new ShadowSettings(512, 12.0f, 0.1f, 40.0f);
        ShadowSettings legacyFive = new ShadowSettings(512, 12.0f, 0.1f, 40.0f, 0.004f);
        ShadowSettings legacySix = new ShadowSettings(512, 12.0f, 0.1f, 40.0f, 0.004f,
                ContactShadowSettings.disabled());
        ShadowSettings legacySeven = new ShadowSettings(512, 12.0f, 0.1f, 40.0f, 0.004f,
                ContactShadowSettings.disabled(), CloudShadowSettings.disabled());
        assertFalse(legacyFour.pcss().active());
        assertFalse(legacyFive.pcss().active());
        assertFalse(legacySix.pcss().active());
        assertFalse(legacySeven.pcss().active());
        assertFalse(ShadowSettings.directionalDefaults().pcss().active());
        assertEquals(PcssSettings.disabled(), legacySeven.pcss(),
                "the pre-PCSS constructor must map to the exact disabled value");

        PcssSettings pcss = new PcssSettings(true, 2.0f, 6, 24, 10, 20, 0.002f);
        ShadowSettings explicit = new ShadowSettings(512, 12.0f, 0.1f, 40.0f, 0.004f,
                ContactShadowSettings.disabled(), CloudShadowSettings.disabled(), pcss);
        assertEquals(pcss, explicit.pcss());
        assertEquals(0.004f, explicit.bias(), 0.0f);
        assertNotEquals(new ShadowSettings(512, 12.0f, 0.1f, 40.0f, 0.004f), explicit,
                "PCSS controls participate in the feature-settings identity");
    }
}
