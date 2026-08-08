package com.kaleblangley.haikalat.demo.ui;

import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiFrameStats;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiEasing;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiTweenSpec;
import com.kaleblangley.haikalat.subsystems.ui.style.ComputedStyle;
import com.kaleblangley.haikalat.subsystems.ui.style.UiInsets;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.widget.Button;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.ui.widget.ListView;
import com.kaleblangley.haikalat.subsystems.ui.widget.Menu;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.ui.widget.Popup;
import com.kaleblangley.haikalat.subsystems.ui.widget.ScrollView;
import com.kaleblangley.haikalat.subsystems.ui.widget.Slider;
import com.kaleblangley.haikalat.subsystems.ui.widget.TextField;
import com.kaleblangley.haikalat.subsystems.ui.widget.Toggle;
import com.kaleblangley.haikalat.subsystems.windowing.input.ClipboardService;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * UiDemo 的 retained tree 与确定性操作脚本。
 *
 * <p>该类不持有 GL 对象；关闭时只负责释放可能已从 document 脱离的 popup tree。</p>
 */
final class UiDemoScene implements AutoCloseable {
    private final UiDocument document;
    private final Label statistics;
    private final Panel header;
    private final Panel controls;
    private final Label footer;
    private final Toggle toggle;
    private final Slider slider;
    private final TextField textField;
    private final ScrollView outerScroll;
    private final ScrollView innerScroll;
    private final ListView listView;
    private final Button menuOwner;
    private final Menu menu;
    private final UiSystem fonts;
    private final List<String> fontFamilies;
    private final Button fontSelector;

    private UiDemoScene(UiDocument document, Panel header, Panel controls,
                        Label statistics, Label footer,
                        Toggle toggle, Slider slider, TextField textField,
                        ScrollView outerScroll, ScrollView innerScroll,
                        ListView listView, Button menuOwner, Menu menu,
                        UiSystem fonts, List<String> fontFamilies, Button fontSelector) {
        this.document = document;
        this.header = header;
        this.controls = controls;
        this.statistics = statistics;
        this.footer = footer;
        this.toggle = toggle;
        this.slider = slider;
        this.textField = textField;
        this.outerScroll = outerScroll;
        this.innerScroll = innerScroll;
        this.listView = listView;
        this.menuOwner = menuOwner;
        this.menu = menu;
        this.fonts = fonts;
        this.fontFamilies = fontFamilies;
        this.fontSelector = fontSelector;
    }

    /** 构造覆盖 v0.11 基础控件和 Yoga 主要布局语义的完整场景。 */
    static UiDemoScene install(UiDocument document, ClipboardService clipboard) {
        return install(document, clipboard, null);
    }

    /** 构造场景，并在存在多个注册字体时加入运行时字体切换按钮。 */
    static UiDemoScene install(UiDocument document, ClipboardService clipboard,
                               UiSystem fonts) {
        Objects.requireNonNull(document, "document").ensureOpen();
        UiDemoStrings text = UiDemoStrings.load();

        Panel root = document.root();
        root.style(UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.percent(100.0f))
                .padding(UiInsets.points(18.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN)
                .gap(12.0f)
                .build());

        Panel header = named(new Panel(), "UiDemoHeader");
        header.style(UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.points(70.0f))
                .flexShrink(0.0f)
                .padding(UiInsets.points(12.0f))
                .flexDirection(UiStyle.FlexDirection.ROW)
                .alignItems(UiStyle.AlignItems.CENTER)
                .gap(14.0f)
                .build());
        Label title = named(new Label(text.title()), "UiDemoTitle");
        title.style(UiStyle.builder().width(UiLength.percent(42.0f)).build());
        Label statistics = named(new Label("UiFrameStats: waiting for first frame"),
                "UiDemoStatistics");
        statistics.style(UiStyle.builder().flexGrow(1.0f).build());
        header.add(title).add(statistics);

        Panel main = named(new Panel(), "UiDemoMain");
        main.style(UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .flexGrow(1.0f)
                .minHeight(UiLength.points(280.0f))
                .padding(UiInsets.points(10.0f))
                .flexDirection(UiStyle.FlexDirection.ROW)
                .alignItems(UiStyle.AlignItems.STRETCH)
                .gap(10.0f)
                .build());

        Panel controls = named(new Panel(), "UiDemoControls");
        controls.style(UiStyle.builder()
                .width(UiLength.points(220.0f))
                .padding(UiInsets.points(10.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN)
                .gap(8.0f)
                .build());
        Label controlsTitle = new Label(text.controls());
        controlsTitle.style(fixedHeight(24.0f));
        Button action = new Button(text.actionButton());
        action.style(controlStyle());
        Toggle toggle = new Toggle(text.toggle());
        toggle.style(controlStyle());
        Button menuOwner = new Button(text.menuButton());
        menuOwner.style(controlStyle());
        Button fontSelector = null;
        List<String> fontFamilies = fonts == null ? List.of() : fonts.fontFamilies();
        if (fontFamilies.size() > 1) {
            fontSelector = named(new Button(fontButtonText(fonts.activeFontFamily())),
                    "UiDemoFontSelector");
            fontSelector.style(controlStyle());
        }

        Label sliderLabel = new Label(text.slider());
        sliderLabel.style(fixedHeight(22.0f));
        Panel sliderSurface = named(new Panel(), "UiDemoSliderSurface");
        sliderSurface.style(UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.points(38.0f))
                .padding(UiInsets.points(5.0f))
                .build());
        Slider slider = new Slider(0.0, 2.0, 1.0).step(0.05);
        slider.debugName("UiDemoSlider");
        slider.style(UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.points(28.0f))
                .build());
        sliderSurface.add(slider);

        TextField textField = new TextField().placeholder(text.textPlaceholder());
        textField.debugName("UiDemoTextField");
        textField.style(controlStyle());
        if (clipboard != null) textField.clipboard(clipboard);
        controls.add(controlsTitle).add(action).add(toggle).add(menuOwner);
        if (fontSelector != null) controls.add(fontSelector);
        controls.add(sliderLabel).add(sliderSurface).add(textField);

        Panel center = named(new Panel(), "UiDemoCenter");
        center.style(UiStyle.builder()
                .flexGrow(1.0f)
                .minWidth(UiLength.points(240.0f))
                .padding(UiInsets.points(10.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN)
                .gap(8.0f)
                .build());
        Label subtitle = new Label(text.subtitle());
        subtitle.style(fixedHeight(24.0f));
        Label scrollTitle = new Label(text.scrollTitle());
        scrollTitle.style(fixedHeight(22.0f));
        ScrollView outerScroll = named(new ScrollView(), "UiDemoOuterScroll");
        outerScroll.style(UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.points(0.0f))
                .minHeight(UiLength.points(0.0f))
                .flexGrow(1.0f)
                .overflow(UiStyle.Overflow.SCROLL)
                .build());
        Panel outerContent = named(new Panel(), "UiDemoOuterContent");
        outerContent.style(UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.percent(100.0f))
                .minHeight(UiLength.points(520.0f))
                .flexShrink(0.0f)
                .padding(UiInsets.points(10.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN)
                .gap(8.0f)
                .build());
        outerContent.add(new Label("Retained paint order remains stable / 绘制顺序保持稳定"));
        outerContent.add(new Label("Scroll offset is applied once / 滚动偏移仅应用一次"));
        outerContent.add(new Label(text.nestedTitle()));

        ScrollView innerScroll = named(new ScrollView(), "UiDemoInnerScroll");
        innerScroll.style(UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.points(132.0f))
                .overflow(UiStyle.Overflow.SCROLL)
                .build());
        Panel innerContent = named(new Panel(), "UiDemoInnerContent");
        innerContent.style(UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.points(260.0f))
                .flexShrink(0.0f)
                .padding(UiInsets.points(8.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN)
                .gap(7.0f)
                .build());
        for (int row = 1; row <= 8; row++) {
            Label line = new Label("Nested row " + row + " / 内层条目 " + row);
            line.style(fixedHeight(22.0f));
            innerContent.add(line);
        }
        innerScroll.content(innerContent).scrollTo(0.0, 18.0);
        outerContent.add(innerScroll);
        for (int row = 1; row <= 5; row++) {
            outerContent.add(new Label("Outer content " + row + " / 外层内容 " + row));
        }
        outerScroll.content(outerContent).scrollTo(0.0, 24.0);
        center.add(subtitle).add(scrollTitle).add(outerScroll);

        Panel listColumn = named(new Panel(), "UiDemoListColumn");
        listColumn.style(UiStyle.builder()
                .width(UiLength.points(220.0f))
                .padding(UiInsets.points(9.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN)
                .gap(7.0f)
                .build());
        Label listTitle = new Label(text.listTitle());
        listTitle.style(fixedHeight(24.0f));
        ListView listView = new ListView();
        listView.debugName("UiDemoListView");
        listView.style(UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.points(0.0f))
                .minHeight(UiLength.points(0.0f))
                .flexGrow(1.0f)
                .overflow(UiStyle.Overflow.SCROLL)
                .build());
        listView.estimatedItemHeight(30.0f).overscan(2).model(1000, index -> {
            Button cell = new Button(String.format(Locale.ROOT,
                    "Item %04d / 条目 %04d", index, index));
            cell.style(UiStyle.builder()
                    .width(UiLength.percent(100.0f))
                    .height(UiLength.points(30.0f))
                    .padding(new UiInsets(
                            UiLength.points(6.0f), UiLength.points(2.0f),
                            UiLength.points(2.0f), UiLength.points(0.0f)))
                    .build());
            return cell;
        });
        listColumn.add(listTitle).add(listView);

        main.add(controls).add(center).add(listColumn);
        Label footer = named(new Label(text.footerReady()), "UiDemoFooter");
        footer.style(UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.points(28.0f))
                .flexShrink(0.0f)
                .build());
        root.add(header).add(main).add(footer);

        Menu menu = named(new Menu(), "UiDemoPopupMenu");
        menu.style(UiStyle.builder()
                .positionType(UiStyle.PositionType.ABSOLUTE)
                .width(UiLength.points(230.0f))
                .height(UiLength.points(116.0f))
                .padding(UiInsets.points(7.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN)
                .gap(5.0f)
                .build());
        menu.item(text.menuFirst(), () -> footer.text("Applied / 已应用"))
                .style(controlStyle());
        menu.item(text.menuSecond(), () -> footer.text("Reset / 已重置"))
                .style(controlStyle());
        UiDemoScene scene = new UiDemoScene(document, header, controls,
                statistics, footer, toggle, slider,
                textField, outerScroll, innerScroll, listView, menuOwner, menu,
                fonts, fontFamilies, fontSelector);
        action.onClick(() -> footer.text("Action completed / 操作完成"));
        toggle.onValueChanged(value -> footer.text("Toggle=" + value + " / 开关=" + value));
        slider.onValueChanged(value -> footer.text(String.format(Locale.ROOT,
                "Slider=%.2f / 滑块=%.2f", value, value)));
        if (fontSelector != null) fontSelector.onClick(scene::cycleFont);
        menuOwner.onClick(scene::showMenu);
        return scene;
    }

    /** 把最近一帧 UiFrameStats 放进 retained tree，下一帧即可显示。 */
    void updateStatistics(UiFrameStats value) {
        Objects.requireNonNull(value, "value");
        statistics.text(String.format(Locale.ROOT,
                "UiFrameStats nodes=%d layout=%d quads=%d batches=%d draws=%d update=%.3fms render=%.3fms",
                value.visibleNodes(), value.layoutPasses(), value.quads(), value.batches(),
                value.drawCalls(), value.uiUpdateNanos() / 1_000_000.0,
                value.renderRecordNanos() / 1_000_000.0));
    }

    /** 执行不依赖平台输入的内置确定性操作。 */
    void applyBuiltinScript(int frame) {
        switch (frame) {
            case 1 -> toggle.value(true);
            case 2 -> slider.value(1.35);
            case 3 -> textField.value("Haikalat UI 中文 English");
            case 4 -> {
                outerScroll.scrollTo(0.0, 68.0);
                innerScroll.scrollTo(0.0, 42.0);
            }
            case 5 -> {
                listView.scrollTo(0.0, 360.0);
                footer.text("List virtualization advanced / 列表已虚拟滚动");
            }
            case 6 -> showMenu();
            case 7 -> cycleFont();
            case 8 -> textField.selectAll();
            default -> {
                // 其余帧只验证稳定 retained 状态。
            }
        }
    }

    void startAnimationProof(UiSystem ui) {
        ComputedStyle style = header.computedStyle();
        header.computedStyle(new ComputedStyle(style.background(), style.foreground(),
                style.borderColor(), style.borderWidth(), style.radius(), 0.0f,
                style.fontSize(), style.fontFamily(), style.textEffect()));
        ui.animations().tweenOpacity(header, 1.0f,
                new UiTweenSpec(0.5f, UiEasing.EASE_IN_OUT_CUBIC));
        ui.animations().transitionLayout(controls,
                UiStyle.builder()
                        .width(UiLength.points(260.0f))
                        .padding(UiInsets.points(10.0f))
                        .flexDirection(UiStyle.FlexDirection.COLUMN)
                        .gap(8.0f)
                        .build(), UiTweenSpec.spring(0.6f));
    }

    float animatedHeaderOpacity() { return header.computedStyle().opacity(); }
    float animatedControlsWidth() { return controls.style().width().value(); }

    /** layout 改变后刷新虚拟列表可见窗口。 */
    void refreshVirtualizedContent() {
        listView.refreshViewport();
    }

    Label statisticsLabel() { return statistics; }
    Toggle toggle() { return toggle; }
    Slider slider() { return slider; }
    TextField textField() { return textField; }
    ScrollView outerScroll() { return outerScroll; }
    ScrollView innerScroll() { return innerScroll; }
    ListView listView() { return listView; }
    Popup popup() { return menu; }

    @Override
    public void close() {
        if (!menu.isClosed()) menu.close();
    }

    private void showMenu() {
        if (!menu.isOpen() && !menu.isClosed()) menu.open(document, menuOwner);
    }

    private void cycleFont() {
        if (fonts == null || fontSelector == null || fontFamilies.size() < 2) return;
        int current = fontFamilies.indexOf(fonts.activeFontFamily());
        String next = fontFamilies.get((current + 1) % fontFamilies.size());
        fonts.selectFontFamily(next);
        fontSelector.text(fontButtonText(next));
        footer.text("Font=" + next + " / 字体已切换");
    }

    private static UiStyle controlStyle() {
        return UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.points(36.0f))
                .padding(UiInsets.points(6.0f))
                .build();
    }

    private static UiStyle fixedHeight(float height) {
        return UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.points(height))
                .build();
    }

    private static String fontButtonText(String familyName) {
        return "Font: " + familyName + " / 字体";
    }

    private static <T extends UiNode> T named(T node, String name) {
        node.debugName(name);
        return node;
    }
}
