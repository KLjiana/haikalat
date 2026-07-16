package com.kaleblangley.haikalat.subsystems.ui;

/** 最近一个已完成 UI frame 的不可变统计。所有耗时单位均为纳秒。 */
public record UiFrameStats(long visibleNodes, long layoutNodes, long layoutPasses,
                           long shapedRuns, long shapingCacheHits, long shapingCacheMisses,
                           long glyphAtlasHits, long glyphAtlasMisses, long glyphAtlasPages,
                           long glyphAtlasEvictions, long paintPrimitives, long quads,
                           long glyphs, long batches, long drawCalls, long vertexBytes,
                           long indexBytes, long atlasUploadBytes, long inputEvents,
                           long dispatchedEvents, long uiUpdateNanos, long layoutNanos,
                           long shapingNanos, long paintNanos, long renderRecordNanos,
                           long ringWaitNanos) {
    public static final UiFrameStats EMPTY = new UiFrameStats(0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public UiFrameStats {
        long[] values = {visibleNodes, layoutNodes, layoutPasses, shapedRuns, shapingCacheHits,
                shapingCacheMisses, glyphAtlasHits, glyphAtlasMisses, glyphAtlasPages,
                glyphAtlasEvictions, paintPrimitives, quads, glyphs, batches, drawCalls,
                vertexBytes, indexBytes, atlasUploadBytes, inputEvents, dispatchedEvents,
                uiUpdateNanos, layoutNanos, shapingNanos, paintNanos, renderRecordNanos,
                ringWaitNanos};
        for (long value : values) {
            if (value < 0L) throw new IllegalArgumentException("UI statistics cannot be negative");
        }
    }
}
