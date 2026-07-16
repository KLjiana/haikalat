package com.kaleblangley.haikalat.subsystems.ui;

import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.widget.Button;
import com.kaleblangley.haikalat.subsystems.ui.widget.Popup;
import com.kaleblangley.haikalat.subsystems.ui.widget.TextField;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.ImeComposition;
import com.kaleblangley.haikalat.subsystems.windowing.input.TestTextInputAdapter;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputCollector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不依赖系统输入法的 UI composition、焦点和树生命周期压力回归。 */
@EnabledIfSystemProperty(named = "haikalat.uiImeSoak", matches = "true")
class UiImeSyntheticSoakTest {
    private static final float DELTA_SECONDS = 1.0f / 60.0f;

    @Test
    void compositionSurvivesLayoutChangesAndEndsCleanlyAtLifecycleBoundaries() {
        int cycles = Integer.getInteger("haikalat.uiImeSoakCycles", 100);
        TestTextInputAdapter adapter = new TestTextInputAdapter();
        WindowInputCollector input = collector(640, 360, 640, 360);

        try (UiSystem ui = UiSystem.create(new FixedWindow(640, 360),
                UiConfig.defaults(), adapter)) {
            for (int cycle = 0; cycle < cycles; cycle++) {
                exerciseCycle(ui, adapter, input, cycle);
            }
        }
    }

    private static void exerciseCycle(UiSystem ui, TestTextInputAdapter adapter,
                                      WindowInputCollector input, int cycle) {
        TextField field = textField();
        ui.document().root().add(field);
        ui.document().focusManager().requestFocus(field);
        update(ui, input);

        adapter.start();
        adapter.update(new ImeComposition("拼音" + cycle, 0, 2, 2));
        update(ui, input);
        assertEquals("拼音" + cycle, field.composition().text());

        input.windowSize(640 + cycle % 3, 360 + cycle % 2);
        input.framebufferSize(960 + cycle % 3, 540 + cycle % 2);
        input.contentScale(1.5f, 1.5f);
        update(ui, input);
        assertTrue(adapter.candidateRect().height() > 0.0);

        if ((cycle & 1) == 0) {
            adapter.commit("中");
            update(ui, input);
            assertEquals("中", field.value());
        } else {
            adapter.cancel();
            update(ui, input);
            assertNull(field.composition());
        }

        adapter.start();
        adapter.update(new ImeComposition("失焦", 0, 2, 2));
        input.focused(false);
        update(ui, input);
        assertNull(field.composition());
        assertTrue(adapter.composition().isEmpty());

        input.focused(true);
        ui.document().focusManager().requestFocus(field);
        update(ui, input);
        adapter.start();
        adapter.update(new ImeComposition("删除", 0, 2, 2));
        field.close();
        update(ui, input);
        assertNull(ui.document().focusManager().focused());
        assertTrue(adapter.composition().isEmpty());

        Button owner = new Button("Popup owner");
        owner.style(UiStyle.builder().width(UiLength.points(160.0f))
                .height(UiLength.points(32.0f)).build());
        ui.document().root().add(owner);
        update(ui, input);
        Popup popup = new Popup();
        popup.style(UiStyle.builder().width(UiLength.points(260.0f))
                .height(UiLength.points(72.0f)).build());
        TextField popupField = textField();
        popup.add(popupField);
        popup.open(ui.document(), owner);
        update(ui, input);
        assertSame(popupField, ui.document().focusManager().focused());

        adapter.start();
        adapter.update(new ImeComposition("弹窗", 0, 2, 2));
        update(ui, input);
        adapter.commit("弹窗");
        update(ui, input);
        assertEquals("弹窗", popupField.value());

        popup.dismiss();
        popup.close();
        owner.close();
        update(ui, input);
    }

    private static TextField textField() {
        TextField field = new TextField();
        field.style(UiStyle.builder().width(UiLength.points(240.0f))
                .height(UiLength.points(40.0f)).build());
        return field;
    }

    private static void update(UiSystem ui, WindowInputCollector input) {
        ui.update(input.snapshot(), DELTA_SECONDS);
    }

    private static WindowInputCollector collector(int logicalWidth, int logicalHeight,
                                                  int framebufferWidth, int framebufferHeight) {
        WindowInputCollector collector = new WindowInputCollector();
        collector.windowSize(logicalWidth, logicalHeight);
        collector.framebufferSize(framebufferWidth, framebufferHeight);
        collector.contentScale((float) framebufferWidth / logicalWidth,
                (float) framebufferHeight / logicalHeight);
        collector.focused(true);
        collector.cursorInside(true);
        return collector;
    }

    private record FixedWindow(int width, int height) implements RenderWindow { }
}
