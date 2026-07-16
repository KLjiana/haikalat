package com.kaleblangley.haikalat.subsystems.ui.text;

import java.util.Objects;
import java.util.Optional;

/**
 * 已成功上传并可进入 UI render snapshot 的 glyph atlas 数据。
 */
public record GlyphAtlasGlyph(
        GlyphKey key,
        long generation,
        Optional<GlyphAtlasPlacement> placement,
        int bearingX,
        int bearingY,
        float advanceX,
        float advanceY) {

    public GlyphAtlasGlyph {
        Objects.requireNonNull(key, "key");
        if (generation <= 0L) {
            throw new IllegalArgumentException("Atlas generation must be positive");
        }
        placement = Objects.requireNonNull(placement, "placement");
        if (!Float.isFinite(advanceX) || !Float.isFinite(advanceY)) {
            throw new IllegalArgumentException("Glyph advances must be finite");
        }
    }

    /** 返回该 glyph 是否包含需要绘制的 coverage。 */
    public boolean drawable() {
        return placement.isPresent();
    }
}
