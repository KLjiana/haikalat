package com.kaleblangley.haikalat.subsystems.text;

/** atlas 已达到 page 上限且没有可容纳目标 glyph 的区域。 */
public final class GlyphAtlasFullException extends IllegalStateException {
    public GlyphAtlasFullException(String message) {
        super(message);
    }
}
