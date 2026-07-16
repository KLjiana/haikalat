package com.kaleblangley.haikalat.subsystems.windowing.input;

import org.junit.jupiter.api.Test;

import java.nio.ReadOnlyBufferException;

import static org.junit.jupiter.api.Assertions.*;

class WindowInputCollectorTest {
    private static final KeyModifiers SHIFT = new KeyModifiers(true, false, false,
            false, false, false);

    @Test
    void pressDownAndReleaseHaveDistinctCrossSnapshotSemantics() {
        WindowInputCollector collector = new WindowInputCollector();
        collector.pressKey(Key.A, SHIFT);

        WindowInputSnapshot pressed = collector.snapshot();
        assertTrue(pressed.keyDown(Key.A));
        assertTrue(pressed.keyPressed(Key.A));
        assertFalse(pressed.keyReleased(Key.A));
        assertEquals(SHIFT, pressed.modifiers());

        WindowInputSnapshot held = collector.snapshot();
        assertTrue(held.keyDown(Key.A));
        assertFalse(held.keyPressed(Key.A));
        assertFalse(held.keyReleased(Key.A));

        collector.releaseKey(Key.A, KeyModifiers.NONE);
        WindowInputSnapshot released = collector.snapshot();
        assertFalse(released.keyDown(Key.A));
        assertFalse(released.keyPressed(Key.A));
        assertTrue(released.keyReleased(Key.A));

        assertFalse(collector.snapshot().keyReleased(Key.A));
    }

    @Test
    void pressAndReleaseInOneCallbackRoundPreserveBothEdges() {
        WindowInputCollector collector = new WindowInputCollector();
        collector.pressKey(Key.ENTER, KeyModifiers.NONE);
        collector.releaseKey(Key.ENTER, KeyModifiers.NONE);

        WindowInputSnapshot snapshot = collector.snapshot();
        assertFalse(snapshot.keyDown(Key.ENTER));
        assertTrue(snapshot.keyPressed(Key.ENTER));
        assertTrue(snapshot.keyReleased(Key.ENTER));
    }

    @Test
    void repeatHasItsOwnOneShotEdgeWithoutCreatingAnotherPress() {
        WindowInputCollector collector = new WindowInputCollector();
        collector.pressKey(Key.SPACE, KeyModifiers.NONE);
        collector.snapshot();
        collector.repeatKey(Key.SPACE, KeyModifiers.NONE);

        WindowInputSnapshot repeated = collector.snapshot();
        assertTrue(repeated.keyDown(Key.SPACE));
        assertFalse(repeated.keyPressed(Key.SPACE));
        assertTrue(repeated.keyRepeated(Key.SPACE));
        assertFalse(repeated.keyReleased(Key.SPACE));
        assertFalse(collector.snapshot().keyRepeated(Key.SPACE));
    }

    @Test
    void mouseCursorAndScrollAccumulateThenClearWithoutClearingDown() {
        WindowInputCollector collector = new WindowInputCollector();
        collector.cursorPosition(10.0, 20.0);
        collector.cursorPosition(13.5, 24.0);
        collector.cursorPosition(12.0, 27.0);
        collector.scroll(1.0, -2.0);
        collector.scroll(0.5, 4.0);
        collector.pressMouse(MouseButton.LEFT, SHIFT);
        collector.pressMouse(MouseButton.BUTTON_5, SHIFT);

        WindowInputSnapshot snapshot = collector.snapshot();
        assertEquals(12.0, snapshot.cursorX());
        assertEquals(27.0, snapshot.cursorY());
        assertEquals(2.0, snapshot.cursorDeltaX());
        assertEquals(7.0, snapshot.cursorDeltaY(), "snapshot Y delta follows top-left, Y-down coordinates");
        assertEquals(1.5, snapshot.scrollX());
        assertEquals(2.0, snapshot.scrollY());
        assertTrue(snapshot.mousePressed(MouseButton.LEFT));
        assertTrue(snapshot.mousePressed(MouseButton.BUTTON_5));

        WindowInputSnapshot stable = collector.snapshot();
        assertEquals(0.0, stable.cursorDeltaX());
        assertEquals(0.0, stable.cursorDeltaY());
        assertEquals(0.0, stable.scrollX());
        assertEquals(0.0, stable.scrollY());
        assertTrue(stable.mouseDown(MouseButton.LEFT));
        assertFalse(stable.mousePressed(MouseButton.LEFT));
    }

    @Test
    void focusLossSynthesizesReleasesAndCancelsTransientInteraction() {
        WindowInputCollector collector = new WindowInputCollector();
        collector.focused(true);
        collector.pressKey(Key.LEFT_SHIFT, SHIFT);
        collector.pressMouse(MouseButton.LEFT, SHIFT);
        collector.composition(new ImeComposition("拼音", 0, 1, 1));
        collector.snapshot();

        collector.focused(false);
        WindowInputSnapshot lost = collector.snapshot();
        assertFalse(lost.focused());
        assertFalse(lost.keyDown(Key.LEFT_SHIFT));
        assertTrue(lost.keyReleased(Key.LEFT_SHIFT));
        assertFalse(lost.mouseDown(MouseButton.LEFT));
        assertTrue(lost.mouseReleased(MouseButton.LEFT));
        assertEquals(KeyModifiers.NONE, lost.modifiers());
        assertTrue(lost.composition().isEmpty());
    }

    @Test
    void supplementaryCodePointsAreImmutableAndConsumedOnce() {
        WindowInputCollector collector = new WindowInputCollector();
        collector.committedCodePoint('A');
        collector.committedCodePoint(0x1F642);

        WindowInputSnapshot snapshot = collector.snapshot();
        var codePoints = snapshot.committedCodePoints();
        assertTrue(codePoints.isReadOnly());
        assertEquals(2, codePoints.remaining());
        assertEquals('A', codePoints.get());
        assertEquals(0x1F642, codePoints.get());
        assertThrows(ReadOnlyBufferException.class,
                () -> snapshot.committedCodePoints().put(0, 'B'));
        assertEquals(0, collector.snapshot().committedCodePoints().remaining());
        assertThrows(IllegalArgumentException.class,
                () -> collector.committedCodePoint(Character.MIN_SURROGATE));
    }

    @Test
    void sequenceAndAllCoordinateSpacesRemainIndependent() {
        WindowInputCollector collector = new WindowInputCollector();
        collector.windowSize(800, 600);
        collector.framebufferSize(1600, 1200);
        collector.contentScale(2.0f, 1.5f);
        collector.cursorInside(true);
        collector.focused(true);

        WindowInputSnapshot first = collector.snapshot();
        assertEquals(1L, first.sequence());
        assertEquals(800, first.windowWidth());
        assertEquals(600, first.windowHeight());
        assertEquals(1600, first.framebufferWidth());
        assertEquals(1200, first.framebufferHeight());
        assertEquals(2.0f, first.contentScaleX());
        assertEquals(1.5f, first.contentScaleY());
        assertTrue(first.cursorInside());

        collector.windowSize(0, 0);
        collector.framebufferSize(0, 0);
        WindowInputSnapshot minimized = collector.snapshot();
        assertEquals(2L, minimized.sequence());
        assertEquals(800, minimized.windowWidth(), "layout keeps the last valid logical size");
        assertEquals(600, minimized.windowHeight());
        assertEquals(0, minimized.framebufferWidth());
        assertEquals(0, minimized.framebufferHeight());
        assertThrows(IllegalArgumentException.class, () -> collector.framebufferSize(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> collector.contentScale(0.0f, 1.0f));
    }
}
