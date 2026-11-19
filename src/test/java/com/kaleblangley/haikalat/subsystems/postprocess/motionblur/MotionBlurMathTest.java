package com.kaleblangley.haikalat.subsystems.postprocess.motionblur;

import com.kaleblangley.haikalat.subsystems.postprocess.MotionBlurSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionBlurMathTest {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    @Test
    void zeroAndSubPixelVelocityAreExactIdentity() {
        MotionBlurSettings settings = MotionBlurSettings.of(0.5f, 16.0f);
        assertEquals(MotionBlurMath.ScreenMotion.ZERO,
                MotionBlurMath.offsetPixels(0.0f, 0.0f, WIDTH, HEIGHT, settings));
        // 0.4 output pixels of motion stays below the half-pixel static threshold.
        assertEquals(MotionBlurMath.ScreenMotion.ZERO,
                MotionBlurMath.offsetPixels(0.4f / WIDTH, 0.0f, WIDTH, HEIGHT, settings));
        assertEquals(MotionBlurMath.ScreenMotion.ZERO,
                MotionBlurMath.offsetPixels(0.05f, 0.05f, WIDTH, HEIGHT,
                        MotionBlurSettings.disabled()));
    }

    @Test
    void shutterScalesTheIndependentPixelArithmetic() {
        // 0.01 uv * 1280 px * 0.5 shutter = 6.4 px; -0.02 uv * 720 px * 0.25 = -3.6 px.
        MotionBlurSettings settings = MotionBlurSettings.of(0.5f, 16.0f);
        MotionBlurMath.ScreenMotion horizontal =
                MotionBlurMath.offsetPixels(0.01f, 0.0f, WIDTH, HEIGHT, settings);
        assertEquals(6.4f, horizontal.uPixels(), 1.0e-5f);
        assertEquals(0.0f, horizontal.vPixels(), 0.0f);

        MotionBlurMath.ScreenMotion vertical =
                MotionBlurMath.offsetPixels(0.0f, -0.02f, WIDTH, HEIGHT,
                        MotionBlurSettings.of(0.25f, 16.0f));
        assertEquals(-3.6f, vertical.vPixels(), 1.0e-5f);

        MotionBlurMath.ScreenMotion quarter =
                MotionBlurMath.offsetPixels(0.01f, 0.0f, WIDTH, HEIGHT,
                        MotionBlurSettings.of(0.25f, 16.0f));
        assertEquals(horizontal.uPixels() * 0.5f, quarter.uPixels(), 1.0e-5f);
    }

    @Test
    void maximumRadiusClampsTheVectorAndPreservesItsDirection() {
        MotionBlurSettings small = MotionBlurSettings.of(0.5f, 4.0f);
        // 0.02 uv * 1280 * 0.5 = 12.8 px of requested motion.
        MotionBlurMath.ScreenMotion clamped =
                MotionBlurMath.offsetPixels(0.02f, 0.0f, WIDTH, HEIGHT, small);
        assertEquals(4.0f, clamped.lengthPixels(), 1.0e-5f);
        assertTrue(clamped.uPixels() > 0.0f);

        MotionBlurMath.ScreenMotion diagonal =
                MotionBlurMath.offsetPixels(0.02f, 0.02f, WIDTH, HEIGHT,
                        MotionBlurSettings.of(0.5f, 2.0f));
        assertEquals(2.0f, diagonal.lengthPixels(), 1.0e-5f);
        // The clamp preserves the requested aspect-ratio direction.
        assertEquals(WIDTH / (float) HEIGHT, diagonal.uPixels() / diagonal.vPixels(), 1.0e-5f);

        // A finite velocity above the shader's defensive bound falls back to
        // the same zero-motion identity instead of clamping.
        MotionBlurMath.ScreenMotion huge =
                MotionBlurMath.offsetPixels(1.0e6f, 0.0f, WIDTH, HEIGHT, small);
        assertEquals(MotionBlurMath.ScreenMotion.ZERO, huge);
    }

    @Test
    void invalidVelocityTakesTheDocumentedZeroFallback() {
        MotionBlurSettings settings = MotionBlurSettings.of(0.5f, 16.0f);
        assertEquals(MotionBlurMath.ScreenMotion.ZERO,
                MotionBlurMath.offsetPixels(Float.NaN, 0.0f, WIDTH, HEIGHT, settings));
        assertEquals(MotionBlurMath.ScreenMotion.ZERO,
                MotionBlurMath.offsetPixels(0.0f, Float.POSITIVE_INFINITY, WIDTH, HEIGHT, settings));
    }

    @Test
    void gatherTapsStayMonotonicInsideTheBoundedSegment() {
        MotionBlurSettings settings = MotionBlurSettings.of(0.5f, 8.0f);
        MotionBlurMath.ScreenMotion motion =
                MotionBlurMath.offsetPixels(0.02f, 0.0f, WIDTH, HEIGHT, settings);
        float previousU = 0.0f;
        for (int index = 0; index < 12; index++) {
            float fraction = MotionBlurMath.tapFraction(index, 12);
            MotionBlurMath.ScreenMotion tap = MotionBlurMath.tapOffset(motion, index, 12);
            assertEquals(motion.uPixels() * fraction, tap.uPixels(), 1.0e-5f);
            assertTrue(tap.uPixels() > previousU);
            assertTrue(tap.lengthPixels() <= motion.lengthPixels() + 1.0e-5f);
            previousU = tap.uPixels();
        }
        assertEquals(0.5f / 12.0f, MotionBlurMath.tapFraction(0, 12), 1.0e-6f);
        assertEquals(11.5f / 12.0f, MotionBlurMath.tapFraction(11, 12), 1.0e-6f);
    }

    @Test
    void rejectsInvalidExtentSettingsAndTapArguments() {
        MotionBlurSettings settings = MotionBlurSettings.of(0.5f, 16.0f);
        assertThrows(IllegalArgumentException.class,
                () -> MotionBlurMath.offsetPixels(0.1f, 0.0f, 0, HEIGHT, settings));
        assertThrows(IllegalArgumentException.class,
                () -> MotionBlurMath.offsetPixels(0.1f, 0.0f, WIDTH, -1, settings));
        assertThrows(NullPointerException.class,
                () -> MotionBlurMath.offsetPixels(0.1f, 0.0f, WIDTH, HEIGHT, null));
        assertThrows(IllegalArgumentException.class,
                () -> MotionBlurMath.tapFraction(0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> MotionBlurMath.tapFraction(12, 12));
        assertThrows(NullPointerException.class,
                () -> MotionBlurMath.tapOffset(null, 0, 12));
    }
}
