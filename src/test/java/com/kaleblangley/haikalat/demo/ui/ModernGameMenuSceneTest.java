package com.kaleblangley.haikalat.demo.ui;

import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.ui.UiVisibility;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.UnavailableTextInputAdapter;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputCollector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModernGameMenuSceneTest {
    @Test
    void sceneBuildsACompleteBilingualGameMenuWithResolvedStyleClasses() {
        ModernUiDemo.Options options = ModernUiDemo.Options.parse(
                "--deterministic", "--frames=90", "--size=1280x720");
        try (UiSystem ui = createUi(options)) {
            ModernGameMenuScene scene = ModernGameMenuScene.install(ui, options, () -> { });

            ui.update(input(1280, 720).snapshot(), 1.0f / 60.0f);
            List<UiNode> nodes = descendants(ui.document().root());

            assertEquals(5, scene.menuButtonCount());
            assertTrue(nodes.size() >= 90, "the showcase should remain a complete menu scene");
            assertTrue(nodes.stream().filter(Label.class::isInstance)
                    .map(Label.class::cast).map(Label::text)
                    .anyMatch(ModernGameMenuSceneTest::containsCjk));
            assertTrue(scene.continueButton().styleClasses().contains("primary-button"));
            assertEquals(ModernGameTheme.ACCENT,
                    scene.continueButton().computedStyle().background());
            assertTrue(scene.continueButton().computedStyle().radius() > 8.0f);
            assertEquals(UiVisibility.VISIBLE, scene.campaignPanel().visibility());
            assertEquals(UiVisibility.COLLAPSED, scene.settingsPanel().visibility());
            assertFalse(scene.settingsOpen());
            assertTrue(scene.ambientSequence() > 0L);
        }
    }

    @Test
    void deterministicScriptExercisesSettingsReducedMotionAndCampaignReturn() {
        ModernUiDemo.Options options = ModernUiDemo.Options.parse(
                "--deterministic", "--frames=90", "--size=1280x720");
        try (UiSystem ui = createUi(options)) {
            ModernGameMenuScene scene = ModernGameMenuScene.install(ui, options, () -> { });
            WindowInputCollector input = input(1280, 720);
            ui.update(input.snapshot(), 1.0f / 60.0f);

            scene.runDeterministicScript(18);
            ui.update(input.snapshot(), 1.0f / 60.0f);
            assertTrue(scene.settingsOpen());
            assertEquals(UiVisibility.COLLAPSED, scene.campaignPanel().visibility());
            assertEquals(UiVisibility.VISIBLE, scene.settingsPanel().visibility());

            scene.runDeterministicScript(32);
            ui.update(input.snapshot(), 1.0f / 60.0f);
            assertTrue(ui.timeline().reducedMotion());
            assertEquals(0L, scene.ambientSequence(),
                    "reduced motion must cancel the long-running ambient loop");

            scene.runDeterministicScript(42);
            assertTrue(scene.statusText().contains("072%"));
            scene.runDeterministicScript(54);
            ui.update(input.snapshot(), 1.0f / 60.0f);
            assertFalse(scene.settingsOpen());
            assertEquals(UiVisibility.VISIBLE, scene.campaignPanel().visibility());
            assertEquals(UiVisibility.COLLAPSED, scene.settingsPanel().visibility());

            scene.runDeterministicScript(70);
            assertTrue(scene.statusText().contains("DETERMINISTIC PROOF"));
        }
    }

    private static UiSystem createUi(ModernUiDemo.Options options) {
        return UiSystem.create(new FixedWindow(options.width(), options.height()),
                ModernGameTheme.config(options.sdf()),
                new UnavailableTextInputAdapter("modern menu JVM test"));
    }

    private static WindowInputCollector input(int width, int height) {
        WindowInputCollector input = new WindowInputCollector();
        input.windowSize(width, height);
        input.framebufferSize(width, height);
        input.contentScale(1.0f, 1.0f);
        input.focused(true);
        return input;
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
        return value.codePoints().anyMatch(codePoint -> codePoint >= 0x4e00
                && codePoint <= 0x9fff);
    }

    private record FixedWindow(int width, int height) implements RenderWindow { }
}
