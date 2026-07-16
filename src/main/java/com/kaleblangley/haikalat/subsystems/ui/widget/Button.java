package com.kaleblangley.haikalat.subsystems.ui.widget;

import com.kaleblangley.haikalat.subsystems.ui.UiDirtyFlag;
import com.kaleblangley.haikalat.subsystems.ui.UiSemanticRole;
import com.kaleblangley.haikalat.subsystems.ui.event.KeyEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.PointerEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEventType;
import com.kaleblangley.haikalat.subsystems.windowing.input.Key;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 支持 pointer capture、键盘激活和状态样式的按钮。 */
public class Button extends Panel {
    private final Label label = new Label();
    private final List<Runnable> clickListeners = new ArrayList<>();
    private boolean hovered;
    private boolean pressed;
    private boolean focused;

    public Button() { this(""); }

    public Button(String text) {
        focusable(true);
        semantics(UiSemanticRole.BUTTON, text, "");
        label.text(text);
        add(label);
    }

    public String text() { return label.text(); }
    public Button text(String value) {
        label.text(Objects.requireNonNull(value, "text"));
        semantics(UiSemanticRole.BUTTON, value, "");
        return this;
    }
    public boolean hovered() { return hovered; }
    public boolean pressed() { return pressed; }
    public boolean focused() { return focused; }

    public AutoCloseable onClick(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        clickListeners.add(listener);
        return () -> clickListeners.remove(listener);
    }

    @Override
    protected void handleDefaultEvent(UiEvent event) {
        switch (event.type()) {
            case POINTER_ENTER -> setHovered(true);
            case POINTER_LEAVE, POINTER_CANCEL -> {
                setHovered(false);
                setPressed(false);
            }
            case POINTER_DOWN -> {
                if (enabled() && event instanceof PointerEvent pointer) {
                    setPressed(true);
                    event.requestFocus();
                    event.capturePointer(pointer.pointerId());
                }
            }
            case POINTER_UP -> {
                if (event instanceof PointerEvent pointer) {
                    boolean activate = enabled() && pressed()
                            && layoutBox().contains(pointer.x(), pointer.y());
                    setPressed(false);
                    event.releasePointer(pointer.pointerId());
                    if (activate) activate();
                }
            }
            case KEY_DOWN -> {
                if (enabled() && event instanceof KeyEvent key && activates(key.key())) {
                    setPressed(true);
                    event.preventDefault();
                }
            }
            case KEY_UP -> {
                if (event instanceof KeyEvent key && activates(key.key())) {
                    boolean activate = enabled() && pressed();
                    setPressed(false);
                    event.preventDefault();
                    if (activate) activate();
                }
            }
            case FOCUS_GAINED -> setFocused(true);
            case FOCUS_LOST -> {
                setFocused(false);
                setPressed(false);
            }
            default -> { }
        }
    }

    protected void activate() {
        for (Runnable listener : List.copyOf(clickListeners)) listener.run();
    }

    protected final void setHovered(boolean value) {
        if (hovered != value) {
            hovered = value;
            markDirty(UiDirtyFlag.STYLE, UiDirtyFlag.PAINT);
        }
    }

    protected final void setPressed(boolean value) {
        if (pressed != value) {
            pressed = value;
            markDirty(UiDirtyFlag.STYLE, UiDirtyFlag.PAINT);
        }
    }

    private void setFocused(boolean value) {
        if (focused != value) {
            focused = value;
            markDirty(UiDirtyFlag.STYLE, UiDirtyFlag.PAINT);
        }
    }

    private static boolean activates(Key key) { return key == Key.SPACE || key == Key.ENTER; }
}
