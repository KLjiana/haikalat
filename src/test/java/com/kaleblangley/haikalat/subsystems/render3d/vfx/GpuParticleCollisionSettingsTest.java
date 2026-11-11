package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounded collision-settings contract for the production particle owner.
 *
 * <p>The disabled value must keep the #27 owner path: no thickness, no
 * restitution and no search. The enabled value rejects every unbounded or
 * unsupported input at construction, especially transparent-depth declarations,
 * because this implementation only consumes opaque single-sample scene depth.</p>
 */
class GpuParticleCollisionSettingsTest {
    @Test
    void disabledValueKeepsTheUnmodifiedParticlePath() {
        GpuParticleCollisionSettings disabled = GpuParticleCollisionSettings.disabled();

        assertFalse(disabled.enabled());
        assertEquals(0.0f, disabled.thickness());
        assertEquals(0.0f, disabled.restitution());
        assertEquals(0.0f, disabled.friction());
        assertEquals(1, disabled.maximumSearchSteps());
        assertEquals(0.0f, disabled.searchRadiusPixels());
        assertTrue(disabled.opaqueDepthOnly());
        assertEquals(GpuParticleCollisionSettings.DepthSampling.TEXEL_CENTRE,
                disabled.depthSampling());
    }

    @Test
    void enabledValueAcceptsTheDocumentedBounds() {
        GpuParticleCollisionSettings settings = GpuParticleCollisionSettings.enabled(
                GpuParticleCollisionSettings.DepthSampling.CONSERVATIVE_MIN_2X2,
                GpuParticleCollisionSettings.MAXIMUM_THICKNESS, 1.0f, 1.0f,
                GpuParticleCollisionSettings.MAXIMUM_SEARCH_STEPS,
                GpuParticleCollisionSettings.MAXIMUM_SEARCH_RADIUS_PIXELS);

        assertTrue(settings.enabled());
        assertTrue(settings.opaqueDepthOnly());
        assertEquals(GpuParticleCollisionSettings.DepthSampling.CONSERVATIVE_MIN_2X2,
                settings.depthSampling());
        assertEquals(1.0f, settings.restitution());
        assertEquals(1.0f, settings.friction());
        assertEquals(GpuParticleCollisionSettings.MAXIMUM_THICKNESS,
                settings.thickness());
    }

    @Test
    void invalidEnabledFieldsAreRejectedInsteadOfClamped() {
        GpuParticleCollisionSettings.DepthSampling sampling =
                GpuParticleCollisionSettings.DepthSampling.TEXEL_CENTRE;

        assertThrows(NullPointerException.class, () -> new GpuParticleCollisionSettings(
                true, null, 0.1f, 0.5f, 0.0f, 1, 0.0f, true));
        assertThrows(IllegalArgumentException.class, () -> GpuParticleCollisionSettings
                .enabled(sampling, 0.0f, 0.5f, 0.0f, 1, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> GpuParticleCollisionSettings
                .enabled(sampling, Float.NaN, 0.5f, 0.0f, 1, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> GpuParticleCollisionSettings
                .enabled(sampling, GpuParticleCollisionSettings.MAXIMUM_THICKNESS + 1.0f,
                        0.5f, 0.0f, 1, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> GpuParticleCollisionSettings
                .enabled(sampling, 0.1f, -0.1f, 0.0f, 1, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> GpuParticleCollisionSettings
                .enabled(sampling, 0.1f, 1.1f, 0.0f, 1, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> GpuParticleCollisionSettings
                .enabled(sampling, 0.1f, Float.NaN, 0.0f, 1, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> GpuParticleCollisionSettings
                .enabled(sampling, 0.1f, 0.5f, -0.1f, 1, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> GpuParticleCollisionSettings
                .enabled(sampling, 0.1f, 0.5f, 1.1f, 1, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> GpuParticleCollisionSettings
                .enabled(sampling, 0.1f, 0.5f, 0.0f, 0, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> GpuParticleCollisionSettings
                .enabled(sampling, 0.1f, 0.5f, 0.0f,
                        GpuParticleCollisionSettings.MAXIMUM_SEARCH_STEPS + 1, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> GpuParticleCollisionSettings
                .enabled(sampling, 0.1f, 0.5f, 0.0f, 1, -1.0f));
        assertThrows(IllegalArgumentException.class, () -> GpuParticleCollisionSettings
                .enabled(sampling, 0.1f, 0.5f, 0.0f, 1,
                        GpuParticleCollisionSettings.MAXIMUM_SEARCH_RADIUS_PIXELS + 1.0f));
        assertThrows(IllegalArgumentException.class, () -> new GpuParticleCollisionSettings(
                true, sampling, 0.1f, 0.5f, 0.0f, 1, 0.0f, false));
        assertThrows(IllegalArgumentException.class, () -> new GpuParticleCollisionSettings(
                false, sampling, 0.1f, 0.5f, 0.0f, 1, 0.0f, false));
    }

    @Test
    void boundedCopiesKeepTheOtherFieldsAndRevalidate() {
        GpuParticleCollisionSettings settings = GpuParticleCollisionSettings.enabled(
                GpuParticleCollisionSettings.DepthSampling.CONSERVATIVE_MIN_2X2,
                0.25f, 0.4f, 0.1f, 3, 4.0f);

        GpuParticleCollisionSettings thicker = settings.withThickness(0.5f);
        GpuParticleCollisionSettings bouncier = settings.withRestitution(0.9f);

        assertEquals(0.5f, thicker.thickness());
        assertEquals(settings.restitution(), thicker.restitution());
        assertEquals(settings.friction(), thicker.friction());
        assertEquals(0.9f, bouncier.restitution());
        assertEquals(settings.thickness(), bouncier.thickness());
        assertEquals(settings.depthSampling(), bouncier.depthSampling());
        assertTrue(thicker.enabled());
        assertTrue(bouncier.opaqueDepthOnly());

        assertThrows(IllegalArgumentException.class,
                () -> settings.withThickness(0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings.withRestitution(1.5f));
    }
}
