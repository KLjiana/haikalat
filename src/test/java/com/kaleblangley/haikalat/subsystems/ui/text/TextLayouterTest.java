package com.kaleblangley.haikalat.subsystems.ui.text;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextLayouterTest {
    private static final FontGeneration GENERATION = new FontGeneration(3);
    private static final FontFaceId LATIN_FACE = new FontFaceId(1);
    private static final FontFaceId CJK_FACE = new FontFaceId(2);
    private static final FontFallbackChain CHAIN = new FontFallbackChain(
            GENERATION, List.of(LATIN_FACE, CJK_FACE));

    private final TextBoundaryService boundaries = new TextBoundaryService();
    private final TextLayouter layouter = new TextLayouter(boundaries, this::shape);

    @Test
    void latinUsesLineBreaksAndCjkWrapsAtIdeographClusters() {
        TextLayout latin = layouter.layout(CHAIN, 16, "ab cd", 12.0f, TextAlignment.START);

        assertEquals(2, latin.lines().size());
        assertEquals(new TextRange(0, 3), range(latin.lines().get(0)));
        assertEquals(new TextRange(3, 5), range(latin.lines().get(1)));
        assertEquals(12.0f, latin.lines().get(0).width());
        assertEquals(10.0f, latin.lines().get(1).width());

        TextLayout cjk = layouter.layout(CHAIN, 16, "中文测试", 20.0f, TextAlignment.START);

        assertEquals(2, cjk.lines().size());
        assertEquals(new TextRange(0, 2), range(cjk.lines().get(0)));
        assertEquals(new TextRange(2, 4), range(cjk.lines().get(1)));
        assertTrue(cjk.lines().stream().flatMap(line -> line.glyphs().stream())
                .allMatch(glyph -> glyph.faceId().equals(CJK_FACE)));
    }

    @Test
    void explicitCrLfBlankAndUnicodeLinesPreserveSourceOffsets() {
        String text = "A\r\n\n中\u2028B";
        TextLayout layout = layouter.layout(CHAIN, 16, text,
                Float.POSITIVE_INFINITY, TextAlignment.START);

        assertEquals(4, layout.lines().size());
        assertEquals(new TextRange(0, 1), range(layout.lines().get(0)));
        assertEquals(new TextRange(3, 3), range(layout.lines().get(1)));
        assertEquals(new TextRange(4, 5), range(layout.lines().get(2)));
        assertEquals(new TextRange(6, 7), range(layout.lines().get(3)));
        assertEquals(8.0f, layout.lines().get(0).baselineY());
        assertEquals(18.0f, layout.lines().get(1).baselineY());
        assertEquals(28.0f, layout.lines().get(2).baselineY());
        assertEquals(38.0f, layout.lines().get(3).baselineY());
        assertEquals(40.0f, layout.height());
    }

    @Test
    void startCenterAndEndAlignmentUseTheAvailableWidth() {
        TextLayout start = layouter.layout(CHAIN, 16, "AB", 20.0f, TextAlignment.START);
        TextLayout center = layouter.layout(CHAIN, 16, "AB", 20.0f, TextAlignment.CENTER);
        TextLayout end = layouter.layout(CHAIN, 16, "AB", 20.0f, TextAlignment.END);

        assertEquals(0.0f, start.lines().getFirst().glyphs().getFirst().x());
        assertEquals(5.0f, center.lines().getFirst().glyphs().getFirst().x());
        assertEquals(10.0f, end.lines().getFirst().glyphs().getFirst().x());
        assertEquals(20.0f, center.width());
    }

    @Test
    void ellipsisNeverSplitsSupplementaryOrCombiningClusters() {
        String text = "A😀e\u0301BC";
        TextLayout layout = layouter.layoutSingleLine(CHAIN, 16, text,
                18.0f, TextAlignment.START, true);
        TextLine line = layout.lines().getFirst();

        assertEquals(3, line.glyphs().size());
        assertEquals(new TextRange(0, 1), range(line.glyphs().get(0)));
        assertEquals(new TextRange(1, 3), range(line.glyphs().get(1)));
        assertEquals(new TextRange(3, 7), range(line.glyphs().get(2)));
        assertEquals(0x2026, line.glyphs().get(2).glyphId());
        assertEquals(18.0f, line.width());
        for (PositionedGlyph glyph : line.glyphs()) {
            assertTrue(boundaries.isGraphemeBoundary(text, glyph.clusterStartUtf16()));
            assertTrue(boundaries.isGraphemeBoundary(text, glyph.clusterEndUtf16()));
        }
    }

    @Test
    void explicitTailTriggersSingleLineEllipsisEvenWhenPrefixFits() {
        String text = "AB\n中";
        TextLayout layout = layouter.layoutSingleLine(CHAIN, 16, text,
                100.0f, TextAlignment.END, true);
        TextLine line = layout.lines().getFirst();

        assertEquals(new TextRange(0, text.length()), range(line));
        assertEquals(3, line.glyphs().size());
        PositionedGlyph ellipsis = line.glyphs().getLast();
        assertEquals(new TextRange(2, text.length()), range(ellipsis));
        assertEquals(86.0f, line.glyphs().getFirst().x());
    }

    @Test
    void overlongLatinWordFallsBackToWholeClusterBreaks() {
        TextLayout layout = layouter.layout(CHAIN, 16, "ABC", 4.0f, TextAlignment.START);

        assertEquals(3, layout.lines().size());
        assertEquals(List.of(new TextRange(0, 1), new TextRange(1, 2), new TextRange(2, 3)),
                layout.lines().stream().map(TextLayouterTest::range).toList());
        assertTrue(layout.lines().stream().allMatch(line -> line.width() == 5.0f));
    }

    @Test
    void maximumLinesAddsClusterSafeEllipsisAndGraphemeModeUsesAvailableWidth() {
        String text = "AB CD EF";
        TextLayout truncated = layouter.layout(CHAIN, 16, text, 12.0f,
                TextAlignment.START, 2, true, TextWrapMode.LINE_BREAK);

        assertEquals(2, truncated.lines().size());
        TextLine finalLine = truncated.lines().getLast();
        assertEquals(text.length(), finalLine.endUtf16());
        assertEquals(0x2026, finalLine.glyphs().getLast().glyphId());
        assertEquals(text.length(), finalLine.glyphs().getLast().clusterEndUtf16());
        assertTrue(boundaries.isGraphemeBoundary(text,
                finalLine.glyphs().getLast().clusterStartUtf16()));

        TextLayout words = layouter.layout(CHAIN, 16, "A BC", 12.0f,
                TextAlignment.START, Integer.MAX_VALUE, false, TextWrapMode.LINE_BREAK);
        TextLayout graphemes = layouter.layout(CHAIN, 16, "A BC", 12.0f,
                TextAlignment.START, Integer.MAX_VALUE, false, TextWrapMode.GRAPHEME);
        assertEquals(new TextRange(0, 2), range(words.lines().getFirst()));
        assertEquals(new TextRange(0, 3), range(graphemes.lines().getFirst()));
    }

    @Test
    void emptyTextStillHasNaturalLineMetricsAndInvalidWidthsFail() {
        TextLayout empty = layouter.layout(CHAIN, 16, "", 0.0f, TextAlignment.START);

        assertEquals(1, empty.lines().size());
        assertEquals(new TextRange(0, 0), range(empty.lines().getFirst()));
        assertEquals(10.0f, empty.height());
        assertThrows(IllegalArgumentException.class,
                () -> layouter.layout(CHAIN, 16, "A", Float.NaN, TextAlignment.START));
        assertThrows(IllegalArgumentException.class,
                () -> layouter.layout(CHAIN, 16, "A", -1.0f, TextAlignment.START));
    }

    private List<TextRun> shape(FontFallbackChain chain, int ppem, String text) {
        if (text.isEmpty()) {
            return List.of(run(chain, ppem, LATIN_FACE, text));
        }
        List<Integer> graphemes = boundaries.graphemeBoundaries(text);
        List<TextRun> runs = new ArrayList<>();
        int runStart = 0;
        FontFaceId runFace = faceFor(text.codePointAt(0));
        for (int index = 1; index < graphemes.size() - 1; index++) {
            int clusterStart = graphemes.get(index);
            FontFaceId clusterFace = faceFor(text.codePointAt(clusterStart));
            if (!clusterFace.equals(runFace)) {
                runs.add(run(chain, ppem, runFace, text.substring(runStart, clusterStart)));
                runStart = clusterStart;
                runFace = clusterFace;
            }
        }
        runs.add(run(chain, ppem, runFace, text.substring(runStart)));
        return List.copyOf(runs);
    }

    private TextRun run(FontFallbackChain chain, int ppem, FontFaceId faceId, String text) {
        ShapingCacheKey key = new ShapingCacheKey(chain.fontGeneration(), faceId, ppem, text,
                TextDirection.LEFT_TO_RIGHT, "und");
        List<Integer> graphemes = boundaries.graphemeBoundaries(text);
        List<ShapedGlyph> glyphs = new ArrayList<>();
        float width = 0.0f;
        for (int index = 0; index < graphemes.size() - 1; index++) {
            int start = graphemes.get(index);
            int end = graphemes.get(index + 1);
            int codePoint = text.codePointAt(start);
            float advance = advance(codePoint, end - start);
            glyphs.add(new ShapedGlyph(codePoint, start, end, advance, 0.0f, 0.0f, 0.0f));
            width += advance;
        }
        return new TextRun(key, glyphs, width, 0.0f, 8.0f, 2.0f);
    }

    private static FontFaceId faceFor(int codePoint) {
        return isCjk(codePoint) ? CJK_FACE : LATIN_FACE;
    }

    private static float advance(int codePoint, int utf16Length) {
        if (codePoint == 0x2026) {
            return 4.0f;
        }
        if (Character.isWhitespace(codePoint)) {
            return 2.0f;
        }
        if (isCjk(codePoint)) {
            return 10.0f;
        }
        if (Character.isSupplementaryCodePoint(codePoint)) {
            return 9.0f;
        }
        if (utf16Length > Character.charCount(codePoint)) {
            return 6.0f;
        }
        return 5.0f;
    }

    private static boolean isCjk(int codePoint) {
        return codePoint >= 0x3400 && codePoint <= 0x9FFF;
    }

    private static TextRange range(TextLine line) {
        return new TextRange(line.startUtf16(), line.endUtf16());
    }

    private static TextRange range(PositionedGlyph glyph) {
        return new TextRange(glyph.clusterStartUtf16(), glyph.clusterEndUtf16());
    }
}
