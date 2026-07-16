package com.kaleblangley.haikalat.subsystems.ui.widget;

import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.event.KeyEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.PointerEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEventType;
import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox;
import com.kaleblangley.haikalat.subsystems.windowing.input.Key;
import com.kaleblangley.haikalat.subsystems.windowing.input.KeyModifiers;
import com.kaleblangley.haikalat.subsystems.windowing.input.MouseButton;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WidgetBehaviorTest {
    @Test
    void buttonCapturesAndClicksOnlyWhenReleasedInside() {
        try (UiDocument document = new UiDocument()) {
            Button button = new Button("Apply");
            button.applyLayout(new LayoutBox(10, 10, 100, 30));
            document.root().add(button);
            AtomicInteger clicks = new AtomicInteger();
            button.onClick(clicks::incrementAndGet);

            document.dispatch(button, pointer(UiEventType.POINTER_DOWN, 20, 20));
            assertTrue(button.pressed());
            assertSame(button, document.pointerCapture().target(0));
            document.dispatch(button, pointer(UiEventType.POINTER_UP, 20, 20));
            assertEquals(1, clicks.get());
            assertFalse(button.pressed());

            document.dispatch(button, pointer(UiEventType.POINTER_DOWN, 20, 20));
            document.dispatch(button, pointer(UiEventType.POINTER_UP, 200, 200));
            assertEquals(1, clicks.get());
        }
    }

    @Test
    void buttonReceivesPointerEventsTargetedAtItsLabelChild() {
        try (UiDocument document = new UiDocument()) {
            Button button = new Button("Apply");
            button.applyLayout(new LayoutBox(10, 10, 100, 30));
            button.children().getFirst().applyLayout(new LayoutBox(12, 12, 80, 24));
            document.root().add(button);
            AtomicInteger clicks = new AtomicInteger();
            button.onClick(clicks::incrementAndGet);

            document.dispatch(button.children().getFirst(),
                    pointer(UiEventType.POINTER_DOWN, 20, 20));
            assertTrue(button.pressed());
            assertSame(button, document.pointerCapture().target(0));
            document.dispatch(button, pointer(UiEventType.POINTER_UP, 20, 20));

            assertEquals(1, clicks.get());
            assertSame(button, document.focusManager().focused());
        }
    }

    @Test
    void nestedScrollViewConsumesWheelAtNearestDescendantOwner() {
        try (UiDocument document = new UiDocument()) {
            ScrollView outer = new ScrollView();
            Panel outerContent = new Panel();
            ScrollView inner = new ScrollView();
            Panel innerContent = new Panel();
            inner.content(innerContent);
            outerContent.add(inner);
            outer.content(outerContent);
            document.root().add(outer);

            document.dispatch(innerContent, new PointerEvent(UiEventType.SCROLL, 1,
                    System.nanoTime(), KeyModifiers.NONE, 0, 10, 10,
                    0, 0, 0, -1, null, 0));

            assertEquals(32.0, inner.scrollY());
            assertEquals(0.0, outer.scrollY());

            document.dispatch(outerContent, new PointerEvent(UiEventType.SCROLL, 2,
                    System.nanoTime(), KeyModifiers.NONE, 0, 10, 10,
                    0, 0, 0, -1, null, 0));
            assertEquals(32.0, outer.scrollY());
        }
    }

    @Test
    void scrollViewClampsInitialOffsetAndTracksContentAndViewportChanges() {
        ScrollView scroll = new ScrollView();
        Panel content = new Panel();
        scroll.content(content).scrollTo(999.0, 999.0);
        scroll.applyLayout(new LayoutBox(0, 0, 100, 80));
        content.applyLayout(new LayoutBox(10, 5, 250, 200));

        scroll.afterLayout();

        assertEquals(160.0, scroll.maxScrollX());
        assertEquals(125.0, scroll.maxScrollY());
        assertEquals(160.0, scroll.scrollX());
        assertEquals(125.0, scroll.scrollY());

        scroll.applyLayout(new LayoutBox(0, 0, 300, 240));
        scroll.afterLayout();
        assertEquals(0.0, scroll.scrollX());
        assertEquals(0.0, scroll.scrollY());

        content.applyLayout(new LayoutBox(0, 0, 40, 30));
        scroll.afterLayout();
        scroll.scrollTo(-10.0, -20.0);
        assertEquals(0.0, scroll.maxScrollX());
        assertEquals(0.0, scroll.maxScrollY());
        assertEquals(0.0, scroll.scrollX());
        assertEquals(0.0, scroll.scrollY());
    }

    @Test
    void listViewUsesLogicalItemExtentAndMaterializesLastItemAtBottom() {
        ListView list = new ListView();
        list.applyLayout(new LayoutBox(0, 0, 200, 90));
        list.content().applyLayout(new LayoutBox(0, 0, 200, 90));
        list.estimatedItemHeight(30.0f).model(1000, ignored -> new Panel());
        list.afterLayout();

        assertEquals(29_910.0, list.maxScrollY());
        list.scrollTo(0.0, Double.MAX_VALUE);
        assertEquals(29_910.0, list.scrollY());
        assertTrue(list.materializedIndices().contains(999));

        list.model(0, ignored -> new Panel());
        assertEquals(0.0, list.maxScrollY());
        assertEquals(0.0, list.scrollY());
        assertEquals(0, list.materializedItemCount());
    }

    @Test
    void nestedScrollChainsToOuterViewAtBothInnerBoundaries() {
        try (UiDocument document = new UiDocument()) {
            ScrollView outer = new ScrollView();
            Panel outerContent = new Panel();
            ScrollView inner = new ScrollView();
            Panel innerContent = new Panel();
            inner.content(innerContent);
            outerContent.add(inner);
            outer.content(outerContent);
            document.root().add(outer);
            outer.applyLayout(new LayoutBox(0, 0, 100, 100));
            outerContent.applyLayout(new LayoutBox(0, 0, 100, 300));
            inner.applyLayout(new LayoutBox(0, 0, 100, 60));
            innerContent.applyLayout(new LayoutBox(0, 0, 100, 180));
            outer.afterLayout();
            inner.afterLayout();

            document.dispatch(innerContent, scrollEvent(-1.0));
            assertEquals(32.0, inner.scrollY());
            assertEquals(0.0, outer.scrollY());

            inner.scrollTo(0.0, inner.maxScrollY());
            document.dispatch(innerContent, scrollEvent(-1.0));
            assertEquals(inner.maxScrollY(), inner.scrollY());
            assertEquals(32.0, outer.scrollY());

            inner.scrollTo(0.0, 0.0);
            document.dispatch(innerContent, scrollEvent(1.0));
            assertEquals(0.0, inner.scrollY());
            assertEquals(0.0, outer.scrollY());
        }
    }

    @Test
    void buttonAndToggleSupportKeyboardActivation() {
        try (UiDocument document = new UiDocument()) {
            Toggle toggle = new Toggle("Enabled");
            document.root().add(toggle);
            document.focusManager().requestFocus(toggle);

            document.dispatch(toggle, key(UiEventType.KEY_DOWN, Key.SPACE));
            assertTrue(toggle.pressed());
            document.dispatch(toggle, key(UiEventType.KEY_UP, Key.SPACE));

            assertTrue(toggle.value());
            assertFalse(toggle.pressed());
            assertEquals("true", toggle.semanticValue());
        }
    }

    @Test
    void sliderNormalizesPointerAndQuantizesKeyboardSteps() {
        try (UiDocument document = new UiDocument()) {
            Slider slider = new Slider(0.0, 10.0, 0.0).step(0.5);
            slider.applyLayout(new LayoutBox(10, 0, 100, 20));
            document.root().add(slider);
            AtomicReference<Double> observed = new AtomicReference<>();
            slider.onValueChanged(observed::set);

            document.dispatch(slider, pointer(UiEventType.POINTER_DOWN, 63, 10));
            assertEquals(5.5, slider.value());
            assertEquals(5.5, observed.get());
            document.dispatch(slider, key(UiEventType.KEY_DOWN, Key.RIGHT));
            assertEquals(6.0, slider.value());
        }
    }

    private static PointerEvent pointer(UiEventType type, double x, double y) {
        return new PointerEvent(type, 1, System.nanoTime(), KeyModifiers.NONE, 0,
                x, y, 0, 0, 0, 0, MouseButton.LEFT, 1);
    }

    private static PointerEvent scrollEvent(double scrollY) {
        return new PointerEvent(UiEventType.SCROLL, 1, System.nanoTime(), KeyModifiers.NONE, 0,
                10, 10, 0, 0, 0, scrollY, null, 0);
    }

    private static KeyEvent key(UiEventType type, Key key) {
        return new KeyEvent(type, 1, System.nanoTime(), KeyModifiers.NONE, key, 0, false);
    }
}
