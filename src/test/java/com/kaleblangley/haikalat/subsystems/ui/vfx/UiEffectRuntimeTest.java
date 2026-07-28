package com.kaleblangley.haikalat.subsystems.ui.vfx;

import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiAnimationSignal;
import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox;
import com.kaleblangley.haikalat.subsystems.ui.render.UiDisplayList;
import com.kaleblangley.haikalat.subsystems.ui.render.UiScreenRect;
import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiEffectRuntimeTest {
    @Test
    void fixedSeedProducesIdenticalBoundedSnapshots() {
        UiEffectDefinition definition = UiEffectDefinition.builder(
                        UiEffectDefinition.Type.CONFETTI)
                .duration(2.0f).particleLifetime(2.0f).seed(42L)
                .maximumParticles(32)
                .colors(UiColor.WHITE, UiColor.TRANSPARENT)
                .build();
        UiEffectInstance first = new UiEffectInstance(1, definition,
                new com.kaleblangley.haikalat.subsystems.ui.UiId(9), 7);
        UiEffectInstance second = new UiEffectInstance(1, definition,
                new com.kaleblangley.haikalat.subsystems.ui.UiId(9), 7);
        first.update(0.25f, true);
        second.update(0.25f, true);
        UiScreenRect bounds = new UiScreenRect(10, 20, 200, 100);
        assertEquals(first.snapshot(bounds), second.snapshot(bounds));
        assertEquals(32, first.snapshot(bounds).particles().size());
    }

    @Test
    void bridgeDeduplicatesSignalsEnforcesCapacityAndRendererUsesUiPath() {
        try (UiDocument document = new UiDocument();
             UiEffectBridge bridge = new UiEffectBridge(document)) {
            Panel panel = new Panel();
            document.root().add(panel);
            panel.applyLayout(new LayoutBox(20, 30, 120, 70));
            UiEffectDefinition ripple = UiEffectDefinition.builder(
                            UiEffectDefinition.Type.RIPPLE)
                    .duration(1.0f).seed(5L).build();
            bridge.register(panel).bindMarker("hit", ripple).maximumActiveEffects(1);
            UiAnimationSignal signal = new UiAnimationSignal(panel.id(), 3,
                    UiAnimationSignal.Type.MARKER, 0.5f, "hit");
            bridge.consume(List.of(signal, signal));
            assertEquals(1, bridge.diagnostics().activeEffects());
            assertNull(bridge.start(ripple, panel, 4));
            assertEquals(1L, bridge.diagnostics().capacityRejected());

            bridge.update(0.25f);
            List<UiEffectSnapshot> snapshots = bridge.snapshot();
            UiDisplayList list = new UiDisplayList();
            new UiEffectRenderer().record(snapshots, list);
            assertTrue(list.quadCount() > 0);
            assertTrue(list.freeze().isFrozen());
        }
    }

    @Test
    void cancelSignalRemovesManualAndBoundEffectsSharingTheTimelineSequence() {
        try (UiDocument document = new UiDocument();
             UiEffectBridge bridge = new UiEffectBridge(document)) {
            Panel panel = new Panel();
            document.root().add(panel);
            UiEffectDefinition ripple = UiEffectDefinition.builder(
                            UiEffectDefinition.Type.RIPPLE)
                    .duration(1.0f).build();
            UiEffectDefinition shimmer = UiEffectDefinition.builder(
                            UiEffectDefinition.Type.SHIMMER)
                    .duration(1.0f).build();
            bridge.register(panel).bind(UiAnimationSignal.Type.START, "", shimmer);

            long sequence = 7L;
            bridge.start(ripple, panel, sequence);
            bridge.consume(List.of(new UiAnimationSignal(panel.id(), sequence,
                    UiAnimationSignal.Type.START, 0.0f, "")));
            assertEquals(2, bridge.diagnostics().activeEffects());

            bridge.consume(List.of(new UiAnimationSignal(panel.id(), sequence,
                    UiAnimationSignal.Type.CANCEL, 0.5f, "")));
            bridge.update(0.0f);
            assertEquals(0, bridge.diagnostics().activeEffects());
            assertEquals(2L, bridge.diagnostics().cancelled());
        }
    }

    @Test
    void deterministicSoakKeepsCapacityStableFor3600Frames() {
        try (UiDocument document = new UiDocument();
             UiEffectBridge bridge = new UiEffectBridge(document)) {
            Panel panel = new Panel();
            document.root().add(panel);
            panel.applyLayout(new LayoutBox(0, 0, 100, 50));
            UiEffectDefinition spark = UiEffectDefinition.builder(
                            UiEffectDefinition.Type.SPARK)
                    .duration(0.25f).particleLifetime(0.25f)
                    .maximumParticles(12).seed(99L).build();
            bridge.register(panel).maximumActiveEffects(4);
            for (int frame = 0; frame < 3600; frame++) {
                if (frame % 30 == 0) bridge.start(spark, panel, frame + 1L);
                bridge.update(1.0f / 60.0f);
                assertTrue(bridge.diagnostics().activeEffects() <= 4);
                assertTrue(bridge.diagnostics().reservedParticles() <= 48);
            }
            assertEquals(0, bridge.diagnostics().activeEffects());
        }
    }

    @Test
    void everyFirstReleaseEffectFamilyRecordsABoundedMainPath() {
        UiDisplayList list = new UiDisplayList();
        int instance = 1;
        for (UiEffectDefinition.Type type : UiEffectDefinition.Type.values()) {
            UiEffectDefinition definition = UiEffectDefinition.builder(type)
                    .duration(1.0f).particleLifetime(1.0f).seed(1234L + type.ordinal())
                    .build();
            UiEffectInstance effect = new UiEffectInstance(instance++, definition,
                    new com.kaleblangley.haikalat.subsystems.ui.UiId(10 + type.ordinal()),
                    100 + type.ordinal());
            effect.update(0.35f, true);
            new UiEffectRenderer().record(List.of(effect.snapshot(
                    new UiScreenRect(20, 30, 160, 90))), list);
        }
        assertTrue(list.quadCount() > 8);
        assertTrue(list.freeze().isFrozen());
    }
}
