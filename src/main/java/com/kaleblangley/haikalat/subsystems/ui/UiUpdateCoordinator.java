package com.kaleblangley.haikalat.subsystems.ui;

import com.kaleblangley.haikalat.subsystems.ui.event.UiInputRouter;
import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox;
import com.kaleblangley.haikalat.subsystems.ui.widget.TextField;
import com.kaleblangley.haikalat.subsystems.windowing.input.ImeComposition;
import com.kaleblangley.haikalat.subsystems.windowing.input.Key;
import com.kaleblangley.haikalat.subsystems.windowing.input.MouseButton;
import com.kaleblangley.haikalat.subsystems.windowing.input.QueuedTextInputClient;
import com.kaleblangley.haikalat.subsystems.windowing.input.TextInputAdapter;
import com.kaleblangley.haikalat.subsystems.windowing.input.TextInputRect;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;

import java.util.Objects;

/**
 * Owner-thread input and viewport phase of a UI update.
 *
 * <p>This component owns the ordering between composition, routed input and native
 * text-input synchronization. Layout, animation and painting remain coordinated by
 * the {@link UiSystem} facade.</p>
 */
final class UiUpdateCoordinator {
    private final UiDocument document;
    private final UiInputRouter inputRouter;
    private final TextInputAdapter textInputAdapter;
    private final QueuedTextInputClient textInputClient = new QueuedTextInputClient();
    private TextField activeTextField;
    private int previousWindowWidth = -1;
    private int previousWindowHeight = -1;
    private double previousContentScaleX = Double.NaN;
    private double previousContentScaleY = Double.NaN;
    private long inputEvents;
    private long dispatchedEvents;

    UiUpdateCoordinator(UiDocument document, TextInputAdapter textInputAdapter) {
        this.document = Objects.requireNonNull(document, "document");
        this.textInputAdapter = Objects.requireNonNull(textInputAdapter, "textInputAdapter");
        inputRouter = new UiInputRouter(document);
    }

    void routeInput(WindowInputSnapshot input) {
        Objects.requireNonNull(input, "input");
        invalidateViewportIfChanged(input);
        inputEvents = countInputEvents(input);
        applyComposition(input, false);
        dispatchedEvents = inputRouter.update(input);
        applyComposition(input, true);
        synchronizeTextInput(input);
    }

    long inputEvents() {
        return inputEvents;
    }

    long dispatchedEvents() {
        return dispatchedEvents;
    }

    void updateCandidateRect(WindowInputSnapshot input) {
        if (activeTextField == null) return;
        LayoutBox box = document.visualLayoutBox(activeTextField);
        LayoutBox nodeBox = activeTextField.layoutBox();
        LayoutBox contentBox = activeTextField.textContentBox();
        double logicalX = box.x() + contentBox.x() - nodeBox.x()
                + activeTextField.visibleCaretX();
        textInputAdapter.setCandidateRect(new TextInputRect(
                logicalX * input.contentScaleX(), box.y() * input.contentScaleY(),
                Math.max(1.0, input.contentScaleX()),
                Math.max(1.0, box.height() * input.contentScaleY())));
    }

    TextInputAdapter textInputAdapter() {
        return textInputAdapter;
    }

    void deactivateTextInput() {
        if (activeTextField == null) {
            textInputClient.clear();
            return;
        }
        TextField field = activeTextField;
        activeTextField = null;
        RuntimeException failure = null;
        try {
            textInputAdapter.deactivate(textInputClient);
        } catch (RuntimeException deactivateFailure) {
            failure = deactivateFailure;
        }
        try {
            if (!field.isClosed()) field.cancelComposition();
        } catch (RuntimeException cancelFailure) {
            if (failure == null) failure = cancelFailure;
            else failure.addSuppressed(cancelFailure);
        } finally {
            textInputClient.clear();
        }
        if (failure != null) throw failure;
    }

    private void invalidateViewportIfChanged(WindowInputSnapshot input) {
        if (previousWindowWidth == input.windowWidth()
                && previousWindowHeight == input.windowHeight()
                && Double.compare(previousContentScaleX, input.contentScaleX()) == 0
                && Double.compare(previousContentScaleY, input.contentScaleY()) == 0) {
            return;
        }
        previousWindowWidth = input.windowWidth();
        previousWindowHeight = input.windowHeight();
        previousContentScaleX = input.contentScaleX();
        previousContentScaleY = input.contentScaleY();
        document.root().markDirty(UiDirtyFlag.MEASURE, UiDirtyFlag.LAYOUT,
                UiDirtyFlag.PAINT, UiDirtyFlag.HIT_TEST);
        document.overlayRoot().markDirty(UiDirtyFlag.MEASURE, UiDirtyFlag.LAYOUT,
                UiDirtyFlag.PAINT, UiDirtyFlag.HIT_TEST);
    }

    private void applyComposition(WindowInputSnapshot input, boolean onlyPresent) {
        UiNode focused = document.focusManager().focused();
        if (!(focused instanceof TextField field)) return;
        if (input.composition().isPresent()) {
            field.updateComposition(input.composition().orElseThrow());
        } else if (!onlyPresent && field.composition() != null) {
            // Clear stale preedit before GLFW committed-char input is routed.
            field.cancelComposition();
        }
    }

    private void synchronizeTextInput(WindowInputSnapshot input) {
        TextField focused = input.focused()
                && document.focusManager().focused() instanceof TextField field ? field : null;
        if (focused != activeTextField) {
            if (activeTextField != null) {
                textInputAdapter.deactivate(textInputClient);
                if (!activeTextField.isClosed()) activeTextField.cancelComposition();
            }
            activeTextField = focused;
            textInputClient.clear();
            if (focused != null) textInputAdapter.activate(textInputClient);
        }
        if (activeTextField == null) {
            textInputClient.clear();
            return;
        }
        textInputClient.drain(command -> {
            if (command instanceof QueuedTextInputClient.Command.Started) {
                activeTextField.updateComposition(new ImeComposition("", 0, 0, 0));
            } else if (command instanceof QueuedTextInputClient.Command.Updated updated) {
                activeTextField.updateComposition(updated.composition());
            } else if (command instanceof QueuedTextInputClient.Command.Committed committed) {
                activeTextField.commitComposition(committed.text());
            } else if (command instanceof QueuedTextInputClient.Command.Cancelled) {
                activeTextField.cancelComposition();
            }
        });
    }

    private static long countInputEvents(WindowInputSnapshot input) {
        long count = input.cursorDeltaX() != 0.0 || input.cursorDeltaY() != 0.0 ? 1L : 0L;
        if (input.scrollX() != 0.0 || input.scrollY() != 0.0) count++;
        for (Key key : Key.values()) {
            if (input.keyPressed(key)) count++;
            if (input.keyReleased(key)) count++;
        }
        for (MouseButton button : MouseButton.values()) {
            if (input.mousePressed(button)) count++;
            if (input.mouseReleased(button)) count++;
        }
        count += input.committedCodePoints().remaining();
        if (input.composition().isPresent()) count++;
        return count;
    }

}
