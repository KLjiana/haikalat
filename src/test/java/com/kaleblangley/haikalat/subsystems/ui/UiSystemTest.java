package com.kaleblangley.haikalat.subsystems.ui;

import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiEasing;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiTweenSpec;
import com.kaleblangley.haikalat.subsystems.ui.text.UiTextEngine;
import com.kaleblangley.haikalat.subsystems.ui.widget.Button;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.ui.widget.TextField;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.ImeComposition;
import com.kaleblangley.haikalat.subsystems.windowing.input.TestTextInputAdapter;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputCollector;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class UiSystemTest {
    @Test
    void configUsesDoubleBufferForSyncAndTripleBufferForAsync() {
        assertEquals(2, UiConfig.defaults().snapshotSlots());
        assertEquals(3, UiConfig.builder().asynchronousSnapshots(true).build().snapshotSlots());
    }

    @Test
    void configuredResolverReceivesNodeStyleClasses() {
        UiColor hot = UiColor.fromSrgbHex(0x36e4daff);
        var fallback = com.kaleblangley.haikalat.subsystems.ui.style.StyleResolver.defaults(
                com.kaleblangley.haikalat.subsystems.ui.style.Theme.dark());
        UiConfig config = UiConfig.builder().styleResolver((widget, classes, states, inherited) -> {
            var resolved = fallback.resolve(widget, classes, states, inherited);
            if (!classes.contains("hot")) return resolved;
            return new com.kaleblangley.haikalat.subsystems.ui.style.ComputedStyle(
                    hot, resolved.foreground(), resolved.borderColor(), resolved.borderWidth(),
                    resolved.radius(), resolved.opacity(), resolved.fontSize(),
                    resolved.fontFamily(), resolved.textEffect());
        }).build();
        try (UiSystem ui = UiSystem.create(new FixedWindow(320, 180), config)) {
            Button button = new Button("Styled");
            button.addStyleClass("hot");
            ui.document().root().add(button);

            ui.update(collector(320, 180, 320, 180).snapshot(), 1.0f / 60.0f);

            assertEquals(hot, button.computedStyle().background());
            assertEquals(java.util.Set.of("hot"), button.styleClasses());
            assertThrows(IllegalArgumentException.class, () -> button.addStyleClass("two words"));
        }
    }

    @Test
    void fontSwitchInvalidatesRetainedTextLayout() throws Exception {
        byte[] fontData;
        try (var input = UiSystemTest.class.getResourceAsStream(
                UiTextEngine.BUNDLED_FONT_RESOURCE)) {
            fontData = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        try (UiSystem ui = UiSystem.create(new FixedWindow(320, 180), UiConfig.defaults())) {
            Button button = new Button("Font 字体");
            ui.document().root().add(button);
            ui.update(collector(320, 180, 320, 180).snapshot(), 1.0f / 60.0f);

            ui.registerFont("Alternate", fontData);
            assertEquals(java.util.List.of(UiTextEngine.DEFAULT_FONT_FAMILY,
                    UiTextEngine.UNIFONT_FONT_FAMILY,
                    UiTextEngine.MONOSPACE_FONT_FAMILY, "Alternate"),
                    ui.fontFamilies());
            assertTrue(ui.selectFontFamily("Alternate"));
            assertEquals("Alternate", ui.activeFontFamily());
            assertTrue(button.isDirty(UiDirtyFlag.MEASURE));
            assertTrue(button.isDirty(UiDirtyFlag.LAYOUT));
            assertTrue(button.isDirty(UiDirtyFlag.PAINT));
        }
    }

    @Test
    void fontSwitchLeavesExplicitFontNodesStable() {
        var fallback = com.kaleblangley.haikalat.subsystems.ui.style.StyleResolver.defaults(
                com.kaleblangley.haikalat.subsystems.ui.style.Theme.dark());
        UiConfig config = UiConfig.builder().styleResolver((widget, classes, states, inherited) -> {
            var resolved = fallback.resolve(widget, classes, states, inherited);
            if (!classes.contains("explicit-font")) return resolved;
            return new com.kaleblangley.haikalat.subsystems.ui.style.ComputedStyle(
                    resolved.background(), resolved.foreground(), resolved.borderColor(),
                    resolved.borderWidth(), resolved.radius(), resolved.opacity(),
                    resolved.fontSize(), UiTextEngine.MONOSPACE_FONT_FAMILY,
                    resolved.textEffect());
        }).build();
        try (UiSystem ui = UiSystem.create(new FixedWindow(320, 180), config)) {
            Button inherited = new Button("Inherited font");
            Label explicit = new Label("Explicit font");
            explicit.addStyleClass("explicit-font");
            ui.document().root().add(inherited).add(explicit);
            ui.update(collector(320, 180, 320, 180).snapshot(), 1.0f / 60.0f);

            assertEquals(UiTextEngine.MONOSPACE_FONT_FAMILY,
                    explicit.computedStyle().fontFamily());
            assertFalse(explicit.isDirty(UiDirtyFlag.MEASURE));
            assertTrue(ui.selectFontFamily(UiTextEngine.MONOSPACE_FONT_FAMILY));

            assertTrue(inherited.isDirty(UiDirtyFlag.MEASURE));
            assertTrue(inherited.isDirty(UiDirtyFlag.LAYOUT));
            assertTrue(inherited.isDirty(UiDirtyFlag.PAINT));
            assertFalse(explicit.isDirty(UiDirtyFlag.MEASURE));
            assertFalse(explicit.isDirty(UiDirtyFlag.LAYOUT));
            assertFalse(explicit.isDirty(UiDirtyFlag.PAINT));
        }
    }

    @Test
    void updateRunsInputStyleYogaPaintAndSnapshotPipeline() {
        try (UiSystem ui = UiSystem.create(new FixedWindow(640, 360), UiConfig.defaults())) {
            Button button = new Button("应用");
            button.style(UiStyle.builder()
                    .width(UiLength.points(120))
                    .height(UiLength.points(36))
                    .build());
            ui.document().root().add(button);
            WindowInputCollector collector = collector(320, 180, 640, 360);

            ui.update(collector.snapshot(), 1.0f);

            UiFrameStats stats = ui.statistics();
            assertTrue(stats.visibleNodes() >= 4);
            assertEquals(1, stats.layoutPasses());
            assertTrue(stats.paintPrimitives() > 0);
            assertTrue(stats.quads() > 0);
            assertEquals(120.0f, button.layoutBox().width(), 0.01f);
            assertEquals(UiConfig.defaults().maximumDeltaSeconds(),
                    ui.animationDeltaSeconds(), 0.0001f);
            assertThrows(IllegalArgumentException.class,
                    () -> ui.update(collector.snapshot(), Float.NaN));
        }
    }

    @Test
    void updateAdvancesAnimationsWithTheBoundedUiDelta() {
        try (UiSystem ui = UiSystem.create(new FixedWindow(320, 180), UiConfig.defaults())) {
            Button button = new Button("Fade");
            ui.document().root().add(button);
            WindowInputCollector input = collector(320, 180, 320, 180);
            ui.update(input.snapshot(), 1.0f / 60.0f);
            ui.animations().tweenOpacity(button, 0.0f,
                    new UiTweenSpec(1.0f, UiEasing.LINEAR));

            ui.update(input.snapshot(), 1.0f);

            assertEquals(1.0f - UiConfig.defaults().maximumDeltaSeconds(),
                    button.computedStyle().opacity(), 1.0e-5f);
            assertEquals(1, ui.animations().activeCount());
        }
    }

    @Test
    void staticUpdateReusesPublishedDisplayListUntilPaintBecomesDirty() {
        try (UiSystem ui = UiSystem.create(new FixedWindow(320, 180), UiConfig.defaults())) {
            Button button = new Button("Stable");
            ui.document().root().add(button);
            WindowInputCollector input = collector(320, 180, 320, 180);

            ui.update(input.snapshot(), 1.0f / 60.0f);
            long published = ui.publishedSnapshotCount();
            UiFrameStats first = ui.statistics();

            ui.update(input.snapshot(), 1.0f / 60.0f);

            UiFrameStats stable = ui.statistics();
            assertEquals(published, ui.publishedSnapshotCount());
            assertEquals(0L, stable.paintNanos());
            assertEquals(first.paintPrimitives(), stable.paintPrimitives());
            assertEquals(first.quads(), stable.quads());
            assertEquals(first.glyphs(), stable.glyphs());
            assertEquals(first.batches(), stable.batches());

            button.animatedValue(1.0);
            ui.update(input.snapshot(), 1.0f / 60.0f);

            assertEquals(published + 1L, ui.publishedSnapshotCount());
            assertTrue(ui.statistics().paintNanos() > 0L);
        }
    }

    @Test
    void attachValidatesBackbufferDependencyAndSealsTopology() {
        try (UiSystem ui = UiSystem.create(new FixedWindow(64, 64), UiConfig.defaults());
             RenderGraph graph = new RenderGraph(64, 64)) {
            graph.addPass("Present").writeToBackbuffer().noClear()
                    .execute((resources, commands) -> { });

            ui.attachTo(graph, "Present");

            assertTrue(graph.hasPass(UiSystem.OVERLAY_PASS_NAME));
            assertTrue(graph.passWritesToBackbuffer(UiSystem.OVERLAY_PASS_NAME));
            assertTrue(graph.isTopologySealed());
            assertThrows(IllegalStateException.class, () -> graph.addPass("Late"));
            assertThrows(IllegalStateException.class, () -> ui.attachTo(graph, "Present"));
        }
    }

    @Test
    void attachRejectsMissingAndOffscreenDependencyWithoutPartialPass() {
        try (UiSystem ui = UiSystem.create(new FixedWindow(64, 64), UiConfig.defaults());
             RenderGraph graph = new RenderGraph(64, 64)) {
            graph.addPass("External").writeToExternalTarget().noClear()
                    .execute((resources, commands) -> { });

            assertThrows(IllegalArgumentException.class, () -> ui.attachTo(graph, "Missing"));
            assertThrows(IllegalArgumentException.class, () -> ui.attachTo(graph, "External"));
            assertFalse(graph.hasPass(UiSystem.OVERLAY_PASS_NAME));
            assertFalse(graph.isTopologySealed());
        }
    }

    @Test
    void closeIsIdempotentAndPublicResourcesRejectFurtherUse() {
        UiSystem ui = UiSystem.create(new FixedWindow(64, 64), UiConfig.defaults());
        ui.close();
        ui.close();

        assertTrue(ui.isClosed());
        assertThrows(IllegalStateException.class, ui::document);
        assertThrows(IllegalStateException.class, ui::statistics);
        assertThrows(IllegalStateException.class,
                () -> ui.update(collector(64, 64, 64, 64).snapshot(), 0.016f));
    }

    @Test
    void asyncLifecycleCanReleaseUninitializedRendererBeforeOwnerClose() {
        UiSystem ui = UiSystem.create(new FixedWindow(64, 64), UiConfig.builder()
                .asynchronousSnapshots(true)
                .snapshotSlots(3)
                .build());
        CompletableFuture.runAsync(ui::closeRenderResources).join();
        ui.closeRenderResources();
        ui.close();

        assertTrue(ui.isClosed());
    }

    @Test
    void injectedImeAdapterUpdatesCompositionCommitAndCandidateRect() {
        TestTextInputAdapter adapter = new TestTextInputAdapter();
        try (UiSystem ui = UiSystem.create(new FixedWindow(640, 360),
                UiConfig.defaults(), adapter)) {
            TextField field = new TextField();
            field.style(UiStyle.builder()
                    .width(UiLength.points(240.0f))
                    .height(UiLength.points(40.0f))
                    .build());
            ui.document().root().add(field);
            ui.document().focusManager().requestFocus(field);
            WindowInputCollector collector = collector(320, 180, 640, 360);

            ui.update(collector.snapshot(), 0.016f);
            assertTrue(adapter.candidateRect().width() > 0.0);
            assertTrue(adapter.candidateRect().height() > 0.0);
            assertEquals(field.layoutBox().y() * 2.0, adapter.candidateRect().y(), 0.01);
            assertEquals(field.layoutBox().bottom() * 2.0,
                    adapter.candidateRect().y() + adapter.candidateRect().height(), 0.01,
                    "CFS_EXCLUDE 矩形底边应与输入框底边一致，让候选窗紧贴其下方");

            adapter.start();
            adapter.update(new ImeComposition("中文", 0, 2, 2));
            ui.update(collector.snapshot(), 0.016f);
            assertNotNull(field.composition());
            assertEquals("中文", field.composition().text());

            adapter.commit("中文");
            ui.update(collector.snapshot(), 0.016f);
            assertNull(field.composition());
            assertEquals("中文", field.value());
        }
    }

    private static WindowInputCollector collector(int logicalWidth, int logicalHeight,
                                                  int framebufferWidth, int framebufferHeight) {
        WindowInputCollector collector = new WindowInputCollector();
        collector.windowSize(logicalWidth, logicalHeight);
        collector.framebufferSize(framebufferWidth, framebufferHeight);
        collector.contentScale((float) framebufferWidth / logicalWidth,
                (float) framebufferHeight / logicalHeight);
        collector.focused(true);
        return collector;
    }

    private record FixedWindow(int width, int height) implements RenderWindow { }
}
