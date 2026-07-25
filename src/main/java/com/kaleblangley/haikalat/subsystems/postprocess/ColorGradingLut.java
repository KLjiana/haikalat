package com.kaleblangley.haikalat.subsystems.postprocess;

import java.util.Arrays;
import java.util.Objects;

/** Immutable tiled RGB color-grading lookup table with a pure-JVM sampling reference. */
public final class ColorGradingLut {
    public static final int MIN_SIZE = 2;
    public static final int MAX_SIZE = 64;

    private final int size;
    private final byte[] rgba8;

    private ColorGradingLut(int size, byte[] rgba8) {
        this.size = requireSize(size);
        int expected = requiredBytes(size);
        if (Objects.requireNonNull(rgba8, "rgba8").length != expected) {
            throw new IllegalArgumentException("LUT payload must contain exactly " + expected
                    + " bytes for size " + size);
        }
        this.rgba8 = rgba8.clone();
    }

    public static ColorGradingLut fromRgba8(int size, byte[] rgba8) {
        return new ColorGradingLut(size, rgba8);
    }

    public static ColorGradingLut identity(int size) {
        return generate(size, Rgb::new);
    }

    /** Generates a blue-slice tiled LUT in linear display color space. */
    public static ColorGradingLut generate(int size, RgbMapper mapper) {
        int checkedSize = requireSize(size);
        Objects.requireNonNull(mapper, "mapper");
        byte[] output = new byte[requiredBytes(checkedSize)];
        float denominator = checkedSize - 1.0f;
        for (int blue = 0; blue < checkedSize; blue++) {
            for (int green = 0; green < checkedSize; green++) {
                for (int red = 0; red < checkedSize; red++) {
                    Rgb mapped = Objects.requireNonNull(mapper.map(red / denominator,
                            green / denominator, blue / denominator), "mapped color");
                    int offset = texelOffset(checkedSize, red, green, blue);
                    output[offset] = quantize(mapped.red());
                    output[offset + 1] = quantize(mapped.green());
                    output[offset + 2] = quantize(mapped.blue());
                    output[offset + 3] = (byte) 0xff;
                }
            }
        }
        return new ColorGradingLut(checkedSize, output);
    }

    public int size() {
        return size;
    }

    public int width() {
        return Math.multiplyExact(size, size);
    }

    public int height() {
        return size;
    }

    public byte[] copyRgba8() {
        return rgba8.clone();
    }

    /** Trilinear CPU reference matching the tiled shader lookup. */
    public Rgb sample(float red, float green, float blue) {
        requireUnit(red, "red");
        requireUnit(green, "green");
        requireUnit(blue, "blue");
        float scaledRed = red * (size - 1);
        float scaledGreen = green * (size - 1);
        float scaledBlue = blue * (size - 1);
        int red0 = (int) Math.floor(scaledRed);
        int green0 = (int) Math.floor(scaledGreen);
        int blue0 = (int) Math.floor(scaledBlue);
        int red1 = Math.min(size - 1, red0 + 1);
        int green1 = Math.min(size - 1, green0 + 1);
        int blue1 = Math.min(size - 1, blue0 + 1);
        float tx = scaledRed - red0;
        float ty = scaledGreen - green0;
        float tz = scaledBlue - blue0;
        return mix(
                mix(mix(texel(red0, green0, blue0), texel(red1, green0, blue0), tx),
                        mix(texel(red0, green1, blue0), texel(red1, green1, blue0), tx), ty),
                mix(mix(texel(red0, green0, blue1), texel(red1, green0, blue1), tx),
                        mix(texel(red0, green1, blue1), texel(red1, green1, blue1), tx), ty), tz);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ColorGradingLut value
                && size == value.size && Arrays.equals(rgba8, value.rgba8);
    }

    @Override
    public int hashCode() {
        return 31 * size + Arrays.hashCode(rgba8);
    }

    private Rgb texel(int red, int green, int blue) {
        int offset = texelOffset(size, red, green, blue);
        return new Rgb(Byte.toUnsignedInt(rgba8[offset]) / 255.0f,
                Byte.toUnsignedInt(rgba8[offset + 1]) / 255.0f,
                Byte.toUnsignedInt(rgba8[offset + 2]) / 255.0f);
    }

    private static Rgb mix(Rgb left, Rgb right, float amount) {
        return new Rgb(left.red + (right.red - left.red) * amount,
                left.green + (right.green - left.green) * amount,
                left.blue + (right.blue - left.blue) * amount);
    }

    private static int texelOffset(int size, int red, int green, int blue) {
        int x = blue * size + red;
        return (green * size * size + x) * 4;
    }

    private static int requireSize(int size) {
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new IllegalArgumentException("LUT size must be within [" + MIN_SIZE + ", "
                    + MAX_SIZE + "]");
        }
        return size;
    }

    private static int requiredBytes(int size) {
        return Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(size, size), size), 4);
    }

    private static byte quantize(float value) {
        requireUnit(value, "mapped component");
        return (byte) Math.round(value * 255.0f);
    }

    private static void requireUnit(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be finite and within [0, 1]");
        }
    }

    @FunctionalInterface
    public interface RgbMapper {
        Rgb map(float red, float green, float blue);
    }

    public record Rgb(float red, float green, float blue) {
        public Rgb {
            requireUnit(red, "red");
            requireUnit(green, "green");
            requireUnit(blue, "blue");
        }
    }
}
