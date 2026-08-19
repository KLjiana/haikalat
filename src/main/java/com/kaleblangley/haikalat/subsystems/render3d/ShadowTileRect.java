package com.kaleblangley.haikalat.subsystems.render3d;

/** One pixel viewport/scissor rectangle and its normalized atlas bounds. */
record ShadowTileRect(int x, int y, int width, int height,
                      float minU, float minV, float maxU, float maxV) {
    ShadowTileRect {
        if (x < 0 || y < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("shadow tile rectangle must be positive");
        }
        if (!Float.isFinite(minU) || !Float.isFinite(minV)
                || !Float.isFinite(maxU) || !Float.isFinite(maxV)
                || minU < 0.0f || minV < 0.0f || maxU > 1.0f || maxV > 1.0f
                || minU >= maxU || minV >= maxV) {
            throw new IllegalArgumentException("invalid normalized shadow tile bounds");
        }
    }
}
