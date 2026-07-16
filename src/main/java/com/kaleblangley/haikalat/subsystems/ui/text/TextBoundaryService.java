package com.kaleblangley.haikalat.subsystems.ui.text;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 集中提供 UTF-16、code point、grapheme、单词和换行边界。
 *
 * <p>所有公开 offset 均采用 Java {@link String} 的 UTF-16 offset。控件不应直接按
 * {@code char} 加减 caret，而应通过本服务移动或生成删除范围。</p>
 */
public final class TextBoundaryService {
    private static final int ZERO_WIDTH_JOINER = 0x200D;
    private static final int CARRIAGE_RETURN = 0x000D;
    private static final int LINE_FEED = 0x000A;

    private final Locale locale;

    /** 使用语言无关的 Unicode 默认规则。 */
    public TextBoundaryService() {
        this(Locale.ROOT);
    }

    /** 使用指定 locale 的单词与换行规则。 */
    public TextBoundaryService(Locale locale) {
        this.locale = Objects.requireNonNull(locale, "locale");
    }

    /** 返回配置的 locale。 */
    public Locale locale() {
        return locale;
    }

    /** 返回 code point 数量，而不是 UTF-16 code unit 数量。 */
    public int codePointCount(String text) {
        Objects.requireNonNull(text, "text");
        return text.codePointCount(0, text.length());
    }

    /** 返回包含 0 和文本末端的全部 code point 边界。 */
    public List<Integer> codePointBoundaries(String text) {
        Objects.requireNonNull(text, "text");
        List<Integer> result = new ArrayList<>(codePointCount(text) + 1);
        result.add(0);
        for (int offset = 0; offset < text.length(); ) {
            offset += Character.charCount(text.codePointAt(offset));
            result.add(offset);
        }
        return List.copyOf(result);
    }

    /** 判断 offset 是否没有落在 surrogate pair 中间。 */
    public boolean isCodePointBoundary(String text, int utf16Offset) {
        Objects.requireNonNull(text, "text");
        requireUtf16Offset(text, utf16Offset);
        return utf16Offset == 0 || utf16Offset == text.length()
                || !Character.isHighSurrogate(text.charAt(utf16Offset - 1))
                || !Character.isLowSurrogate(text.charAt(utf16Offset));
    }

    /** 将合法 code point 边界转换为 code point index。 */
    public int codePointIndex(String text, int utf16Offset) {
        Objects.requireNonNull(text, "text");
        if (!isCodePointBoundary(text, utf16Offset)) {
            throw new IllegalArgumentException("UTF-16 offset splits a surrogate pair: " + utf16Offset);
        }
        return text.codePointCount(0, utf16Offset);
    }

    /** 将 code point index 转换为 UTF-16 offset。 */
    public int utf16Offset(String text, int codePointIndex) {
        Objects.requireNonNull(text, "text");
        int count = codePointCount(text);
        if (codePointIndex < 0 || codePointIndex > count) {
            throw new IndexOutOfBoundsException("Code point index " + codePointIndex + " outside [0, " + count + "]");
        }
        return text.offsetByCodePoints(0, codePointIndex);
    }

    /** 返回严格位于当前 caret 前面的 code point 边界；文本开头返回 0。 */
    public int previousCodePointBoundary(String text, int utf16Offset) {
        Objects.requireNonNull(text, "text");
        requireUtf16Offset(text, utf16Offset);
        if (utf16Offset == 0) {
            return 0;
        }
        if (!isCodePointBoundary(text, utf16Offset)) {
            return utf16Offset - 1;
        }
        return text.offsetByCodePoints(utf16Offset, -1);
    }

    /** 返回严格位于当前 caret 后面的 code point 边界；文本末端返回末端。 */
    public int nextCodePointBoundary(String text, int utf16Offset) {
        Objects.requireNonNull(text, "text");
        requireUtf16Offset(text, utf16Offset);
        if (utf16Offset == text.length()) {
            return utf16Offset;
        }
        if (!isCodePointBoundary(text, utf16Offset)) {
            return utf16Offset + 1;
        }
        return text.offsetByCodePoints(utf16Offset, 1);
    }

    /** 返回扩展 grapheme cluster 边界，结果始终包含 0 和文本末端。 */
    public List<Integer> graphemeBoundaries(String text) {
        Objects.requireNonNull(text, "text");
        BreakIterator iterator = BreakIterator.getCharacterInstance(locale);
        iterator.setText(text);
        List<Integer> result = new ArrayList<>();
        for (int boundary = iterator.first(); boundary != BreakIterator.DONE; boundary = iterator.next()) {
            if (boundary == 0 || boundary == text.length() || isAllowedGraphemeBoundary(text, boundary)) {
                result.add(boundary);
            }
        }
        if (result.isEmpty() || result.get(0) != 0) {
            result.add(0, 0);
        }
        if (result.get(result.size() - 1) != text.length()) {
            result.add(text.length());
        }
        return List.copyOf(result);
    }

    /** 判断 offset 是否为允许放置 caret 的 grapheme 边界。 */
    public boolean isGraphemeBoundary(String text, int utf16Offset) {
        Objects.requireNonNull(text, "text");
        requireUtf16Offset(text, utf16Offset);
        return Collections.binarySearch(graphemeBoundaries(text), utf16Offset) >= 0;
    }

    /** 返回严格位于 caret 前面的 grapheme 边界；文本开头返回 0。 */
    public int previousGraphemeBoundary(String text, int utf16Offset) {
        return adjacentBoundary(graphemeBoundaries(requireTextAndOffset(text, utf16Offset)), utf16Offset, false);
    }

    /** 返回严格位于 caret 后面的 grapheme 边界；文本末端返回末端。 */
    public int nextGraphemeBoundary(String text, int utf16Offset) {
        return adjacentBoundary(graphemeBoundaries(requireTextAndOffset(text, utf16Offset)), utf16Offset, true);
    }

    /** {@link #previousGraphemeBoundary(String, int)} 的 caret 语义别名。 */
    public int previousCaretOffset(String text, int utf16Offset) {
        return previousGraphemeBoundary(text, utf16Offset);
    }

    /** {@link #nextGraphemeBoundary(String, int)} 的 caret 语义别名。 */
    public int nextCaretOffset(String text, int utf16Offset) {
        return nextGraphemeBoundary(text, utf16Offset);
    }

    /**
     * 返回 Backspace 应删除的完整 grapheme 范围。
     * 如果输入 offset 意外位于 cluster 内部，则删除整个所在 cluster，绝不拆分它。
     */
    public TextRange backwardDeletionRange(String text, int utf16Offset) {
        List<Integer> boundaries = graphemeBoundaries(requireTextAndOffset(text, utf16Offset));
        int index = Collections.binarySearch(boundaries, utf16Offset);
        if (index >= 0) {
            return index == 0
                    ? TextRange.emptyAt(0)
                    : new TextRange(boundaries.get(index - 1), boundaries.get(index));
        }
        int insertionPoint = -index - 1;
        return new TextRange(boundaries.get(insertionPoint - 1), boundaries.get(insertionPoint));
    }

    /**
     * 返回 Delete 应删除的完整 grapheme 范围。
     * 如果输入 offset 意外位于 cluster 内部，则删除整个所在 cluster，绝不拆分它。
     */
    public TextRange forwardDeletionRange(String text, int utf16Offset) {
        List<Integer> boundaries = graphemeBoundaries(requireTextAndOffset(text, utf16Offset));
        int index = Collections.binarySearch(boundaries, utf16Offset);
        if (index >= 0) {
            return index == boundaries.size() - 1
                    ? TextRange.emptyAt(utf16Offset)
                    : new TextRange(boundaries.get(index), boundaries.get(index + 1));
        }
        int insertionPoint = -index - 1;
        return new TextRange(boundaries.get(insertionPoint - 1), boundaries.get(insertionPoint));
    }

    /** 返回经过 grapheme 过滤的单词边界。 */
    public List<Integer> wordBoundaries(String text) {
        return filteredBreakBoundaries(requireText(text), BreakIterator.getWordInstance(locale));
    }

    /** 返回经过 grapheme 过滤的合法换行边界，CJK 文本通常允许逐字换行。 */
    public List<Integer> lineBreakBoundaries(String text) {
        return filteredBreakBoundaries(requireText(text), BreakIterator.getLineInstance(locale));
    }

    private List<Integer> filteredBreakBoundaries(String text, BreakIterator iterator) {
        List<Integer> graphemes = graphemeBoundaries(text);
        iterator.setText(text);
        List<Integer> result = new ArrayList<>();
        for (int boundary = iterator.first(); boundary != BreakIterator.DONE; boundary = iterator.next()) {
            if (Collections.binarySearch(graphemes, boundary) >= 0) {
                result.add(boundary);
            }
        }
        if (result.isEmpty() || result.get(0) != 0) {
            result.add(0, 0);
        }
        if (result.get(result.size() - 1) != text.length()) {
            result.add(text.length());
        }
        return List.copyOf(result);
    }

    private static int adjacentBoundary(List<Integer> boundaries, int offset, boolean next) {
        int index = Collections.binarySearch(boundaries, offset);
        if (index >= 0) {
            if (next) {
                return boundaries.get(Math.min(index + 1, boundaries.size() - 1));
            }
            return boundaries.get(Math.max(index - 1, 0));
        }
        int insertionPoint = -index - 1;
        return next ? boundaries.get(insertionPoint) : boundaries.get(insertionPoint - 1);
    }

    private static boolean isAllowedGraphemeBoundary(String text, int boundary) {
        if (!isStaticCodePointBoundary(text, boundary)) {
            return false;
        }
        int before = text.codePointBefore(boundary);
        int after = text.codePointAt(boundary);
        if (before == CARRIAGE_RETURN && after == LINE_FEED) {
            return false;
        }
        if (before == ZERO_WIDTH_JOINER || after == ZERO_WIDTH_JOINER) {
            return false;
        }
        if (isGraphemeExtension(after) || isEmojiTag(after)) {
            return false;
        }
        if (isRegionalIndicator(before) && isRegionalIndicator(after)) {
            int countBefore = 0;
            for (int offset = boundary; offset > 0; ) {
                int codePoint = text.codePointBefore(offset);
                if (!isRegionalIndicator(codePoint)) {
                    break;
                }
                countBefore++;
                offset -= Character.charCount(codePoint);
            }
            return countBefore % 2 == 0;
        }
        return true;
    }

    private static boolean isGraphemeExtension(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || codePoint >= 0xFE00 && codePoint <= 0xFE0F
                || codePoint >= 0xE0100 && codePoint <= 0xE01EF
                || codePoint >= 0x1F3FB && codePoint <= 0x1F3FF;
    }

    private static boolean isEmojiTag(int codePoint) {
        return codePoint >= 0xE0020 && codePoint <= 0xE007F;
    }

    private static boolean isRegionalIndicator(int codePoint) {
        return codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF;
    }

    private static boolean isStaticCodePointBoundary(String text, int offset) {
        return offset == 0 || offset == text.length()
                || !Character.isHighSurrogate(text.charAt(offset - 1))
                || !Character.isLowSurrogate(text.charAt(offset));
    }

    private static String requireTextAndOffset(String text, int utf16Offset) {
        requireText(text);
        requireUtf16Offset(text, utf16Offset);
        return text;
    }

    private static String requireText(String text) {
        return Objects.requireNonNull(text, "text");
    }

    private static void requireUtf16Offset(String text, int utf16Offset) {
        if (utf16Offset < 0 || utf16Offset > text.length()) {
            throw new IndexOutOfBoundsException(
                    "UTF-16 offset " + utf16Offset + " outside [0, " + text.length() + "]");
        }
    }
}
