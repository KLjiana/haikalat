package com.kaleblangley.haikalat.subsystems.text;

import com.kaleblangley.haikalat.subsystems.text.markdown.MarkdownDocument;
import com.kaleblangley.haikalat.subsystems.text.markdown.MarkdownParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shared owner and facade for the CPU-side text pipeline.
 *
 * <p>The system centralizes fonts, fallback selection, shaping, layout caching, glyph
 * atlas preparation and Markdown parsing. It does not create GPU resources or depend on
 * a renderer; consumers publish {@link GlyphUploadRequest} values only after their own
 * upload path succeeds.</p>
 */
public final class TextSystem implements AutoCloseable {
    public static final int DEFAULT_LAYOUT_CACHE_ENTRIES = 2_048;

    private final TextThreadOwner threadOwner = new TextThreadOwner();
    private final FontManager fonts;
    private final LinkedHashMap<String, FontFace> fontFaces;
    private final TextShaper shaper;
    private final TextLayouter layouter;
    private final GlyphAtlas atlas;
    private final MarkdownParser markdown;
    private final int maximumLayoutCacheEntries;
    private final LinkedHashMap<LayoutKey, TextLayout> layoutCache =
            new LinkedHashMap<>(64, 0.75f, true);
    private final LinkedHashMap<String, FontFallbackChain> fallbacks = new LinkedHashMap<>();
    private String activeFontFamily;
    private long shapedRuns;
    private long layoutNanos;
    private long layoutCacheHits;
    private long layoutCacheMisses;
    private boolean closed;

    private TextSystem(FontManager fonts, LinkedHashMap<String, FontFace> fontFaces,
                       String activeFontFamily, TextShaper shaper, TextLayouter layouter,
                       GlyphAtlas atlas, int maximumLayoutCacheEntries) {
        this.fonts = Objects.requireNonNull(fonts, "fonts");
        this.fontFaces = Objects.requireNonNull(fontFaces, "fontFaces");
        this.activeFontFamily = Objects.requireNonNull(activeFontFamily, "activeFontFamily");
        this.shaper = Objects.requireNonNull(shaper, "shaper");
        this.layouter = Objects.requireNonNull(layouter, "layouter");
        this.atlas = Objects.requireNonNull(atlas, "atlas");
        if (maximumLayoutCacheEntries <= 0) {
            throw new IllegalArgumentException("maximumLayoutCacheEntries must be positive");
        }
        this.maximumLayoutCacheEntries = maximumLayoutCacheEntries;
        markdown = new MarkdownParser();
        rebuildFallback();
    }

    /** Creates a complete text pipeline using the engine's deterministic bundled fonts. */
    public static TextSystem createBundled(int atlasWidth, int atlasHeight, int atlasPadding,
                                           int maximumAtlasPages) {
        return createBundled(atlasWidth, atlasHeight, atlasPadding, maximumAtlasPages,
                DEFAULT_LAYOUT_CACHE_ENTRIES);
    }

    /** Creates a bundled pipeline with an explicit layout cache capacity. */
    public static TextSystem createBundled(int atlasWidth, int atlasHeight, int atlasPadding,
                                           int maximumAtlasPages,
                                           int maximumLayoutCacheEntries) {
        FontManager fonts = null;
        TextShaper shaper = null;
        GlyphAtlas atlas = null;
        try {
            fonts = new FontManager();
            LinkedHashMap<String, FontFace> faces = BundledFonts.registerAll(fonts);
            shaper = new TextShaper(fonts);
            TextLayouter layouter = new TextLayouter(shaper);
            atlas = new GlyphAtlas(atlasWidth, atlasHeight, atlasPadding, maximumAtlasPages);
            return new TextSystem(fonts, faces, BundledFonts.NOTO_SANS_SC_FAMILY,
                    shaper, layouter, atlas, maximumLayoutCacheEntries);
        } catch (RuntimeException | Error failure) {
            closeSuppressed(atlas, failure);
            closeSuppressed(shaper, failure);
            closeSuppressed(fonts, failure);
            throw failure;
        }
    }

    public List<String> fontFamilies() {
        ensureOpen();
        return List.copyOf(fontFaces.keySet());
    }

    public String activeFontFamily() {
        ensureOpen();
        return activeFontFamily;
    }

    public FontGeneration fontGeneration() {
        ensureOpen();
        return fonts.generation();
    }

    public void registerFont(String familyName, Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        registerFont(familyName, Files.readAllBytes(path), 0);
    }

    public void registerFont(String familyName, byte[] fontData) {
        registerFont(familyName, fontData, 0);
    }

    public void registerFont(String familyName, byte[] fontData, int faceIndex) {
        ensureOpen();
        String normalized = normalizeFamilyName(familyName);
        Objects.requireNonNull(fontData, "fontData");
        if (fontFaces.containsKey(normalized)) {
            throw new IllegalArgumentException("Font family is already registered: " + normalized);
        }
        FontFamily family = fonts.registerFamily(normalized);
        try {
            fontFaces.put(normalized, fonts.registerFace(family, fontData, faceIndex));
        } catch (RuntimeException | Error failure) {
            try {
                fonts.discardFamily(family);
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        } finally {
            // Rebuild with the current generation after either a successful registration
            // or rollback of the empty family.
            rebuildFallback();
        }
    }

    /** Selects the primary face while retaining every other registered face as fallback. */
    public boolean selectFontFamily(String familyName) {
        ensureOpen();
        String normalized = normalizeFamilyName(familyName);
        if (!fontFaces.containsKey(normalized)) {
            throw new IllegalArgumentException("Unknown font family: " + normalized);
        }
        if (activeFontFamily.equals(normalized)) return false;
        activeFontFamily = normalized;
        return true;
    }

    public TextLayout layout(int ppem, String text, float availableWidth,
                             TextAlignment alignment, int maximumLines,
                             boolean ellipsis, TextWrapMode wrapMode) {
        return layout(activeFontFamily, ppem, text, availableWidth, alignment,
                maximumLines, ellipsis, wrapMode);
    }

    public TextLayout layout(String familyName, int ppem, String text, float availableWidth,
                             TextAlignment alignment, int maximumLines,
                             boolean ellipsis, TextWrapMode wrapMode) {
        return layoutCached(familyName, false, ppem, text, availableWidth, alignment,
                maximumLines, ellipsis, wrapMode);
    }

    public TextLayout layoutSingleLine(int ppem, String text, float availableWidth,
                                       TextAlignment alignment, boolean ellipsis) {
        return layoutSingleLine(activeFontFamily, ppem, text, availableWidth, alignment, ellipsis);
    }

    public TextLayout layoutSingleLine(String familyName, int ppem, String text,
                                       float availableWidth, TextAlignment alignment,
                                       boolean ellipsis) {
        return layoutCached(familyName, true, ppem, text, availableWidth, alignment,
                1, ellipsis, TextWrapMode.LINE_BREAK);
    }

    /** Resolves or prepares one glyph without exposing the owned font manager or atlas. */
    public GlyphAtlasLookup lookupGlyph(GlyphKey key) {
        ensureOpen();
        Objects.requireNonNull(key, "key");
        FontFace face = fonts.face(key.faceId()).orElseThrow(() ->
                new IllegalArgumentException("Glyph key references missing face "
                        + key.faceId().value()));
        return atlas.lookup(face, key);
    }

    /**
     * Returns a ready glyph or prepares its upload on a miss. The ready-state path does
     * not allocate a {@link GlyphAtlasLookup} wrapper.
     */
    public GlyphAtlasGlyph resolveGlyph(GlyphKey key) {
        ensureOpen();
        Objects.requireNonNull(key, "key");
        GlyphAtlasGlyph ready = atlas.readyGlyph(key);
        if (ready != null) return ready;
        GlyphAtlasLookup lookup = lookupGlyph(key);
        return lookup.ready() ? lookup.glyph().orElseThrow() : null;
    }

    public List<GlyphUploadRequest> pendingUploads() {
        ensureOpen();
        return atlas.pendingUploads();
    }

    public void publishUpload(GlyphUploadRequest request) {
        ensureOpen();
        atlas.publishUpload(request);
    }

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

    public LayoutStatistics layoutStatistics() {
        ensureOpen();
        return new LayoutStatistics(shapedRuns, layoutNanos,
                layoutCacheHits, layoutCacheMisses, layoutCache.size());
    }

    public MarkdownDocument parseMarkdown(String source) {
        ensureOpen();
        return markdown.parse(source);
    }

    @Override
    public void close() {
        threadOwner.check("TextSystem.close");
        if (closed) return;
        // Atlas leases belong to published snapshots. If one is still active, keep the
        // facade open so the owner can release it and retry shutdown without leaking the
        // atlas or closing the native resources out of order.
        atlas.close();
        RuntimeException failure = null;
        failure = closeCollect(shaper, failure);
        failure = closeCollect(fonts, failure);
        layoutCache.clear();
        closed = true;
        if (failure != null) throw failure;
    }

    private TextLayout layoutCached(String familyName, boolean singleLine, int ppem, String text,
                                    float availableWidth, TextAlignment alignment,
                                    int maximumLines, boolean ellipsis,
                                    TextWrapMode wrapMode) {
        ensureOpen();
        String normalizedFamily = normalizeFamilyName(familyName);
        FontFallbackChain chain = fallbackFor(normalizedFamily);
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(alignment, "alignment");
        Objects.requireNonNull(wrapMode, "wrapMode");
        LayoutKey key = new LayoutKey(fonts.generation(), normalizedFamily, text, ppem,
                Float.floatToIntBits(availableWidth), singleLine, alignment,
                maximumLines, ellipsis, wrapMode);
        TextLayout cached = layoutCache.get(key);
        if (cached != null) {
            layoutCacheHits = Math.addExact(layoutCacheHits, 1L);
            return cached;
        }

        layoutCacheMisses = Math.addExact(layoutCacheMisses, 1L);
        long start = System.nanoTime();
        TextLayout result;
        try {
            result = singleLine
                    ? layouter.layoutSingleLine(chain, ppem, text, availableWidth,
                            alignment, ellipsis)
                    : layouter.layout(chain, ppem, text, availableWidth, alignment,
                            maximumLines, ellipsis, wrapMode);
        } finally {
            layoutNanos = Math.addExact(layoutNanos, System.nanoTime() - start);
        }
        shapedRuns = Math.addExact(shapedRuns, result.lines().size());
        layoutCache.put(key, result);
        if (layoutCache.size() > maximumLayoutCacheEntries) {
            Iterator<Map.Entry<LayoutKey, TextLayout>> iterator = layoutCache.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }
        return result;
    }

    private void rebuildFallback() {
        fallbacks.clear();
        for (String primary : fontFaces.keySet()) {
            FontFace active = fontFaces.get(primary);
            ArrayList<FontFace> ordered = new ArrayList<>(fontFaces.size());
            ordered.add(active);
            for (FontFace face : fontFaces.values()) {
                if (face != active) ordered.add(face);
            }
            fallbacks.put(primary, fonts.fallbackChain(ordered));
        }
        if (!fallbacks.containsKey(activeFontFamily)) {
            throw new IllegalStateException("Active font is not registered: " + activeFontFamily);
        }
        layoutCache.clear();
        shaper.clearCache();
    }

    private FontFallbackChain fallbackFor(String familyName) {
        FontFallbackChain result = fallbacks.get(familyName);
        if (result == null) {
            throw new IllegalArgumentException("Unknown font family: " + familyName);
        }
        return result;
    }

    private static String normalizeFamilyName(String familyName) {
        String normalized = Objects.requireNonNull(familyName, "familyName").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("font family name must not be blank");
        }
        return normalized;
    }

    private void ensureOpen() {
        threadOwner.check("TextSystem");
        if (closed) throw new IllegalStateException("TextSystem is closed");
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
            RuntimeException wrapped = new IllegalStateException("Text resource close failed", failure);
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

    public record LayoutStatistics(long shapedRuns, long layoutNanos,
                                   long cacheHits, long cacheMisses,
                                   int cachedLayouts) {
    }

    private record LayoutKey(FontGeneration generation, String familyName, String text, int ppem,
                             int widthBits, boolean singleLine, TextAlignment alignment,
                             int maximumLines, boolean ellipsis, TextWrapMode wrapMode) {
    }
}
