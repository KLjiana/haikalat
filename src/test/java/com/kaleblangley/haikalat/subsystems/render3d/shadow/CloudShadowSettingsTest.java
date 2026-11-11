package com.kaleblangley.haikalat.subsystems.render3d.shadow;

import com.kaleblangley.haikalat.subsystems.render3d.ShadowSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudShadowSettingsTest {
    @Test
    void defaultsAreDisabledAndTheEnabledCopyStaysImmutable() {
        CloudShadowSettings defaults = CloudShadowSettings.disabled();
        assertFalse(defaults.enabled());
        assertFalse(defaults.active());

        CloudShadowSettings enabled = defaults.withEnabled(true);
        assertTrue(enabled.enabled());
        assertTrue(enabled.active());
        assertFalse(defaults.enabled(), "withEnabled must not mutate the shared disabled value");
        assertEquals(CloudShadowSettings.MAX_SCALE, 4.0f);
        assertEquals(CloudShadowSettings.MAX_SPEED, 20.0f);
    }

    @Test
    void zeroStrengthIsAnExactNoOpEvenWhenEnabled() {
        CloudShadowSettings zeroStrength =
                new CloudShadowSettings(true, 0.5f, 0.4f, 0.1f, 1.0f, 0.0f);
        assertTrue(zeroStrength.enabled());
        assertFalse(zeroStrength.active());
    }

    @Test
    void validatesTheBoundedControls() {
        assertThrows(IllegalArgumentException.class,
                () -> new CloudShadowSettings(true, -0.01f, 0.4f, 0.1f, 1.0f, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new CloudShadowSettings(true, 1.01f, 0.4f, 0.1f, 1.0f, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new CloudShadowSettings(true, Float.NaN, 0.4f, 0.1f, 1.0f, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new CloudShadowSettings(true, 0.5f, -0.01f, 0.1f, 1.0f, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new CloudShadowSettings(true, 0.5f, 1.01f, 0.1f, 1.0f, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new CloudShadowSettings(true, 0.5f, Float.NaN, 0.1f, 1.0f, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new CloudShadowSettings(true, 0.5f, 0.4f, 0.0f, 1.0f, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new CloudShadowSettings(true, 0.5f, 0.4f,
                        CloudShadowSettings.MAX_SCALE + 0.01f, 1.0f, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new CloudShadowSettings(true, 0.5f, 0.4f, Float.NaN, 1.0f, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new CloudShadowSettings(true, 0.5f, 0.4f, 0.1f, -0.01f, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new CloudShadowSettings(true, 0.5f, 0.4f, 0.1f,
                        CloudShadowSettings.MAX_SPEED + 0.01f, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new CloudShadowSettings(true, 0.5f, 0.4f, 0.1f, Float.NaN, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new CloudShadowSettings(true, 0.5f, 0.4f, 0.1f, 1.0f, -0.01f));
        assertThrows(IllegalArgumentException.class,
                () -> new CloudShadowSettings(true, 0.5f, 0.4f, 0.1f, 1.0f, 1.01f));
        assertThrows(IllegalArgumentException.class,
                () -> new CloudShadowSettings(true, 0.5f, 0.4f, 0.1f, 1.0f, Float.NaN));
    }

    @Test
    void legacyShadowSettingsRemainDisabledAndTheNewComponentIsPreserved() {
        ShadowSettings legacyFour = new ShadowSettings(512, 12.0f, 0.1f, 40.0f);
        ShadowSettings legacyFive = new ShadowSettings(512, 12.0f, 0.1f, 40.0f, 0.004f);
        ShadowSettings legacySix = new ShadowSettings(512, 12.0f, 0.1f, 40.0f, 0.004f,
                ContactShadowSettings.disabled());
        assertFalse(legacyFour.cloudShadows().active());
        assertFalse(legacyFive.cloudShadows().active());
        assertFalse(legacySix.cloudShadows().active());
        assertFalse(ShadowSettings.directionalDefaults().cloudShadows().active());

        CloudShadowSettings clouds = new CloudShadowSettings(true, 0.6f, 0.3f, 0.08f, 1.5f, 0.7f);
        ShadowSettings explicit = new ShadowSettings(512, 12.0f, 0.1f, 40.0f, 0.004f,
                ContactShadowSettings.disabled(), clouds);
        assertEquals(clouds, explicit.cloudShadows());
        assertEquals(0.004f, explicit.bias(), 0.0f);
        assertFalse(explicit.equals(new ShadowSettings(512, 12.0f, 0.1f, 40.0f, 0.004f)),
                "cloud-shadow controls participate in the feature-settings identity");
    }
}
