package com.kaleblangley.haikalat.subsystems.ui.text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 将 fallback + HarfBuzz 输出转换为不可变的 Latin/CJK 文本布局。
 *
 * <p>公开 offset 均为原始 Java 字符串中的 UTF-16 offset。换行只会落在 HarfBuzz
 * cluster 边界；Latin 优先使用 Unicode line break，CJK 通常可以逐字换行。单行省略号
 * 自身也经过 fallback 和 shaping，并映射到完整的被省略原文范围。</p>
 *
 * <p>v0.11 正式支持从左到右的 Latin/CJK。完整 BiDi 和复杂脚本段落布局不属于本类合同。</p>
 */
public final class TextLayouter {
    private static final String ELLIPSIS = "\u2026";

    private final TextThreadOwner threadOwner = new TextThreadOwner();
    private final TextBoundaryService boundaries;
    private final RunShaper runShaper;

    public TextLayouter(TextShaper shaper) {
        this(shaper, new TextBoundaryService());
    }

    public TextLayouter(TextShaper shaper, TextBoundaryService boundaries) {
        Objects.requireNonNull(shaper, "shaper");
        this.boundaries = Objects.requireNonNull(boundaries, "boundaries");
        this.runShaper = (chain, ppem, text) -> shaper.shapeWithFallback(chain, ppem, text,
                TextDirection.LEFT_TO_RIGHT, "und", List.of());
    }

    TextLayouter(TextBoundaryService boundaries, RunShaper runShaper) {
        this.boundaries = Objects.requireNonNull(boundaries, "boundaries");
        this.runShaper = Objects.requireNonNull(runShaper, "runShaper");
    }

    /**
     * 完成显式换行与受宽度约束的多行布局。
     *
     * @param availableWidth 排版区域宽度；可以为正无穷，表示仅响应显式换行
     */
    public TextLayout layout(FontFallbackChain chain, int ppem, String text,
                             float availableWidth, TextAlignment alignment) {
        return layout(chain, ppem, text, availableWidth, alignment,
                Integer.MAX_VALUE, false, TextWrapMode.LINE_BREAK);
    }

    /**
     * 完成带最大行数、cluster-safe ellipsis 和明确换行策略的多行布局。
     */
    public TextLayout layout(FontFallbackChain chain, int ppem, String text,
                             float availableWidth, TextAlignment alignment,
                             int maximumLines, boolean ellipsis,
                             TextWrapMode wrapMode) {
        checkThread("TextLayouter.layout");
        validateArguments(chain, ppem, text, availableWidth, alignment);
        if (maximumLines <= 0) {
            throw new IllegalArgumentException("maximumLines must be positive");
        }
        Objects.requireNonNull(wrapMode, "wrapMode");
        List<LineDraft> drafts = new ArrayList<>();
        for (Paragraph paragraph : paragraphs(text)) {
            ShapedParagraph shaped = shapeParagraph(chain, ppem, text, paragraph);
            wrapParagraph(shaped, availableWidth, wrapMode, drafts);
        }
        if (drafts.size() > maximumLines) {
            drafts = truncateLines(chain, ppem, text, drafts,
                    maximumLines, availableWidth, ellipsis);
        }
        return position(chain.fontGeneration(), text, drafts, availableWidth, alignment);
    }

    /**
     * 完成单行布局；启用 ellipsis 时，溢出内容或显式换行后的内容会在完整 cluster 边界截断。
     *
     * @param availableWidth 排版区域宽度；可以为正无穷
     * @param ellipsis 是否在内容被截断时追加经过 shaping 的 U+2026
     */
    public TextLayout layoutSingleLine(FontFallbackChain chain, int ppem, String text,
                                       float availableWidth, TextAlignment alignment,
                                       boolean ellipsis) {
        checkThread("TextLayouter.layoutSingleLine");
        validateArguments(chain, ppem, text, availableWidth, alignment);
        Paragraph first = paragraphs(text).getFirst();
        ShapedParagraph source = shapeParagraph(chain, ppem, text, first);
        boolean truncated = first.breakEndUtf16() < text.length()
                || source.width() > availableWidth;
        if (!ellipsis || !truncated) {
            LineDraft draft = new LineDraft(first.startUtf16(), first.endUtf16(),
                    source.clusters(), source.width(), source.ascent(), source.descent());
            return position(chain.fontGeneration(), text, List.of(draft), availableWidth, alignment);
        }

        ShapedParagraph shapedEllipsis = shapeSynthetic(chain, ppem, ELLIPSIS);
        float ellipsisWidth = shapedEllipsis.width();
        int keptCount = 0;
        float keptWidth = 0.0f;
        while (keptCount < source.clusters().size()) {
            Cluster cluster = source.clusters().get(keptCount);
            float candidateWidth = checkedAdd(checkedAdd(keptWidth, cluster.advanceX()), ellipsisWidth);
            if (candidateWidth > availableWidth) {
                break;
            }
            keptWidth = checkedAdd(keptWidth, cluster.advanceX());
            keptCount++;
        }

        int omittedStart = keptCount < source.clusters().size()
                ? source.clusters().get(keptCount).startUtf16()
                : first.endUtf16();
        int omittedEnd = text.length();
        if (omittedStart >= omittedEnd) {
            throw new IllegalStateException("Ellipsis requires a non-empty omitted source range");
        }
        List<Cluster> displayed = new ArrayList<>(keptCount + shapedEllipsis.clusters().size());
        displayed.addAll(source.clusters().subList(0, keptCount));
        for (Cluster cluster : shapedEllipsis.clusters()) {
            displayed.add(cluster.remap(omittedStart, omittedEnd));
        }
        float width = checkedAdd(keptWidth, ellipsisWidth);
        float ascent = Math.max(source.ascent(), shapedEllipsis.ascent());
        float descent = Math.max(source.descent(), shapedEllipsis.descent());
        LineDraft draft = new LineDraft(0, text.length(), List.copyOf(displayed), width, ascent, descent);
        return position(chain.fontGeneration(), text, List.of(draft), availableWidth, alignment);
    }

    private ShapedParagraph shapeParagraph(FontFallbackChain chain, int ppem, String source,
                                           Paragraph paragraph) {
        String paragraphText = source.substring(paragraph.startUtf16(), paragraph.endUtf16());
        List<TextRun> runs = requireRuns(runShaper.shape(chain, ppem, paragraphText),
                chain.fontGeneration(), ppem, paragraphText);
        return flattenRuns(runs, paragraph.startUtf16(), paragraphText);
    }

    private ShapedParagraph shapeSynthetic(FontFallbackChain chain, int ppem, String text) {
        List<TextRun> runs = requireRuns(runShaper.shape(chain, ppem, text),
                chain.fontGeneration(), ppem, text);
        return flattenRuns(runs, 0, text);
    }

    private ShapedParagraph flattenRuns(List<TextRun> runs, int sourceOffset, String expectedText) {
        List<Cluster> clusters = new ArrayList<>();
        int runOffset = 0;
        float ascent = 0.0f;
        float descent = 0.0f;
        for (TextRun run : runs) {
            if (!expectedText.regionMatches(runOffset, run.text(), 0, run.text().length())) {
                throw new IllegalArgumentException("Shaping runs do not reproduce the requested text");
            }
            ascent = Math.max(ascent, run.ascent());
            descent = Math.max(descent, run.descent());
            int absoluteRunOffset = Math.addExact(sourceOffset, runOffset);
            for (ShapedGlyph glyph : run.glyphs()) {
                int start = Math.addExact(absoluteRunOffset, glyph.clusterStartUtf16());
                int end = Math.addExact(absoluteRunOffset, glyph.clusterEndUtf16());
                GlyphDraft glyphDraft = new GlyphDraft(run.faceId(), run.ppem(), glyph.glyphId(),
                        glyph.advanceX(), glyph.advanceY(), glyph.offsetX(), glyph.offsetY());
                if (!clusters.isEmpty()) {
                    Cluster previous = clusters.getLast();
                    if (previous.startUtf16() == start && previous.endUtf16() == end) {
                        previous.add(glyphDraft);
                        continue;
                    }
                    if (start < previous.endUtf16()) {
                        throw new IllegalArgumentException("Shaping clusters overlap or are not monotone");
                    }
                }
                clusters.add(new Cluster(start, end, glyphDraft));
            }
            runOffset = Math.addExact(runOffset, run.text().length());
        }
        if (runOffset != expectedText.length()) {
            throw new IllegalArgumentException("Shaping runs cover " + runOffset
                    + " UTF-16 units but requested text contains " + expectedText.length());
        }
        float width = 0.0f;
        for (Cluster cluster : clusters) {
            width = checkedAdd(width, cluster.advanceX());
        }
        return new ShapedParagraph(List.copyOf(clusters), width, ascent, descent,
                sourceOffset, expectedText);
    }

    private void wrapParagraph(ShapedParagraph paragraph, float availableWidth,
                               TextWrapMode wrapMode, List<LineDraft> output) {
        if (paragraph.clusters().isEmpty()) {
            output.add(new LineDraft(paragraph.sourceStartUtf16(), paragraph.sourceStartUtf16(),
                    List.of(), 0.0f, paragraph.ascent(), paragraph.descent()));
            return;
        }

        String sourceText = paragraph.sourceText();
        Set<Integer> legalBreaks = wrapMode == TextWrapMode.GRAPHEME
                ? new HashSet<>(boundaries.graphemeBoundaries(sourceText))
                : new HashSet<>(boundaries.lineBreakBoundaries(sourceText));
        int cursor = 0;
        while (cursor < paragraph.clusters().size()) {
            int end = cursor;
            int lastLegalEnd = -1;
            float width = 0.0f;
            while (end < paragraph.clusters().size()) {
                Cluster cluster = paragraph.clusters().get(end);
                float candidateWidth = checkedAdd(width, cluster.advanceX());
                if (candidateWidth > availableWidth && end > cursor) {
                    break;
                }
                width = candidateWidth;
                end++;
                int localEnd = cluster.endUtf16() - paragraph.sourceStartUtf16();
                if (legalBreaks.contains(localEnd)) {
                    lastLegalEnd = end;
                }
                if (candidateWidth > availableWidth) {
                    break;
                }
            }
            int selectedEnd;
            if (end == paragraph.clusters().size()) {
                selectedEnd = end;
            } else if (lastLegalEnd > cursor) {
                selectedEnd = lastLegalEnd;
            } else {
                selectedEnd = Math.max(cursor + 1, end);
            }
            List<Cluster> lineClusters = List.copyOf(paragraph.clusters().subList(cursor, selectedEnd));
            float lineWidth = clusterWidth(lineClusters);
            output.add(new LineDraft(lineClusters.getFirst().startUtf16(),
                    lineClusters.getLast().endUtf16(), lineClusters, lineWidth,
                    paragraph.ascent(), paragraph.descent()));
            cursor = selectedEnd;
        }
    }

    private List<LineDraft> truncateLines(FontFallbackChain chain, int ppem, String text,
                                          List<LineDraft> drafts, int maximumLines,
                                          float availableWidth, boolean ellipsis) {
        ArrayList<LineDraft> result = new ArrayList<>(drafts.subList(0, maximumLines));
        if (!ellipsis) return List.copyOf(result);

        LineDraft source = result.getLast();
        ShapedParagraph shapedEllipsis = shapeSynthetic(chain, ppem, ELLIPSIS);
        float ellipsisWidth = shapedEllipsis.width();
        ArrayList<Cluster> kept = new ArrayList<>(source.clusters());
        float keptWidth = source.width();
        while (!kept.isEmpty() && checkedAdd(keptWidth, ellipsisWidth) > availableWidth) {
            Cluster removed = kept.removeLast();
            keptWidth -= removed.advanceX();
        }
        int omittedStart = kept.isEmpty()
                ? source.startUtf16() : kept.getLast().endUtf16();
        if (omittedStart >= text.length()) {
            throw new IllegalStateException("Truncated text has no omitted source range");
        }
        for (Cluster cluster : shapedEllipsis.clusters()) {
            kept.add(cluster.remap(omittedStart, text.length()));
        }
        float width = checkedAdd(Math.max(0.0f, keptWidth), ellipsisWidth);
        result.set(result.size() - 1, new LineDraft(source.startUtf16(), text.length(),
                List.copyOf(kept), width,
                Math.max(source.ascent(), shapedEllipsis.ascent()),
                Math.max(source.descent(), shapedEllipsis.descent())));
        return List.copyOf(result);
    }

    private TextLayout position(FontGeneration generation, String text, List<LineDraft> drafts,
                                float availableWidth, TextAlignment alignment) {
        List<TextLine> lines = new ArrayList<>(drafts.size());
        float top = 0.0f;
        float widest = 0.0f;
        for (LineDraft draft : drafts) {
            float baseline = checkedAdd(top, draft.ascent());
            float x = alignmentOffset(availableWidth, draft.width(), alignment);
            List<PositionedGlyph> glyphs = new ArrayList<>();
            float penX = x;
            for (Cluster cluster : draft.clusters()) {
                for (GlyphDraft glyph : cluster.glyphs()) {
                    glyphs.add(new PositionedGlyph(glyph.faceId(), glyph.ppem(), glyph.glyphId(),
                            cluster.startUtf16(), cluster.endUtf16(),
                            checkedAdd(penX, glyph.offsetX()), baseline - glyph.offsetY(),
                            glyph.advanceX(), glyph.advanceY()));
                    penX = checkedAdd(penX, glyph.advanceX());
                }
            }
            lines.add(new TextLine(draft.startUtf16(), draft.endUtf16(), baseline, draft.width(),
                    draft.ascent(), draft.descent(), glyphs));
            top = checkedAdd(top, checkedAdd(draft.ascent(), draft.descent()));
            widest = Math.max(widest, draft.width());
        }
        float width = Float.isInfinite(availableWidth) ? widest : Math.max(availableWidth, widest);
        return new TextLayout(generation, text, lines, width, top);
    }

    private static List<TextRun> requireRuns(List<TextRun> runs, FontGeneration generation,
                                             int ppem, String expectedText) {
        List<TextRun> result = List.copyOf(Objects.requireNonNull(runs, "shaping result"));
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Shaping result must contain at least one run");
        }
        for (TextRun run : result) {
            Objects.requireNonNull(run, "run");
            if (!run.fontGeneration().equals(generation)) {
                throw new IllegalArgumentException("Shaping run uses a different font generation");
            }
            if (run.ppem() != ppem) {
                throw new IllegalArgumentException("Shaping run uses a different ppem");
            }
        }
        if (expectedText.isEmpty() && result.stream().anyMatch(run -> !run.text().isEmpty())) {
            throw new IllegalArgumentException("Empty text shaping result contains source characters");
        }
        return result;
    }

    private static List<Paragraph> paragraphs(String text) {
        List<Paragraph> result = new ArrayList<>();
        int start = 0;
        int offset = 0;
        while (offset < text.length()) {
            int codePoint = text.codePointAt(offset);
            if (!isLineTerminator(codePoint)) {
                offset += Character.charCount(codePoint);
                continue;
            }
            int breakStart = offset;
            offset += Character.charCount(codePoint);
            if (codePoint == '\r' && offset < text.length() && text.charAt(offset) == '\n') {
                offset++;
            }
            result.add(new Paragraph(start, breakStart, offset));
            start = offset;
        }
        result.add(new Paragraph(start, text.length(), text.length()));
        return List.copyOf(result);
    }

    private static boolean isLineTerminator(int codePoint) {
        return codePoint == '\n' || codePoint == '\r' || codePoint == 0x2028 || codePoint == 0x2029;
    }

    private static float alignmentOffset(float availableWidth, float contentWidth,
                                         TextAlignment alignment) {
        if (Float.isInfinite(availableWidth) || contentWidth >= availableWidth) {
            return 0.0f;
        }
        float remaining = availableWidth - contentWidth;
        return switch (alignment) {
            case START -> 0.0f;
            case CENTER -> remaining * 0.5f;
            case END -> remaining;
        };
    }

    private static float clusterWidth(List<Cluster> clusters) {
        float width = 0.0f;
        for (Cluster cluster : clusters) {
            width = checkedAdd(width, cluster.advanceX());
        }
        return width;
    }

    private static float checkedAdd(float left, float right) {
        float result = left + right;
        if (!Float.isFinite(result)) {
            throw new IllegalArgumentException("Text layout coordinate overflow");
        }
        return result;
    }

    private static void validateArguments(FontFallbackChain chain, int ppem, String text,
                                          float availableWidth, TextAlignment alignment) {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(alignment, "alignment");
        if (ppem <= 0) {
            throw new IllegalArgumentException("ppem must be positive");
        }
        if (Float.isNaN(availableWidth) || availableWidth < 0.0f
                || availableWidth == Float.NEGATIVE_INFINITY) {
            throw new IllegalArgumentException("availableWidth must be non-negative");
        }
    }

    private void checkThread(String operation) {
        threadOwner.check(operation);
    }

    @FunctionalInterface
    interface RunShaper {
        List<TextRun> shape(FontFallbackChain chain, int ppem, String text);
    }

    private record Paragraph(int startUtf16, int endUtf16, int breakEndUtf16) {
    }

    private record ShapedParagraph(List<Cluster> clusters, float width, float ascent, float descent,
                                   int sourceStartUtf16, String sourceText) {
    }

    private record LineDraft(int startUtf16, int endUtf16, List<Cluster> clusters,
                             float width, float ascent, float descent) {
    }

    private record GlyphDraft(FontFaceId faceId, int ppem, int glyphId,
                              float advanceX, float advanceY, float offsetX, float offsetY) {
    }

    private static final class Cluster {
        private final int startUtf16;
        private final int endUtf16;
        private final List<GlyphDraft> glyphs = new ArrayList<>();
        private float advanceX;

        private Cluster(int startUtf16, int endUtf16, GlyphDraft firstGlyph) {
            if (startUtf16 < 0 || endUtf16 <= startUtf16) {
                throw new IllegalArgumentException("Shaping cluster must be a non-empty UTF-16 range");
            }
            this.startUtf16 = startUtf16;
            this.endUtf16 = endUtf16;
            add(firstGlyph);
        }

        private void add(GlyphDraft glyph) {
            glyphs.add(Objects.requireNonNull(glyph, "glyph"));
            advanceX = checkedAdd(advanceX, glyph.advanceX());
        }

        private Cluster remap(int start, int end) {
            Cluster result = new Cluster(start, end, glyphs.getFirst());
            for (int index = 1; index < glyphs.size(); index++) {
                result.add(glyphs.get(index));
            }
            return result;
        }

        private int startUtf16() {
            return startUtf16;
        }

        private int endUtf16() {
            return endUtf16;
        }

        private List<GlyphDraft> glyphs() {
            return glyphs;
        }

        private float advanceX() {
            return advanceX;
        }
    }
}
