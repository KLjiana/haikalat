package com.kaleblangley.haikalat.subsystems.ui.event;

import com.kaleblangley.haikalat.subsystems.windowing.input.KeyModifiers;
import com.kaleblangley.haikalat.subsystems.windowing.input.MouseButton;

/** 鼠标或未来触摸设备产生的统一 pointer 事件。 */
public final class PointerEvent extends UiEvent {
    public static final int MOUSE_POINTER_ID = 0;

    private final int pointerId;
    private final double x;
    private final double y;
    private final double deltaX;
    private final double deltaY;
    private final double scrollX;
    private final double scrollY;
    private final MouseButton button;
    private final int clickCount;

    public PointerEvent(UiEventType type, long timestampNanos, long sequence,
                        KeyModifiers modifiers, int pointerId, double x, double y,
                        double deltaX, double deltaY, double scrollX, double scrollY,
                        MouseButton button, int clickCount) {
        super(requirePointerType(type), timestampNanos, sequence, modifiers);
        if (pointerId < 0) throw new IllegalArgumentException("pointerId must be non-negative");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(deltaX)
                || !Double.isFinite(deltaY) || !Double.isFinite(scrollX) || !Double.isFinite(scrollY)) {
            throw new IllegalArgumentException("pointer coordinates and deltas must be finite");
        }
        if (clickCount < 0) throw new IllegalArgumentException("clickCount must be non-negative");
        this.pointerId = pointerId;
        this.x = x;
        this.y = y;
        this.deltaX = deltaX;
        this.deltaY = deltaY;
        this.scrollX = scrollX;
        this.scrollY = scrollY;
        this.button = button;
        this.clickCount = clickCount;
    }

    public int pointerId() { return pointerId; }
    public double x() { return x; }
    public double y() { return y; }
    public double deltaX() { return deltaX; }
    public double deltaY() { return deltaY; }
    public double scrollX() { return scrollX; }
    public double scrollY() { return scrollY; }
    public MouseButton button() { return button; }
    public int clickCount() { return clickCount; }

    private static UiEventType requirePointerType(UiEventType type) {
        return switch (type) {
            case POINTER_MOVE, POINTER_DOWN, POINTER_UP, POINTER_ENTER, POINTER_LEAVE,
                    POINTER_CANCEL, SCROLL, CLICK -> type;
            default -> throw new IllegalArgumentException(type + " is not a pointer event type");
        };
    }
}
