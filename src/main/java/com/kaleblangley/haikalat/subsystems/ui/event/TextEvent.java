package com.kaleblangley.haikalat.subsystems.ui.event;

import com.kaleblangley.haikalat.subsystems.windowing.input.KeyModifiers;

import java.util.Objects;

/** 已提交且可写入控件值的 Unicode 文本事件。 */
public final class TextEvent extends UiEvent {
    private final String text;

    public TextEvent(long timestampNanos, long sequence, KeyModifiers modifiers, String text) {
        super(UiEventType.TEXT_INPUT, timestampNanos, sequence, modifiers);
        this.text = Objects.requireNonNull(text, "text");
        if (text.isEmpty()) throw new IllegalArgumentException("text must not be empty");
    }

    public String text() { return text; }
}
