package com.kaleblangley.haikalat.subsystems.ui;

import com.kaleblangley.haikalat.subsystems.ui.render.UiDisplayList;
import com.kaleblangley.haikalat.subsystems.ui.render.UiRenderSnapshot;
import com.kaleblangley.haikalat.subsystems.ui.render.UiSnapshotExchange;
import com.kaleblangley.haikalat.subsystems.ui.text.GlyphUploadRequest;

import java.util.List;
import java.util.Objects;

/**
 * UI update → render 的 snapshot 发布边界。
 *
 * <p>序号只在这里分配，{@link UiSystem} 不再直接管理 exchange 的代次。
 * 该类保持 package-private，避免把交换器生命周期细节扩大到公共 API。</p>
 */
final class UiSnapshotPublisher implements AutoCloseable {
    private final UiSnapshotExchange exchange;
    private long sequence;

    UiSnapshotPublisher(int slotCount) {
        exchange = new UiSnapshotExchange(slotCount);
    }

    UiRenderSnapshot publish(int windowWidth, int windowHeight,
                             int framebufferWidth, int framebufferHeight,
                             double contentScaleX, double contentScaleY,
                             UiDisplayList displayList,
                             List<GlyphUploadRequest> glyphUploads)
            throws InterruptedException {
        Objects.requireNonNull(displayList, "displayList");
        Objects.requireNonNull(glyphUploads, "glyphUploads");
        return exchange.captureAndPublish(++sequence, windowWidth, windowHeight,
                framebufferWidth, framebufferHeight, contentScaleX, contentScaleY,
                displayList, glyphUploads);
    }

    UiSnapshotExchange.Lease tryAcquire() {
        return exchange.tryAcquire();
    }

    long publishedCount() {
        return exchange.publishedCount();
    }

    long droppedCount() {
        return exchange.droppedCount();
    }

    @Override
    public void close() {
        exchange.close();
    }
}
