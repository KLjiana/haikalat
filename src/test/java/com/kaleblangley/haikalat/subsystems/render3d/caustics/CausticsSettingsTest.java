package com.kaleblangley.haikalat.subsystems.render3d.caustics;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM contract for the bounded caustics configuration.
 *
 * <p>Covers constructor rejection, the exact disabled no-op, defensive copies
 * and the derived receiver slope cosine. Pattern/attenuation values live in
 * {@link CausticsReferenceTest}.</p>
 */
class CausticsSettingsTest {
    private static CausticsSettings enabled() {
        return CausticsSettings.builder()
                .enabled(true)
                .intensity(1.5f)
                .color(0.55f, 0.80f, 1.0f)
                .worldScaleMetres(2.5f)
                .animationSpeed(0.4f)
                .maximumProjectionDistance(12.0f)
                .waterPlane(0.0f)
                .waterRegion(-6.0f, -4.0f, 7.0f, 5.0f)
                .receiverHeight(-5.0f, -0.5f)
                .depthBias(0.05f)
                .distanceFalloff(0.12f)
                .edgeFadeDistance(0.8f)
                .maximumReceiverSlopeDegrees(55.0f)
                .patternTexture("")
                .build();
    }

    @Test
    void disabledValueIsAnExactNoOp() {
        CausticsSettings disabled = CausticsSettings.disabled();
        assertFalse(disabled.active());
        assertFalse(disabled.hasPatternTexture());
        assertEquals(1.0f, disabled.intensity(), 0.0f);
        assertEquals(disabled, CausticsSettings.disabled());
        assertEquals(disabled, disabled.withEnabled(false));
        assertEquals(disabled.hashCode(), CausticsSettings.disabled().hashCode());

        CausticsSettings enabled = disabled.withEnabled(true);
        assertTrue(enabled.active());
        assertEquals(disabled.intensity(), enabled.intensity(), 0.0f);
        assertEquals(disabled.waterLevel(), enabled.waterLevel(), 0.0f);
        assertEquals(disabled.maximumProjectionDistance(),
                enabled.maximumProjectionDistance(), 0.0f);
    }

    @Test
    void builderRoundTripPreservesValuesAndDefensiveCopies() {
        CausticsSettings settings = enabled();
        assertTrue(settings.active());
        assertEquals(1.5f, settings.intensity(), 0.0f);
        assertEquals(2.5f, settings.worldScaleMetres(), 0.0f);
        assertEquals(13.0f, settings.waterWidth(), 0.0f);
        assertEquals(9.0f, settings.waterDepth(), 0.0f);
        assertEquals(4.5f, settings.receiverMaximumHeight()
                - settings.receiverMinimumHeight(), 1.0e-6f);
        assertEquals((float) Math.cos(Math.toRadians(55.0)), settings.minimumReceiverUpDot(),
                1.0e-6f);

        Vector3f first = settings.color();
        first.set(100.0f, 100.0f, 100.0f);
        assertEquals(0.55f, settings.color().x, 0.0f);
        assertEquals(0.80f, settings.color().y, 0.0f);
        assertEquals(1.0f, settings.color().z, 0.0f);

        Vector3f destination = new Vector3f();
        settings.colorTo(destination);
        assertEquals(settings.color(), destination);

        assertEquals(settings, enabled());
        assertEquals(settings.hashCode(), enabled().hashCode());
        assertEquals(settings, settings.withEnabled(true));
        assertFalse(settings.withEnabled(false).active());
        assertNotEquals(settings, settings.withEnabled(false));
    }

    @Test
    void constructionRejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> builder().intensity(-0.1f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .intensity(CausticsSettings.MAXIMUM_INTENSITY + 0.1f).build());
        assertThrows(IllegalArgumentException.class, () -> builder().intensity(Float.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .color(-0.1f, 0.0f, 0.0f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .color(0.0f, CausticsSettings.MAXIMUM_COLOR_COMPONENT + 0.1f, 0.0f).build());
        assertThrows(IllegalArgumentException.class, () -> builder().color(Float.NaN, 0.0f, 0.0f)
                .build());
        assertThrows(IllegalArgumentException.class, () -> builder().worldScaleMetres(0.0f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .worldScaleMetres(CausticsSettings.MAXIMUM_WORLD_SCALE * 2.0f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .animationSpeed(CausticsSettings.MAXIMUM_ANIMATION_SPEED + 0.1f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .animationSpeed(Float.NEGATIVE_INFINITY).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .maximumProjectionDistance(0.0f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .maximumProjectionDistance(Float.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> builder().waterPlane(Float.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .waterPlane(CausticsSettings.MAXIMUM_ABSOLUTE_HEIGHT + 1.0f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .waterRegion(2.0f, 0.0f, 1.0f, 4.0f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .waterRegion(0.0f, 0.0f, CausticsSettings.MINIMUM_EXTENT * 0.5f, 4.0f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .waterRegion(0.0f, 0.0f, CausticsSettings.MAXIMUM_EXTENT * 2.0f, 4.0f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .receiverHeight(-1.0f, -1.0f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .receiverHeight(-1.0f, -0.995f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .receiverHeight(Float.NaN, -0.5f).build());
        assertThrows(IllegalArgumentException.class, () -> builder().depthBias(-0.1f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .depthBias(CausticsSettings.MAXIMUM_DEPTH_BIAS + 0.1f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .distanceFalloff(-0.1f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .distanceFalloff(CausticsSettings.MAXIMUM_DISTANCE_FALLOFF + 0.1f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .edgeFadeDistance(-0.1f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .edgeFadeDistance(CausticsSettings.MAXIMUM_EDGE_FADE_DISTANCE + 0.1f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .maximumReceiverSlopeDegrees(-0.1f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .maximumReceiverSlopeDegrees(
                        CausticsSettings.MAXIMUM_RECEIVER_SLOPE_DEGREES + 0.1f).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .maximumReceiverSlopeDegrees(Float.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> builder().patternTexture(null).build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .patternTexture("vfx/masks/kenney/no-leading-slash.png").build());
        assertThrows(IllegalArgumentException.class, () -> builder()
                .patternTexture("/" + "a".repeat(CausticsSettings.MAXIMUM_PATTERN_TEXTURE_PATH))
                .build());
    }

    @Test
    void optionalPatternTextureIsRetainedByTheValue() {
        CausticsSettings settings = builder()
                .patternTexture("/vfx/masks/kenney/light_masks/transparent/water_caustics_a.png")
                .build();
        assertTrue(settings.hasPatternTexture());
        assertEquals("/vfx/masks/kenney/light_masks/transparent/water_caustics_a.png",
                settings.patternTexture());
        assertEquals(settings, settings.withEnabled(true));
        assertEquals(settings.patternTexture(), settings.withEnabled(false).patternTexture());
    }

    private static CausticsSettings.Builder builder() {
        return CausticsSettings.builder()
                .enabled(true)
                .waterRegion(-20.0f, -20.0f, 20.0f, 20.0f)
                .receiverHeight(-8.0f, -0.5f);
    }
}
