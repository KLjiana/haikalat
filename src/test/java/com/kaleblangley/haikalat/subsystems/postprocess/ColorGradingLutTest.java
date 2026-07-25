package com.kaleblangley.haikalat.subsystems.postprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ColorGradingLutTest {
    @Test
    void identityLutMatchesTrilinearInputWithinEightBitPrecision() {
        ColorGradingLut lut = ColorGradingLut.identity(16);
        ColorGradingLut.Rgb sampled = lut.sample(0.17f, 0.52f, 0.91f);

        assertEquals(0.17f, sampled.red(), 1.0f / 255.0f);
        assertEquals(0.52f, sampled.green(), 1.0f / 255.0f);
        assertEquals(0.91f, sampled.blue(), 1.0f / 255.0f);
        assertEquals(256, lut.width());
        assertEquals(16, lut.height());
    }

    @Test
    void generatedLutUsesBlueSlicesAndDefensivelyCopiesPayload() {
        ColorGradingLut lut = ColorGradingLut.generate(4,
                (red, green, blue) -> new ColorGradingLut.Rgb(blue, red, green));
        ColorGradingLut.Rgb sampled = lut.sample(0.2f, 0.4f, 0.8f);
        assertEquals(0.8f, sampled.red(), 1.0f / 255.0f);
        assertEquals(0.2f, sampled.green(), 1.0f / 255.0f);
        assertEquals(0.4f, sampled.blue(), 1.0f / 255.0f);

        byte[] copy = lut.copyRgba8();
        copy[0] ^= 0x7f;
        assertNotEquals(copy[0], lut.copyRgba8()[0]);
    }

    @Test
    void malformedLutsAndMapperOutputsFailFast() {
        assertThrows(IllegalArgumentException.class, () -> ColorGradingLut.identity(1));
        assertThrows(IllegalArgumentException.class,
                () -> ColorGradingLut.fromRgba8(4, new byte[4]));
        assertThrows(IllegalArgumentException.class, () -> ColorGradingLut.generate(4,
                (red, green, blue) -> new ColorGradingLut.Rgb(2.0f, green, blue)));
    }
}
