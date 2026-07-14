package com.kaleblangley.haikalat.core.mesh;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Pure-JVM codec for the 16-byte std430 {@code uvec4} instance representation. */
public final class PackedInstanceLayout {
    public static final int STRIDE_BYTES = 4 * Integer.BYTES;

    private PackedInstanceLayout() {
    }

    public static ByteBuffer allocate(int instanceCount) {
        if (instanceCount < 0) throw new IllegalArgumentException("instanceCount must be non-negative");
        return ByteBuffer.allocateDirect(Math.multiplyExact(instanceCount, STRIDE_BYTES))
                .order(ByteOrder.nativeOrder());
    }

    public static void pack(ByteBuffer destination, int instanceIndex,
                            float translationX, float translationY, float translationZ,
                            float scale, float rotationCos, float rotationSin, int rgba8) {
        Objects.requireNonNull(destination, "destination").order(ByteOrder.nativeOrder());
        int offset = checkedOffset(destination, instanceIndex);
        destination.putInt(offset, packHalf2x16(translationX, translationY));
        destination.putInt(offset + 4, packHalf2x16(translationZ, scale));
        destination.putInt(offset + 8, packSnorm2x16(rotationCos, rotationSin));
        destination.putInt(offset + 12, rgba8);
    }

    public static PackedInstance unpack(ByteBuffer source, int instanceIndex) {
        Objects.requireNonNull(source, "source").order(ByteOrder.nativeOrder());
        int offset = checkedOffset(source, instanceIndex);
        float[] xy = unpackHalf2x16(source.getInt(offset));
        float[] zs = unpackHalf2x16(source.getInt(offset + 4));
        float[] rotation = unpackSnorm2x16(source.getInt(offset + 8));
        int color = source.getInt(offset + 12);
        return new PackedInstance(xy[0], xy[1], zs[0], zs[1],
                rotation[0], rotation[1], color);
    }

    public static int packHalf2x16(float low, float high) {
        return Short.toUnsignedInt(floatToHalf(low))
                | (Short.toUnsignedInt(floatToHalf(high)) << 16);
    }

    public static float[] unpackHalf2x16(int packed) {
        return new float[]{
                halfToFloat((short) packed),
                halfToFloat((short) (packed >>> 16))
        };
    }

    public static int packSnorm2x16(float low, float high) {
        return Short.toUnsignedInt(packSnorm16(low))
                | (Short.toUnsignedInt(packSnorm16(high)) << 16);
    }

    public static float[] unpackSnorm2x16(int packed) {
        return new float[]{unpackSnorm16((short) packed), unpackSnorm16((short) (packed >>> 16))};
    }

    /** 按 OpenGL unpackUnorm4x8 顺序打包 RGBA；R 占最低有效字节。 */
    public static int packRgba8(float red, float green, float blue, float alpha) {
        return packUnorm8(red)
                | (packUnorm8(green) << 8)
                | (packUnorm8(blue) << 16)
                | (packUnorm8(alpha) << 24);
    }

    public static float unpackRed(int rgba8) { return (rgba8 & 0xff) / 255.0f; }
    public static float unpackGreen(int rgba8) { return ((rgba8 >>> 8) & 0xff) / 255.0f; }
    public static float unpackBlue(int rgba8) { return ((rgba8 >>> 16) & 0xff) / 255.0f; }
    public static float unpackAlpha(int rgba8) { return ((rgba8 >>> 24) & 0xff) / 255.0f; }

    static short floatToHalf(float value) {
        int bits = Float.floatToRawIntBits(value);
        int sign = (bits >>> 16) & 0x8000;
        int magnitude = bits & 0x7fff_ffff;
        int rounded = magnitude + 0x1000;
        if (rounded >= 0x4780_0000) {
            if (magnitude >= 0x4780_0000) {
                if (rounded < 0x7f80_0000) return (short) (sign | 0x7c00);
                return (short) (sign | 0x7c00 | ((magnitude & 0x007f_ffff) >>> 13));
            }
            return (short) (sign | 0x7bff);
        }
        if (rounded >= 0x3880_0000) return (short) (sign | ((rounded - 0x3800_0000) >>> 13));
        if (rounded < 0x3300_0000) return (short) sign;
        int exponent = magnitude >>> 23;
        int mantissa = (magnitude & 0x007f_ffff) | 0x0080_0000;
        int shift = 126 - exponent;
        return (short) (sign | ((mantissa + (0x0080_0000 >>> (shift + 1))) >>> shift));
    }

    static float halfToFloat(short half) {
        int value = Short.toUnsignedInt(half);
        int sign = (value & 0x8000) << 16;
        int exponent = (value >>> 10) & 0x1f;
        int mantissa = value & 0x03ff;
        int bits;
        if (exponent == 0) {
            if (mantissa == 0) return Float.intBitsToFloat(sign);
            int adjustedExponent = 127 - 15 + 1;
            while ((mantissa & 0x0400) == 0) {
                mantissa <<= 1;
                adjustedExponent--;
            }
            mantissa &= 0x03ff;
            bits = sign | (adjustedExponent << 23) | (mantissa << 13);
        } else if (exponent == 0x1f) {
            bits = sign | 0x7f80_0000 | (mantissa << 13);
        } else {
            bits = sign | ((exponent + 127 - 15) << 23) | (mantissa << 13);
        }
        return Float.intBitsToFloat(bits);
    }

    private static short packSnorm16(float value) {
        float clamped = Math.max(-1.0f, Math.min(1.0f, value));
        return (short) Math.round(clamped * 32767.0f);
    }

    private static float unpackSnorm16(short value) {
        return Math.max(-1.0f, value / 32767.0f);
    }

    private static int packUnorm8(float value) {
        return Math.round(Math.max(0.0f, Math.min(1.0f, value)) * 255.0f);
    }

    private static int checkedOffset(ByteBuffer buffer, int instanceIndex) {
        if (instanceIndex < 0) throw new IllegalArgumentException("instanceIndex must be non-negative");
        int offset = Math.multiplyExact(instanceIndex, STRIDE_BYTES);
        if (offset > buffer.capacity() - STRIDE_BYTES) {
            throw new IndexOutOfBoundsException("packed instance exceeds buffer capacity");
        }
        return offset;
    }

    public record PackedInstance(float translationX, float translationY, float translationZ,
                                 float scale, float rotationCos, float rotationSin, int rgba8) {
    }
}
