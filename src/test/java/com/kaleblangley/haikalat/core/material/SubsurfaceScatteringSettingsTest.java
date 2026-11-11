package com.kaleblangley.haikalat.core.material;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the bounded subsurface-scattering value contract: inert defaults,
 * metre-space bounds, profile/thickness selectors, screen-space participation
 * and the builder/wither round trip.
 */
class SubsurfaceScatteringSettingsTest {
    @Test
    void disabledSettingsAreInertZeroValues() {
        SubsurfaceScatteringSettings disabled = SubsurfaceScatteringSettings.disabled();

        assertFalse(disabled.enabled());
        assertFalse(disabled.active());
        assertFalse(disabled.screenSpaceActive());
        assertEquals(0.0f, disabled.intensity());
        assertEquals(0.0f, disabled.profileScaleMetres());
        assertEquals(0.0f, disabled.thicknessMetres());
        assertEquals(0.0f, disabled.diffusionRadiusMetres());
        assertEquals(0.0f, disabled.maxDistanceMetres());
        assertEquals(SubsurfaceScatteringSettings.Profile.UNIFORM, disabled.profile());
        assertEquals(SubsurfaceScatteringSettings.ThicknessSource.CONSTANT,
                disabled.thicknessSource());
        assertEquals(SubsurfaceScatteringSettings.disabled(),
                SubsurfaceScatteringSettings.disabled().withEnabled(false),
                "withEnabled(false) must stay the inert default");
    }

    @Test
    void builderDefaultsAreActiveAndScreenSpaceReady() {
        SubsurfaceScatteringSettings settings = SubsurfaceScatteringSettings.builder().build();

        assertTrue(settings.active());
        assertTrue(settings.screenSpaceActive());
        assertEquals(SubsurfaceScatteringSettings.Profile.SKIN, settings.profile());
        assertEquals(SubsurfaceScatteringSettings.ThicknessSource.CONSTANT,
                settings.thicknessSource());
        assertEquals(1.0f, settings.intensity());
        assertTrue(settings.profileScaleMetres() > 0.0f);
        assertTrue(settings.thicknessMetres() > 0.0f);
        assertTrue(settings.diffusionRadiusMetres() > 0.0f);
        assertTrue(settings.maxDistanceMetres() > 0.0f);
    }

    @Test
    void factoriesAndWithersPreserveTheOtherComponents() {
        SubsurfaceScatteringSettings skin = SubsurfaceScatteringSettings.ofSkin();
        SubsurfaceScatteringSettings wax = SubsurfaceScatteringSettings.ofWax();
        assertEquals(SubsurfaceScatteringSettings.Profile.SKIN, skin.profile());
        assertEquals(SubsurfaceScatteringSettings.Profile.UNIFORM, wax.profile());
        assertEquals(new Vector3f(1.0f, 0.55f, 0.30f), skin.penetration());
        assertEquals(new Vector3f(1.0f), wax.penetration());

        SubsurfaceScatteringSettings changed = skin.withScatterColor(0.2f, 0.4f, 0.6f)
                .withIntensity(2.5f)
                .withProfile(SubsurfaceScatteringSettings.Profile.UNIFORM)
                .withProfileScaleMetres(0.08f)
                .withThicknessSource(SubsurfaceScatteringSettings.ThicknessSource.OCCLUSION)
                .withThicknessMetres(0.3f)
                .withDiffusionRadiusMetres(0.4f)
                .withMaxDistanceMetres(9.0f);
        assertEquals(0.2f, changed.scatterRed());
        assertEquals(0.4f, changed.scatterGreen());
        assertEquals(0.6f, changed.scatterBlue());
        assertEquals(2.5f, changed.intensity());
        assertEquals(SubsurfaceScatteringSettings.Profile.UNIFORM, changed.profile());
        assertEquals(0.08f, changed.profileScaleMetres());
        assertEquals(SubsurfaceScatteringSettings.ThicknessSource.OCCLUSION,
                changed.thicknessSource());
        assertEquals(0.3f, changed.thicknessMetres());
        assertEquals(0.4f, changed.diffusionRadiusMetres());
        assertEquals(9.0f, changed.maxDistanceMetres());

        SubsurfaceScatteringSettings disabled = changed.withEnabled(false);
        assertFalse(disabled.active());
        assertFalse(disabled.screenSpaceActive());
        assertEquals(0.3f, disabled.thicknessMetres(),
                "disabling must keep the authored thickness for later re-enable");

        SubsurfaceScatteringSettings restored = skin.toBuilder().intensity(3.0f).build();
        assertEquals(3.0f, restored.intensity());
        assertEquals(skin.scatterRed(), restored.scatterRed());
        assertEquals(skin.thicknessMetres(), restored.thicknessMetres());
        assertEquals(skin.maxDistanceMetres(), restored.maxDistanceMetres());
    }

    @Test
    void scatterColorIsAnImmutableValueCopy() {
        Vector3f authored = new Vector3f(0.9f, 0.2f, 0.1f);
        SubsurfaceScatteringSettings settings = SubsurfaceScatteringSettings.builder()
                .scatterColor(authored).build();
        authored.set(0.0f, 1.0f, 0.0f);

        assertEquals(new Vector3f(0.9f, 0.2f, 0.1f), settings.scatterColor());
        Vector3f copy = settings.scatterColor();
        copy.set(0.0f);
        assertEquals(new Vector3f(0.9f, 0.2f, 0.1f), settings.scatterColor(),
                "the accessor must not expose the record's components");
        assertEquals(settings, settings.withScatterColor(0.9f, 0.2f, 0.1f));
        assertNotEquals(settings, settings.withScatterColor(0.8f, 0.2f, 0.1f));
    }

    @Test
    void rejectsOutOfRangeAndNonFiniteValues() {
        for (float channel : new float[]{-0.001f, 1.001f, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SubsurfaceScatteringSettings.builder()
                            .scatterColor(channel, 0.5f, 0.5f).build(),
                    "scatter channel " + channel + " must be rejected");
        }
        for (float intensity : new float[]{-0.001f, 4.001f, Float.NaN,
                Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SubsurfaceScatteringSettings.builder().intensity(intensity).build(),
                    "intensity " + intensity + " must be rejected");
        }
        for (float scale : new float[]{-0.001f, 0.0f, 10.001f, Float.NaN,
                Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SubsurfaceScatteringSettings.builder()
                            .profileScaleMetres(scale).build(),
                    "profile scale " + scale + " must be rejected when enabled");
        }
        for (float thickness : new float[]{-0.001f, 2.001f, Float.NaN,
                Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SubsurfaceScatteringSettings.builder()
                            .thicknessMetres(thickness).build(),
                    "thickness " + thickness + " must be rejected");
        }
        for (float radius : new float[]{-0.001f, 2.001f, Float.NaN}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SubsurfaceScatteringSettings.builder()
                            .diffusionRadiusMetres(radius).build(),
                    "diffusion radius " + radius + " must be rejected");
        }
        for (float distance : new float[]{-0.001f, 100.001f, Float.NaN}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SubsurfaceScatteringSettings.builder()
                            .maxDistanceMetres(distance).build(),
                    "maximum distance " + distance + " must be rejected");
        }
        assertThrows(NullPointerException.class,
                () -> SubsurfaceScatteringSettings.builder().profile(null).build());
        assertThrows(NullPointerException.class,
                () -> SubsurfaceScatteringSettings.builder().thicknessSource(null).build());
    }

    @Test
    void screenSpaceParticipationRequiresACompleteEnabledMaterial() {
        SubsurfaceScatteringSettings disabledButConfigured = SubsurfaceScatteringSettings
                .builder().enabled(false).screenSpaceEnabled(true).build();
        assertFalse(disabledButConfigured.active());
        assertFalse(disabledButConfigured.screenSpaceActive(),
                "a disabled material cannot contribute to the screen-space mask");
        assertTrue(disabledButConfigured.screenSpaceEnabled(),
                "the authored screen-space choice must survive a temporary disable");
        assertThrows(IllegalArgumentException.class,
                () -> SubsurfaceScatteringSettings.builder()
                        .diffusionRadiusMetres(0.0f).screenSpaceEnabled(true).build(),
                "screen-space participation requires a positive radius");
        assertThrows(IllegalArgumentException.class,
                () -> SubsurfaceScatteringSettings.builder()
                        .maxDistanceMetres(0.0f).screenSpaceEnabled(true).build(),
                "screen-space participation requires a positive maximum distance");

        SubsurfaceScatteringSettings screenOff = SubsurfaceScatteringSettings.builder()
                .screenSpaceEnabled(false).build();
        assertTrue(screenOff.active());
        assertFalse(screenOff.screenSpaceActive(),
                "forward scatter must remain available without the screen-space pass");

        SubsurfaceScatteringSettings zeroIntensity = SubsurfaceScatteringSettings.builder()
                .intensity(0.0f).build();
        assertFalse(zeroIntensity.active());
        assertFalse(zeroIntensity.screenSpaceActive());

        SubsurfaceScatteringSettings black = SubsurfaceScatteringSettings.builder()
                .scatterColor(0.0f, 0.0f, 0.0f).build();
        assertFalse(black.active(), "a black scatter tint contributes no positive term");
    }

    @Test
    void enumSelectorsRoundTripThroughTheirShaderIndex() {
        for (SubsurfaceScatteringSettings.Profile profile
                : SubsurfaceScatteringSettings.Profile.values()) {
            assertEquals(profile, SubsurfaceScatteringSettings.Profile
                    .fromShaderIndex(profile.shaderIndex()));
        }
        for (SubsurfaceScatteringSettings.ThicknessSource source
                : SubsurfaceScatteringSettings.ThicknessSource.values()) {
            assertEquals(source, SubsurfaceScatteringSettings.ThicknessSource
                    .fromShaderIndex(source.shaderIndex()));
        }
        assertThrows(IllegalArgumentException.class,
                () -> SubsurfaceScatteringSettings.Profile.fromShaderIndex(2));
        assertThrows(IllegalArgumentException.class,
                () -> SubsurfaceScatteringSettings.ThicknessSource.fromShaderIndex(3));
    }
}
