package com.kaleblangley.haikalat.demo.ui;

import com.kaleblangley.haikalat.subsystems.ui.UiFrameStats;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.ui.UiVisibility;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiAnimationGroup;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiAnimationSequence;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiEasing;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiPropertyTrack;
import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;
import com.kaleblangley.haikalat.subsystems.ui.style.UiInsets;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.text.UiTextEngine;
import com.kaleblangley.haikalat.subsystems.ui.vfx.UiEffectDefinition;
import com.kaleblangley.haikalat.subsystems.ui.widget.Button;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.ui.widget.Slider;
import com.kaleblangley.haikalat.subsystems.ui.widget.Toggle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Retained scene graph for the standalone game main-menu showcase. */
final class ModernGameMenuScene {
    private final UiSystem ui;
    private final ModernUiDemo.Options options;
    private final Runnable requestClose;
    private final Panel shell;
    private final Panel campaignPanel;
    private final Panel settingsPanel;
    private final Panel orb;
    private final Button continueButton;
    private final Button settingsButton;
    private final Toggle motionToggle;
    private final Slider volumeSlider;
    private final Label status;
    private final Label diagnostics;
    private final List<Button> menuButtons;
    private long ambientSequence;
    private long actionSequence = 10_000L;
    private boolean settingsOpen;

    private ModernGameMenuScene(UiSystem ui, ModernUiDemo.Options options,
                                Runnable requestClose, Panel shell, Panel campaignPanel,
                                Panel settingsPanel, Panel orb, Button continueButton,
                                Button settingsButton, Toggle motionToggle,
                                Slider volumeSlider, Label status, Label diagnostics,
                                List<Button> menuButtons) {
        this.ui = ui;
        this.options = options;
        this.requestClose = requestClose;
        this.shell = shell;
        this.campaignPanel = campaignPanel;
        this.settingsPanel = settingsPanel;
        this.orb = orb;
        this.continueButton = continueButton;
        this.settingsButton = settingsButton;
        this.motionToggle = motionToggle;
        this.volumeSlider = volumeSlider;
        this.status = status;
        this.diagnostics = diagnostics;
        this.menuButtons = List.copyOf(menuButtons);
    }

    static ModernGameMenuScene install(UiSystem ui, ModernUiDemo.Options options,
                                       Runnable requestClose) {
        ui.selectFontFamily(UiTextEngine.DEFAULT_FONT_FAMILY);
        var root = ui.document().root();
        root.style(UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.percent(100.0f))
                .padding(UiInsets.points(24.0f))
                .build());

        Panel shell = panel("screen", UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.percent(100.0f))
                .padding(UiInsets.points(24.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN)
                .gap(18.0f)
                .build(), "GameMenuShell");
        shell.layerDescription(ModernUiDemo.layer(options.width(), options.height()));

        Panel topBar = topBar();
        Panel body = panel("transparent", UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .flexGrow(1.0f)
                .flexDirection(UiStyle.FlexDirection.ROW)
                .gap(26.0f)
                .build(), "GameMenuBody");
        Panel navigation = panel("transparent", UiStyle.builder()
                .width(UiLength.percent(35.0f))
                .height(UiLength.percent(100.0f))
                .padding(UiInsets.points(8.0f, 4.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN)
                .gap(13.0f)
                .build(), "GameMenuNavigation");

        Panel intro = panel("transparent", UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .flexGrow(1.0f)
                .flexDirection(UiStyle.FlexDirection.COLUMN)
                .gap(4.0f)
                .build(), "GameMenuIntro");
        intro.add(label("CAMPAIGN // ARCHIVE 07", "eyebrow", 20.0f));
        intro.add(label("ASHEN", "display-title", 54.0f));
        intro.add(label("HORIZON", "display-title", 54.0f));
        Label premise = label("穿过极夜边境，重启最后一座天气塔。你的队伍仍在等待。",
                "muted", 54.0f);
        premise.wrap(Label.Wrap.WORD).maximumLines(2);
        intro.add(premise);
        Panel accent = panel("accent-line", UiStyle.builder()
                .width(UiLength.points(64.0f)).height(UiLength.points(4.0f)).build(),
                "GameMenuAccent");
        intro.add(accent);

        List<Button> menuButtons = new ArrayList<>();
        Button continueButton = menuButton("继续游戏   CONTINUE", "primary-button", 58.0f);
        Button newGameButton = menuButton("新游戏     NEW CAMPAIGN", "nav-button", 43.0f);
        Button loadoutButton = menuButton("装备配置   LOADOUT", "nav-button", 43.0f);
        Button settingsButton = menuButton("系统设置   SETTINGS", "nav-button", 43.0f);
        Button quitButton = menuButton("退出档案   EXIT", "danger-button", 43.0f);
        menuButtons.add(continueButton);
        menuButtons.add(newGameButton);
        menuButtons.add(loadoutButton);
        menuButtons.add(settingsButton);
        menuButtons.add(quitButton);
        Panel menu = panel("transparent", UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN)
                .gap(8.0f)
                .build(), "GameMenuActions");
        menuButtons.forEach(menu::add);
        navigation.add(intro).add(menu);

        Panel content = panel("transparent", UiStyle.builder()
                .flexGrow(1.0f).height(UiLength.percent(100.0f))
                .build(), "GameMenuContent");
        Panel orb = orb();
        Panel campaign = campaignPanel(orb);
        Panel settings = createSettingsPanel();
        settings.visibility(UiVisibility.COLLAPSED);
        content.add(campaign).add(settings);
        body.add(navigation).add(content);

        Label status = label("SYSTEM READY // CHECKPOINT 04:17:32", "mono", 22.0f);
        Label diagnostics = label("UI PIPELINE WARMING UP", "dim", 22.0f);
        diagnostics.alignment(Label.Alignment.END);
        Panel footer = panel("footer", UiStyle.builder()
                .width(UiLength.percent(100.0f)).height(UiLength.points(24.0f))
                .flexDirection(UiStyle.FlexDirection.ROW)
                .justifyContent(UiStyle.JustifyContent.SPACE_BETWEEN)
                .alignItems(UiStyle.AlignItems.CENTER)
                .build(), "GameMenuFooter");
        footer.add(status).add(diagnostics);

        shell.add(topBar).add(body).add(footer);
        root.add(shell);

        Toggle motionToggle = descendants(settings).stream()
                .filter(Toggle.class::isInstance).map(Toggle.class::cast)
                .filter(node -> "MotionToggle".equals(node.debugName()))
                .findFirst().orElseThrow();
        Slider volumeSlider = descendants(settings).stream()
                .filter(Slider.class::isInstance).map(Slider.class::cast)
                .filter(node -> "VolumeSlider".equals(node.debugName()))
                .findFirst().orElseThrow();
        Button backButton = descendants(settings).stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(node -> "SettingsBack".equals(node.debugName()))
                .findFirst().orElseThrow();

        ModernGameMenuScene scene = new ModernGameMenuScene(ui, options, requestClose,
                shell, campaign, settings, orb, continueButton, settingsButton,
                motionToggle, volumeSlider, status, diagnostics, menuButtons);
        scene.bindActions(newGameButton, loadoutButton, quitButton, backButton);
        scene.startPresentation(topBar, navigation, campaign);
        return scene;
    }

    private void bindActions(Button newGame, Button loadout, Button quit, Button back) {
        continueButton.onClick(() -> activate(continueButton,
                "CHECKPOINT ACCEPTED // PREPARING FROSTLINE", UiEffectDefinition.Type.SPARK));
        newGame.onClick(() -> activate(newGame,
                "NEW CAMPAIGN SLOT RESERVED", UiEffectDefinition.Type.RIPPLE));
        loadout.onClick(() -> activate(loadout,
                "LOADOUT SYNCHRONIZED // 3 MODULES READY", UiEffectDefinition.Type.RIPPLE));
        settingsButton.onClick(() -> setSettingsOpen(true));
        back.onClick(() -> setSettingsOpen(false));
        quit.onClick(() -> {
            activate(quit, "ARCHIVE SESSION CLOSED", UiEffectDefinition.Type.GLITCH);
            requestClose.run();
        });
        motionToggle.value(options.reducedMotion());
        motionToggle.text(options.reducedMotion() ? "REDUCED" : "FULL");
        motionToggle.onValueChanged(value -> {
            motionToggle.text(value ? "REDUCED" : "FULL");
            if (value && ambientSequence > 0L) {
                ui.timeline().cancel(ambientSequence);
                ambientSequence = 0L;
            }
            ui.timeline().reducedMotion(value);
            ui.effects().reducedMotion(value);
            if (!value && options.animation() && ambientSequence == 0L) {
                ambientSequence = startAmbientAnimation();
            }
            status.text(value ? "MOTION PROFILE // REDUCED" : "MOTION PROFILE // FULL");
        });
        volumeSlider.onValueChanged(value -> status.text(String.format(Locale.ROOT,
                "MASTER OUTPUT // %03.0f%%", value)));
    }

    private void startPresentation(Panel topBar, Panel navigation, Panel campaign) {
        ui.timeline().reducedMotion(options.reducedMotion());
        ui.effects().reducedMotion(options.reducedMotion()).maximumActiveEffects(12);
        ui.effects().register(shell).register(campaign).register(orb);
        for (Button button : menuButtons) ui.effects().register(button);

        if (options.animation()) {
            UiAnimationSequence topEntrance = entrance(topBar, 0.0, -18.0);
            UiAnimationSequence navEntrance = entrance(navigation, -30.0, 0.0);
            UiAnimationSequence cardEntrance = entrance(campaign, 36.0, 0.0);
            ui.timeline().play(new UiAnimationGroup(
                    List.of(topEntrance, navEntrance, cardEntrance),
                    UiAnimationGroup.Completion.ALL));
            if (!options.reducedMotion()) {
                ambientSequence = startAmbientAnimation();
            }
        }
        if (options.uiVfx() && options.effectEnabled("shimmer")) {
            ui.effects().start(effect(UiEffectDefinition.Type.SHIMMER, ++actionSequence),
                    campaign, actionSequence);
        }
    }

    private long startAmbientAnimation() {
        return ui.timeline().play(UiAnimationSequence.builder()
                .then(orb, 1.4f, UiEasing.EASE_IN_OUT_CUBIC,
                        UiPropertyTrack.numeric(UiPropertyTrack.Property.SCALE_X, 1.0, 1.035),
                        UiPropertyTrack.numeric(UiPropertyTrack.Property.SCALE_Y, 1.0, 1.035),
                        UiPropertyTrack.numeric(UiPropertyTrack.Property.EFFECT_STRENGTH,
                                0.15, 0.8))
                .then(orb, 1.4f, UiEasing.EASE_IN_OUT_CUBIC,
                        UiPropertyTrack.numeric(UiPropertyTrack.Property.SCALE_X, 1.035, 1.0),
                        UiPropertyTrack.numeric(UiPropertyTrack.Property.SCALE_Y, 1.035, 1.0),
                        UiPropertyTrack.numeric(UiPropertyTrack.Property.EFFECT_STRENGTH,
                                0.8, 0.15))
                .repeat(10_000, false)
                .build());
    }

    private void activate(Button button, String message, UiEffectDefinition.Type type) {
        status.text(message);
        long sequence = ++actionSequence;
        if (options.animation() && !ui.timeline().reducedMotion()) {
            sequence = ui.timeline().play(UiAnimationSequence.builder()
                    .then(button, 0.08f, UiEasing.EASE_OUT_CUBIC,
                            UiPropertyTrack.numeric(UiPropertyTrack.Property.SCALE_X, 1.0, 0.975),
                            UiPropertyTrack.numeric(UiPropertyTrack.Property.SCALE_Y, 1.0, 0.975))
                    .then(button, 0.18f, UiEasing.EASE_OUT_CUBIC,
                            UiPropertyTrack.numeric(UiPropertyTrack.Property.SCALE_X, 0.975, 1.0),
                            UiPropertyTrack.numeric(UiPropertyTrack.Property.SCALE_Y, 0.975, 1.0))
                    .build());
        }
        if (options.uiVfx()) {
            ui.effects().start(effect(type, actionSequence), button, sequence);
        }
    }

    private void setSettingsOpen(boolean value) {
        if (settingsOpen == value) return;
        settingsOpen = value;
        Panel incoming = value ? settingsPanel : campaignPanel;
        Panel outgoing = value ? campaignPanel : settingsPanel;
        outgoing.visibility(UiVisibility.COLLAPSED);
        incoming.visibility(UiVisibility.VISIBLE);
        status.text(value ? "SETTINGS CHANNEL OPEN" : "CAMPAIGN CHANNEL RESTORED");
        if (options.animation()) {
            ui.timeline().play(entrance(incoming, value ? 26.0 : -26.0, 0.0));
        }
        if (options.uiVfx() && options.effectEnabled("ripple")) {
            ui.effects().start(effect(UiEffectDefinition.Type.RIPPLE, ++actionSequence),
                    incoming, actionSequence);
        }
    }

    void runDeterministicScript(int frame) {
        if (frame == 18) setSettingsOpen(true);
        else if (frame == 32) motionToggle.value(true);
        else if (frame == 42) volumeSlider.value(72.0);
        else if (frame == 54) setSettingsOpen(false);
        else if (frame == 70) activate(continueButton,
                    "CHECKPOINT ACCEPTED // DETERMINISTIC PROOF", UiEffectDefinition.Type.SPARK);
    }

    void update(UiFrameStats stats, boolean updateStatistics) {
        if (!updateStatistics) return;
        diagnostics.text(String.format(Locale.ROOT,
                "NODES %02d  //  QUADS %03d  //  DRAWS %02d  //  FX %02d",
                stats.visibleNodes(), stats.quads(), stats.drawCalls(),
                ui.effects().diagnostics().activeEffects()));
    }

    String effectSummary() {
        if (!options.uiVfx()) return "off";
        return ui.effects().diagnostics().activeEffects() + " active";
    }

    Panel shell() { return shell; }
    Panel campaignPanel() { return campaignPanel; }
    Panel settingsPanel() { return settingsPanel; }
    Button continueButton() { return continueButton; }
    int menuButtonCount() { return menuButtons.size(); }
    boolean settingsOpen() { return settingsOpen; }
    String statusText() { return status.text(); }
    long ambientSequence() { return ambientSequence; }

    private static Panel topBar() {
        Panel top = panel("top-bar", UiStyle.builder()
                .width(UiLength.percent(100.0f)).height(UiLength.points(70.0f))
                .padding(UiInsets.points(16.0f, 10.0f))
                .flexDirection(UiStyle.FlexDirection.ROW)
                .justifyContent(UiStyle.JustifyContent.SPACE_BETWEEN)
                .alignItems(UiStyle.AlignItems.CENTER)
                .gap(16.0f)
                .build(), "GameMenuTopBar");
        Panel brand = panel("transparent", UiStyle.builder()
                .width(UiLength.percent(52.0f)).height(UiLength.percent(100.0f))
                .flexDirection(UiStyle.FlexDirection.ROW)
                .alignItems(UiStyle.AlignItems.CENTER).gap(12.0f).build(), "GameMenuBrand");
        Panel mark = panel("brand-mark", UiStyle.builder()
                .width(UiLength.points(42.0f)).height(UiLength.points(42.0f))
                .alignItems(UiStyle.AlignItems.CENTER)
                .justifyContent(UiStyle.JustifyContent.CENTER).build(), "GameMenuBrandMark");
        Label markText = label("H", "mono", 28.0f);
        markText.alignment(Label.Alignment.CENTER);
        mark.add(markText);
        Panel names = panel("transparent", UiStyle.builder()
                .height(UiLength.points(44.0f)).flexGrow(1.0f)
                .flexDirection(UiStyle.FlexDirection.COLUMN).gap(1.0f).build(),
                "GameMenuBrandNames");
        names.add(label("HAIKALAT // AFTERLIGHT", "eyebrow", 20.0f))
                .add(label("TACTICAL WEATHER ARCHIVE", "dim", 18.0f));
        brand.add(mark).add(names);

        Panel profile = panel("profile-card", UiStyle.builder()
                .width(UiLength.points(330.0f)).height(UiLength.points(46.0f))
                .padding(UiInsets.points(12.0f, 7.0f))
                .flexDirection(UiStyle.FlexDirection.ROW)
                .alignItems(UiStyle.AlignItems.CENTER)
                .justifyContent(UiStyle.JustifyContent.SPACE_BETWEEN)
                .gap(10.0f).build(), "GameMenuProfile");
        Panel online = panel("chip", UiStyle.builder()
                .width(UiLength.points(92.0f)).height(UiLength.points(28.0f))
                .alignItems(UiStyle.AlignItems.CENTER)
                .justifyContent(UiStyle.JustifyContent.CENTER).build(), "OnlineChip");
        Label onlineText = label("● ONLINE", "mono", 20.0f);
        onlineText.alignment(Label.Alignment.CENTER);
        online.add(onlineText);
        Label profileName = label("KL_JIANA   LV.27", "mono", 22.0f);
        profileName.alignment(Label.Alignment.END);
        profile.add(online).add(profileName);
        top.add(brand).add(profile);
        return top;
    }

    private static Panel campaignPanel(Panel orb) {
        Panel campaign = panel("hero-card", UiStyle.builder()
                .width(UiLength.percent(100.0f)).height(UiLength.percent(100.0f))
                .padding(UiInsets.points(22.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN).gap(14.0f)
                .build(), "CampaignPanel");
        Panel heading = panel("transparent", UiStyle.builder()
                .width(UiLength.percent(100.0f)).height(UiLength.points(38.0f))
                .flexDirection(UiStyle.FlexDirection.ROW)
                .alignItems(UiStyle.AlignItems.CENTER)
                .justifyContent(UiStyle.JustifyContent.SPACE_BETWEEN).build(),
                "CampaignHeading");
        heading.add(label("ACTIVE EXPEDITION", "eyebrow", 22.0f));
        Panel chip = panel("chip", UiStyle.builder()
                .width(UiLength.points(126.0f)).height(UiLength.points(28.0f))
                .alignItems(UiStyle.AlignItems.CENTER)
                .justifyContent(UiStyle.JustifyContent.CENTER).build(), "DifficultyChip");
        Label chipText = label("HARD // +35%", "mono", 20.0f);
        chipText.alignment(Label.Alignment.CENTER);
        chip.add(chipText);
        heading.add(chip);

        Panel center = panel("transparent", UiStyle.builder()
                .width(UiLength.percent(100.0f)).flexGrow(1.0f)
                .flexDirection(UiStyle.FlexDirection.ROW).gap(16.0f).build(),
                "CampaignCenter");
        Panel details = panel("transparent", UiStyle.builder()
                .width(UiLength.percent(44.0f)).height(UiLength.percent(100.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN).gap(7.0f).build(),
                "CampaignDetails");
        details.add(label("FROSTLINE", "screen-title", 36.0f));
        Label description = label("气象塔 07 已离线。抵达北部中继站，在风暴墙闭合前恢复核心链路。",
                "muted", 58.0f);
        description.wrap(Label.Wrap.WORD).maximumLines(3);
        details.add(description);
        details.add(label("MISSION PROGRESS", "eyebrow", 20.0f));
        Panel progress = panel("progress-track", UiStyle.builder()
                .width(UiLength.percent(100.0f)).height(UiLength.points(12.0f))
                .padding(UiInsets.points(2.0f)).build(), "MissionProgress");
        Panel fill = panel("progress-fill", UiStyle.builder()
                .width(UiLength.percent(64.0f)).height(UiLength.percent(100.0f)).build(),
                "MissionProgressFill");
        progress.add(fill);
        details.add(progress);
        details.add(label("64%  //  03 OBJECTIVES REMAIN", "mono", 20.0f));

        Panel art = panel("art-frame", UiStyle.builder()
                .flexGrow(1.0f).height(UiLength.percent(100.0f))
                .alignItems(UiStyle.AlignItems.CENTER)
                .justifyContent(UiStyle.JustifyContent.CENTER).build(), "CampaignArt");
        art.add(orb);
        center.add(details).add(art);

        Panel stats = panel("transparent", UiStyle.builder()
                .width(UiLength.percent(100.0f)).height(UiLength.points(84.0f))
                .flexDirection(UiStyle.FlexDirection.ROW).gap(10.0f).build(),
                "CampaignStats");
        stats.add(stat("TIME WINDOW", "04:17"))
                .add(stat("SQUAD", "03 / 04"))
                .add(stat("TEMPERATURE", "−38°C"));
        campaign.add(heading).add(center).add(stats);
        return campaign;
    }

    private static Panel orb() {
        Panel orb = panel("orb", UiStyle.builder()
                .width(UiLength.points(220.0f)).height(UiLength.points(220.0f))
                .alignItems(UiStyle.AlignItems.CENTER)
                .justifyContent(UiStyle.JustifyContent.CENTER).build(), "WeatherOrb");
        Panel core = panel("orb-core", UiStyle.builder()
                .width(UiLength.points(132.0f)).height(UiLength.points(132.0f))
                .alignItems(UiStyle.AlignItems.CENTER)
                .justifyContent(UiStyle.JustifyContent.CENTER)
                .flexDirection(UiStyle.FlexDirection.COLUMN).gap(1.0f).build(),
                "WeatherOrbCore");
        Label number = label("07", "orb-number", 42.0f);
        number.alignment(Label.Alignment.CENTER);
        Label state = label("OFFLINE", "eyebrow", 20.0f);
        state.alignment(Label.Alignment.CENTER);
        core.add(number).add(state);
        orb.add(core);
        return orb;
    }

    private static Panel createSettingsPanel() {
        Panel settings = panel("settings-panel", UiStyle.builder()
                .width(UiLength.percent(100.0f)).height(UiLength.percent(100.0f))
                .padding(UiInsets.points(24.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN).gap(12.0f).build(),
                "SettingsPanel");
        settings.add(label("SYSTEM SETTINGS", "screen-title", 40.0f));
        settings.add(label("调整本地显示、声音与界面动态。所有改动即时生效。",
                "muted", 34.0f));

        Toggle motion = new Toggle("FULL");
        motion.debugName("MotionToggle");
        motion.addStyleClass("settings-toggle");
        motion.style(UiStyle.builder().width(UiLength.points(128.0f))
                .height(UiLength.points(38.0f)).build());
        settings.add(settingRow("界面动态", "MOTION PROFILE", motion));

        Toggle fullscreen = new Toggle("BORDERLESS");
        fullscreen.debugName("FullscreenToggle");
        fullscreen.addStyleClass("settings-toggle");
        fullscreen.style(UiStyle.builder().width(UiLength.points(128.0f))
                .height(UiLength.points(38.0f)).build());
        settings.add(settingRow("显示模式", "DISPLAY MODE", fullscreen));

        Slider volume = new Slider(0.0, 100.0, 84.0).step(1.0);
        volume.debugName("VolumeSlider");
        volume.addStyleClass("settings-slider");
        volume.style(UiStyle.builder().width(UiLength.points(230.0f))
                .height(UiLength.points(38.0f)).build());
        settings.add(settingRow("主音量", "MASTER OUTPUT // 084%", volume));

        Slider interfaceScale = new Slider(80.0, 120.0, 100.0).step(5.0);
        interfaceScale.debugName("InterfaceScaleSlider");
        interfaceScale.addStyleClass("settings-slider");
        interfaceScale.style(UiStyle.builder().width(UiLength.points(230.0f))
                .height(UiLength.points(38.0f)).build());
        settings.add(settingRow("界面缩放", "INTERFACE SCALE // 100%", interfaceScale));

        Panel spacer = panel("transparent", UiStyle.builder().flexGrow(1.0f).build(),
                "SettingsSpacer");
        Button back = menuButton("返回战役   BACK TO CAMPAIGN", "primary-button", 48.0f);
        back.debugName("SettingsBack");
        settings.add(spacer).add(back);
        return settings;
    }

    private static Panel settingRow(String title, String detail, UiNode control) {
        Panel row = panel("settings-row", UiStyle.builder()
                .width(UiLength.percent(100.0f)).height(UiLength.points(66.0f))
                .padding(UiInsets.points(14.0f, 9.0f))
                .flexDirection(UiStyle.FlexDirection.ROW)
                .alignItems(UiStyle.AlignItems.CENTER)
                .justifyContent(UiStyle.JustifyContent.SPACE_BETWEEN)
                .gap(16.0f).build(), "SettingsRow");
        Panel names = panel("transparent", UiStyle.builder().flexGrow(1.0f)
                .height(UiLength.percent(100.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN).gap(1.0f).build(),
                "SettingsNames");
        names.add(label(title, "section-title", 26.0f))
                .add(label(detail, "dim", 18.0f));
        row.add(names).add(control);
        return row;
    }

    private static Panel stat(String name, String value) {
        Panel card = panel("stat-card", UiStyle.builder()
                .flexGrow(1.0f).height(UiLength.percent(100.0f))
                .padding(UiInsets.points(12.0f, 9.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN).gap(2.0f).build(),
                "CampaignStat");
        card.add(label(name, "dim", 18.0f)).add(label(value, "stat-value", 34.0f));
        return card;
    }

    private static UiAnimationSequence entrance(UiNode target, double offsetX, double offsetY) {
        return UiAnimationSequence.builder()
                .then(target, 0.56f, UiEasing.EASE_OUT_CUBIC,
                        UiPropertyTrack.numeric(UiPropertyTrack.Property.OPACITY, 0.0, 1.0),
                        UiPropertyTrack.numeric(UiPropertyTrack.Property.TRANSLATION_X,
                                offsetX, 0.0),
                        UiPropertyTrack.numeric(UiPropertyTrack.Property.TRANSLATION_Y,
                                offsetY, 0.0))
                .build();
    }

    private static UiEffectDefinition effect(UiEffectDefinition.Type type, long seed) {
        UiEffectDefinition.Builder builder = UiEffectDefinition.builder(type)
                .duration(type == UiEffectDefinition.Type.SHIMMER ? 1.8f : 0.72f)
                .particleLifetime(type == UiEffectDefinition.Type.SHIMMER ? 1.8f : 0.72f)
                .seed(seed)
                .clip(UiEffectDefinition.ClipPolicy.NODE_BOUNDS)
                .colors(ModernGameTheme.ACCENT,
                        ModernGameTheme.ACCENT.withAlpha(0.0f));
        if (type == UiEffectDefinition.Type.SPARK) builder.maximumParticles(28);
        return builder.build();
    }

    private static Button menuButton(String text, String styleClass, float height) {
        Button button = new Button(text);
        button.addStyleClass(styleClass);
        button.style(UiStyle.builder().width(UiLength.percent(100.0f))
                .height(UiLength.points(height)).padding(UiInsets.points(14.0f, 6.0f))
                .build());
        return button;
    }

    private static Label label(String text, String styleClass, float height) {
        Label label = new Label(text);
        label.addStyleClass(styleClass);
        label.style(UiStyle.builder().width(UiLength.AUTO)
                .height(UiLength.points(height)).build());
        return label;
    }

    private static Panel panel(String styleClass, UiStyle style, String debugName) {
        Panel panel = new Panel();
        panel.debugName(debugName);
        panel.addStyleClass(styleClass);
        panel.style(style);
        return panel;
    }

    private static List<UiNode> descendants(UiNode root) {
        List<UiNode> output = new ArrayList<>();
        append(root, output);
        return output;
    }

    private static void append(UiNode node, List<UiNode> output) {
        output.add(node);
        for (UiNode child : node.children()) append(child, output);
    }
}
