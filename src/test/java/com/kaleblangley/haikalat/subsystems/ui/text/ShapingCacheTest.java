package com.kaleblangley.haikalat.subsystems.ui.text;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapingCacheTest {
    @Test
    void leastRecentlyUsedEntryIsEvictedAtTheHardLimit() {
        ShapingCache cache = new ShapingCache(2);
        ShapingCacheKey first = key(1, "a");
        ShapingCacheKey second = key(1, "b");
        ShapingCacheKey third = key(1, "c");
        TextRun firstRun = run(first);
        cache.put(first, firstRun);
        cache.put(second, run(second));

        assertSame(firstRun, cache.get(first).orElseThrow());
        cache.put(third, run(third));

        assertTrue(cache.get(second).isEmpty());
        assertTrue(cache.get(first).isPresent());
        assertTrue(cache.get(third).isPresent());
        assertEquals(2, cache.size());
        assertEquals(1, cache.statistics().evictions());
    }

    @Test
    void keyContainsGenerationFacePpemTextDirectionLanguageAndFeatures() {
        ShapingCacheKey baseline = key(7, "text");

        assertNotEquals(baseline, new ShapingCacheKey(8, baseline.faceId(), baseline.ppem(), baseline.text(),
                baseline.direction(), baseline.language(), baseline.features()));
        assertNotEquals(baseline, new ShapingCacheKey(7, new FontFaceId(2), baseline.ppem(), baseline.text(),
                baseline.direction(), baseline.language(), baseline.features()));
        assertNotEquals(baseline, new ShapingCacheKey(7, baseline.faceId(), 17, baseline.text(),
                baseline.direction(), baseline.language(), baseline.features()));
        assertNotEquals(baseline, new ShapingCacheKey(7, baseline.faceId(), baseline.ppem(), "other",
                baseline.direction(), baseline.language(), baseline.features()));
        assertNotEquals(baseline, new ShapingCacheKey(7, baseline.faceId(), baseline.ppem(), baseline.text(),
                TextDirection.RIGHT_TO_LEFT, baseline.language(), baseline.features()));
        assertNotEquals(baseline, new ShapingCacheKey(7, baseline.faceId(), baseline.ppem(), baseline.text(),
                baseline.direction(), "zh-CN", baseline.features()));
        assertNotEquals(baseline, new ShapingCacheKey(7, baseline.faceId(), baseline.ppem(), baseline.text(),
                baseline.direction(), baseline.language(), List.of(OpenTypeFeature.disabled("liga"))));
    }

    @Test
    void featureSetIsCanonicalAndImmutable() {
        List<OpenTypeFeature> source = new ArrayList<>(List.of(
                OpenTypeFeature.enabled("liga"), OpenTypeFeature.enabled("kern")));
        ShapingCacheKey first = new ShapingCacheKey(1, new FontFaceId(1), 16, "a",
                TextDirection.LEFT_TO_RIGHT, "EN-us", source);
        ShapingCacheKey second = new ShapingCacheKey(1, new FontFaceId(1), 16, "a",
                TextDirection.LEFT_TO_RIGHT, "en-US", List.of(
                OpenTypeFeature.enabled("kern"), OpenTypeFeature.enabled("liga")));
        source.clear();

        assertEquals(first, second);
        assertEquals("en-US", first.language());
        assertThrows(UnsupportedOperationException.class,
                () -> first.features().add(OpenTypeFeature.enabled("calt")));
    }

    @Test
    void getOrComputePublishesOnlyRunWithMatchingKey() {
        ShapingCache cache = new ShapingCache(1);
        ShapingCacheKey key = key(1, "x");
        TextRun run = cache.getOrCompute(key, ShapingCacheTest::run);

        assertSame(run, cache.getOrCompute(key, ignored -> {
            throw new AssertionError("cache hit must not recompute");
        }));
        assertEquals(1, cache.statistics().hits());
        assertEquals(1, cache.statistics().misses());
        assertThrows(IllegalArgumentException.class, () -> cache.put(key, run(key(2, "x"))));
    }

    @Test
    void immutableRunAndLayoutProtectSupplementaryClusterRanges() {
        ShapingCacheKey emoji = key(1, "\uD83D\uDE00");
        List<ShapedGlyph> glyphSource = new ArrayList<>(List.of(
                new ShapedGlyph(12, 0, 2, 8.0f, 0.0f, 0.0f, 0.0f)));
        TextRun run = new TextRun(emoji, glyphSource, 8.0f, 0.0f, 7.0f, 2.0f);
        glyphSource.clear();
        PositionedGlyph positioned = new PositionedGlyph(emoji.faceId(), emoji.ppem(), 12,
                0, 2, 0.0f, 7.0f, 8.0f, 0.0f);
        TextLine line = new TextLine(0, 2, 7.0f, 8.0f, 7.0f, 2.0f, List.of(positioned));
        TextLayout layout = new TextLayout(emoji.fontGeneration(), emoji.text(), List.of(line), 8.0f, 9.0f);

        assertEquals(1, run.glyphs().size());
        assertEquals(1, layout.glyphCount());
        ShapedGlyph splitSurrogate = new ShapedGlyph(12, 0, 1,
                8.0f, 0.0f, 0.0f, 0.0f);
        assertThrows(IllegalArgumentException.class, () -> new TextRun(emoji, List.of(splitSurrogate),
                8.0f, 0.0f, 7.0f, 2.0f));
    }

    private static ShapingCacheKey key(long generation, String text) {
        return new ShapingCacheKey(generation, new FontFaceId(1), 16, text,
                TextDirection.LEFT_TO_RIGHT, "en", List.of(OpenTypeFeature.enabled("liga")));
    }

    private static TextRun run(ShapingCacheKey key) {
        int end = key.text().length();
        List<ShapedGlyph> glyphs = end == 0 ? List.of()
                : List.of(new ShapedGlyph(1, 0, end, 1.0f, 0.0f, 0.0f, 0.0f));
        return new TextRun(key, glyphs, end, 0.0f, 1.0f, 0.0f);
    }
}
