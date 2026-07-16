package com.kaleblangley.haikalat.subsystems.ui.text;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.harfbuzz.HarfBuzz;
import org.lwjgl.util.harfbuzz.hb_feature_t;
import org.lwjgl.util.harfbuzz.hb_glyph_info_t;
import org.lwjgl.util.harfbuzz.hb_glyph_position_t;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 线程封闭的 HarfBuzz shaping 门面。
 *
 * <p>每次调用只在方法内部创建并销毁 HB buffer；HB font 由 {@link FontFace} 拥有，任何
 * native handle 都不会进入公开 API。结果通过有界 {@link ShapingCache} 复用。</p>
 */
public final class TextShaper implements AutoCloseable {
    public static final int DEFAULT_CACHE_ENTRIES = 1_024;

    private final FontManager fontManager;
    private final TextThreadOwner threadOwner = new TextThreadOwner();
    private final TextBoundaryService boundaries;
    private final ShapingCache cache;
    private boolean closed;

    public TextShaper(FontManager fontManager) {
        this(fontManager, new TextBoundaryService(), DEFAULT_CACHE_ENTRIES);
    }

    public TextShaper(FontManager fontManager, int maximumCacheEntries) {
        this(fontManager, new TextBoundaryService(), maximumCacheEntries);
    }

    public TextShaper(FontManager fontManager, TextBoundaryService boundaries, int maximumCacheEntries) {
        this.fontManager = Objects.requireNonNull(fontManager, "fontManager");
        this.boundaries = Objects.requireNonNull(boundaries, "boundaries");
        this.cache = new ShapingCache(maximumCacheEntries);
        fontManager.checkUsable("TextShaper.create");
    }

    /** 使用指定 face 完成单个 run 的 shaping。 */
    public TextRun shape(FontFace face, int ppem, String text, TextDirection direction,
                         String language, List<OpenTypeFeature> features) {
        checkUsable("TextShaper.shape");
        FontFace registeredFace = fontManager.requireFace(face);
        ShapingCacheKey key = new ShapingCacheKey(fontManager.generation(), registeredFace.id(), ppem,
                text, direction, language, features);
        return cache.getOrCompute(key, ignored -> shapeUncached(registeredFace, key));
    }

    /** 使用默认语言和 feature set 的便捷入口。 */
    public TextRun shape(FontFace face, int ppem, String text) {
        return shape(face, ppem, text, TextDirection.LEFT_TO_RIGHT, "und", List.of());
    }

    /**
     * 按 grapheme cluster 选择完整覆盖它的 fallback face，并合并相邻同 face run 后 shaping。
     * 全部 face miss 时使用 chain 首项产生明确 tofu glyph，不会静默丢字符。
     */
    public List<TextRun> shapeWithFallback(FontFallbackChain chain, int ppem, String text,
                                           TextDirection direction, String language,
                                           List<OpenTypeFeature> features) {
        checkUsable("TextShaper.shapeWithFallback");
        Objects.requireNonNull(text, "text");
        List<Integer> clusterBoundaries = boundaries.graphemeBoundaries(text);
        if (text.isEmpty()) {
            FontFace face = fontManager.resolveFaceOrTofu(chain, text, 0, 0);
            return List.of(shape(face, ppem, text, direction, language, features));
        }

        List<TextRun> result = new ArrayList<>();
        int runStart = 0;
        FontFace runFace = null;
        for (int index = 0; index < clusterBoundaries.size() - 1; index++) {
            int clusterStart = clusterBoundaries.get(index);
            int clusterEnd = clusterBoundaries.get(index + 1);
            FontFace clusterFace = fontManager.resolveFaceOrTofu(chain, text, clusterStart, clusterEnd);
            if (runFace == null) {
                runFace = clusterFace;
                runStart = clusterStart;
            } else if (!runFace.id().equals(clusterFace.id())) {
                result.add(shape(runFace, ppem, text.substring(runStart, clusterStart),
                        direction, language, features));
                runFace = clusterFace;
                runStart = clusterStart;
            }
        }
        result.add(shape(runFace, ppem, text.substring(runStart), direction, language, features));
        return List.copyOf(result);
    }

    /** 返回 shaping cache 的累计统计。 */
    public ShapingCache.Statistics cacheStatistics() {
        checkUsable("TextShaper.cacheStatistics");
        return cache.statistics();
    }

    /** 清空 shaping cache，不影响 glyph atlas。 */
    public void clearCache() {
        checkUsable("TextShaper.clearCache");
        cache.clear();
    }

    public boolean isClosed() {
        return closed;
    }

    /** 幂等关闭纯 Java cache；FontFace 仍由 FontManager 负责。 */
    @Override
    public void close() {
        threadOwner.check("TextShaper.close");
        if (closed) {
            return;
        }
        cache.clear();
        closed = true;
    }

    private TextRun shapeUncached(FontFace face, ShapingCacheKey key) {
        face.setPixelSize(key.ppem());
        FontMetrics metrics = face.metrics(key.ppem());
        if (key.text().isEmpty()) {
            return new TextRun(key, List.of(), 0.0f, 0.0f, metrics.ascent(), metrics.descent());
        }

        long buffer = HarfBuzz.hb_buffer_create();
        if (buffer == 0L) {
            throw new FontNativeException("hb_buffer_create", "returned a null buffer handle");
        }
        try {
            HarfBuzz.hb_buffer_set_cluster_level(buffer,
                    HarfBuzz.HB_BUFFER_CLUSTER_LEVEL_MONOTONE_GRAPHEMES);
            HarfBuzz.hb_buffer_set_direction(buffer, key.direction() == TextDirection.LEFT_TO_RIGHT
                    ? HarfBuzz.HB_DIRECTION_LTR : HarfBuzz.HB_DIRECTION_RTL);
            long language = HarfBuzz.hb_language_from_string(key.language());
            if (language != 0L) {
                HarfBuzz.hb_buffer_set_language(buffer, language);
            }
            HarfBuzz.hb_buffer_add_utf16(buffer, key.text(), 0, key.text().length());
            HarfBuzz.hb_buffer_guess_segment_properties(buffer);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                hb_feature_t.Buffer nativeFeatures = nativeFeatures(key.features(), stack);
                face.beginNativeShape();
                RuntimeException shapeFailure = null;
                try {
                    HarfBuzz.hb_shape(face.harfBuzzFontHandle(), buffer, nativeFeatures);
                } catch (RuntimeException failure) {
                    shapeFailure = failure;
                    throw failure;
                } finally {
                    try {
                        face.finishNativeShape();
                    } catch (RuntimeException callbackFailure) {
                        if (shapeFailure == null) {
                            throw callbackFailure;
                        }
                        shapeFailure.addSuppressed(callbackFailure);
                    }
                }
            }
            if (!HarfBuzz.hb_buffer_allocation_successful(buffer)) {
                throw new FontNativeException("hb_shape", "HarfBuzz buffer allocation failed");
            }
            return copyResult(key, metrics, buffer);
        } finally {
            HarfBuzz.hb_buffer_destroy(buffer);
        }
    }

    private static TextRun copyResult(ShapingCacheKey key, FontMetrics metrics, long buffer) {
        int glyphCount = HarfBuzz.hb_buffer_get_length(buffer);
        hb_glyph_info_t.Buffer infos = HarfBuzz.hb_buffer_get_glyph_infos(buffer);
        hb_glyph_position_t.Buffer positions = HarfBuzz.hb_buffer_get_glyph_positions(buffer);
        if (glyphCount <= 0 || infos == null || positions == null) {
            return new TextRun(key, List.of(), 0.0f, 0.0f, metrics.ascent(), metrics.descent());
        }

        NavigableSet<Integer> clusterStarts = new TreeSet<>();
        for (int index = 0; index < glyphCount; index++) {
            int cluster = infos.get(index).cluster();
            requireClusterStart(key.text(), cluster);
            clusterStarts.add(cluster);
        }
        clusterStarts.add(key.text().length());

        List<ShapedGlyph> glyphs = new ArrayList<>(glyphCount);
        float totalAdvanceX = 0.0f;
        float totalAdvanceY = 0.0f;
        for (int index = 0; index < glyphCount; index++) {
            hb_glyph_info_t info = infos.get(index);
            hb_glyph_position_t position = positions.get(index);
            int clusterStart = info.cluster();
            Integer clusterEnd = clusterStarts.higher(clusterStart);
            if (clusterEnd == null) {
                throw new FontNativeException("hb_shape", "glyph cluster has no source end");
            }
            float advanceX = position.x_advance() / 64.0f;
            float advanceY = position.y_advance() / 64.0f;
            glyphs.add(new ShapedGlyph(info.codepoint(), clusterStart, clusterEnd,
                    advanceX, advanceY,
                    position.x_offset() / 64.0f,
                    position.y_offset() / 64.0f));
            totalAdvanceX += advanceX;
            totalAdvanceY += advanceY;
        }
        return new TextRun(key, glyphs, totalAdvanceX, totalAdvanceY,
                metrics.ascent(), metrics.descent());
    }

    private static hb_feature_t.Buffer nativeFeatures(List<OpenTypeFeature> features, MemoryStack stack) {
        if (features.isEmpty()) {
            return null;
        }
        hb_feature_t.Buffer result = hb_feature_t.calloc(features.size(), stack);
        for (int index = 0; index < features.size(); index++) {
            OpenTypeFeature feature = features.get(index);
            result.get(index).set(HarfBuzz.hb_tag_from_string(feature.tag()), feature.value(),
                    HarfBuzz.HB_FEATURE_GLOBAL_START, HarfBuzz.HB_FEATURE_GLOBAL_END);
        }
        return result;
    }

    private static void requireClusterStart(String text, int offset) {
        if (offset < 0 || offset >= text.length()) {
            throw new FontNativeException("hb_shape", "invalid UTF-16 cluster offset " + offset);
        }
        if (offset > 0 && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset))) {
            throw new FontNativeException("hb_shape", "cluster splits a supplementary code point");
        }
    }

    private void checkUsable(String operation) {
        threadOwner.check(operation);
        if (closed) {
            throw new IllegalStateException(operation + " cannot use closed TextShaper");
        }
        fontManager.checkUsable(operation);
    }
}
