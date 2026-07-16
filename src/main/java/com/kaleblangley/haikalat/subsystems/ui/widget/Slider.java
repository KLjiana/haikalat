package com.kaleblangley.haikalat.subsystems.ui.widget;

import com.kaleblangley.haikalat.subsystems.ui.UiDirtyFlag;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.UiSemanticRole;
import com.kaleblangley.haikalat.subsystems.ui.event.KeyEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.PointerEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEvent;
import com.kaleblangley.haikalat.subsystems.windowing.input.Key;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleConsumer;

/** 有界、可步进且支持 pointer/keyboard 的水平 slider。 */
public final class Slider extends UiNode {
    private final List<DoubleConsumer> valueListeners = new ArrayList<>();
    private double minimum;
    private double maximum;
    private double step;
    private double value;
    private boolean dragging;

    public Slider(double minimum, double maximum, double value) {
        range(minimum, maximum);
        step = 0.0;
        this.value = clamp(value);
        focusable(true);
        updateSemantics();
    }

    public double minimum() { return minimum; }
    public double maximum() { return maximum; }
    public double step() { return step; }
    public double value() { return value; }
    public boolean dragging() { return dragging; }

    public Slider range(double min, double max) {
        if (!Double.isFinite(min) || !Double.isFinite(max) || min >= max) {
            throw new IllegalArgumentException("slider range must be finite and minimum < maximum");
        }
        minimum = min;
        maximum = max;
        value = clamp(value);
        updateSemantics();
        markDirty(UiDirtyFlag.PAINT, UiDirtyFlag.SEMANTICS);
        return this;
    }

    public Slider step(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException("slider step must be finite and non-negative");
        }
        step = value;
        return this;
    }

    public Slider value(double newValue) {
        if (!Double.isFinite(newValue)) throw new IllegalArgumentException("slider value must be finite");
        newValue = quantize(clamp(newValue));
        if (Double.compare(value, newValue) != 0) {
            value = newValue;
            updateSemantics();
            markDirty(UiDirtyFlag.PAINT, UiDirtyFlag.SEMANTICS);
            for (DoubleConsumer listener : List.copyOf(valueListeners)) listener.accept(value);
        }
        return this;
    }

    public AutoCloseable onValueChanged(DoubleConsumer listener) {
        Objects.requireNonNull(listener, "listener");
        valueListeners.add(listener);
        return () -> valueListeners.remove(listener);
    }

    @Override
    protected void handleDefaultEvent(UiEvent event) {
        switch (event.type()) {
            case POINTER_DOWN -> {
                if (enabled() && event instanceof PointerEvent pointer) {
                    dragging = true;
                    event.requestFocus();
                    event.capturePointer(pointer.pointerId());
                    updateFromPointer(pointer.x());
                }
            }
            case POINTER_MOVE -> {
                if (dragging && event instanceof PointerEvent pointer) updateFromPointer(pointer.x());
            }
            case POINTER_UP, POINTER_CANCEL -> {
                if (event instanceof PointerEvent pointer) event.releasePointer(pointer.pointerId());
                if (dragging) {
                    dragging = false;
                    markDirty(UiDirtyFlag.STYLE, UiDirtyFlag.PAINT);
                }
            }
            case KEY_DOWN -> {
                if (enabled() && event instanceof KeyEvent key) {
                    double increment = step > 0.0 ? step : (maximum - minimum) / 100.0;
                    if (key.key() == Key.LEFT || key.key() == Key.DOWN) {
                        value(value - increment);
                        event.preventDefault();
                    } else if (key.key() == Key.RIGHT || key.key() == Key.UP) {
                        value(value + increment);
                        event.preventDefault();
                    } else if (key.key() == Key.HOME) {
                        value(minimum);
                        event.preventDefault();
                    } else if (key.key() == Key.END) {
                        value(maximum);
                        event.preventDefault();
                    }
                }
            }
            default -> { }
        }
    }

    private void updateFromPointer(double x) {
        double width = layoutBox().width();
        double normalized = width <= 0.0 ? 0.0 : (x - layoutBox().x()) / width;
        value(minimum + Math.max(0.0, Math.min(1.0, normalized)) * (maximum - minimum));
    }

    private double clamp(double candidate) { return Math.max(minimum, Math.min(maximum, candidate)); }
    private double quantize(double candidate) {
        if (step == 0.0) return candidate;
        double steps = Math.rint((candidate - minimum) / step);
        return clamp(minimum + steps * step);
    }
    private void updateSemantics() {
        semantics(UiSemanticRole.SLIDER, "", Double.toString(value));
    }
}
