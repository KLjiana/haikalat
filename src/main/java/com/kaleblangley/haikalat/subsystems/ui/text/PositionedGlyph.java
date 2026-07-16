package com.kaleblangley.haikalat.subsystems.ui.text;

import java.util.Objects;

/** 布局完成后可直接记录为 paint primitive 的 glyph 位置。 */
public record PositionedGlyph(
        FontFaceId faceId,
        int ppem,
        int glyphId,
        int clusterStartUtf16,
        int clusterEndUtf16,
        float x,
        float y,
        float advanceX,
        float advanceY) {

    public PositionedGlyph {
        Objects.requireNonNull(faceId, "faceId");
        if (ppem <= 0) {
            throw new IllegalArgumentException("ppem must be positive");
        }
        if (glyphId < 0) {
            throw new IllegalArgumentException("glyphId must be non-negative");
        }
        if (clusterStartUtf16 < 0 || clusterEndUtf16 <= clusterStartUtf16) {
            throw new IllegalArgumentException("Glyph cluster must be a non-empty UTF-16 range");
        }
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(advanceX, "advanceX");
        requireFinite(advanceY, "advanceY");
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
