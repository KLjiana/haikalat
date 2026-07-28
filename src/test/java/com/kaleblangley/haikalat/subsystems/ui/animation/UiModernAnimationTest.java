package com.kaleblangley.haikalat.subsystems.ui.animation;

import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox;
import com.kaleblangley.haikalat.subsystems.ui.render.UiDisplayList;
import com.kaleblangley.haikalat.subsystems.ui.render.UiPainter;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiModernAnimationTest {
    @Test
    void visualTransformIsInheritedByPaintAndInvertedForHitTesting() {
        try (UiDocument document = new UiDocument()) {
            document.root().applyLayout(new LayoutBox(0, 0, 400, 300));
            Panel parent = new Panel();
            Panel child = new Panel();
            document.root().add(parent);
            parent.add(child);
            parent.applyLayout(new LayoutBox(10, 20, 100, 60));
            child.applyLayout(new LayoutBox(20, 30, 20, 20));
            parent.visualTransform(UiVisualTransform.translation(50, 12));

            assertEquals(child, document.hitTest(75, 47));
            assertNotSame(child, document.hitTest(25, 35));

            UiDisplayList list = new UiPainter().paint(document);
            assertTrue(list.quadCount() >= 2);
            assertEquals(50.0, list.quadTransform(0).translateX(), 1.0e-9);
            assertEquals(12.0, list.quadTransform(0).translateY(), 1.0e-9);
        }
    }

    @Test
    void propertyTracksTimelineMarkersAndReducedMotionAreDeterministic() {
        try (UiDocument document = new UiDocument();
             UiTimeline timeline = new UiTimeline(document)) {
            Panel panel = new Panel();
            document.root().add(panel);
            UiAnimationSequence sequence = UiAnimationSequence.builder()
                    .then(panel, 1.0f, UiEasing.LINEAR,
                            List.of(new UiAnimationTrigger(0.5f, "half")),
                            UiPropertyTrack.numeric(UiPropertyTrack.Property.OPACITY, 1.0, 0.0),
                            UiPropertyTrack.numeric(UiPropertyTrack.Property.TRANSLATION_X, 0.0, 40.0))
                    .build();
            timeline.play(sequence);
            timeline.update(0.5f);
            assertEquals(0.5f, panel.computedStyle().opacity(), 1.0e-5f);
            assertEquals(20.0, panel.visualTransform().translateX(), 1.0e-5);
            List<UiAnimationSignal> signals = timeline.drainSignals();
            assertEquals(List.of(UiAnimationSignal.Type.START, UiAnimationSignal.Type.MARKER),
                    signals.stream().map(UiAnimationSignal::type).toList());

            timeline.reducedMotion(true);
            timeline.play(sequence);
            assertEquals(0.0f, panel.computedStyle().opacity(), 0.0f);
            assertEquals(0, timeline.activeCount());
        }
    }

    @Test
    void staggerPreservesInputOrderAndFlipMapsLastBoundsBackToFirst() {
        try (UiDocument document = new UiDocument()) {
            Panel first = new Panel();
            Panel second = new Panel();
            document.root().add(first).add(second);
            UiAnimationGroup group = UiStagger.group(List.of(first, second),
                    0.1f, 0.5f, UiEasing.LINEAR,
                    node -> UiPropertyTrack.numeric(
                            UiPropertyTrack.Property.OPACITY, 0.0, 1.0));
            assertEquals(0, group.sequences().get(0).steps().stream()
                    .filter(step -> step.target() == null).count());
            assertEquals(1, group.sequences().get(1).steps().stream()
                    .filter(step -> step.target() == null).count());

            LayoutBox before = new LayoutBox(10, 20, 100, 40);
            LayoutBox after = new LayoutBox(70, 80, 200, 80);
            UiFlipTransition flip = UiFlipTransition.between(before, after);
            UiVisualTransform.Point mapped = flip.inverseTransform().matrix(after)
                    .transform(after.x() + after.width() * 0.5,
                            after.y() + after.height() * 0.5);
            assertEquals(before.x() + before.width() * 0.5, mapped.x(), 1.0e-6);
            assertEquals(before.y() + before.height() * 0.5, mapped.y(), 1.0e-6);
        }
    }
}
