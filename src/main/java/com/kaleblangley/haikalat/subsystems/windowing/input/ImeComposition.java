package com.kaleblangley.haikalat.subsystems.windowing.input;

import java.util.Objects;

/**
 * 输入法尚未提交的预编辑文本。
 *
 * @param text           UTF-16 预编辑文本
 * @param selectionStart composition 内选择起点
 * @param selectionEnd   composition 内选择终点
 * @param caretIndex     composition 内光标位置
 */
public record ImeComposition(String text, int selectionStart, int selectionEnd, int caretIndex) {
    public ImeComposition {
        Objects.requireNonNull(text, "text");
        if (selectionStart < 0 || selectionStart > selectionEnd
                || selectionEnd > text.length() || caretIndex < 0 || caretIndex > text.length()) {
            throw new IllegalArgumentException("composition indices must be within the text");
        }
    }
}
