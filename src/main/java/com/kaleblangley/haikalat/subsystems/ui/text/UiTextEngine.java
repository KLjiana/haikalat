package com.kaleblangley.haikalat.subsystems.ui.text;

import com.kaleblangley.haikalat.subsystems.text.BundledFonts;
import com.kaleblangley.haikalat.subsystems.text.GlyphAtlasGenerationLease;
import com.kaleblangley.haikalat.subsystems.text.GlyphAtlasGlyph;
import com.kaleblangley.haikalat.subsystems.text.GlyphAtlasPlacement;
import com.kaleblangley.haikalat.subsystems.text.GlyphAtlasStatistics;
import com.kaleblangley.haikalat.subsystems.text.GlyphKey;
import com.kaleblangley.haikalat.subsystems.text.GlyphUploadRequest;
import com.kaleblangley.haikalat.subsystems.text.PositionedGlyph;
import com.kaleblangley.haikalat.subsystems.text.ShapingCache;
import com.kaleblangley.haikalat.subsystems.text.TextAlignment;
import com.kaleblangley.haikalat.subsystems.text.TextLayout;
import com.kaleblangley.haikalat.subsystems.text.TextLine;
import com.kaleblangley.haikalat.subsystems.text.TextSystem;
import com.kaleblangley.haikalat.subsystems.text.TextWrapMode;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.layout.MeasureContext;
import com.kaleblangley.haikalat.subsystems.ui.layout.MeasureResult;
import com.kaleblangley.haikalat.subsystems.ui.render.UiBlendMode;
import com.kaleblangley.haikalat.subsystems.ui.render.UiDisplayList;
import com.kaleblangley.haikalat.subsystems.ui.render.UiGlyphPainter;
import com.kaleblangley.haikalat.subsystems.ui.render.UiScreenRect;
import com.kaleblangley.haikalat.subsystems.ui.render.UiTextLineMetrics;
import com.kaleblangley.haikalat.subsystems.ui.render.UiUvRect;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.ui.widget.TextField;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * UI update 线程拥有的字体、shaping、文本布局和 CPU glyph atlas 闭环。
 *
 * <p>本类不创建或调用 OpenGL。atlas miss 只产生不可变 {@link GlyphUploadRequest}，
 * placement 在 render thread 报告整批上传成功前不会进入 display list。</p>
 */
public final class UiTextEngine implements UiGlyphPainter, AutoCloseable {
    public static final String BUNDLED_FONT_RESOURCE = BundledFonts.NOTO_SANS_SC_RESOURCE;
    public static final String DEFAULT_FONT_FAMILY = BundledFonts.NOTO_SANS_SC_FAMILY;
    public static final String UNIFONT_FONT_FAMILY = BundledFonts.UNIFONT_FAMILY;
    public static final String MONOSPACE_FONT_FAMILY = BundledFonts.JETBRAINS_MONO_FAMILY;
    private static final int ATLAS_PADDING = (int) TextEffect.MAXIMUM_EXTENT;
    private static final int LOGICAL_GLYPH_SAMPLER = 0;

    private final TextSystem text;
    private float contentScale = 1.0f;
    private long frameShapedRuns;
    private long frameGlyphs;
    private long frameShapingNanos;
    private GlyphAtlasGlyph[] resolvedGlyphs = new GlyphAtlasGlyph[64];
    private boolean closed;

    private UiTextEngine(TextSystem text) {
        this.text = Objects.requireNonNull(text, "text");
    }

    /** 从统一内建字体目录创建完整文本服务。 */
    public static UiTextEngine createBundled(int atlasWidth, int atlasHeight,
                                             int maximumAtlasPages) {
        return new UiTextEngine(TextSystem.createBundled(atlasWidth, atlasHeight,
                ATLAS_PADDING, maximumAtlasPages));
    }

    /** 开始一帧 update，并选择实际 raster ppem 使用的 DPI 缩放。 */
    public void beginFrame(double contentScaleX, double contentScaleY) {
        ensureOpen();
        if (!Double.isFinite(contentScaleX) || !Double.isFinite(contentScaleY)
                || contentScaleX <= 0.0 || contentScaleY <= 0.0) {
            throw new IllegalArgumentException("content scale must be finite and positive");
        }
        contentScale = (float) Math.max(contentScaleX, contentScaleY);
        frameShapedRuns = 0L;
        frameGlyphs = 0L;
        frameShapingNanos = 0L;
    }

    /** 按注册顺序返回可供 UI 选择的字体族。 */
    public List<String> fontFamilies() {
        ensureOpen();
        return text.fontFamilies();
    }

    /** 返回当前作为 fallback chain 首选项的字体族。 */
    public String activeFontFamily() {
        ensureOpen();
        return text.activeFontFamily();
    }

    /** 从文件注册一个运行时字体；字体数据会由共享 {@link TextSystem} 独立持有。 */
    public void registerFont(String familyName, Path path) throws IOException {
        ensureOpen();
        text.registerFont(familyName, path);
    }

    /** 从内存注册 face index 0 的运行时字体。 */
    public void registerFont(String familyName, byte[] fontData) {
        registerFont(familyName, fontData, 0);
    }

    /**
     * 从内存注册运行时字体。新增字体同时成为现有首选字体的 fallback，且不会清空 glyph atlas。
     */
    public void registerFont(String familyName, byte[] fontData, int faceIndex) {
        ensureOpen();
        text.registerFont(familyName, fontData, faceIndex);
    }

    /**
     * 切换全局 UI 首选字体；其他已注册字体继续参与缺字 fallback。
     *
     * @return 字体是否发生变化
     */
    public boolean selectFontFamily(String familyName) {
        ensureOpen();
        return text.selectFontFamily(familyName);
    }

    /** Yoga 固有尺寸入口；非文本节点仍使用节点自己的 measure 合同。 */
    public MeasureResult measure(UiNode node, MeasureContext context) {
        ensureOpen();
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(context, "context");
        String text = displayedText(node);
        if (text == null) return node.measure(context);

        float available = context.widthMode() == MeasureContext.Mode.UNDEFINED
                ? Float.POSITIVE_INFINITY : context.availableWidth();
        TextLayout layout = layout(node, text, available);
        float width = contentWidth(layout) / contentScale;
        float height = layout.height() / contentScale;
        if (context.widthMode() != MeasureContext.Mode.UNDEFINED) {
            width = Math.min(width, context.availableWidth());
        }
        return new MeasureResult(width, height);
    }

    /**
     * 把已经 READY 的 glyph 记录为按 atlas page 分组的相邻 run。
     * 任一 glyph 尚待上传时返回 false，让 painter 在本帧使用确定性占位字形。
     */
    @Override
    public boolean paint(UiDisplayList displayList, UiNode node, String text,
                         UiScreenRect bounds, int premultipliedRgba8) {
        ensureOpen();
        Objects.requireNonNull(displayList, "displayList");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(bounds, "bounds");
        if (text.isEmpty()) return true;

        TextLayout layout = layout(node, text, (float) bounds.width());
        ensureResolvedGlyphCapacity(layout.glyphCount());
        int resolvedCount = 0;
        boolean allReady = true;
        for (TextLine line : layout.lines()) {
            for (PositionedGlyph positioned : line.glyphs()) {
                GlyphKey key = positioned.glyphKey();
                GlyphAtlasGlyph glyph = this.text.resolveGlyph(key);
                resolvedGlyphs[resolvedCount++] = glyph;
                if (glyph == null) {
                    allReady = false;
                }
            }
        }
        if (!allReady) {
            Arrays.fill(resolvedGlyphs, 0, resolvedCount, null);
            return false;
        }

        double logicalLayoutHeight = layout.height() / contentScale;
        double originY = bounds.y() + Math.max(0.0,
                (bounds.height() - logicalLayoutHeight) * 0.5);
        boolean paddedGlyphs = usesEffectPadding(node);
        int activePage = -1;
        boolean runOpen = false;
        try {
            int glyphIndex = 0;
            for (TextLine line : layout.lines()) {
                for (PositionedGlyph positioned : line.glyphs()) {
                GlyphAtlasGlyph glyph = resolvedGlyphs[glyphIndex++];
                if (!glyph.drawable()) continue;
                GlyphAtlasPlacement placement = glyph.placement().orElseThrow();
                if (placement.pageIndex() != activePage) {
                    if (runOpen) displayList.endGlyphRun();
                    activePage = placement.pageIndex();
                    displayList.beginGlyphRun(activePage, LOGICAL_GLYPH_SAMPLER,
                            UiBlendMode.PREMULTIPLIED_ALPHA);
                    runOpen = true;
                }
                double x = bounds.x() + (positioned.x() + glyph.bearingX()) / contentScale;
                double y = originY + (positioned.y() - glyph.bearingY()) / contentScale;
                if (paddedGlyphs) {
                    double padding = (double) placement.padding() / contentScale;
                    displayList.addGlyph(x - padding, y - padding,
                            (double) placement.allocatedWidth() / contentScale,
                            (double) placement.allocatedHeight() / contentScale,
                            placement.allocatedU0(), placement.allocatedV0(),
                            placement.allocatedU1(), placement.allocatedV1(),
                            premultipliedRgba8);
                } else {
                    displayList.addGlyph(x, y,
                            (double) placement.width() / contentScale,
                            (double) placement.height() / contentScale,
                            placement.u0(), placement.v0(), placement.u1(), placement.v1(),
                            premultipliedRgba8);
                }
                frameGlyphs++;
                }
            }
            if (runOpen) displayList.endGlyphRun();
            return true;
        } catch (RuntimeException | Error failure) {
            if (runOpen) displayList.abortGlyphRun();
            throw failure;
        } finally {
            Arrays.fill(resolvedGlyphs, 0, resolvedCount, null);
        }
    }

    private void ensureResolvedGlyphCapacity(int required) {
        if (required <= resolvedGlyphs.length) return;
        int capacity = Math.max(required, resolvedGlyphs.length + (resolvedGlyphs.length >> 1));
        resolvedGlyphs = Arrays.copyOf(resolvedGlyphs, capacity);
    }

    private static boolean usesEffectPadding(UiNode node) {
        if (!(node instanceof Label label)) return false;
        return switch (label.textEffect().type()) {
            case OUTLINE, DROP_SHADOW, GLOW -> true;
            case NONE, INNER_GLOW, GRADIENT -> false;
        };
    }

    @Override
    public UiTextLineMetrics measureLine(UiNode node, String text) {
        ensureOpen();
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(text, "text");
        TextLayout measured = layout(node, text, Float.POSITIVE_INFINITY);
        java.util.TreeMap<Integer, Double> positions = new java.util.TreeMap<>();
        positions.put(0, 0.0);
        double width = 0.0;
        for (TextLine line : measured.lines()) {
            width = Math.max(width, line.width() / contentScale);
            for (PositionedGlyph glyph : line.glyphs()) {
                double start = glyph.x() / contentScale;
                double end = (glyph.x() + glyph.advanceX()) / contentScale;
                positions.merge(glyph.clusterStartUtf16(), start, Math::min);
                positions.merge(glyph.clusterEndUtf16(), end, Math::max);
            }
        }
        positions.putIfAbsent(text.length(), width);
        List<UiTextLineMetrics.CaretStop> stops = positions.entrySet().stream()
                .map(entry -> new UiTextLineMetrics.CaretStop(entry.getKey(),
                        Math.max(0.0, entry.getValue())))
                .toList();
        return new UiTextLineMetrics(text, stops, width);
    }

    /** 返回当前全部待 render thread 上传的稳定请求。 */
    public List<GlyphUploadRequest> pendingUploads() {
        ensureOpen();
        return text.pendingUploads();
    }

    /** 发布 render thread 已完整执行并确认成功的单个请求。 */
    public void publishUpload(GlyphUploadRequest request) {
        ensureOpen();
        text.publishUpload(request);
    }

    /** 保留失败请求及 placement，供后续帧原样重试。 */
    public void uploadFailed(GlyphUploadRequest request) {
        ensureOpen();
        text.uploadFailed(request);
    }

    public GlyphAtlasGenerationLease acquireAtlasGeneration() {
        ensureOpen();
        return text.acquireAtlasGeneration();
    }

    public ShapingCache.Statistics shapingStatistics() {
        ensureOpen();
        return text.shapingStatistics();
    }

    public GlyphAtlasStatistics atlasStatistics() {
        ensureOpen();
        return text.atlasStatistics();
    }

    public long frameShapedRuns() {
        return frameShapedRuns;
    }

    public long frameGlyphs() {
        return frameGlyphs;
    }

    /** 返回本帧 cache miss 实际执行 shaping 与文本布局的累计纳秒数。 */
    public long frameShapingNanos() {
        return frameShapingNanos;
    }

    public long layoutCacheHits() {
        ensureOpen();
        return text.layoutStatistics().cacheHits();
    }

    public long layoutCacheMisses() {
        ensureOpen();
        return text.layoutStatistics().cacheMisses();
    }

    @Override
    public void close() {
        if (closed) return;
        text.close();
        closed = true;
    }

    private TextLayout layout(UiNode node, String text, float logicalAvailableWidth) {
        int ppem = Math.max(1, Math.round(node.computedStyle().fontSize() * contentScale));
        float physicalWidth = Float.isInfinite(logicalAvailableWidth)
                ? Float.POSITIVE_INFINITY
                : Math.max(0.0f, logicalAvailableWidth * contentScale);
        LayoutMode mode = layoutMode(node);
        TextAlignment alignment = alignment(node);
        int maximumLines = node instanceof Label label ? label.maximumLines() : 1;
        boolean ellipsis = node instanceof Label label && label.ellipsis();
        TextWrapMode wrapMode = node instanceof Label label
                && label.wrap() == Label.Wrap.GRAPHEME
                ? TextWrapMode.GRAPHEME : TextWrapMode.LINE_BREAK;
        String familyName = effectiveFamily(node.computedStyle().fontFamily());
        TextSystem.LayoutStatistics before = this.text.layoutStatistics();
        TextLayout result = mode == LayoutMode.SINGLE_LINE
                ? this.text.layoutSingleLine(familyName, ppem, text, physicalWidth,
                        alignment, ellipsis)
                : this.text.layout(familyName, ppem, text, physicalWidth, alignment,
                        maximumLines, ellipsis, wrapMode);
        TextSystem.LayoutStatistics after = this.text.layoutStatistics();
        frameShapingNanos += after.layoutNanos() - before.layoutNanos();
        frameShapedRuns += after.shapedRuns() - before.shapedRuns();
        return result;
    }

    /** Maps the theme's default family to the currently selected UI family. */
    private String effectiveFamily(String requestedFamily) {
        return DEFAULT_FONT_FAMILY.equals(requestedFamily)
                ? text.activeFontFamily() : requestedFamily;
    }

    private static LayoutMode layoutMode(UiNode node) {
        if (node instanceof Label label && label.wrap() != Label.Wrap.NONE) {
            return LayoutMode.MULTI_LINE;
        }
        return LayoutMode.SINGLE_LINE;
    }

    private static TextAlignment alignment(UiNode node) {
        if (!(node instanceof Label label)) return TextAlignment.START;
        return switch (label.alignment()) {
            case START -> TextAlignment.START;
            case CENTER -> TextAlignment.CENTER;
            case END -> TextAlignment.END;
        };
    }

    private static String displayedText(UiNode node) {
        if (node instanceof Label label) return label.text();
        if (!(node instanceof TextField field)) return null;
        if (field.value().isEmpty() && field.composition() == null) return field.placeholder();
        String value = field.password()
                ? "\u2022".repeat(field.value().codePointCount(0, field.value().length()))
                : field.value();
        if (!field.password() && field.composition() != null) {
            value += field.composition().text();
        }
        return value;
    }

    private static float contentWidth(TextLayout layout) {
        float result = 0.0f;
        for (TextLine line : layout.lines()) result = Math.max(result, line.width());
        return result;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("UiTextEngine is closed");
    }

    private enum LayoutMode { SINGLE_LINE, MULTI_LINE }

}
