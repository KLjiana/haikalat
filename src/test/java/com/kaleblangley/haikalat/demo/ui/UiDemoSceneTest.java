package com.kaleblangley.haikalat.demo.ui;

import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiFrameStats;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.UiConfig;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.widget.Button;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.ui.widget.ListView;
import com.kaleblangley.haikalat.subsystems.ui.widget.Menu;
import com.kaleblangley.haikalat.subsystems.ui.widget.Popup;
import com.kaleblangley.haikalat.subsystems.ui.widget.ScrollView;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.UnavailableTextInputAdapter;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputCollector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiDemoSceneTest {
    @Test
    void demoPropertiesAreDecodedAsUtf8() {
        UiDemoStrings strings = UiDemoStrings.load();

        assertEquals("Haikalat Retained UI / 保留模式界面", strings.title());
        assertEquals("Interactive controls / 交互控件", strings.controls());
        assertEquals("Type text / 输入中文或 English", strings.textPlaceholder());
        assertEquals("Ready · retained tree is live / 就绪", strings.footerReady());
    }

    @Test
    void optionalOtfAppearsInRuntimeFontSelector() throws Exception {
        try (UiSystem ui = UiSystem.create(new FixedWindow(720, 480), UiConfig.defaults(),
                new UnavailableTextInputAdapter("font selector regression"))) {
            try (UiDemoScene scene = UiDemoScene.install(ui.document(), null, ui)) {
                Button selector = descendants(ui.document().root()).stream()
                        .filter(node -> "UiDemoFontSelector".equals(node.debugName()))
                        .map(Button.class::cast)
                        .findFirst().orElseThrow();

                assertEquals(List.of("Noto Sans SC", "Unifont", "JetBrains Mono"),
                        ui.fontFamilies());
                assertTrue(selector.text().contains("Noto Sans SC"));
                scene.applyBuiltinScript(7);
                assertEquals("Unifont", ui.activeFontFamily());
                assertTrue(selector.text().contains("Unifont"));
            }
        }
    }

    @Test
    void scrollingPreservesTextExtentAndPopupAnchorsToOwner() {
        try (UiSystem ui = UiSystem.create(new FixedWindow(720, 480), UiConfig.defaults(),
                new UnavailableTextInputAdapter("layout regression"));
             UiDemoScene scene = UiDemoScene.install(ui.document(), null)) {
            WindowInputCollector collector = new WindowInputCollector();
            collector.windowSize(720, 480);
            collector.framebufferSize(720, 480);
            collector.contentScale(1.0f, 1.0f);
            collector.focused(true);
            ui.update(collector.snapshot(), 1.0f / 60.0f);
            scene.refreshVirtualizedContent();
            scene.applyBuiltinScript(4);
            scene.applyBuiltinScript(5);
            scene.applyBuiltinScript(6);
            ui.update(collector.snapshot(), 1.0f / 60.0f);

            Label innerRow = descendants(scene.innerScroll()).stream()
                    .filter(Label.class::isInstance).map(Label.class::cast)
                    .filter(label -> label.text().startsWith("Nested row 1"))
                    .findFirst().orElseThrow();
            assertEquals(22.0f, innerRow.layoutBox().height(), 0.01f,
                    "scroll content must not flex-shrink to viewport height");

            UiNode cell = scene.listView().materializedCell(12);
            Label cellLabel = descendants(cell).stream()
                    .filter(Label.class::isInstance).map(Label.class::cast)
                    .findFirst().orElseThrow();
            var visualCell = ui.document().visualLayoutBox(cell);
            var visualLabel = ui.document().visualLayoutBox(cellLabel);
            assertTrue(visualLabel.x() >= visualCell.x() + 5.9f,
                    "list cell text should keep its rightward content inset");
            assertTrue(visualLabel.y() >= visualCell.y() + 1.9f,
                    "list cell text should keep its downward content inset");
            assertTrue(visualLabel.y() >= visualCell.y()
                            && visualLabel.bottom() <= visualCell.bottom(),
                    "virtualized cell descendants must follow the same visual offset");

            var popup = ui.document().visualLayoutBox(scene.popup());
            var owner = ui.document().visualLayoutBox(scene.popup().owner());
            assertEquals(owner.x(), popup.x(), 0.01f);
            assertEquals(owner.bottom(), popup.y(), 0.01f);

            Label title = descendants(ui.document().root()).stream()
                    .filter(Label.class::isInstance).map(Label.class::cast)
                    .filter(label -> label.text().startsWith("Haikalat Retained UI"))
                    .findFirst().orElseThrow();
            Label footer = descendants(ui.document().root()).stream()
                    .filter(Label.class::isInstance).map(Label.class::cast)
                    .filter(label -> label.text().startsWith("List virtualization advanced"))
                    .findFirst().orElseThrow();
            assertTrue(title.layoutBox().height() >= 18.0f,
                    "scroll content must not collapse the header");
            assertTrue(footer.layoutBox().height() >= 18.0f
                            && footer.layoutBox().bottom() <= ui.document().root().layoutBox().bottom(),
                    "scroll content must not push the footer outside the viewport");
        }
    }

    @Test
    void enlargedWindowStretchesCenterScrollContentToItsViewport() {
        try (UiSystem ui = UiSystem.create(new FixedWindow(960, 600), UiConfig.defaults(),
                new UnavailableTextInputAdapter("layout resize regression"));
             UiDemoScene scene = UiDemoScene.install(ui.document(), null)) {
            WindowInputCollector collector = new WindowInputCollector();
            collector.windowSize(960, 600);
            collector.framebufferSize(960, 600);
            collector.contentScale(1.0f, 1.0f);
            collector.focused(true);
            ui.update(collector.snapshot(), 1.0f / 60.0f);
            UiNode outerContent = descendants(scene.outerScroll()).stream()
                    .filter(node -> "UiDemoOuterContent".equals(node.debugName()))
                    .findFirst().orElseThrow();
            float initialViewportHeight = scene.outerScroll().layoutBox().height();
            float initialContentHeight = outerContent.layoutBox().height();

            collector.windowSize(960, 900);
            collector.framebufferSize(960, 900);
            ui.update(collector.snapshot(), 1.0f / 60.0f);

            assertTrue(scene.outerScroll().layoutBox().height() > initialViewportHeight + 250.0f);
            assertTrue(outerContent.layoutBox().height() > initialContentHeight,
                    "放大后内容高度应随视口增长，而不是停留在 520px");
            assertTrue(outerContent.layoutBox().bottom()
                            >= scene.outerScroll().layoutBox().bottom() - 2.1f,
                    "放大后滚动内容边框应至少延伸到视口底部");
        }
    }

    @Test
    void sceneCoversLayoutWidgetsBilingualTextAndPopupOverlay() {
        try (UiDocument document = new UiDocument();
             UiDemoScene scene = UiDemoScene.install(document, null)) {
            List<UiNode> nodes = descendants(document.root());
            UiNode main = nodes.stream().filter(node -> "UiDemoMain".equals(node.debugName()))
                    .findFirst().orElseThrow();

            assertEquals(UiStyle.FlexDirection.COLUMN, document.root().style().flexDirection());
            assertTrue(document.root().style().padding().left().value() > 0.0f);
            assertEquals(UiStyle.FlexDirection.ROW, main.style().flexDirection());
            assertTrue(nodes.stream().anyMatch(node -> node.style().flexGrow() > 0.0f));
            assertTrue(nodes.stream().filter(ScrollView.class::isInstance).count() >= 3,
                    "outer, inner and ListView scroll paths must all be present");
            assertTrue(descendants(scene.outerScroll()).contains(scene.innerScroll()));
            assertInstanceOf(ListView.class, scene.listView());
            assertInstanceOf(Popup.class, scene.popup());
            assertInstanceOf(Menu.class, scene.popup());
            assertFalse(((Popup) scene.popup()).isOpen());
            assertTrue(document.overlayRoot().children().isEmpty());
            assertTrue(nodes.stream().filter(Label.class::isInstance)
                    .map(Label.class::cast).map(Label::text)
                    .anyMatch(UiDemoSceneTest::containsCjk));
        }
    }

    @Test
    void builtinScriptMutatesControlsAndStatisticsDeterministically() {
        try (UiDocument document = new UiDocument();
             UiDemoScene scene = UiDemoScene.install(document, null)) {
            for (int frame = 0; frame <= 8; frame++) scene.applyBuiltinScript(frame);
            scene.updateStatistics(new UiFrameStats(10, 8, 1, 0, 0, 0, 0, 0, 0,
                    0, 12, 11, 0, 3, 2, 880, 132, 0, 0, 0,
                    1_000_000, 400_000, 100_000, 300_000, 200_000, 0,
                    com.kaleblangley.haikalat.subsystems.ui.UiBatchBreakStats.EMPTY));

            assertTrue(scene.toggle().value());
            assertEquals(1.35, scene.slider().value(), 0.0001);
            assertEquals("Haikalat UI 中文 English", scene.textField().value());
            assertEquals(0, scene.textField().selectionStart());
            assertEquals(scene.textField().value().length(), scene.textField().selectionEnd());
            assertEquals(68.0, scene.outerScroll().scrollY(), 0.0001);
            assertEquals(42.0, scene.innerScroll().scrollY(), 0.0001);
            assertEquals(360.0, scene.listView().scrollY(), 0.0001);
            assertTrue(scene.statisticsLabel().text().contains("nodes=10"));
            assertTrue(scene.statisticsLabel().text().contains("draws=2"));
        }
    }

    private static List<UiNode> descendants(UiNode root) {
        List<UiNode> nodes = new ArrayList<>();
        append(root, nodes);
        return nodes;
    }

    private static void append(UiNode node, List<UiNode> output) {
        output.add(node);
        for (UiNode child : node.children()) append(child, output);
    }

    private static boolean containsCjk(String value) {
        return value.codePoints().anyMatch(codePoint -> codePoint >= 0x4e00 && codePoint <= 0x9fff);
    }

    private record FixedWindow(int width, int height) implements RenderWindow { }
}
