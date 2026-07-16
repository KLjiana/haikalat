package com.kaleblangley.haikalat.subsystems.ui.event;

import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.windowing.input.KeyModifiers;

/** 键盘焦点身份变化事件。 */
public final class FocusEvent extends UiEvent {
    private final UiNode relatedTarget;

    public FocusEvent(UiEventType type, long timestampNanos, long sequence, UiNode relatedTarget) {
        super(requireFocusType(type), timestampNanos, sequence, KeyModifiers.NONE);
        this.relatedTarget = relatedTarget;
    }

    public UiNode relatedTarget() { return relatedTarget; }

    private static UiEventType requireFocusType(UiEventType type) {
        if (type != UiEventType.FOCUS_GAINED && type != UiEventType.FOCUS_LOST) {
            throw new IllegalArgumentException(type + " is not a focus event type");
        }
        return type;
    }
}
