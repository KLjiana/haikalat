package com.kaleblangley.haikalat.subsystems.ui;

import com.kaleblangley.haikalat.subsystems.ui.event.UiEventType;
import com.kaleblangley.haikalat.subsystems.ui.event.UiInputRouter;
import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox;
import com.kaleblangley.haikalat.subsystems.ui.widget.Button;
import com.kaleblangley.haikalat.subsystems.ui.widget.TextField;
import com.kaleblangley.haikalat.subsystems.windowing.input.KeyModifiers;
import com.kaleblangley.haikalat.subsystems.windowing.input.Key;
import com.kaleblangley.haikalat.subsystems.windowing.input.MouseButton;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputCollector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class UiInputRouterTest {
    @Test
    void routesSnapshotEdgesExactlyOnceAndCommitsUnicodeText() {
        try (UiDocument document = new UiDocument()) {
            document.root().applyLayout(new LayoutBox(0, 0, 300, 200));
            TextField field = new TextField();
            field.applyLayout(new LayoutBox(10, 10, 120, 30));
            document.root().add(field);
            UiInputRouter router = new UiInputRouter(document);
            WindowInputCollector collector = collectorAt(20, 20);
            collector.pressMouse(MouseButton.LEFT, KeyModifiers.NONE);

            var press = collector.snapshot();
            assertTrue(router.update(press) > 0);
            assertSame(field, document.focusManager().focused());
            assertThrows(IllegalArgumentException.class, () -> router.update(press));

            collector.releaseMouse(MouseButton.LEFT, KeyModifiers.NONE);
            collector.committedCodePoint('中');
            collector.committedCodePoint(0x1F642);
            router.update(collector.snapshot());
            assertEquals("中🙂", field.value());
            assertNull(document.pointerCapture().target(0));
        }
    }

    @Test
    void hoverDiffAndFocusLossCancelCapture() {
        try (UiDocument document = new UiDocument()) {
            document.root().applyLayout(new LayoutBox(0, 0, 300, 200));
            Button button = new Button("Drag");
            button.applyLayout(new LayoutBox(10, 10, 100, 30));
            document.root().add(button);
            List<UiEventType> types = new ArrayList<>();
            button.on(UiEventType.POINTER_ENTER, event -> types.add(event.type()));
            button.on(UiEventType.POINTER_LEAVE, event -> types.add(event.type()));
            button.on(UiEventType.POINTER_CANCEL, event -> types.add(event.type()));
            UiInputRouter router = new UiInputRouter(document);
            WindowInputCollector collector = collectorAt(20, 20);
            collector.pressMouse(MouseButton.LEFT, KeyModifiers.NONE);
            router.update(collector.snapshot());
            assertTrue(button.pressed());

            collector.focused(false);
            router.update(collector.snapshot());

            assertFalse(button.pressed());
            assertNull(document.pointerCapture().target(0));
            assertTrue(types.contains(UiEventType.POINTER_ENTER));
            assertTrue(types.contains(UiEventType.POINTER_CANCEL));
        }
    }

    @Test
    void repeatedBackspaceContinuesDeletingFocusedText() {
        try (UiDocument document = new UiDocument()) {
            TextField field = new TextField().value("中文AB");
            document.root().add(field);
            document.focusManager().requestFocus(field);
            UiInputRouter router = new UiInputRouter(document);
            WindowInputCollector collector = collectorAt(20, 20);

            collector.pressKey(Key.BACKSPACE, KeyModifiers.NONE);
            router.update(collector.snapshot());
            assertEquals("中文A", field.value());

            collector.repeatKey(Key.BACKSPACE, KeyModifiers.NONE);
            router.update(collector.snapshot());
            collector.repeatKey(Key.BACKSPACE, KeyModifiers.NONE);
            router.update(collector.snapshot());
            assertEquals("中", field.value());
        }
    }

    private static WindowInputCollector collectorAt(double x, double y) {
        WindowInputCollector collector = new WindowInputCollector();
        collector.windowSize(300, 200);
        collector.framebufferSize(300, 200);
        collector.focused(true);
        collector.cursorInside(true);
        collector.cursorPosition(x, y);
        return collector;
    }
}
