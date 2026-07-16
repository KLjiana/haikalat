package com.kaleblangley.haikalat.subsystems.ui.widget;

import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.event.KeyEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.TextEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEventType;
import com.kaleblangley.haikalat.subsystems.windowing.input.ClipboardService;
import com.kaleblangley.haikalat.subsystems.windowing.input.ImeComposition;
import com.kaleblangley.haikalat.subsystems.windowing.input.Key;
import com.kaleblangley.haikalat.subsystems.windowing.input.KeyModifiers;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TextFieldTest {
    @Test
    void committedTextAndDeletionRespectGraphemeBoundaries() {
        try (UiDocument document = new UiDocument()) {
            TextField field = new TextField();
            document.root().add(field);
            document.focusManager().requestFocus(field);
            document.dispatch(field, new TextEvent(1, 1, KeyModifiers.NONE, "A👩‍💻e\u0301"));
            assertEquals("A👩‍💻e\u0301", field.value());

            document.dispatch(field, key(Key.BACKSPACE, KeyModifiers.NONE));
            assertEquals("A👩‍💻", field.value(), "combining sequence must be deleted as one grapheme");
            document.dispatch(field, key(Key.BACKSPACE, KeyModifiers.NONE));
            assertEquals("A", field.value(), "ZWJ emoji must be deleted as one grapheme");
        }
    }

    @Test
    void clipboardValidationUndoAndSelectionRemainAtomic() {
        try (UiDocument document = new UiDocument()) {
            MemoryClipboard clipboard = new MemoryClipboard();
            TextField field = new TextField().clipboard(clipboard)
                    .validator(value -> value.chars().allMatch(Character::isLetter));
            document.root().add(field);
            field.value("hello").select(1, 4);
            document.dispatch(field, key(Key.C, modifiers(true, false)));
            assertEquals("ell", clipboard.value);

            clipboard.value = "42";
            document.dispatch(field, key(Key.V, modifiers(true, false)));
            assertEquals("hello", field.value(), "invalid paste must preserve value and selection");
            assertEquals(1, field.selectionStart());
            assertEquals(4, field.selectionEnd());

            clipboard.value = "i";
            document.dispatch(field, key(Key.V, modifiers(true, false)));
            assertEquals("hio", field.value());
            document.dispatch(field, key(Key.Z, modifiers(true, false)));
            assertEquals("hello", field.value());
        }
    }

    @Test
    void controlASelectsCompleteUtf16Range() {
        try (UiDocument document = new UiDocument()) {
            TextField field = new TextField().value("A中文👩‍💻");
            document.root().add(field);
            document.focusManager().requestFocus(field);
            KeyEvent selectAll = key(Key.A, modifiers(true, false));

            document.dispatch(field, selectAll);

            assertTrue(field.hasSelection());
            assertEquals(0, field.selectionStart());
            assertEquals(field.value().length(), field.selectionEnd());
            assertTrue(selectAll.defaultPrevented());
        }
    }

    @Test
    void compositionIsTemporaryUntilCommitAndCancelDoesNotRollbackValue() {
        TextField field = new TextField().value("中");
        field.updateComposition(new ImeComposition("wen", 0, 3, 3));
        assertEquals("中", field.value());
        assertEquals("wen", field.composition().text());
        field.cancelComposition();
        assertNull(field.composition());
        assertEquals("中", field.value());

        field.updateComposition(new ImeComposition("文", 0, 1, 1));
        assertTrue(field.commitComposition("文"));
        assertEquals("中文", field.value());
        assertNull(field.composition());
        field.close();
    }

    @Test
    void listenerMutationConvergesWithoutRecursiveCorruption() {
        TextField field = new TextField();
        AtomicInteger calls = new AtomicInteger();
        field.onValueChanged(value -> {
            calls.incrementAndGet();
            if (value.equals("a")) field.value("ab");
        });

        field.value("a");

        assertEquals("ab", field.value());
        assertEquals(2, calls.get());
        field.close();
    }

    private static KeyEvent key(Key key, KeyModifiers modifiers) {
        return new KeyEvent(UiEventType.KEY_DOWN, 1, System.nanoTime(), modifiers, key, 0, false);
    }

    private static KeyModifiers modifiers(boolean control, boolean shift) {
        return new KeyModifiers(shift, control, false, false, false, false);
    }

    private static final class MemoryClipboard implements ClipboardService {
        private String value = "";
        @Override public Optional<String> readText() { return Optional.of(value); }
        @Override public void writeText(String text) { value = text; }
    }
}
