package com.kaleblangley.haikalat.subsystems.ui.event;

import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.windowing.input.Key;
import com.kaleblangley.haikalat.subsystems.windowing.input.MouseButton;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 把窗口快照中的一次性边沿转换为 retained tree 事件。 */
public final class UiInputRouter {
    private final UiDocument document;
    private List<UiNode> hoverPath = List.of();
    private long lastSnapshotSequence = -1L;
    private long eventSequence;
    private long dispatchedEvents;

    public UiInputRouter(UiDocument document) {
        this.document = Objects.requireNonNull(document, "document");
    }

    /**
     * 消费一份新快照。重复或倒退的 sequence 会被拒绝，防止一次性边沿重复分派。
     *
     * @return 本次实际分派的事件数量
     */
    public long update(WindowInputSnapshot input) {
        Objects.requireNonNull(input, "input");
        document.ensureOpen();
        if (input.sequence() <= lastSnapshotSequence) {
            throw new IllegalArgumentException("input snapshot sequence must increase: previous="
                    + lastSnapshotSequence + ", current=" + input.sequence());
        }
        lastSnapshotSequence = input.sequence();
        long before = dispatchedEvents;
        long now = System.nanoTime();

        UiNode hit = input.focused() && input.cursorInside()
                ? document.hitTest(input.cursorX(), input.cursorY()) : null;
        List<UiNode> nextHover = hit == null ? List.of() : document.pathTo(hit);
        updateHoverPath(input, nextHover, now);

        if (!input.focused()) {
            UiNode captured = document.pointerCapture().target(PointerEvent.MOUSE_POINTER_ID);
            if (captured != null) {
                dispatch(captured, pointer(input, UiEventType.POINTER_CANCEL, null, 0, now));
            }
            document.pointerCapture().releaseAll();
        }
        UiNode pointerTarget = capturedOr(hit);
        if (input.focused() && pointerTarget != null
                && (input.cursorDeltaX() != 0.0 || input.cursorDeltaY() != 0.0)) {
            dispatch(pointerTarget, pointer(input, UiEventType.POINTER_MOVE, null, 0, now));
        }
        if (input.focused()) {
            for (MouseButton button : MouseButton.values()) {
                if (input.mousePressed(button) && hit != null) {
                    dispatch(hit, pointer(input, UiEventType.POINTER_DOWN, button, 1, now));
                }
                if (input.mouseReleased(button)) {
                    UiNode releaseTarget = capturedOr(hit);
                    if (releaseTarget != null) {
                        dispatch(releaseTarget, pointer(input, UiEventType.POINTER_UP, button, 1, now));
                    }
                    document.pointerCapture().release(PointerEvent.MOUSE_POINTER_ID);
                }
            }
        }
        if (input.focused() && (input.scrollX() != 0.0 || input.scrollY() != 0.0)
                && pointerTarget != null) {
            dispatch(pointerTarget, pointer(input, UiEventType.SCROLL, null, 0, now));
        }

        UiNode keyboardTarget = document.focusManager().focused();
        if (keyboardTarget == null) keyboardTarget = document.root();
        for (Key key : Key.values()) {
            if (input.keyPressed(key)) {
                KeyEvent event = new KeyEvent(UiEventType.KEY_DOWN, now, nextEventSequence(),
                        input.modifiers(), key, 0, false);
                dispatch(keyboardTarget, event);
                if (key == Key.TAB && !event.defaultPrevented()) {
                    document.focusManager().focusNext(input.modifiers().shift());
                    keyboardTarget = document.focusManager().focused();
                    if (keyboardTarget == null) keyboardTarget = document.root();
                }
            }
            if (input.keyRepeated(key)) {
                dispatch(keyboardTarget, new KeyEvent(UiEventType.KEY_DOWN, now,
                        nextEventSequence(), input.modifiers(), key, 0, true));
            }
            if (input.keyReleased(key)) {
                dispatch(keyboardTarget, new KeyEvent(UiEventType.KEY_UP, now, nextEventSequence(),
                        input.modifiers(), key, 0, false));
            }
        }
        IntBuffer codePoints = input.committedCodePoints();
        if (codePoints.hasRemaining()) {
            StringBuilder text = new StringBuilder(codePoints.remaining());
            while (codePoints.hasRemaining()) text.appendCodePoint(codePoints.get());
            UiNode textTarget = document.focusManager().focused();
            if (textTarget != null) {
                dispatch(textTarget, new TextEvent(now, nextEventSequence(), input.modifiers(), text.toString()));
            }
        }

        if (!input.focused()) hoverPath = List.of();
        return dispatchedEvents - before;
    }

    public long lastSnapshotSequence() { return lastSnapshotSequence; }
    public long dispatchedEvents() { return dispatchedEvents; }

    private void updateHoverPath(WindowInputSnapshot input, List<UiNode> next, long now) {
        int common = 0;
        int limit = Math.min(hoverPath.size(), next.size());
        while (common < limit && hoverPath.get(common) == next.get(common)) common++;
        for (int index = hoverPath.size() - 1; index >= common; index--) {
            UiNode node = hoverPath.get(index);
            if (!node.isClosed() && node.document() == document) {
                dispatch(node, pointer(input, UiEventType.POINTER_LEAVE, null, 0, now));
            }
        }
        for (int index = common; index < next.size(); index++) {
            dispatch(next.get(index), pointer(input, UiEventType.POINTER_ENTER, null, 0, now));
        }
        hoverPath = List.copyOf(next);
    }

    private UiNode capturedOr(UiNode fallback) {
        UiNode captured = document.pointerCapture().target(PointerEvent.MOUSE_POINTER_ID);
        return captured == null ? fallback : captured;
    }

    private PointerEvent pointer(WindowInputSnapshot input, UiEventType type, MouseButton button,
                                 int clickCount, long timestamp) {
        return new PointerEvent(type, timestamp, nextEventSequence(), input.modifiers(),
                PointerEvent.MOUSE_POINTER_ID, input.cursorX(), input.cursorY(),
                input.cursorDeltaX(), input.cursorDeltaY(), input.scrollX(), input.scrollY(),
                button, clickCount);
    }

    private void dispatch(UiNode target, UiEvent event) {
        document.dispatch(target, event);
        dispatchedEvents++;
    }

    private long nextEventSequence() { return ++eventSequence; }
}
