package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.runtime.diagnostics.DiagnosticsLevel;
import com.kaleblangley.haikalat.runtime.diagnostics.FrameDiagnostics;
import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.event.KeyEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.PointerEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEventType;
import com.kaleblangley.haikalat.subsystems.ui.widget.Button;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.ui.widget.ScrollView;
import com.kaleblangley.haikalat.subsystems.windowing.input.ClipboardService;
import com.kaleblangley.haikalat.subsystems.windowing.input.Key;
import com.kaleblangley.haikalat.subsystems.windowing.input.KeyModifiers;
import com.kaleblangley.haikalat.subsystems.windowing.input.MouseButton;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsPanelTest {
    @Test
    void diagnosticRowsSupportAdditiveRangeAndClipboardSelection() {
        MemoryClipboard clipboard = new MemoryClipboard();
        try (FrameDiagnostics diagnostics = new FrameDiagnostics(DiagnosticsLevel.BASIC);
             UiDocument document = new UiDocument()) {
            DiagnosticsPanel panel = new DiagnosticsPanel(
                    diagnostics, Path.of("unused.json"), clipboard);
            document.root().add(panel.root());
            panel.show();
            ScrollView scroll = (ScrollView) panel.root().children().get(1);
            Panel body = (Panel) scroll.content();
            UiNode first = body.children().get(2);
            UiNode middle = body.children().get(3);
            UiNode last = body.children().get(4);

            click(document, first, KeyModifiers.NONE);
            click(document, last, modifiers(false, true));
            document.dispatch(last, key(Key.C, modifiers(false, true)));

            assertSame(last, document.focusManager().focused());
            assertTrue(((Button) first).pressed());
            assertTrue(((Button) last).pressed());
            assertEquals(((Button) first).text() + "\n" + ((Button) last).text(),
                    clipboard.value);

            click(document, middle, modifiers(true, false));
            assertTrue(!((Button) first).pressed());
            assertTrue(((Button) middle).pressed());
            assertTrue(((Button) last).pressed());

            document.dispatch(middle, key(Key.A, modifiers(false, true)));
            document.dispatch(middle, key(Key.C, modifiers(false, true)));
            assertEquals(body.children().stream()
                    .map(node -> ((Button) node).text())
                    .reduce((left, right) -> left + "\n" + right).orElse(""), clipboard.value);
        }
    }

    private static void click(UiDocument document, UiNode row, KeyModifiers modifiers) {
        document.dispatch(row, pointer(UiEventType.POINTER_DOWN, modifiers));
        document.dispatch(row, pointer(UiEventType.POINTER_UP, modifiers));
    }

    private static PointerEvent pointer(UiEventType type, KeyModifiers modifiers) {
        return new PointerEvent(type, 1, 1, modifiers, 0,
                10, 10, 0, 0, 0, 0, MouseButton.LEFT, 1);
    }

    private static KeyEvent key(Key key, KeyModifiers modifiers) {
        return new KeyEvent(UiEventType.KEY_DOWN, 3, 3, modifiers, key, 0, false);
    }

    private static KeyModifiers modifiers(boolean shift, boolean control) {
        return new KeyModifiers(shift, control, false, false, false, false);
    }

    private static final class MemoryClipboard implements ClipboardService {
        private String value = "";

        @Override
        public Optional<String> readText() {
            return Optional.of(value);
        }

        @Override
        public void writeText(String text) {
            value = text;
        }
    }
}
