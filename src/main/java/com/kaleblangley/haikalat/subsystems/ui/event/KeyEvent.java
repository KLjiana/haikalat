package com.kaleblangley.haikalat.subsystems.ui.event;

import com.kaleblangley.haikalat.subsystems.windowing.input.Key;
import com.kaleblangley.haikalat.subsystems.windowing.input.KeyModifiers;

import java.util.Objects;

/** 与平台键码解耦后的 UI 键盘事件。 */
public final class KeyEvent extends UiEvent {
    private final Key key;
    private final int scanCode;
    private final boolean repeat;

    public KeyEvent(UiEventType type, long timestampNanos, long sequence,
                    KeyModifiers modifiers, Key key, int scanCode, boolean repeat) {
        super(requireKeyType(type), timestampNanos, sequence, modifiers);
        this.key = Objects.requireNonNull(key, "key");
        this.scanCode = scanCode;
        this.repeat = repeat;
    }

    public Key key() { return key; }
    public int scanCode() { return scanCode; }
    public boolean repeat() { return repeat; }

    private static UiEventType requireKeyType(UiEventType type) {
        if (type != UiEventType.KEY_DOWN && type != UiEventType.KEY_UP) {
            throw new IllegalArgumentException(type + " is not a key event type");
        }
        return type;
    }
}
