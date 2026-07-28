package com.kaleblangley.haikalat.subsystems.ui;

import java.util.Objects;

/**
 * UI update/render 线程之间的诊断快照。
 *
 * <p>更新线程只发布完整的 {@link UiFrameStats}，render 线程只写入自己的耗时和
 * draw-call 计数；读取时再合并，避免 {@code UiSystem} 同时承担统计状态管理。</p>
 */
final class UiDiagnostics {
    private volatile UiFrameStats update = UiFrameStats.EMPTY;
    private volatile long renderRecordNanos;
    private volatile long renderedDrawCalls;

    void recordUpdate(UiFrameStats value) {
        update = Objects.requireNonNull(value, "value");
    }

    void recordRender(long recordNanos, long drawCalls) {
        if (recordNanos < 0L || drawCalls < 0L) {
            throw new IllegalArgumentException("UI render diagnostics cannot be negative");
        }
        renderRecordNanos = recordNanos;
        renderedDrawCalls = drawCalls;
    }

    long renderRecordNanos() {
        return renderRecordNanos;
    }

    long renderedDrawCalls() {
        return renderedDrawCalls;
    }

    UiFrameStats snapshot(long ringWaitNanos) {
        UiFrameStats value = update;
        return new UiFrameStats(value.visibleNodes(), value.layoutNodes(), value.layoutPasses(),
                value.shapedRuns(), value.shapingCacheHits(), value.shapingCacheMisses(),
                value.glyphAtlasHits(), value.glyphAtlasMisses(), value.glyphAtlasPages(),
                value.glyphAtlasEvictions(), value.paintPrimitives(), value.quads(), value.glyphs(),
                value.batches(), renderedDrawCalls, value.vertexBytes(), value.indexBytes(),
                value.atlasUploadBytes(), value.inputEvents(), value.dispatchedEvents(),
                value.uiUpdateNanos(), value.layoutNanos(), value.shapingNanos(), value.paintNanos(),
                renderRecordNanos, ringWaitNanos, value.batchBreaks());
    }
}
