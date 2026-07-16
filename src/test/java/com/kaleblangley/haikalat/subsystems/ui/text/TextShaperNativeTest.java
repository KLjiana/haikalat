package com.kaleblangley.haikalat.subsystems.ui.text;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextShaperNativeTest {
    @Test
    void latinShapeProducesGlyphsAdvancesClustersAndCacheHit() throws Exception {
        Path font = requireConditionalFont();
        try (FontManager manager = new FontManager()) {
            FontFace face = manager.registerFace(manager.registerFamily("shape-test"), font, 0);
            try (TextShaper shaper = new TextShaper(manager, 16)) {
                TextRun first = shaper.shape(face, 20, "office", TextDirection.LEFT_TO_RIGHT,
                        "en", List.of(OpenTypeFeature.enabled("liga"), OpenTypeFeature.enabled("kern")));
                TextRun second = shaper.shape(face, 20, "office", TextDirection.LEFT_TO_RIGHT,
                        "en", List.of(OpenTypeFeature.enabled("kern"), OpenTypeFeature.enabled("liga")));

                assertFalse(first.glyphs().isEmpty());
                assertTrue(first.advanceX() > 0.0f);
                assertEquals(first, second);
                assertEquals(1, shaper.cacheStatistics().hits());
                assertEquals(1, shaper.cacheStatistics().misses());
                for (ShapedGlyph glyph : first.glyphs()) {
                    assertTrue(glyph.clusterStartUtf16() >= 0);
                    assertTrue(glyph.clusterEndUtf16() <= first.text().length());
                    assertTrue(glyph.clusterEndUtf16() > glyph.clusterStartUtf16());
                }
            }
        }
    }

    @Test
    void supplementaryInputNeverPublishesSplitCluster() throws Exception {
        Path font = requireConditionalFont();
        String text = "A\uD83D\uDE00B";
        try (FontManager manager = new FontManager()) {
            FontFace face = manager.registerFace(manager.registerFamily("supplementary-test"), font, 0);
            try (TextShaper shaper = new TextShaper(manager, 8)) {
                TextRun run = shaper.shape(face, 18, text);

                for (ShapedGlyph glyph : run.glyphs()) {
                    assertFalse(glyph.clusterStartUtf16() == 2 || glyph.clusterEndUtf16() == 2,
                            "shape result split the surrogate pair");
                }
            }
        }
    }

    @Test
    void rasterizedGlyphOwnsTightCoverageAfterNativeSlotChanges() throws Exception {
        Path font = requireConditionalFont();
        try (FontManager manager = new FontManager()) {
            FontFace face = manager.registerFace(manager.registerFamily("raster-test"), font, 0);
            int glyphId = face.glyphIndex('A');
            assertTrue(glyphId > 0);
            GlyphBitmap bitmap = face.rasterize(new GlyphKey(face.id(), glyphId, 24));
            byte[] retained = bitmap.copyCoverage();

            face.rasterize(new GlyphKey(face.id(), face.glyphIndex('B'), 32));

            assertTrue(bitmap.width() > 0);
            assertTrue(bitmap.height() > 0);
            assertEquals(bitmap.width() * bitmap.height(), bitmap.uploadByteCount());
            assertEquals(ByteBuffer.wrap(retained), ByteBuffer.wrap(bitmap.copyCoverage()));
        }
    }

    @Test
    void fallbackShapesAdjacentClustersAndRejectsStaleChain() throws Exception {
        Path font = requireConditionalFont();
        try (FontManager manager = new FontManager()) {
            FontFace face = manager.registerFace(manager.registerFamily("fallback-test"), font, 0);
            FontFallbackChain chain = manager.fallbackChain(face);
            try (TextShaper shaper = new TextShaper(manager, 8)) {
                List<TextRun> runs = shaper.shapeWithFallback(chain, 16, "ABC",
                        TextDirection.LEFT_TO_RIGHT, "en", List.of());
                assertEquals(1, runs.size());
                assertEquals("ABC", runs.get(0).text());

                manager.registerFamily("generation-change");
                assertThrows(IllegalStateException.class, () -> shaper.shapeWithFallback(chain, 16, "ABC",
                        TextDirection.LEFT_TO_RIGHT, "en", List.of()));
            }
        }
    }

    private static Path requireConditionalFont() {
        Path path = FontNativeLifecycleTest.conditionalFont().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(path != null,
                "No system/JDK font is available; deterministic project font asset is not installed yet");
        return path;
    }
}
