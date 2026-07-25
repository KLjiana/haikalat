package com.kaleblangley.haikalat.subsystems.ui.animation;

import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiDirtyFlag;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiAnimationSystemTest {
    @Test
    void opacityTweenAdvancesDeterministicallyAndCompletesExactlyAtTarget() {
        try (UiDocument document = new UiDocument();
             UiAnimationSystem animations = new UiAnimationSystem(document)) {
            Panel panel = new Panel();
            document.root().add(panel);
            UiAnimationHandle handle = animations.tweenOpacity(panel, 0.0f,
                    new UiTweenSpec(1.0f, UiEasing.LINEAR));

            animations.update(0.25f);
            assertEquals(0.75f, panel.computedStyle().opacity(), 1.0e-6f);
            assertTrue(panel.isDirty(UiDirtyFlag.PAINT));
            assertTrue(handle.isActive());

            animations.update(0.75f);
            assertEquals(0.0f, panel.computedStyle().opacity(), 0.0f);
            assertTrue(handle.isFinished());
            assertEquals(0, animations.activeCount());
        }
    }

    @Test
    void layoutTransitionInterpolatesCompatibleLengthsAndRejectsUnitChanges() {
        try (UiDocument document = new UiDocument();
             UiAnimationSystem animations = new UiAnimationSystem(document)) {
            Panel panel = new Panel();
            UiStyle start = UiStyle.builder().width(UiLength.points(100.0f)).build();
            UiStyle target = UiStyle.builder().width(UiLength.points(220.0f)).gap(12.0f).build();
            panel.style(start);
            document.root().add(panel);

            animations.transitionLayout(panel, target,
                    new UiTweenSpec(2.0f, UiEasing.LINEAR));
            animations.update(1.0f);
            assertEquals(160.0f, panel.style().width().value(), 1.0e-6f);
            assertEquals(6.0f, panel.style().gap(), 1.0e-6f);
            assertTrue(panel.isDirty(UiDirtyFlag.LAYOUT));

            assertThrows(IllegalArgumentException.class, () -> animations.transitionLayout(panel,
                    UiStyle.builder().width(UiLength.percent(50.0f)).build(),
                    UiTweenSpec.easeOut(0.2f)));
        }
    }

    @Test
    void newerAnimationReplacesSameChannelAndClosedNodesCancel() {
        try (UiDocument document = new UiDocument();
             UiAnimationSystem animations = new UiAnimationSystem(document)) {
            Panel panel = new Panel();
            document.root().add(panel);
            UiAnimationHandle first = animations.tweenOpacity(panel, 0.0f,
                    UiTweenSpec.easeOut(1.0f));
            UiAnimationHandle second = animations.tweenOpacity(panel, 0.5f,
                    UiTweenSpec.spring(1.0f));

            assertTrue(first.isCancelled());
            assertTrue(second.isActive());
            panel.close();
            animations.update(0.1f);
            assertTrue(second.isCancelled());
        }
    }

    @Test
    void easingAndSpecsRejectOutOfRangeTime() {
        assertEquals(0.0f, UiEasing.SPRING.apply(0.0f));
        assertEquals(1.0f, UiEasing.SPRING.apply(1.0f));
        assertThrows(IllegalArgumentException.class, () -> UiEasing.LINEAR.apply(1.1f));
        assertThrows(IllegalArgumentException.class,
                () -> new UiTweenSpec(Float.NaN, UiEasing.LINEAR));
    }

    @Test
    void pauseResumeProgressAndDiagnosticsRemainDeterministic() {
        try (UiDocument document = new UiDocument();
             UiAnimationSystem animations = new UiAnimationSystem(document)) {
            Panel panel = new Panel();
            document.root().add(panel);
            UiAnimationHandle replaced = animations.tweenOpacity(panel, 0.0f,
                    new UiTweenSpec(1.0f, UiEasing.LINEAR));
            UiAnimationHandle active = animations.tweenOpacity(panel, 0.5f,
                    new UiTweenSpec(1.0f, UiEasing.LINEAR));
            active.pause();

            animations.update(0.5f);
            UiAnimationDiagnostics paused = animations.diagnostics();
            assertTrue(replaced.isCancelled());
            assertTrue(active.isPaused());
            assertEquals(0.0f, active.progress(), 0.0f);
            assertEquals(2L, paused.started());
            assertEquals(1L, paused.cancelled());
            assertEquals(1L, paused.replaced());
            assertEquals(1, paused.paused());
            assertEquals(1, paused.activeVisual());

            active.resume();
            animations.update(0.5f);
            assertEquals(0.5f, active.progress(), 1.0e-6f);
            animations.update(0.5f);
            UiAnimationDiagnostics complete = animations.diagnostics();
            assertTrue(active.isFinished());
            assertEquals(1L, complete.completed());
            assertEquals(0, complete.active());
            assertEquals(3L, complete.updates());
            assertEquals(1, complete.peakActive());
        }
    }
}
