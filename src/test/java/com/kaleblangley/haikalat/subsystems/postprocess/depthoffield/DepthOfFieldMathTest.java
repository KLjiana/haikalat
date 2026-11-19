package com.kaleblangley.haikalat.subsystems.postprocess.depthoffield;

import com.kaleblangley.haikalat.subsystems.postprocess.DepthOfFieldSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DepthOfFieldMathTest {
    private static final float FOCUS = 4.0f;
    private static final float APERTURE = 2.8f;
    private static final int WIDTH = 1280;

    @Test
    void focusPlaneIsExactlySharp() {
        DepthOfFieldSettings settings = DepthOfFieldSettings.of(FOCUS, APERTURE, 8.0f);
        assertEquals(0.0f,
                DepthOfFieldMath.cocRadiusPixels(FOCUS, settings, WIDTH), 0.0f);
        assertEquals(0.0f,
                DepthOfFieldMath.cocRadiusPixels(0.0f, DepthOfFieldSettings.disabled(), WIDTH),
                0.0f);
    }

    @Test
    void farDefocusMatchesIndependentLensArithmetic() {
        // Independent arithmetic for a 50 mm lens, 36 mm sensor and f/2.8:
        // diameter = 0.05^2 * |10 - 4| / (2.8 * 10 * (4 - 0.05)) metres,
        // radius pixels = 0.5 * diameter * (1280 / 0.036).
        DepthOfFieldSettings settings = DepthOfFieldSettings.of(FOCUS, APERTURE, 16.0f);
        assertEquals(2.411f,
                DepthOfFieldMath.cocRadiusPixels(10.0f, settings, WIDTH), 0.01f);
    }

    @Test
    void longerFocalLengthIncreasesCircleOfConfusion() {
        DepthOfFieldSettings fifty = DepthOfFieldSettings.of(FOCUS, 50.0f, APERTURE, 16.0f);
        DepthOfFieldSettings hundred = DepthOfFieldSettings.of(FOCUS, 100.0f, APERTURE, 16.0f);
        assertTrue(DepthOfFieldMath.cocRadiusPixels(10.0f, hundred, WIDTH)
                > DepthOfFieldMath.cocRadiusPixels(10.0f, fifty, WIDTH));
    }

    @Test
    void largerApertureNumberReducesBlurAndNearSideBlursMoreThanFarSide() {
        DepthOfFieldSettings wide = DepthOfFieldSettings.of(FOCUS, 1.4f, 16.0f);
        DepthOfFieldSettings narrow = DepthOfFieldSettings.of(FOCUS, 11.0f, 16.0f);
        assertTrue(DepthOfFieldMath.cocRadiusPixels(10.0f, wide, WIDTH)
                > DepthOfFieldMath.cocRadiusPixels(10.0f, narrow, WIDTH));
        assertTrue(DepthOfFieldMath.cocRadiusPixels(2.0f, wide, WIDTH)
                > DepthOfFieldMath.cocRadiusPixels(6.0f, wide, WIDTH));
        assertTrue(DepthOfFieldMath.cocRadiusPixels(6.0f, wide, WIDTH)
                > DepthOfFieldMath.cocRadiusPixels(FOCUS, wide, WIDTH));
    }

    @Test
    void maximumCoCAndViewportControlTheRadiusBound() {
        DepthOfFieldSettings small = DepthOfFieldSettings.of(FOCUS, APERTURE, 2.0f);
        DepthOfFieldSettings large = DepthOfFieldSettings.of(FOCUS, APERTURE, 12.0f);
        assertEquals(2.0f, DepthOfFieldMath.cocRadiusPixels(1000.0f, small, WIDTH), 0.0f);
        assertEquals(12.0f, DepthOfFieldMath.cocRadiusPixels(0.0f, large, WIDTH), 0.0f);

        DepthOfFieldSettings unclamped = DepthOfFieldSettings.of(FOCUS, APERTURE, 16.0f);
        float halfWidth = DepthOfFieldMath.cocRadiusPixels(10.0f, unclamped, WIDTH / 2);
        float fullWidth = DepthOfFieldMath.cocRadiusPixels(10.0f, unclamped, WIDTH);
        assertEquals(fullWidth, halfWidth * 2.0f, 0.01f);
    }

    @Test
    void rejectsInvalidSamplingInputs() {
        DepthOfFieldSettings settings = DepthOfFieldSettings.of(FOCUS, APERTURE, 8.0f);
        assertThrows(IllegalArgumentException.class,
                () -> DepthOfFieldMath.cocRadiusPixels(-1.0f, settings, WIDTH));
        assertThrows(IllegalArgumentException.class,
                () -> DepthOfFieldMath.cocRadiusPixels(Float.NaN, settings, WIDTH));
        assertThrows(IllegalArgumentException.class,
                () -> DepthOfFieldMath.cocRadiusPixels(FOCUS, settings, 0));
        assertThrows(NullPointerException.class,
                () -> DepthOfFieldMath.cocRadiusPixels(FOCUS, null, WIDTH));
    }
}
