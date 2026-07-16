package com.kaleblangley.haikalat.subsystems.ui.widget;

import com.kaleblangley.haikalat.subsystems.ui.UiDirtyFlag;
import com.kaleblangley.haikalat.subsystems.ui.UiSemanticRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** 带变更通知的二态按钮。 */
public final class Toggle extends Button {
    private final List<Consumer<Boolean>> valueListeners = new ArrayList<>();
    private boolean value;

    public Toggle(String text) {
        super(text);
        updateSemantics();
    }

    public boolean value() { return value; }
    public Toggle value(boolean newValue) {
        if (value != newValue) {
            value = newValue;
            updateSemantics();
            markDirty(UiDirtyFlag.STYLE, UiDirtyFlag.PAINT, UiDirtyFlag.SEMANTICS);
            for (Consumer<Boolean> listener : List.copyOf(valueListeners)) listener.accept(value);
        }
        return this;
    }

    public AutoCloseable onValueChanged(Consumer<Boolean> listener) {
        Objects.requireNonNull(listener, "listener");
        valueListeners.add(listener);
        return () -> valueListeners.remove(listener);
    }

    @Override
    protected void activate() {
        value(!value);
        super.activate();
    }

    private void updateSemantics() {
        semantics(UiSemanticRole.CHECKBOX, text(), Boolean.toString(value));
    }
}
