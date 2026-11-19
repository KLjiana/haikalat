package com.kaleblangley.haikalat.core.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the parallax occlusion value contract: the inert default, the positive
 * path, the documented angular/distance windows and the construction bounds
 * that keep the shader march finite and bounded.
 */
class ParallaxOcclusionSettingsTest {
    @Test
    void disabledSettingsNeverSelectThePositivePath() {
        ParallaxOcclusionSettings disabled = ParallaxOcclusionSettings.disabled();

        assertFalse(disabled.enabled());
        assertFalse(disabled.active());
        assertEquals(0.0f, disabled.heightScale(),
                "disabled must not modify any sampled UV");
        assertEquals(ParallaxOcclusionSettings.DEFAULT_MIN_STEPS, disabled.minSteps());
        assertEquals(ParallaxOcclusionSettings.DEFAULT_MAX_STEPS, disabled.maxSteps());
        assertEquals(ParallaxOcclusionSettings.HeightChannel.RED, disabled.heightChannel());
        assertFalse(disabled.flipHeight());
        assertFalse(disabled.withEnabled(false).active());
        assertEquals(ParallaxOcclusionSettings.disabled(),
                ParallaxOcclusionSettings.disabled().toBuilder().build(),
                "the builder must round-trip the inert default exactly");
    }

    @Test
    void zeroHeightScaleIsAnExactNoOpEvenWhenEnabled() {
        ParallaxOcclusionSettings zero = ParallaxOcclusionSettings.builder()
                .enabled(true).heightScale(0.0f).build();

        assertTrue(zero.enabled());
        assertFalse(zero.active(), "zero height scale must keep the original UV path");
        assertFalse(ParallaxOcclusionSettings.of(0.0f).active());
    }

    @Test
    void builderAndWithersPreserveTheOtherComponents() {
        ParallaxOcclusionSettings settings = ParallaxOcclusionSettings.builder()
                .heightScale(0.12f)
                .steps(6, 24)
                .fadeAngles(55.0f, 82.0f)
                .distanceRange(4.0f, 12.0f)
                .heightChannel(ParallaxOcclusionSettings.HeightChannel.GREEN)
                .flipHeight(true)
                .build();

        assertTrue(settings.active());
        assertEquals(0.12f, settings.heightScale());
        assertEquals(6, settings.minSteps());
        assertEquals(24, settings.maxSteps());
        assertEquals(55.0f, settings.fadeStart());
        assertEquals(82.0f, settings.fadeEnd());
        assertEquals(4.0f, settings.fadeStartDistance());
        assertEquals(12.0f, settings.maxDistance());
        assertEquals(ParallaxOcclusionSettings.HeightChannel.GREEN, settings.heightChannel());
        assertTrue(settings.flipHeight());

        ParallaxOcclusionSettings changed = settings.withHeightScale(0.2f)
                .withSteps(8, 32)
                .withFadeAngles(60.0f, 85.0f)
                .withDistanceRange(8.0f, 20.0f)
                .withHeightChannel(ParallaxOcclusionSettings.HeightChannel.BLUE)
                .withFlipHeight(false)
                .withEnabled(false);
        assertEquals(new ParallaxOcclusionSettings(false, 0.2f, 8, 32, 60.0f, 85.0f,
                8.0f, 20.0f, ParallaxOcclusionSettings.HeightChannel.BLUE, false), changed);
        assertEquals(settings, settings.toBuilder().build(),
                "toBuilder must reproduce an equal record");
        assertEquals(ParallaxOcclusionSettings.of(0.05f),
                ParallaxOcclusionSettings.builder().heightScale(0.05f).build());
        assertEquals(ParallaxOcclusionSettings.builder().steps(4, 16).build(),
                ParallaxOcclusionSettings.of(ParallaxOcclusionSettings.DEFAULT_HEIGHT_SCALE,
                        4, 16));
    }

    @Test
    void rejectsOutOfRangeAndNonFiniteValues() {
        for (float scale : new float[]{-0.001f, 1.001f, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ParallaxOcclusionSettings(true, scale, 8, 32, 60.0f, 85.0f,
                            8.0f, 20.0f, ParallaxOcclusionSettings.HeightChannel.RED, false),
                    "height scale " + scale + " must be rejected");
        }
        for (int steps : new int[]{0, -1, 129, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ParallaxOcclusionSettings(true, 0.1f, steps, 32, 60.0f, 85.0f,
                            8.0f, 20.0f, ParallaxOcclusionSettings.HeightChannel.RED, false),
                    "minSteps " + steps + " must be rejected");
            assertThrows(IllegalArgumentException.class,
                    () -> new ParallaxOcclusionSettings(true, 0.1f, 8, steps, 60.0f, 85.0f,
                            8.0f, 20.0f, ParallaxOcclusionSettings.HeightChannel.RED, false),
                    "maxSteps " + steps + " must be rejected");
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ParallaxOcclusionSettings(true, 0.1f, 16, 8, 60.0f, 85.0f,
                        8.0f, 20.0f, ParallaxOcclusionSettings.HeightChannel.RED, false),
                "maxSteps below minSteps must be rejected");
        for (float start : new float[]{-0.1f, 90.0f, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ParallaxOcclusionSettings(true, 0.1f, 8, 32, start, 90.0f,
                            8.0f, 20.0f, ParallaxOcclusionSettings.HeightChannel.RED, false),
                    "fade start " + start + " must be rejected");
        }
        for (float end : new float[]{60.0f, 59.9f, 90.1f, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ParallaxOcclusionSettings(true, 0.1f, 8, 32, 60.0f, end,
                            8.0f, 20.0f, ParallaxOcclusionSettings.HeightChannel.RED, false),
                    "fade end " + end + " must be rejected");
        }
        for (float start : new float[]{-0.1f, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ParallaxOcclusionSettings(true, 0.1f, 8, 32, 60.0f, 85.0f,
                            start, 20.0f, ParallaxOcclusionSettings.HeightChannel.RED, false),
                    "fade start distance " + start + " must be rejected");
        }
        for (float maximum : new float[]{8.0f, 7.9f, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ParallaxOcclusionSettings(true, 0.1f, 8, 32, 60.0f, 85.0f,
                            8.0f, maximum, ParallaxOcclusionSettings.HeightChannel.RED, false),
                    "max distance " + maximum + " must be rejected");
        }
        assertThrows(NullPointerException.class,
                () -> new ParallaxOcclusionSettings(true, 0.1f, 8, 32, 60.0f, 85.0f,
                        8.0f, 20.0f, null, false));
        assertEquals(1.0f, ParallaxOcclusionSettings.of(1.0f).heightScale(),
                "the full height scale is the inclusive upper bound");
        assertEquals(128, ParallaxOcclusionSettings.builder().steps(1, 128).build().maxSteps());
    }

    @Test
    void heightChannelMapsToTheFrozenShaderSelector() {
        assertEquals(0, ParallaxOcclusionSettings.HeightChannel.RED.shaderIndex());
        assertEquals(1, ParallaxOcclusionSettings.HeightChannel.GREEN.shaderIndex());
        assertEquals(2, ParallaxOcclusionSettings.HeightChannel.BLUE.shaderIndex());
        assertEquals(3, ParallaxOcclusionSettings.HeightChannel.ALPHA.shaderIndex());
        for (ParallaxOcclusionSettings.HeightChannel channel
                : ParallaxOcclusionSettings.HeightChannel.values()) {
            assertEquals(channel,
                    ParallaxOcclusionSettings.HeightChannel.fromShaderIndex(channel.shaderIndex()));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ParallaxOcclusionSettings.HeightChannel.fromShaderIndex(-1));
        assertThrows(IllegalArgumentException.class,
                () -> ParallaxOcclusionSettings.HeightChannel.fromShaderIndex(4));
    }
}
