package com.kaleblangley.haikalat.core.mesh;

import java.util.Objects;

/**
 * 不可变的三维轴对齐包围盒。
 *
 * <p>{@link #unbounded()} 表示无法可靠裁剪的几何体。它是显式状态，不使用 NaN、无穷大或
 * 极大浮点数伪装，因此调用方可以始终采取保守可见策略。</p>
 */
public final class Bounds3f {
    private static final Bounds3f UNBOUNDED = new Bounds3f();

    private final boolean finite;
    private final float minX;
    private final float minY;
    private final float minZ;
    private final float maxX;
    private final float maxY;
    private final float maxZ;

    private Bounds3f() {
        finite = false;
        minX = minY = minZ = maxX = maxY = maxZ = 0.0f;
    }

    private Bounds3f(float minX, float minY, float minZ,
                     float maxX, float maxY, float maxZ) {
        requireFinite(minX, "minX");
        requireFinite(minY, "minY");
        requireFinite(minZ, "minZ");
        requireFinite(maxX, "maxX");
        requireFinite(maxY, "maxY");
        requireFinite(maxZ, "maxZ");
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("bounds minimum must not exceed maximum");
        }
        finite = true;
        this.minX = canonicalZero(minX);
        this.minY = canonicalZero(minY);
        this.minZ = canonicalZero(minZ);
        this.maxX = canonicalZero(maxX);
        this.maxY = canonicalZero(maxY);
        this.maxZ = canonicalZero(maxZ);
    }

    /** 创建有限包围盒；点、线、平面等退化范围同样合法。 */
    public static Bounds3f of(float minX, float minY, float minZ,
                              float maxX, float maxY, float maxZ) {
        return new Bounds3f(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** 返回无法可靠裁剪的显式包围盒。 */
    public static Bounds3f unbounded() {
        return UNBOUNDED;
    }

    public boolean isFinite() { return finite; }
    public boolean isUnbounded() { return !finite; }
    public float minX() { return minX; }
    public float minY() { return minY; }
    public float minZ() { return minZ; }
    public float maxX() { return maxX; }
    public float maxY() { return maxY; }
    public float maxZ() { return maxZ; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Bounds3f bounds) || finite != bounds.finite) return false;
        return !finite || Float.floatToIntBits(minX) == Float.floatToIntBits(bounds.minX)
                && Float.floatToIntBits(minY) == Float.floatToIntBits(bounds.minY)
                && Float.floatToIntBits(minZ) == Float.floatToIntBits(bounds.minZ)
                && Float.floatToIntBits(maxX) == Float.floatToIntBits(bounds.maxX)
                && Float.floatToIntBits(maxY) == Float.floatToIntBits(bounds.maxY)
                && Float.floatToIntBits(maxZ) == Float.floatToIntBits(bounds.maxZ);
    }

    @Override
    public int hashCode() {
        return finite ? Objects.hash(minX, minY, minZ, maxX, maxY, maxZ) : 31;
    }

    @Override
    public String toString() {
        return finite ? "Bounds3f[" + minX + ", " + minY + ", " + minZ + " -> "
                + maxX + ", " + maxY + ", " + maxZ + "]" : "Bounds3f[UNBOUNDED]";
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }

    private static float canonicalZero(float value) {
        return value == 0.0f ? 0.0f : value;
    }
}
