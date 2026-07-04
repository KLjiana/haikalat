package com.kaleblangley.haikalat.core.mesh;

public final class VertexPacking {
    private VertexPacking() {
    }

    public static int packOctNormal(float x, float y, float z) {
        float invL1 = 1.0f / (Math.abs(x) + Math.abs(y) + Math.abs(z));
        x *= invL1;
        y *= invL1;
        z *= invL1;

        if (z < 0.0f) {
            float oldX = x;
            x = (1.0f - Math.abs(y)) * Math.copySign(1.0f, oldX);
            y = (1.0f - Math.abs(oldX)) * Math.copySign(1.0f, y);
        }

        int nx = Math.round((x * 0.5f + 0.5f) * 65535.0f);
        int ny = Math.round((y * 0.5f + 0.5f) * 65535.0f);
        return (nx << 16) | (ny & 0xFFFF);
    }

    public static float[] unpackOctNormal(int packed) {
        float x = ((packed >>> 16) & 0xFFFF) / 65535.0f * 2.0f - 1.0f;
        float y = (packed & 0xFFFF) / 65535.0f * 2.0f - 1.0f;
        float z = 1.0f - Math.abs(x) - Math.abs(y);

        if (z < 0.0f) {
            float oldX = x;
            x = (1.0f - Math.abs(y)) * Math.copySign(1.0f, oldX);
            y = (1.0f - Math.abs(oldX)) * Math.copySign(1.0f, y);
            z = 1.0f - Math.abs(x) - Math.abs(y);
        }

        float invLength = 1.0f / (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x * invLength, y * invLength, z * invLength};
    }

    public static int packNormalizedU16(float value) {
        float clamped = Math.max(0.0f, Math.min(1.0f, value));
        return Math.round(clamped * 65535.0f) & 0xFFFF;
    }

    public static float unpackNormalizedU16(int packed) {
        return (packed & 0xFFFF) / 65535.0f;
    }
}
