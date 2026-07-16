package com.kaleblangley.haikalat.subsystems.ui.text;

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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * UI update 线程拥有的字体、shaping、文本布局和 CPU glyph atlas 闭环。
 *
 * <p>本类不创建或调用 OpenGL。atlas miss 只产生不可变 {@link GlyphUploadRequest}，
 * placement 在 render thread 报告整批上传成功前不会进入 display list。</p>
 */
public final class UiTextEngine implements UiGlyphPainter, AutoCloseable {
    public static final String BUNDLED_FONT_RESOURCE = "/ui/fonts/NotoSansSC-VF.ttf";
    public static final String DEFAULT_FONT_FAMILY = "Noto Sans SC";
    private static final int ATLAS_PADDING = 1;
    private static final int MAXIMUM_LAYOUT_CACHE_ENTRIES = 2_048;
    private static final int LOGICAL_GLYPH_SAMPLER = 0;

    private final FontManager fonts;
    private final LinkedHashMap<String, FontFace> fontFaces;
    private final TextShaper shaper;
    private final TextLayouter layouter;
    private final GlyphAtlas atlas;
    private final LinkedHashMap<LayoutKey, TextLayout> layoutCache =
            new LinkedHashMap<>(64, 0.75f, true);
    private float contentScale = 1.0f;
    private long frameShapedRuns;
    private long frameGlyphs;
    private long frameShapingNanos;
    private long layoutCacheHits;
    private long layoutCacheMisses;
    private FontFallbackChain fallback;
    private String activeFontFamily;
    private boolean closed;

    private UiTextEngine(FontManager fonts, LinkedHashMap<String, FontFace> fontFaces,
                         String activeFontFamily, TextShaper shaper,
                         TextLayouter layouter, GlyphAtlas atlas) {
        this.fonts = fonts;
        this.fontFaces = fontFaces;
        this.activeFontFamily = activeFontFamily;
        this.shaper = shaper;
        this.layouter = layouter;
        this.atlas = atlas;
        rebuildFallback();
    }

    /** 从仓库内确定性 Noto Sans SC 资源创建完整文本服务。 */
    public static UiTextEngine createBundled(int atlasWidth, int atlasHeight,
                                             int maximumAtlasPages) {
        byte[] fontData = readBundledFont();
        FontManager fonts = null;
        TextShaper shaper = null;
        GlyphAtlas atlas = null;
        try {
            fonts = new FontManager();
            FontFamily family = fonts.registerFamily(DEFAULT_FONT_FAMILY);
            FontFace face = fonts.registerFace(family, fontData, 0)
                    .variationCoordinate("wght", 400.0f);
            shaper = new TextShaper(fonts);
            TextLayouter layouter = new TextLayouter(shaper);
            atlas = new GlyphAtlas(atlasWidth, atlasHeight,
                    ATLAS_PADDING, maximumAtlasPages);
            LinkedHashMap<String, FontFace> faces = new LinkedHashMap<>();
            faces.put(DEFAULT_FONT_FAMILY, face);
            return new UiTextEngine(fonts, faces, DEFAULT_FONT_FAMILY,
                    shaper, layouter, atlas);
        } catch (RuntimeException | Error failure) {
            closeSuppressed(atlas, failure);
            closeSuppressed(shaper, failure);
            closeSuppressed(fonts, failure);
            throw failure;
        }
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
        return List.copyOf(fontFaces.keySet());
    }

    /** 返回当前作为 fallback chain 首选项的字体族。 */
    public String activeFontFamily() {
        ensureOpen();
        return activeFontFamily;
    }

    /** 从文件注册一个运行时字体；字体数据会由 {@link FontManager} 独立持有。 */
    public void registerFont(String familyName, Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        registerFont(familyName, Files.readAllBytes(path), 0);
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
        String normalized = normalizeFamilyName(familyName);
        Objects.requireNonNull(fontData, "fontData");
        if (fontFaces.containsKey(normalized)) {
            throw new IllegalArgumentException("UI font family is already registered: " + normalized);
        }
        FontFamily family = fonts.registerFamily(normalized);
        try {
            FontFace face = fonts.registerFace(family, fontData, faceIndex);
            fontFaces.put(normalized, face);
        } catch (RuntimeException | Error failure) {
            // registerFamily 已推进 generation；重建旧 face 的 chain，避免留下 stale fallback。
            rebuildFallback();
            throw failure;
        }
        rebuildFallback();
    }

    /**
     * 切换全局 UI 首选字体；其他已注册字体继续参与缺字 fallback。
     *
     * @return 字体是否发生变化
     */
    public boolean selectFontFamily(String familyName) {
        ensureOpen();
        String normalized = normalizeFamilyName(familyName);
        if (!fontFaces.containsKey(normalized)) {
            throw new IllegalArgumentException("Unknown UI font family: " + normalized);
        }
        if (activeFontFamily.equals(normalized)) {
            return false;
        }
        activeFontFamily = normalized;
        rebuildFallback();
        return true;
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
        List<ResolvedGlyph> resolved = new ArrayList<>(layout.glyphCount());
        boolean allReady = true;
        for (TextLine line : layout.lines()) {
            for (PositionedGlyph positioned : line.glyphs()) {
                FontFace face = fonts.face(positioned.faceId()).orElseThrow(() ->
                        new IllegalStateException("Text layout references missing face "
                                + positioned.faceId().value()));
                GlyphKey key = new GlyphKey(positioned.faceId(), positioned.glyphId(),
                        positioned.ppem(), GlyphHinting.NORMAL, GlyphRasterMode.GRAYSCALE);
                GlyphAtlasLookup lookup = atlas.lookup(face, key);
                if (lookup.ready()) {
                    resolved.add(new ResolvedGlyph(positioned, lookup.glyph().orElseThrow()));
                } else {
                    allReady = false;
                }
            }
        }
        if (!allReady) return false;

        double logicalLayoutHeight = layout.height() / contentScale;
        double originY = bounds.y() + Math.max(0.0,
                (bounds.height() - logicalLayoutHeight) * 0.5);
        int activePage = -1;
        boolean runOpen = false;
        try {
            for (ResolvedGlyph item : resolved) {
                GlyphAtlasGlyph glyph = item.glyph();
                if (!glyph.drawable()) continue;
                GlyphAtlasPlacement placement = glyph.placement().orElseThrow();
                if (placement.pageIndex() != activePage) {
                    if (runOpen) displayList.endGlyphRun();
                    activePage = placement.pageIndex();
                    displayList.beginGlyphRun(activePage, LOGICAL_GLYPH_SAMPLER,
                            UiBlendMode.PREMULTIPLIED_ALPHA);
                    runOpen = true;
                }
                PositionedGlyph positioned = item.positioned();
                double x = bounds.x() + (positioned.x() + glyph.bearingX()) / contentScale;
                double y = originY + (positioned.y() - glyph.bearingY()) / contentScale;
                displayList.addGlyph(new UiScreenRect(x, y,
                                (double) placement.width() / contentScale,
                                (double) placement.height() / contentScale),
                        new UiUvRect(placement.u0(), placement.v0(),
                                placement.u1(), placement.v1()), premultipliedRgba8);
                frameGlyphs++;
            }
            if (runOpen) displayList.endGlyphRun();
            return true;
        } catch (RuntimeException | Error failure) {
            if (runOpen) displayList.abortGlyphRun();
            throw failure;
        }
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
        return atlas.pendingUploads();
    }

    /** 发布 render thread 已完整执行并确认成功的单个请求。 */
    public void publishUpload(GlyphUploadRequest request) {
        ensureOpen();
        atlas.publishUpload(request);
    }

    /** 保留失败请求及 placement，供后续帧原样重试。 */
    public void uploadFailed(GlyphUploadRequest request) {
        ensureOpen();
        atlas.uploadFailed(request);
    }

    public GlyphAtlasGenerationLease acquireAtlasGeneration() {
        ensureOpen();
        return atlas.acquireGeneration();
    }

    public ShapingCache.Statistics shapingStatistics() {
        ensureOpen();
        return shaper.cacheStatistics();
    }

    public GlyphAtlasStatistics atlasStatistics() {
        ensureOpen();
        return atlas.statistics();
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
        return layoutCacheHits;
    }

    public long layoutCacheMisses() {
        return layoutCacheMisses;
    }

    @Override
    public void close() {
        if (closed) return;
        RuntimeException failure = null;
        failure = closeCollect(atlas, failure);
        failure = closeCollect(shaper, failure);
        failure = closeCollect(fonts, failure);
        layoutCache.clear();
        closed = true;
        if (failure != null) throw failure;
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
        LayoutKey key = new LayoutKey(fonts.generation(), text, ppem,
                Float.floatToIntBits(physicalWidth), mode, alignment,
                maximumLines, ellipsis, wrapMode);
        TextLayout cached = layoutCache.get(key);
        if (cached != null) {
            layoutCacheHits++;
            return cached;
        }
        layoutCacheMisses++;
        TextLayout result;
        long shapingStart = System.nanoTime();
        try {
            if (mode == LayoutMode.SINGLE_LINE) {
                result = layouter.layoutSingleLine(fallback, ppem, text, physicalWidth,
                        alignment, ellipsis);
            } else {
                result = layouter.layout(fallback, ppem, text, physicalWidth, alignment,
                        maximumLines, ellipsis, wrapMode);
            }
        } finally {
            frameShapingNanos += System.nanoTime() - shapingStart;
        }
        frameShapedRuns += result.lines().size();
        layoutCache.put(key, result);
        if (layoutCache.size() > MAXIMUM_LAYOUT_CACHE_ENTRIES) {
            Iterator<Map.Entry<LayoutKey, TextLayout>> iterator = layoutCache.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }
        return result;
    }

    private void rebuildFallback() {
        FontFace active = fontFaces.get(activeFontFamily);
        if (active == null) {
            throw new IllegalStateException("Active UI font is not registered: " + activeFontFamily);
        }
        ArrayList<FontFace> ordered = new ArrayList<>(fontFaces.size());
        ordered.add(active);
        for (FontFace face : fontFaces.values()) {
            if (face != active) ordered.add(face);
        }
        fallback = fonts.fallbackChain(ordered);
        layoutCache.clear();
        shaper.clearCache();
    }

    private static String normalizeFamilyName(String familyName) {
        String normalized = Objects.requireNonNull(familyName, "familyName").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("font family name must not be blank");
        }
        return normalized;
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

    private static byte[] readBundledFont() {
        try (InputStream input = UiTextEngine.class.getResourceAsStream(BUNDLED_FONT_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled UI font " + BUNDLED_FONT_RESOURCE);
            }
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to load bundled UI font "
                    + BUNDLED_FONT_RESOURCE, failure);
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("UiTextEngine is closed");
    }

    private static RuntimeException closeCollect(AutoCloseable closeable,
                                                 RuntimeException current) {
        if (closeable == null) return current;
        try {
            closeable.close();
        } catch (RuntimeException failure) {
            if (current == null) return failure;
            current.addSuppressed(failure);
        } catch (Exception failure) {
            RuntimeException wrapped = new IllegalStateException("UI text resource close failed", failure);
            if (current == null) return wrapped;
            current.addSuppressed(wrapped);
        }
        return current;
    }

    private static void closeSuppressed(AutoCloseable closeable, Throwable primary) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception | LinkageError cleanup) {
            primary.addSuppressed(cleanup);
        }
    }

    private enum LayoutMode { SINGLE_LINE, MULTI_LINE }

    private record LayoutKey(FontGeneration generation, String text, int ppem,
                             int widthBits, LayoutMode mode, TextAlignment alignment,
                             int maximumLines, boolean ellipsis, TextWrapMode wrapMode) {
    }

    private record ResolvedGlyph(PositionedGlyph positioned, GlyphAtlasGlyph glyph) {
    }
}
