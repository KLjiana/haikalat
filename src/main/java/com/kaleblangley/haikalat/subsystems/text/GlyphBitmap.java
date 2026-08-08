package com.kaleblangley.haikalat.subsystems.text;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * FreeType 光栅化后、等待 render thread 上传的不可变紧密 R8 coverage。
 */
public final class GlyphBitmap {
    private final GlyphKey key;
    private final int width;
    private final int height;
    private final int bearingX;
    private final int bearingY;
    private final float advanceX;
    private final float advanceY;
    private final byte[] coverage;

    public GlyphBitmap(GlyphKey key, int width, int height, int bearingX, int bearingY,
                       float advanceX, float advanceY, byte[] coverage) {
        this.key = Objects.requireNonNull(key, "key");
        this.width = requireNonNegative(width, "width");
        this.height = requireNonNegative(height, "height");
        this.bearingX = bearingX;
        this.bearingY = bearingY;
        this.advanceX = requireFinite(advanceX, "advanceX");
        this.advanceY = requireFinite(advanceY, "advanceY");
        int requiredBytes = Math.multiplyExact(width, height);
        byte[] source = Objects.requireNonNull(coverage, "coverage");
        if (source.length != requiredBytes) {
            throw new IllegalArgumentException(
                    "R8 coverage length " + source.length + " does not match " + width + "x" + height);
        }
        this.coverage = source.clone();
    }

    public GlyphBitmap(GlyphKey key, int width, int height, int bearingX, int bearingY,
                       float advanceX, float advanceY, ByteBuffer coverage) {
        this(key, width, height, bearingX, bearingY, advanceX, advanceY,
                copyRemaining(Objects.requireNonNull(coverage, "coverage")));
    }

    public GlyphKey key() {
        return key;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int bearingX() {
        return bearingX;
    }

    public int bearingY() {
        return bearingY;
    }

    public float advanceX() {
        return advanceX;
    }

    public float advanceY() {
        return advanceY;
    }

    /** 返回独立只读视图，调用方不能修改内部 coverage。 */
    public ByteBuffer coverage() {
        return ByteBuffer.wrap(coverage).asReadOnlyBuffer();
    }

    /** 返回 coverage 的防御性副本。 */
    public byte[] copyCoverage() {
        return coverage.clone();
    }

    /** 返回上传字节数。 */
    public int uploadByteCount() {
        return coverage.length;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof GlyphBitmap other)) {
            return false;
        }
        return width == other.width && height == other.height
                && bearingX == other.bearingX && bearingY == other.bearingY
                && Float.compare(advanceX, other.advanceX) == 0
                && Float.compare(advanceY, other.advanceY) == 0
                && key.equals(other.key) && Arrays.equals(coverage, other.coverage);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(key, width, height, bearingX, bearingY, advanceX, advanceY);
        return 31 * result + Arrays.hashCode(coverage);
    }

    @Override
    public String toString() {
        return "GlyphBitmap[key=" + key + ", width=" + width + ", height=" + height
                + ", bearingX=" + bearingX + ", bearingY=" + bearingY
                + ", advanceX=" + advanceX + ", advanceY=" + advanceY + ']';
    }

    private static byte[] copyRemaining(ByteBuffer source) {
        ByteBuffer copy = source.duplicate();
        byte[] result = new byte[copy.remaining()];
        copy.get(result);
        return result;
    }

    private static int requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }

    private static float requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value;
    }
}
