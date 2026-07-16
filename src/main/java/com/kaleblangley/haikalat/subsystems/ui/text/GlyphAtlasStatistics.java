package com.kaleblangley.haikalat.subsystems.ui.text;

/** update 侧 glyph atlas 累计统计快照。 */
public record GlyphAtlasStatistics(
        long hits,
        long misses,
        int pages,
        long evictions,
        long uploadBytes,
        int readyGlyphs,
        int pendingUploads,
        int activeGenerationLeases) {

    public GlyphAtlasStatistics {
        if (hits < 0L || misses < 0L || pages < 0 || evictions < 0L || uploadBytes < 0L
                || readyGlyphs < 0 || pendingUploads < 0 || activeGenerationLeases < 0) {
            throw new IllegalArgumentException("Glyph atlas statistics must be non-negative");
        }
    }
}
