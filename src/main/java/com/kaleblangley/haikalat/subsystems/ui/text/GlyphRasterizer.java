package com.kaleblangley.haikalat.subsystems.ui.text;

/** update/text owner 线程使用的 glyph 光栅化入口。 */
@FunctionalInterface
public interface GlyphRasterizer {
    GlyphBitmap rasterize(GlyphKey key);
}
