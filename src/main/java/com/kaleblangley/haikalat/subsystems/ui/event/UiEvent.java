package com.kaleblangley.haikalat.subsystems.ui.event;

import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.windowing.input.KeyModifiers;

import java.util.Objects;

/**
 * 一次可传播的 UI 事件。
 *
 * <p>事件只能由一个 {@code UiDocument} 分派一次；传播控制只影响本次稳定路径，
 * 不会直接修改 UI 树。</p>
 */
public abstract class UiEvent {
    private final UiEventType type;
    private final long timestampNanos;
    private final long sequence;
    private final KeyModifiers modifiers;
    private UiNode target;
    private UiNode currentTarget;
    private EventPhase phase;
    private boolean propagationStopped;
    private boolean defaultPrevented;
    private boolean dispatched;
    private boolean focusRequested;
    private UiNode focusRequester;
    private Integer capturePointerId;
    private UiNode capturePointerRequester;
    private Integer releasePointerId;

    protected UiEvent(UiEventType type, long timestampNanos, long sequence,
                      KeyModifiers modifiers) {
        if (timestampNanos < 0L) throw new IllegalArgumentException("timestampNanos must be non-negative");
        if (sequence < 0L) throw new IllegalArgumentException("sequence must be non-negative");
        this.type = Objects.requireNonNull(type, "type");
        this.timestampNanos = timestampNanos;
        this.sequence = sequence;
        this.modifiers = Objects.requireNonNullElse(modifiers, KeyModifiers.NONE);
    }

    public final UiEventType type() { return type; }
    public final long timestampNanos() { return timestampNanos; }
    public final long sequence() { return sequence; }
    public final KeyModifiers modifiers() { return modifiers; }
    public final UiNode target() { return target; }
    public final UiNode currentTarget() { return currentTarget; }
    public final EventPhase phase() { return phase; }
    public final boolean propagationStopped() { return propagationStopped; }
    public final boolean defaultPrevented() { return defaultPrevented; }

    public final void stopPropagation() { propagationStopped = true; }
    public final void continuePropagation() { propagationStopped = false; }
    public final void preventDefault() { defaultPrevented = true; }

    /** 默认行为完成后把键盘焦点交给当前处理节点。 */
    public final void requestFocus() {
        focusRequested = true;
        focusRequester = actionRequester();
    }

    /** 默认行为完成后由当前处理节点捕获指定 pointer。 */
    public final void capturePointer(int pointerId) {
        if (pointerId < 0) throw new IllegalArgumentException("pointerId must be non-negative");
        capturePointerId = pointerId;
        capturePointerRequester = actionRequester();
        releasePointerId = null;
    }

    /** 默认行为完成后释放指定 pointer。 */
    public final void releasePointer(int pointerId) {
        if (pointerId < 0) throw new IllegalArgumentException("pointerId must be non-negative");
        releasePointerId = pointerId;
        capturePointerId = null;
        capturePointerRequester = null;
    }

    public final void beginDispatch(UiNode value) {
        if (dispatched) throw new IllegalStateException("UI event has already been dispatched");
        target = Objects.requireNonNull(value, "target");
        dispatched = true;
    }

    public final void enter(UiNode value, EventPhase valuePhase) {
        currentTarget = Objects.requireNonNull(value, "currentTarget");
        phase = Objects.requireNonNull(valuePhase, "phase");
    }

    public final boolean focusRequested() { return focusRequested; }
    public final UiNode focusRequester() { return focusRequester; }
    public final Integer capturePointerId() { return capturePointerId; }
    public final UiNode capturePointerRequester() { return capturePointerRequester; }
    public final Integer releasePointerId() { return releasePointerId; }

    private UiNode actionRequester() {
        UiNode requester = currentTarget != null ? currentTarget : target;
        if (requester == null) {
            throw new IllegalStateException("UI event action must be requested during dispatch");
        }
        return requester;
    }
}
