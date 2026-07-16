package com.kaleblangley.haikalat.subsystems.ui.text;

/** 文本自动换行边界策略。 */
public enum TextWrapMode {
    /** Latin 优先单词边界，CJK 使用 Unicode 合法换行边界。 */
    LINE_BREAK,
    /** 每个完整 grapheme/shaping cluster 都可以换行。 */
    GRAPHEME
}
