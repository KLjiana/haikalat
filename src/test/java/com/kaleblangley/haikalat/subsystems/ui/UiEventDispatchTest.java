package com.kaleblangley.haikalat.subsystems.ui;

import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox;
import com.kaleblangley.haikalat.subsystems.ui.event.EventPhase;
import com.kaleblangley.haikalat.subsystems.ui.event.PointerEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEventType;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.ui.widget.ScrollView;
import com.kaleblangley.haikalat.subsystems.windowing.input.KeyModifiers;
import com.kaleblangley.haikalat.subsystems.windowing.input.MouseButton;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UiEventDispatchTest {
    @Test
    void dispatchKeepsCaptureTargetAndBubbleOrder() {
        try (UiDocument document = new UiDocument()) {
            Panel parent = new Panel();
            DefaultPanel target = new DefaultPanel();
            document.root().add(parent);
            parent.add(target);
            List<String> order = new ArrayList<>();
            document.root().on(UiEventType.POINTER_DOWN, EventPhase.CAPTURE,
                    event -> order.add("root-capture"));
            parent.on(UiEventType.POINTER_DOWN, EventPhase.CAPTURE,
                    event -> order.add("parent-capture"));
            target.on(UiEventType.POINTER_DOWN, EventPhase.CAPTURE,
                    event -> order.add("target-capture"));
            target.on(UiEventType.POINTER_DOWN, event -> order.add("target"));
            parent.on(UiEventType.POINTER_DOWN, event -> order.add("parent-bubble"));
            document.root().on(UiEventType.POINTER_DOWN, event -> order.add("root-bubble"));
            target.defaultOrder = order;

            document.dispatch(target, pointer(UiEventType.POINTER_DOWN));

            assertEquals(List.of("root-capture", "parent-capture", "target-capture", "target",
                    "default", "parent-bubble", "root-bubble"), order);
        }
    }

    @Test
    void dispatchUsesStablePathWhileTreeMutationsAreQueued() {
        try (UiDocument document = new UiDocument()) {
            Panel parent = new Panel();
            Panel target = new Panel();
            document.root().add(parent);
            parent.add(target);
            List<String> order = new ArrayList<>();
            parent.on(UiEventType.POINTER_DOWN, EventPhase.CAPTURE, event -> {
                order.add("remove");
                document.root().remove(parent);
            });
            target.on(UiEventType.POINTER_DOWN, event -> order.add("target"));
            parent.on(UiEventType.POINTER_DOWN, event -> order.add("bubble"));

            document.dispatch(target, pointer(UiEventType.POINTER_DOWN));

            assertEquals(List.of("remove", "target", "bubble"), order);
            assertNull(parent.parent());
            assertSame(document, parent.document(), "detached nodes retain document ownership in v0.11");
        }
    }

    @Test
    void listenerFailureDiscardsQueuedMutations() {
        try (UiDocument document = new UiDocument()) {
            Panel target = new Panel();
            document.root().add(target);
            target.on(UiEventType.POINTER_DOWN, event -> {
                document.root().remove(target);
                throw new IllegalStateException("listener failed");
            });

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> document.dispatch(target, pointer(UiEventType.POINTER_DOWN)));
            assertEquals("listener failed", failure.getMessage());
            assertSame(document.root(), target.parent());
        }
    }

    @Test
    void preventDefaultAndPropagationControlsAreIndependent() {
        try (UiDocument document = new UiDocument()) {
            DefaultPanel target = new DefaultPanel();
            document.root().add(target);
            List<String> order = new ArrayList<>();
            target.defaultOrder = order;
            target.on(UiEventType.POINTER_DOWN, event -> {
                event.preventDefault();
                event.stopPropagation();
                order.add("listener");
            });
            document.root().on(UiEventType.POINTER_DOWN, event -> order.add("root"));

            document.dispatch(target, pointer(UiEventType.POINTER_DOWN));

            assertEquals(List.of("listener"), order);
        }
    }

    @Test
    void eventRequestsFocusAndPointerCaptureAfterDispatch() {
        try (UiDocument document = new UiDocument()) {
            Panel target = new Panel();
            target.focusable(true);
            document.root().add(target);
            target.on(UiEventType.POINTER_DOWN, event -> {
                event.requestFocus();
                event.capturePointer(PointerEvent.MOUSE_POINTER_ID);
            });

            document.dispatch(target, pointer(UiEventType.POINTER_DOWN));

            assertSame(target, document.focusManager().focused());
            assertSame(target, document.pointerCapture().target(PointerEvent.MOUSE_POINTER_ID));
        }
    }

    @Test
    void ancestorDefaultActionRunsDuringBubbleAndOwnsRequestedActions() {
        try (UiDocument document = new UiDocument()) {
            DefaultPanel control = new DefaultPanel();
            Panel label = new Panel();
            control.focusable(true);
            document.root().add(control);
            control.add(label);
            List<String> order = new ArrayList<>();
            control.defaultOrder = order;
            control.on(UiEventType.POINTER_DOWN, event -> order.add("control-bubble"));
            control.defaultAction = event -> {
                event.requestFocus();
                event.capturePointer(PointerEvent.MOUSE_POINTER_ID);
            };

            document.dispatch(label, pointer(UiEventType.POINTER_DOWN));

            assertEquals(List.of("control-bubble", "default"), order);
            assertSame(control, document.focusManager().focused());
            assertSame(control, document.pointerCapture().target(PointerEvent.MOUSE_POINTER_ID));
        }
    }

    @Test
    void preventedChildDefaultDoesNotRunAncestorDefault() {
        try (UiDocument document = new UiDocument()) {
            DefaultPanel parent = new DefaultPanel();
            DefaultPanel child = new DefaultPanel();
            document.root().add(parent);
            parent.add(child);
            List<String> order = new ArrayList<>();
            parent.defaultOrder = order;
            child.defaultOrder = order;
            child.defaultAction = UiEvent::preventDefault;

            document.dispatch(child, pointer(UiEventType.POINTER_DOWN));

            assertEquals(List.of("default"), order);
        }
    }

    @Test
    void removingFocusedCapturedSubtreeSendsLifecycleEventsBeforeDetach() {
        try (UiDocument document = new UiDocument()) {
            Panel parent = new Panel();
            Panel target = new Panel();
            target.focusable(true);
            document.root().add(parent);
            parent.add(target);
            document.focusManager().requestFocus(target);
            document.pointerCapture().capture(0, target);
            List<UiEventType> lifecycle = new ArrayList<>();
            target.on(UiEventType.POINTER_CANCEL, event -> {
                assertSame(parent, target.parent());
                lifecycle.add(event.type());
            });
            target.on(UiEventType.FOCUS_LOST, event -> {
                assertSame(parent, target.parent());
                lifecycle.add(event.type());
            });

            document.root().remove(parent);

            assertEquals(List.of(UiEventType.POINTER_CANCEL, UiEventType.FOCUS_LOST), lifecycle);
            assertNull(document.focusManager().focused());
            assertNull(document.pointerCapture().target(0));
            assertNull(parent.parent());
        }
    }

    @Test
    void closeDuringDispatchIsDeferredUntilStablePathCompletes() {
        try (UiDocument document = new UiDocument()) {
            Panel parent = new Panel();
            Panel target = new Panel();
            document.root().add(parent);
            parent.add(target);
            List<String> order = new ArrayList<>();
            parent.on(UiEventType.POINTER_DOWN, EventPhase.CAPTURE, event -> target.close());
            target.on(UiEventType.POINTER_DOWN, event -> order.add("target"));
            parent.on(UiEventType.POINTER_DOWN, event -> order.add("bubble"));

            document.dispatch(target, pointer(UiEventType.POINTER_DOWN));

            assertEquals(List.of("target", "bubble"), order);
            assertTrue(target.isClosed());
            assertTrue(parent.children().isEmpty());
        }
    }

    @Test
    void hitTestAndVisualBoundsApplyScrollExactlyOnce() {
        try (UiDocument document = new UiDocument()) {
            ScrollView scroll = new ScrollView();
            Panel child = new Panel();
            scroll.applyLayout(new LayoutBox(0, 0, 100, 100));
            child.applyLayout(new LayoutBox(10, 100, 40, 20));
            scroll.content(child).scrollTo(0, 80);
            document.root().add(scroll);

            assertEquals(new LayoutBox(10, 20, 40, 20), document.visualLayoutBox(child));
            assertSame(child, document.hitTest(20, 25));
            assertNotSame(child, document.hitTest(20, 105));
        }
    }

    @Test
    void emptyOverlayRootDoesNotBlockNormalTreeHitTest() {
        try (UiDocument document = new UiDocument()) {
            document.root().applyLayout(new LayoutBox(0, 0, 200, 120));
            document.overlayRoot().applyLayout(new LayoutBox(0, 0, 200, 120));
            Panel normal = new Panel();
            normal.applyLayout(new LayoutBox(10, 10, 80, 40));
            document.root().add(normal);

            assertSame(normal, document.hitTest(20, 20));

            Panel overlay = new Panel();
            overlay.applyLayout(new LayoutBox(15, 15, 30, 20));
            document.overlayRoot().add(overlay);
            assertSame(overlay, document.hitTest(20, 20));
        }
    }

    private static PointerEvent pointer(UiEventType type) {
        return new PointerEvent(type, 1L, 1L, KeyModifiers.NONE,
                PointerEvent.MOUSE_POINTER_ID, 10.0, 20.0, 0.0, 0.0,
                0.0, 0.0, MouseButton.LEFT, 1);
    }

    private static final class DefaultPanel extends Panel {
        private List<String> defaultOrder;
        private java.util.function.Consumer<UiEvent> defaultAction;

        @Override
        protected void handleDefaultEvent(UiEvent event) {
            if (event.type() != UiEventType.POINTER_DOWN) return;
            if (defaultOrder != null) defaultOrder.add("default");
            if (defaultAction != null) defaultAction.accept(event);
        }
    }
}
