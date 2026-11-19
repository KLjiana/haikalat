package com.kaleblangley.haikalat.subsystems.render3d.shadow;

import com.kaleblangley.haikalat.subsystems.render3d.ShadowSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContactShadowSettingsTest {
    @Test
    void defaultsAreDisabledAndTheEnabledCopyStaysImmutable() {
        ContactShadowSettings defaults = ContactShadowSettings.disabled();
        assertFalse(defaults.enabled());
        assertFalse(defaults.active());

        ContactShadowSettings enabled = defaults.withEnabled(true);
        assertTrue(enabled.enabled());
        assertTrue(enabled.active());
        assertFalse(defaults.enabled(), "withEnabled must not mutate the shared disabled value");
        assertEquals(32, ContactShadowSettings.MAX_STEPS);
    }

    @Test
    void zeroStrengthIsAnExactNoOpEvenWhenEnabled() {
        ContactShadowSettings zeroStrength = new ContactShadowSettings(true, 1.0f, 0.5f, 8, 0.0f);
        assertTrue(zeroStrength.enabled());
        assertFalse(zeroStrength.active());
    }

    @Test
    void validatesTheBoundedControls() {
        assertThrows(IllegalArgumentException.class,
                () -> new ContactShadowSettings(true, 0.0f, 0.5f, 8, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new ContactShadowSettings(true, -1.0f, 0.5f, 8, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new ContactShadowSettings(true, Float.NaN, 0.5f, 8, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new ContactShadowSettings(true, ContactShadowSettings.MAX_DISTANCE + 0.01f,
                        0.5f, 8, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new ContactShadowSettings(true, 1.0f, 1.01f, 8, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new ContactShadowSettings(true, 1.0f, Float.NaN, 8, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new ContactShadowSettings(true, 1.0f, 0.5f, 0, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new ContactShadowSettings(true, 1.0f, 0.5f,
                        ContactShadowSettings.MAX_STEPS + 1, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new ContactShadowSettings(true, 1.0f, 0.5f, 8, -0.01f));
        assertThrows(IllegalArgumentException.class,
                () -> new ContactShadowSettings(true, 1.0f, 0.5f, 8, 1.01f));
        assertThrows(IllegalArgumentException.class,
                () -> new ContactShadowSettings(true, 1.0f, 0.5f, 8, Float.NaN));
    }

    @Test
    void legacyShadowSettingsRemainDisabledAndTheNewComponentIsPreserved() {
        ShadowSettings legacyFour = new ShadowSettings(512, 12.0f, 0.1f, 40.0f);
        ShadowSettings legacyFive = new ShadowSettings(512, 12.0f, 0.1f, 40.0f, 0.004f);
        assertFalse(legacyFour.contactShadows().active());
        assertFalse(legacyFive.contactShadows().active());
        assertFalse(ShadowSettings.directionalDefaults().contactShadows().active());

        ContactShadowSettings contact = new ContactShadowSettings(true, 1.25f, 0.4f, 10, 0.7f);
        ShadowSettings explicit = new ShadowSettings(512, 12.0f, 0.1f, 40.0f, 0.004f, contact);
        assertEquals(contact, explicit.contactShadows());
        assertEquals(0.004f, explicit.bias(), 0.0f);
        assertFalse(explicit.equals(new ShadowSettings(512, 12.0f, 0.1f, 40.0f, 0.004f)),
                "contact-shadow controls participate in the feature-settings identity");
    }
}
