package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.subsystems.ui.text.GlyphUploadRequest;

import java.util.List;
import java.util.Objects;

/**
 * UI/update 线程发布给 render thread 的不可变渲染快照。
 *
 * <p>快照持有紧凑冻结 display list 和与其对应的 batch，不引用可变 builder arena。</p>
 */
public final class UiRenderSnapshot {
    private final long sequence;
    private final int windowWidth;
    private final int windowHeight;
    private final int framebufferWidth;
    private final int framebufferHeight;
    private final double contentScaleX;
    private final double contentScaleY;
    private final UiDisplayList displayList;
    private final UiBatcher.Result batches;
    private final List<GlyphUploadRequest> glyphUploads;

    private UiRenderSnapshot(long sequence,
                             int windowWidth, int windowHeight,
                             int framebufferWidth, int framebufferHeight,
                             double contentScaleX, double contentScaleY,
                             UiDisplayList displayList, UiBatcher.Result batches,
                             List<GlyphUploadRequest> glyphUploads) {
        this.sequence = sequence;
        this.windowWidth = windowWidth;
        this.windowHeight = windowHeight;
        this.framebufferWidth = framebufferWidth;
        this.framebufferHeight = framebufferHeight;
        this.contentScaleX = contentScaleX;
        this.contentScaleY = contentScaleY;
        this.displayList = displayList;
        this.batches = batches;
        this.glyphUploads = glyphUploads;
    }

    /**
     * 冻结 display list 并立即生成 batch。
     *
     * @param sequence UI 帧序号，必须非负
     * @param windowWidth 窗口逻辑宽度
     * @param windowHeight 窗口逻辑高度
     * @param framebufferWidth framebuffer 宽度，可在最小化时为零
     * @param framebufferHeight framebuffer 高度，可在最小化时为零
     * @param contentScaleX 水平内容缩放
     * @param contentScaleY 垂直内容缩放
     * @param displayList 待冻结的完整 display list
     * @param batcher 可复用 batcher
     * @return 不可变渲染快照
     */
    public static UiRenderSnapshot capture(long sequence,
                                            int windowWidth, int windowHeight,
                                            int framebufferWidth, int framebufferHeight,
                                            double contentScaleX, double contentScaleY,
                                            UiDisplayList displayList, UiBatcher batcher) {
        return capture(sequence, windowWidth, windowHeight, framebufferWidth, framebufferHeight,
                contentScaleX, contentScaleY, displayList, batcher, List.of());
    }

    /**
     * 冻结 display list、batch 和本帧稳定 glyph upload 请求。
     */
    public static UiRenderSnapshot capture(long sequence,
                                           int windowWidth, int windowHeight,
                                           int framebufferWidth, int framebufferHeight,
                                           double contentScaleX, double contentScaleY,
                                           UiDisplayList displayList, UiBatcher batcher,
                                           List<GlyphUploadRequest> glyphUploads) {
        validateMetadata(sequence, windowWidth, windowHeight, framebufferWidth,
                framebufferHeight, contentScaleX, contentScaleY);
        UiDisplayList frozenList = Objects.requireNonNull(displayList, "displayList").freeze();
        return assemble(sequence, windowWidth, windowHeight, framebufferWidth,
                framebufferHeight, contentScaleX, contentScaleY, frozenList,
                Objects.requireNonNull(batcher, "batcher"), glyphUploads);
    }

    /** 把 builder 复制进交换槽拥有的只读 arena，并创建该槽的新快照 header。 */
    static UiRenderSnapshot captureInto(long sequence,
                                        int windowWidth, int windowHeight,
                                        int framebufferWidth, int framebufferHeight,
                                        double contentScaleX, double contentScaleY,
                                        UiDisplayList displayList, UiDisplayList slotArena,
                                        UiBatcher slotBatcher,
                                        List<GlyphUploadRequest> glyphUploads) {
        validateMetadata(sequence, windowWidth, windowHeight, framebufferWidth,
                framebufferHeight, contentScaleX, contentScaleY);
        Objects.requireNonNull(slotArena, "slotArena").replaceSnapshotFrom(
                Objects.requireNonNull(displayList, "displayList"));
        return assemble(sequence, windowWidth, windowHeight, framebufferWidth,
                framebufferHeight, contentScaleX, contentScaleY, slotArena,
                Objects.requireNonNull(slotBatcher, "slotBatcher"), glyphUploads);
    }

    private static UiRenderSnapshot assemble(long sequence,
                                             int windowWidth, int windowHeight,
                                             int framebufferWidth, int framebufferHeight,
                                             double contentScaleX, double contentScaleY,
                                             UiDisplayList frozenList, UiBatcher batcher,
                                             List<GlyphUploadRequest> glyphUploads) {
        UiBatcher.Result frozenBatches = batcher.batch(frozenList);
        List<GlyphUploadRequest> frozenUploads = List.copyOf(
                Objects.requireNonNull(glyphUploads, "glyphUploads"));
        return new UiRenderSnapshot(sequence, windowWidth, windowHeight,
                framebufferWidth, framebufferHeight, contentScaleX, contentScaleY,
                frozenList, frozenBatches, frozenUploads);
    }

    private static void validateMetadata(long sequence,
                                         int windowWidth, int windowHeight,
                                         int framebufferWidth, int framebufferHeight,
                                         double contentScaleX, double contentScaleY) {
        if (sequence < 0) {
            throw new IllegalArgumentException("snapshot sequence must be non-negative");
        }
        if (windowWidth < 0 || windowHeight < 0
                || framebufferWidth < 0 || framebufferHeight < 0) {
            throw new IllegalArgumentException("snapshot dimensions must be non-negative");
        }
        if (!Double.isFinite(contentScaleX) || !Double.isFinite(contentScaleY)
                || contentScaleX <= 0.0 || contentScaleY <= 0.0) {
            throw new IllegalArgumentException("content scale must be finite and positive");
        }
    }

    /**
     * 创建不带窗口尺寸元数据的纯 JVM 快照，主要用于交换器和数据层测试。
     *
     * @param sequence UI 帧序号
     * @param displayList 待冻结 display list
     * @return 不可变渲染快照
     */
    public static UiRenderSnapshot capture(long sequence, UiDisplayList displayList) {
        return capture(sequence, 0, 0, 0, 0, 1.0, 1.0,
                displayList, new UiBatcher());
    }

    /** 返回 UI 帧序号。 */
    public long sequence() {
        return sequence;
    }

    /** 返回逻辑窗口宽度。 */
    public int windowWidth() {
        return windowWidth;
    }

    /** 返回逻辑窗口高度。 */
    public int windowHeight() {
        return windowHeight;
    }

    /** 返回 framebuffer 宽度。 */
    public int framebufferWidth() {
        return framebufferWidth;
    }

    /** 返回 framebuffer 高度。 */
    public int framebufferHeight() {
        return framebufferHeight;
    }

    /** 返回水平内容缩放。 */
    public double contentScaleX() {
        return contentScaleX;
    }

    /** 返回垂直内容缩放。 */
    public double contentScaleY() {
        return contentScaleY;
    }

    /**
     * 返回逻辑 UI 坐标到 framebuffer 像素的水平比例。
     *
     * <p>该值刻意由两组尺寸计算，不能用 monitor content scale 替代；Windows 在部分 DPI
     * 模式下会报告 1.25 等内容缩放，但窗口与 framebuffer 仍保持相同像素尺寸。</p>
     */
    public double framebufferScaleX() {
        return windowWidth == 0 ? 1.0 : (double) framebufferWidth / windowWidth;
    }

    /** 返回逻辑 UI 坐标到 framebuffer 像素的垂直比例。 */
    public double framebufferScaleY() {
        return windowHeight == 0 ? 1.0 : (double) framebufferHeight / windowHeight;
    }

    /** 返回只读 display list。 */
    public UiDisplayList displayList() {
        return displayList;
    }

    /** 返回不可变 batch 结果。 */
    public UiBatcher.Result batches() {
        return batches;
    }

    /** 返回 update thread 为本帧发布的稳定 glyph upload 请求。 */
    public List<GlyphUploadRequest> glyphUploads() {
        return glyphUploads;
    }
}
