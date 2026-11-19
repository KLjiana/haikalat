package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureActivity;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureState;
import com.kaleblangley.haikalat.subsystems.ui.UiConfig;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.ui.UiVisibility;
import com.kaleblangley.haikalat.subsystems.ui.style.ComputedStyle;
import com.kaleblangley.haikalat.subsystems.ui.style.StyleResolver;
import com.kaleblangley.haikalat.subsystems.ui.style.Theme;
import com.kaleblangley.haikalat.subsystems.ui.style.ThemeTokens;
import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;
import com.kaleblangley.haikalat.subsystems.ui.style.UiInsets;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.text.TextEffect;
import com.kaleblangley.haikalat.subsystems.ui.widget.Button;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.ui.widget.ScrollView;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Retained-mode station card, museum hall bar and controls.
 *
 * <p>The station card is split into labelled sections (overview, limits, gate,
 * evidence, live production status). The hall bar shows the exhibit in view.</p>
 */
final class ExhibitionHud implements AutoCloseable {
    private final UiSystem ui;
    private final ExhibitionSession session;
    private final Label stationTitleLabel;
    private final Label stationSubtitleLabel;
    private final Label phaseLabel;
    private final Label wiringLabel;
    private final Label subjectLabel;
    private final Label limitationLabel;
    private final Label gateLabel;
    private final Label evidenceLabel;
    private final Label featureLabel;
    private final Label diagnosticsLabel;
    private final Button phaseButton;
    private final Button resetButton;
    private final Button previousButton;
    private final Button nextButton;
    private final Panel activePanel;
    private final Label hallTitle;
    private final Label hallSubject;
    private final Label hallComparison;
    private final Label hallBudget;
    private final boolean museumMode;

    private int renderedStationId = -1;
    private ExhibitionPhase renderedPhase;
    private boolean renderedWiring;
    private int shownExhibitId = -1;

    private ExhibitionHud(GlfwWindow window, ExhibitionSession session, boolean museumMode) {
        this.session = session;
        this.museumMode = museumMode;
        this.ui = UiSystem.create(window, config());
        Panel root = ui.document().root();
        root.addStyleClass("layout");
        root.style(UiStyle.builder().width(UiLength.percent(100)).height(UiLength.percent(100))
                .padding(UiInsets.points(12))
                .flexDirection(UiStyle.FlexDirection.COLUMN)
                .justifyContent(UiStyle.JustifyContent.FLEX_END)
                .alignItems(UiStyle.AlignItems.FLEX_START).build());

        Panel hall = new Panel();
        hall.debugName("ExhibitionHall");
        hall.addStyleClass("surface");
        hall.style(UiStyle.builder().width(UiLength.points(420)).maxWidth(UiLength.percent(100))
                .padding(UiInsets.points(12)).gap(4)
                .flexDirection(UiStyle.FlexDirection.COLUMN).build());
        hallTitle = label("实时渲染展览馆", "chip", 24f);
        hall.add(hallTitle);
        hallSubject = value("");
        hallSubject.visibility(UiVisibility.COLLAPSED);
        hall.add(hallSubject);
        hallComparison = muted("", 1);
        hallComparison.visibility(UiVisibility.COLLAPSED);
        hall.add(hallComparison);
        hallBudget = muted("体积粒子超过当前画面的覆盖预算，拉远后恢复", 2);
        hallBudget.visibility(UiVisibility.COLLAPSED);
        hall.add(hallBudget);
        hall.add(muted("WASD 无碰撞飞行 · 空格上升 · Shift 下降"
                + "\n鼠标环顾 · 靠近展位演示"
                + "\nTab 俯视 · M 释放指针 · H 隐藏说明 · Esc 离馆", 3));

        Panel card = new Panel();
        ScrollView cardScroll = new ScrollView();
        cardScroll.addStyleClass("surface");
        cardScroll.style(UiStyle.builder().width(UiLength.points(440))
                .maxWidth(UiLength.percent(100)).height(UiLength.percent(100)).build());
        cardScroll.content(card);
        card.debugName("ExhibitionStationCard");
        card.style(UiStyle.builder().width(UiLength.percent(100))
                .padding(UiInsets.points(12)).gap(3)
                .flexDirection(UiStyle.FlexDirection.COLUMN).build());
        stationTitleLabel = label("", "title", 34f);
        card.add(stationTitleLabel);
        stationSubtitleLabel = muted("", 1);
        card.add(stationSubtitleLabel);
        phaseLabel = label("", "chip", 20f);
        card.add(phaseLabel);
        wiringLabel = label("", "muted", 20f);
        card.add(wiringLabel);
        Panel controls = new Panel();
        controls.addStyleClass("layout");
        controls.style(UiStyle.builder().width(UiLength.percent(100)).height(UiLength.points(32))
                .flexDirection(UiStyle.FlexDirection.ROW).gap(4).build());
        previousButton = button("上一站");
        previousButton.onClick(session::previous);
        phaseButton = button("A/B");
        phaseButton.onClick(session::togglePhase);
        resetButton = button("重置");
        resetButton.onClick(session::reset);
        nextButton = button("下一站");
        nextButton.onClick(session::next);
        controls.add(previousButton).add(phaseButton).add(resetButton).add(nextButton);
        card.add(controls);
        card.add(muted("M 指针 / 滚轮阅读 · H 隐藏说明 · B 切换 · N/P 巡览 · R 重置 · Esc 离馆", 2));

        card.add(spacer(2));
        card.add(section("观察重点"));
        subjectLabel = value("");
        card.add(subjectLabel);
        card.add(spacer(2));
        card.add(section("已知限制"));
        limitationLabel = value("");
        card.add(limitationLabel);
        card.add(spacer(2));
        card.add(section("运行条件"));
        gateLabel = value("");
        card.add(gateLabel);
        card.add(spacer(2));
        card.add(section("验证依据"));
        evidenceLabel = value("");
        card.add(evidenceLabel);
        card.add(spacer(2));
        card.add(section("实时渲染状态"));
        featureLabel = value("");
        card.add(featureLabel);
        diagnosticsLabel = muted("", 1);
        card.add(diagnosticsLabel);

        activePanel = museumMode ? hall : cardScroll;
        root.add(activePanel);
        ui.attachTo(session.pipeline().graph(), session.pipeline().finalPassName());
        if (!museumMode) refreshCard();
    }

    static ExhibitionHud attach(GlfwWindow window, ExhibitionSession session, boolean museumMode) {
        return new ExhibitionHud(window, session, museumMode);
    }

    void update(WindowInputSnapshot input, float deltaSeconds) {
        if (museumMode) {
            hallBudget.visibility(session.hallParticleBudgetLimited() ? UiVisibility.VISIBLE : UiVisibility.COLLAPSED);
            hallComparison.visibility(session.hallGiComparisonAvailable() ? UiVisibility.VISIBLE : UiVisibility.COLLAPSED);
            if (session.hallGiComparisonAvailable()) {
                hallComparison.text(session.hallGiBaseline() ? "B 对照 · 当前关闭间接光"
                        : "B 对照 · 当前启用间接光");
            }
        } else {
            refreshCard();
            refreshLive();
        }
        ui.update(input, deltaSeconds);
        // A button callback can replace the graph during input dispatch.
        ui.rebindTo(session.pipeline().graph(), session.pipeline().finalPassName());
    }

    void toggleCard() {
        activePanel.visibility(activePanel.visibility() == UiVisibility.VISIBLE
                ? UiVisibility.COLLAPSED : UiVisibility.VISIBLE);
    }

    void setNearest(int stationId) {
        if (stationId == shownExhibitId) return;
        shownExhibitId = stationId;
        if (stationId < 0) {
            hallTitle.text("实时渲染展览馆");
            hallSubject.visibility(UiVisibility.COLLAPSED);
        } else {
            ExhibitionStation station = ExhibitionCatalog.station(stationId);
            hallTitle.text(station.displayName());
            hallSubject.text(station.subject());
            hallSubject.visibility(UiVisibility.VISIBLE);
        }
    }

    private void refreshCard() {
        ExhibitionStation station = session.station();
        if (station.id() != renderedStationId || session.phase() != renderedPhase
                || session.wired() != renderedWiring) {
            renderedStationId = station.id();
            renderedPhase = session.phase();
            renderedWiring = session.wired();
            stationTitleLabel.text(station.displayName());
            stationSubtitleLabel.text(station.nameEn() + " · 展翼 "
                    + station.wing().displayName());
            subjectLabel.text(station.subject());
            limitationLabel.text(String.join("；", station.limitations()));
            gateLabel.text(station.gate());
            evidenceLabel.text(String.join(" · ", station.evidence()));
            wiringLabel.text(session.wired() ? "生产场景已接入"
                    : "○ 接线: 未接线（批次 " + session.pendingBatch().title() + "）");
            wiringLabel.addStyleClass(session.wired() ? "section" : "muted");
        }
        phaseLabel.text("相位 " + session.phase().key() + " · " + session.phase().label());
        if (session.wired()) {
            String note = session.note();
            subjectLabel.text(session.station().subject()
                    + (note.isEmpty() ? "" : "\n" + note));
        }
    }

    private void refreshLive() {
        RenderFeatureState state = session.featureState();
        phaseButton.text(session.phase() == ExhibitionPhase.BASELINE ? "B 效果" : "A 基线");
        List<RenderFeatureActivity> activities = state.activities();
        long active = activities.stream().filter(RenderFeatureActivity::active).count();
        String reasonSummary = activities.stream().filter(activity -> !activity.active())
                .limit(3)
                .map(activity -> activity.feature() + ":" + activity.reason())
                .collect(Collectors.joining(", "));
        String feature = "status " + state.status()
                + " · req rev " + state.requestedRevision()
                + " / eff rev " + state.effectiveRevision()
                + " · active " + active + "/" + activities.size();
        if (!state.failureReason().isBlank()) {
            feature += "\nfailure: " + state.failureReason();
        }
        if (!reasonSummary.isEmpty()) {
            feature += "\ninactive: " + reasonSummary;
        }
        featureLabel.text(feature);
        diagnosticsLabel.text("generation " + session.diagnostics().activeGenerationId()
                + (session.diagnostics().topologyRebuilt() ? " (rebuilt)" : "")
                + " · frame " + session.frameIndex());
    }

    @Override
    public void close() {
        ui.close();
    }

    private static UiConfig config() {
        Theme theme = Theme.dark();
        ThemeTokens tokens = theme.tokens();
        StyleResolver resolver = (widgetType, classes, states, inherited) -> {
            boolean visualSurface = classes.contains("surface") || widgetType.equals("Button");
            UiColor foreground = tokens.text();
            float fontSize = inherited == null ? tokens.fontSize() : inherited.fontSize();
            if (classes.contains("title")) {
                foreground = tokens.text();
                fontSize = 30.0f;
            } else if (classes.contains("section")) {
                foreground = UiColor.fromSrgbHex(0xE2BD80FF);
                fontSize = 15.0f;
            } else if (classes.contains("chip")) {
                foreground = UiColor.fromSrgbHex(0x75D8DEFF);
                fontSize = 16.0f;
            } else if (classes.contains("muted")) {
                foreground = UiColor.fromSrgbHex(0xA8B7C9FF);
                fontSize = 15.0f;
            } else if (classes.contains("value")) {
                foreground = tokens.text();
                fontSize = 16.0f;
            }
            if (states.contains(StyleResolver.PseudoState.DISABLED)) {
                foreground = UiColor.fromSrgbHex(0xA8B7C9FF);
            }
            return new ComputedStyle(
                    visualSurface ? UiColor.fromSrgbHex(widgetType.equals("Button")
                            ? (states.contains(StyleResolver.PseudoState.PRESSED) ? 0x276574FF
                            : states.contains(StyleResolver.PseudoState.HOVER) ? 0x344F67FF : 0x25364BFF)
                            : 0x18282FF5) : UiColor.TRANSPARENT,
                    foreground,
                    visualSurface ? UiColor.fromSrgbHex(0x42576EFF) : UiColor.TRANSPARENT,
                    visualSurface ? 1.0f : 0.0f,
                    visualSurface ? 10.0f : 0.0f,
                    1.0f, fontSize,
                    inherited != null ? inherited.fontFamily() : tokens.fontFamily(),
                    inherited != null ? inherited.textEffect() : TextEffect.none());
        };
        return UiConfig.builder().theme(theme).styleResolver(resolver).build();
    }

    private Label label(String text, String styleClass, float height) {
        Label label = new Label(text);
        label.maximumLines(1).ellipsis(true);
        label.addStyleClass(styleClass);
        label.style(UiStyle.builder().width(UiLength.percent(100))
                .height(UiLength.points(height)).build());
        return label;
    }

    private Label section(String text) {
        return label(text, "section", 22.0f);
    }

    private Panel spacer(float height) {
        Panel spacer = new Panel();
        spacer.style(UiStyle.builder().width(UiLength.percent(100))
                .height(UiLength.points(height)).build());
        return spacer;
    }

    private Label value(String text) {
        Label label = new Label(text);
        label.addStyleClass("value");
        label.wrap(Label.Wrap.WORD);
        label.style(UiStyle.builder().width(UiLength.percent(100)).build());
        return label;
    }

    private Label muted(String text, int lines) {
        Label label = new Label(text);
        label.addStyleClass("muted");
        label.wrap(Label.Wrap.WORD).maximumLines(lines).ellipsis(true);
        label.style(UiStyle.builder().width(UiLength.percent(100))
                .height(UiLength.points(17.0f * lines + 4.0f)).build());
        return label;
    }

    private Button button(String text) {
        Button button = new Button(text);
        // Pointer-only controls: Space (fly up) must never re-activate a button.
        button.focusable(false);
        button.style(UiStyle.builder().flexGrow(1).height(UiLength.points(30)).build());
        return button;
    }

}
