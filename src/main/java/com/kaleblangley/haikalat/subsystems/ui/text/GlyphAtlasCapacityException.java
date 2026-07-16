package com.kaleblangley.haikalat.subsystems.ui.text;

/** glyph 连同 padding 永久无法放入单个 atlas page。 */
public final class GlyphAtlasCapacityException extends IllegalArgumentException {
    public GlyphAtlasCapacityException(String message) {
        super(message);
    }
}
