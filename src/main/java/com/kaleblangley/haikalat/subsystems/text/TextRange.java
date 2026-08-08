package com.kaleblangley.haikalat.subsystems.text;

/**
 * 以 UTF-16 offset 表示的半开文本范围。
 */
public record TextRange(int startUtf16, int endUtf16) {
    public TextRange {
        if (startUtf16 < 0 || endUtf16 < startUtf16) {
            throw new IllegalArgumentException("Invalid UTF-16 range: [" + startUtf16 + ", " + endUtf16 + ")");
        }
    }

    /** 返回该范围包含的 UTF-16 code unit 数量。 */
    public int lengthUtf16() {
        return endUtf16 - startUtf16;
    }

    /** 返回该范围是否为空。 */
    public boolean isEmpty() {
        return startUtf16 == endUtf16;
    }

    /** 创建位于指定 offset 的空范围。 */
    public static TextRange emptyAt(int utf16Offset) {
        return new TextRange(utf16Offset, utf16Offset);
    }
}
