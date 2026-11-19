package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the shared height fallback data contract. This is the regression test
 * for the ALPHA channel defect: a 255 alpha fallback was sampled as height one
 * by {@code HeightChannel.ALPHA}, so a material with no height map still moved
 * UVs. The fallback must be zero in every channel, including alpha; the
 * real-GL {@code ParallaxGlTest} covers the rendered pixel equality.
 *
 * <p>The constants are read without constructing {@link PbrFallbackTextures},
 * so this case stays a pure JVM test and creates no GL texture.</p>
 */
class PbrFallbackTexturesTest {
    @Test
    void heightFallbackIsZeroInEveryChannelIncludingAlpha() {
        assertEquals(0, PbrFallbackTextures.HEIGHT_FALLBACK_RED,
                "the red height fallback must be a flat surface");
        assertEquals(0, PbrFallbackTextures.HEIGHT_FALLBACK_GREEN,
                "the green height fallback must be a flat surface");
        assertEquals(0, PbrFallbackTextures.HEIGHT_FALLBACK_BLUE,
                "the blue height fallback must be a flat surface");
        assertEquals(0, PbrFallbackTextures.HEIGHT_FALLBACK_ALPHA,
                "HeightChannel.ALPHA samples .a; the fallback alpha must be zero, "
                        + "not 255, or a missing height map would move UVs");
    }
}
