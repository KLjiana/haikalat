package com.kaleblangley.haikalat.demo.ui;

import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiFrameStats;
import com.kaleblangley.haikalat.subsystems.ui.UiConfig;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.text.markdown.MarkdownDocument;
import com.kaleblangley.haikalat.subsystems.text.markdown.MarkdownParser;
import com.kaleblangley.haikalat.subsystems.ui.style.ComputedStyle;
import com.kaleblangley.haikalat.subsystems.ui.style.StyleResolver;
import com.kaleblangley.haikalat.subsystems.ui.style.Theme;
import com.kaleblangley.haikalat.subsystems.ui.style.ThemeTokens;
import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;
import com.kaleblangley.haikalat.subsystems.ui.style.UiInsets;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.text.TextEffect;
import com.kaleblangley.haikalat.subsystems.ui.text.TextEffectType;
import com.kaleblangley.haikalat.subsystems.ui.text.UiTextEngine;
import com.kaleblangley.haikalat.subsystems.ui.widget.Button;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.ui.widget.ScrollView;
import com.kaleblangley.haikalat.subsystems.ui.widget.Slider;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import static com.kaleblangley.haikalat.subsystems.text.markdown.MarkdownDocument.BlockKind;
import static com.kaleblangley.haikalat.subsystems.text.markdown.MarkdownDocument.InlineKind;

/**
 * A Markdown document paired with the retained UI text effects.
 *
 * <p>Parsing belongs to the shared text subsystem; this class only maps document
 * semantics to ordinary UI nodes and existing text effects.</p>
 */
public final class MarkdownTextDemo {
    private static final MarkdownParser MARKDOWN_PARSER = new MarkdownParser();
    private static final float EFFECT_FONT_SIZE_MINIMUM = 18.0f;
    private static final float EFFECT_FONT_SIZE_MAXIMUM = 40.0f;
    private static final float EFFECT_FONT_SIZE_DEFAULT = 28.0f;

    public static final String MARKDOWN_SOURCE = String.join("\n",
            "# Markdown, rendered",
            "",
            "A small block parser can still produce a useful preview.",
            "It keeps **strong** and `code` runs visible beside the UI tree.",
            "",
            "## Blocks become visual roles",
            "",
            "> Readable defaults matter more than a large parser surface.",
            "",
            "- headings use a moving gradient",
            "- quotes use a restrained drop shadow",
            "- code uses a monospace glow",
            "",
            "### Code stays easy to inspect",
            "",
            "```java",
            "Label title = new Label(\"Gradient\");",
            "title.gradient(ACCENT, MAGENTA, 90.0f);",
            "```");

    private static final UiColor INK = color(0x081018ff);
    private static final UiColor SCREEN = color(0x0b141eff);
    private static final UiColor SURFACE = color(0x122130f5);
    private static final UiColor SURFACE_HIGH = color(0x182d40f5);
    private static final UiColor SURFACE_HOVER = color(0x21455aff);
    private static final UiColor BORDER = color(0x2d4c61e0);
    private static final UiColor BORDER_SOFT = color(0x203b4db8);
    private static final UiColor TEXT = color(0xf1f7f8ff);
    private static final UiColor MUTED = color(0x9bb0bcff);
    private static final UiColor DIM = color(0x6d8490ff);
    private static final UiColor CYAN = color(0x57e5d6ff);
    private static final UiColor BLUE = color(0x6fa9ffff);
    private static final UiColor MAGENTA = color(0xf178c5ff);
    private static final UiColor SHADOW = color(0x627ed6cc);
    private static final UiColor GOLD = color(0xf4c976ff);
    private static final UiColor GREEN = color(0x73e6a8ff);

    private final UiSystem ui;
    private final Panel root;
    private final Label animatedTitle;
    private final Label status;
    private final Button fontButton;
    private final Label effectSizeValue;
    private final Slider effectSizeSlider;
    private final List<Label> effectLabels;
    private final List<String> fontFamilies;
    private int fontIndex;
    private float gradientAngle = 18.0f;

    private MarkdownTextDemo(UiSystem ui, Panel root, Label animatedTitle,
                             Label status, Button fontButton, Label effectSizeValue,
                             Slider effectSizeSlider, List<Label> effectLabels) {
        this.ui = ui;
        this.root = root;
        this.animatedTitle = animatedTitle;
        this.status = status;
        this.fontButton = fontButton;
        this.effectSizeValue = effectSizeValue;
        this.effectSizeSlider = effectSizeSlider;
        this.effectLabels = List.copyOf(effectLabels);
        this.fontFamilies = List.copyOf(ui.fontFamilies());
        this.fontIndex = Math.max(0, this.fontFamilies.indexOf(ui.activeFontFamily()));
    }

    /** Creates and installs the demo scene into an existing UI document. */
    public static MarkdownTextDemo install(UiSystem ui) {
        Objects.requireNonNull(ui, "ui");
        UiDocument document = ui.document();
        Panel root = document.root();
        root.addStyleClass("markdown-root");
        root.style(UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.percent(100.0f))
                .padding(UiInsets.points(22.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN)
                .gap(12.0f)
                .build());

        Panel frame = panel("frame", UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.percent(100.0f))
                .padding(UiInsets.points(18.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN)
                .gap(12.0f)
                .build(), "MarkdownFrame");

        Panel header = panel("header", UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.points(54.0f))
                .flexShrink(0.0f)
                .padding(UiInsets.points(4.0f, 0.0f))
                .flexDirection(UiStyle.FlexDirection.ROW)
                .alignItems(UiStyle.AlignItems.CENTER)
                .gap(14.0f)
                .build(), "MarkdownHeader");
        Label animatedTitle = label("MARKDOWN / TEXT EFFECTS", "title", 44.0f, "MarkdownTitle");
        animatedTitle.gradient(CYAN, MAGENTA, 18.0f);
        animatedTitle.style(UiStyle.builder().width(UiLength.points(430.0f))
                .height(UiLength.points(44.0f)).flexShrink(0.0f).build());
        header.add(animatedTitle);
        Label subtitle = label("demo-local blocks | retained labels | live glyph atlas", "subtitle",
                22.0f, "MarkdownSubtitle");
        subtitle.style(UiStyle.builder().width(UiLength.AUTO).flexGrow(1.0f)
                .height(UiLength.points(22.0f)).build());
        header.add(subtitle);
        Button fontButton = new Button("CYCLE FONT");
        fontButton.addStyleClass("control");
        fontButton.debugName("CycleFont");
        fontButton.style(UiStyle.builder().width(UiLength.points(128.0f))
                .height(UiLength.points(34.0f)).flexShrink(0.0f).build());
        header.add(fontButton);

        Panel body = panel("body", UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .flexGrow(1.0f)
                .minHeight(UiLength.points(300.0f))
                .flexDirection(UiStyle.FlexDirection.ROW)
                .alignItems(UiStyle.AlignItems.STRETCH)
                .gap(12.0f)
                .build(), "MarkdownBody");
        EffectGallery effects = effectGallery();
        body.add(sourcePane()).add(previewPane(effects.panel()));

        Label status = label("FONT " + ui.activeFontFamily() + " | waiting for first frame",
                "footer", 20.0f, "MarkdownStatus");
        status.style(UiStyle.builder().width(UiLength.percent(100.0f))
                .height(UiLength.points(20.0f)).flexShrink(0.0f).build());

        frame.add(header).add(body).add(status);
        root.add(frame);

        MarkdownTextDemo scene = new MarkdownTextDemo(ui, root, animatedTitle, status, fontButton,
                effects.sizeValue(), effects.sizeSlider(), effects.labels());
        fontButton.onClick(scene::cycleFont);
        effects.sizeSlider().onValueChanged(scene::setEffectFontSize);
        return scene;
    }

    /** Advances the animated gradient and refreshes the compact runtime status line. */
    public void update(float deltaSeconds, UiFrameStats statistics) {
        Objects.requireNonNull(statistics, "statistics");
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
        gradientAngle = (gradientAngle + deltaSeconds * 42.0f) % 360.0f;
        animatedTitle.gradient(CYAN, MAGENTA, gradientAngle);
        String next = String.format(Locale.ROOT, "FONT %s | nodes %d | glyphs %d | quads %d",
                ui.activeFontFamily(), statistics.visibleNodes(), statistics.glyphs(), statistics.quads());
        if (!status.text().equals(next)) status.text(next);
    }

    void verifyDefaultLayout() {
        UiNode previewScroll = requireNode("MarkdownPreviewScroll");
        UiNode previewContent = requireNode("MarkdownPreviewContent");
        UiNode effects = requireNode("TextEffectGallery");
        UiNode sizeValue = requireNode("EffectFontSizeValue");
        UiNode sizeSlider = requireNode("EffectFontSizeSlider");
        UiNode lastBlock = previewContent.children().get(previewContent.children().size() - 1);
        float epsilon = 0.5f;
        if (lastBlock.layoutBox().bottom() > previewContent.layoutBox().bottom() + epsilon) {
            throw new IllegalStateException("Markdown preview content height clips its final block");
        }
        if (previewContent.layoutBox().bottom() > previewScroll.layoutBox().bottom() + epsilon) {
            throw new IllegalStateException("Default viewport requires scrolling the Markdown preview");
        }
        if (previewScroll.layoutBox().bottom() > effects.layoutBox().y() + epsilon) {
            throw new IllegalStateException("Text effects overlap the Markdown preview viewport");
        }
        if (sizeValue.layoutBox().right() > sizeSlider.layoutBox().x() + epsilon
                || sizeSlider.layoutBox().width() <= 0.0f
                || Math.abs(effectSizeSlider.value() - EFFECT_FONT_SIZE_DEFAULT) > epsilon) {
            throw new IllegalStateException("Text effect font-size control has an invalid default layout");
        }
        for (Label effectLabel : effectLabels) {
            if (Math.abs(effectLabel.computedStyle().fontSize()
                    - EFFECT_FONT_SIZE_DEFAULT) > epsilon
                    || effectLabel.layoutBox().height()
                    < EFFECT_FONT_SIZE_DEFAULT * 1.2f - epsilon) {
                throw new IllegalStateException("Text effect sample is clipped at the default font size");
            }
        }
        Label strong = (Label) requireNode("MarkdownInline-strong");
        Label code = (Label) requireNode("MarkdownInline-code");
        for (Label inline : List.of(strong, code)) {
            if (inline.layoutBox().width() <= 0.0f || inline.layoutBox().height() <= 0.0f) {
                throw new IllegalStateException("Styled Markdown span has no visible layout area");
            }
            if (inline.layoutBox().y() < previewScroll.layoutBox().y() - epsilon
                    || inline.layoutBox().bottom() > previewScroll.layoutBox().bottom() + epsilon) {
                throw new IllegalStateException("Styled Markdown span is outside the default viewport");
            }
        }
        if (strong.textEffect().type() != TextEffectType.OUTLINE) {
            throw new IllegalStateException("Strong Markdown span has no visible emphasis");
        }
        if (!UiTextEngine.MONOSPACE_FONT_FAMILY.equals(code.computedStyle().fontFamily())
                || code.textEffect().type() != TextEffectType.GLOW) {
            throw new IllegalStateException("Inline Markdown code is not rendered as monospace code");
        }
        Label innerGlow = (Label) requireNode("EffectValue-inner");
        if (innerGlow.textEffect().type() != TextEffectType.INNER_GLOW) {
            throw new IllegalStateException("Font switching removed the inner-glow effect");
        }
    }

    private UiNode requireNode(String debugName) {
        UiNode found = findNode(root, debugName);
        if (found == null) throw new IllegalStateException("Missing demo node " + debugName);
        return found;
    }

    private static UiNode findNode(UiNode node, String debugName) {
        if (debugName.equals(node.debugName())) return node;
        for (UiNode child : node.children()) {
            UiNode found = findNode(child, debugName);
            if (found != null) return found;
        }
        return null;
    }

    public Panel root() {
        return root;
    }

    /** Cycles the bundled primary font so the same tree can be inspected with each face. */
    public void cycleFont() {
        if (fontFamilies.size() < 2) return;
        fontIndex = (fontIndex + 1) % fontFamilies.size();
        ui.selectFontFamily(fontFamilies.get(fontIndex));
        fontButton.text("FONT: " + fontFamilies.get(fontIndex));
    }

    void applyDeterministicScript(int frame) {
        if (frame == 8 || frame == 16) cycleFont();
    }

    private void setEffectFontSize(double requestedSize) {
        float fontSize = (float) requestedSize;
        effectSizeValue.text(String.format(Locale.ROOT, "SIZE %.0f", requestedSize));
        for (Label label : effectLabels) {
            ComputedStyle style = label.computedStyle();
            label.computedStyle(computed(style.background(), style.foreground(),
                    style.borderColor(), style.borderWidth(), style.radius(), style.opacity(),
                    fontSize, style.fontFamily(), style.textEffect()));
        }
    }

    /** Parses CommonMark through the shared text subsystem. */
    public static List<MarkdownDocument.Block> parse(String markdown) {
        return MARKDOWN_PARSER.parse(markdown).blocks();
    }

    /** Configures the standalone palette and keeps all styling in the demo layer. */
    public static UiConfig config() {
        ThemeTokens tokens = new ThemeTokens(SURFACE, SURFACE_HOVER, color(0x0e1a27ff),
                CYAN, TEXT, DIM, BORDER, 8.0f, 7.0f, 34.0f, 15.0f,
                UiTextEngine.DEFAULT_FONT_FAMILY);
        Theme theme = new Theme(tokens, false);
        StyleResolver fallback = StyleResolver.defaults(theme);
        StyleResolver resolver = (widget, classes, states, inherited) -> {
            ComputedStyle base = fallback.resolve(widget, classes, states, inherited);
            float size = inherited == null ? base.fontSize() : inherited.fontSize();
            String family = inherited == null ? base.fontFamily() : inherited.fontFamily();
            UiColor foreground = inherited == null ? TEXT : inherited.foreground();

            if (widget.equals("Label")) {
                if (has(classes, "title")) { foreground = TEXT; size = 31.0f; }
                else if (has(classes, "subtitle")) {
                    foreground = MUTED; size = 12.0f; family = UiTextEngine.MONOSPACE_FONT_FAMILY;
                } else if (has(classes, "section-title")) {
                    foreground = CYAN; size = 12.0f; family = UiTextEngine.MONOSPACE_FONT_FAMILY;
                } else if (has(classes, "source-line")) {
                    foreground = MUTED; size = 12.0f; family = UiTextEngine.MONOSPACE_FONT_FAMILY;
                } else if (has(classes, "source-heading")) {
                    foreground = GOLD; size = 12.0f; family = UiTextEngine.MONOSPACE_FONT_FAMILY;
                } else if (has(classes, "markdown-h1")) { foreground = TEXT; size = 27.0f; }
                else if (has(classes, "markdown-h2")) { foreground = BLUE; size = 20.0f; }
                else if (has(classes, "markdown-h3")) { foreground = GOLD; size = 16.0f; }
                else if (has(classes, "markdown-body")) { foreground = TEXT; size = 15.0f; }
                else if (has(classes, "markdown-strong")) { foreground = GOLD; size = 15.0f; }
                else if (has(classes, "markdown-emphasis")) { foreground = CYAN; size = 15.0f; }
                else if (has(classes, "markdown-inline-code")) {
                    foreground = GREEN; size = 13.0f; family = UiTextEngine.MONOSPACE_FONT_FAMILY;
                }
                else if (has(classes, "markdown-quote")) { foreground = MUTED; size = 14.0f; }
                else if (has(classes, "markdown-list")) { foreground = TEXT; size = 14.0f; }
                else if (has(classes, "markdown-code")) {
                    foreground = GREEN; size = 13.0f; family = UiTextEngine.MONOSPACE_FONT_FAMILY;
                } else if (has(classes, "effect-label")) {
                    foreground = TEXT; size = EFFECT_FONT_SIZE_DEFAULT;
                }
                else if (has(classes, "effect-caption")) {
                    foreground = DIM; size = 11.0f; family = UiTextEngine.MONOSPACE_FONT_FAMILY;
                } else if (has(classes, "effect-size-value")) {
                    foreground = CYAN; size = 11.0f; family = UiTextEngine.MONOSPACE_FONT_FAMILY;
                } else if (has(classes, "footer")) {
                    foreground = DIM; size = 11.0f; family = UiTextEngine.MONOSPACE_FONT_FAMILY;
                }
                UiColor background = has(classes, "markdown-inline-code")
                        ? color(0x173d30dd) : UiColor.TRANSPARENT;
                UiColor border = has(classes, "markdown-inline-code") ? GREEN : UiColor.TRANSPARENT;
                float borderWidth = has(classes, "markdown-inline-code") ? 1.0f : 0.0f;
                float radius = has(classes, "markdown-inline-code") ? 3.0f : 0.0f;
                return computed(background, foreground, border,
                        borderWidth, radius, 1.0f, size, family, base.textEffect());
            }
            if (widget.equals("Button")) {
                UiColor background = states.contains(StyleResolver.PseudoState.PRESSED)
                        ? color(0x193c4aff)
                        : states.contains(StyleResolver.PseudoState.HOVER) ? SURFACE_HOVER : SURFACE_HIGH;
                UiColor border = states.contains(StyleResolver.PseudoState.FOCUSED) ? CYAN : BORDER;
                return computed(background, CYAN, border, 1.0f, 5.0f, 1.0f, 11.0f,
                        UiTextEngine.MONOSPACE_FONT_FAMILY, base.textEffect());
            }
            if (has(classes, "markdown-root")) {
                return computed(UiColor.TRANSPARENT, TEXT, UiColor.TRANSPARENT,
                        0.0f, 0.0f, 1.0f, size, family, base.textEffect());
            }
            if (has(classes, "frame")) {
                return computed(SCREEN, TEXT, BORDER, 1.0f, 8.0f,
                        1.0f, size, family, base.textEffect());
            }
            if (has(classes, "surface") || has(classes, "source-surface")
                    || has(classes, "preview-surface")) {
                return computed(SURFACE, TEXT, BORDER_SOFT, 1.0f, 6.0f,
                        1.0f, size, family, base.textEffect());
            }
            if (has(classes, "effects-surface") || has(classes, "effect-cell")) {
                return computed(SURFACE_HIGH, TEXT, BORDER_SOFT, 1.0f, 5.0f,
                        1.0f, size, family, base.textEffect());
            }
            if (has(classes, "transparent")) {
                return computed(UiColor.TRANSPARENT, foreground, UiColor.TRANSPARENT,
                        0.0f, 0.0f, 1.0f, size, family, base.textEffect());
            }
            return base;
        };
        return UiConfig.builder()
                .theme(theme).styleResolver(resolver)
                .primitiveCapacity(1_024, 16_384)
                .glyphAtlas(1_024, 1_024, 4)
                .build();
    }

    private static Panel sourcePane() {
        Panel pane = panel("source-surface", UiStyle.builder()
                .width(UiLength.percent(42.0f)).height(UiLength.percent(100.0f))
                .padding(UiInsets.points(12.0f)).flexDirection(UiStyle.FlexDirection.COLUMN)
                .gap(8.0f).build(), "MarkdownSourcePane");
        pane.add(section("MARKDOWN SOURCE", "MarkdownSourceHeading"));
        ScrollView scroll = new ScrollView();
        scroll.addStyleClass("transparent");
        scroll.debugName("MarkdownSourceScroll");
        scroll.style(UiStyle.builder().width(UiLength.percent(100.0f)).height(UiLength.points(0.0f))
                .minHeight(UiLength.points(0.0f)).flexGrow(1.0f)
                .overflow(UiStyle.Overflow.SCROLL).build());
        Panel content = panel("transparent", UiStyle.builder()
                .width(UiLength.percent(100.0f)).height(UiLength.points(390.0f))
                .padding(UiInsets.points(4.0f, 2.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN).gap(1.0f).build(),
                "MarkdownSourceContent");
        String[] lines = MARKDOWN_SOURCE.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isEmpty()) {
                content.add(spacer(8.0f, "MarkdownSourceSpacer"));
            } else {
                String style = line.trim().startsWith("#") ? "source-heading" : "source-line";
                Label sourceLine = label(String.format(Locale.ROOT, "%02d  %s", index + 1, line),
                        style, 18.0f, "MarkdownSourceLine" + index);
                content.add(sourceLine);
            }
        }
        scroll.content(content);
        pane.add(scroll);
        return pane;
    }

    private static Panel previewPane(Panel effects) {
        Panel pane = panel("preview-surface", UiStyle.builder()
                .flexGrow(1.0f).height(UiLength.percent(100.0f))
                .padding(UiInsets.points(12.0f)).flexDirection(UiStyle.FlexDirection.COLUMN)
                .gap(8.0f).build(), "MarkdownPreviewPane");
        pane.add(section("RENDERED PREVIEW", "MarkdownPreviewHeading"));
        ScrollView scroll = new ScrollView();
        scroll.addStyleClass("transparent");
        scroll.debugName("MarkdownPreviewScroll");
        scroll.style(UiStyle.builder().width(UiLength.percent(100.0f)).height(UiLength.points(0.0f))
                .minHeight(UiLength.points(0.0f)).flexGrow(1.0f)
                .overflow(UiStyle.Overflow.SCROLL).build());
        Panel content = panel("transparent", UiStyle.builder()
                .width(UiLength.percent(100.0f)).height(UiLength.points(372.0f))
                .padding(UiInsets.points(2.0f, 0.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN).gap(2.0f).build(),
                "MarkdownPreviewContent");
        for (MarkdownDocument.Block block : parse(MARKDOWN_SOURCE)) {
            addPreviewBlock(content, block);
        }
        scroll.content(content);
        pane.add(scroll);
        pane.add(effects);
        return pane;
    }

    private static void addPreviewBlock(Panel content, MarkdownDocument.Block block) {
        switch (block.kind()) {
            case SPACER -> content.add(spacer(5.0f, "MarkdownPreviewSpacer"));
            case HEADING_1 -> content.add(label(block.text(), "markdown-h1", 35.0f,
                    "MarkdownHeading1").gradient(CYAN, MAGENTA, 18.0f));
            case HEADING_2 -> content.add(label(block.text(), "markdown-h2", 27.0f,
                    "MarkdownHeading2").gradient(BLUE, CYAN, 90.0f));
            case HEADING_3 -> content.add(label(block.text(), "markdown-h3", 23.0f,
                    "MarkdownHeading3").outline(SURFACE_HIGH, 1.0f));
            case PARAGRAPH -> addInlineAwareBlock(content, block, "", "markdown-body",
                    27.0f, "MarkdownParagraph");
            case QUOTE -> addInlineAwareBlock(content, block, "| ", "markdown-quote",
                    23.0f, "MarkdownQuote");
            case LIST -> addInlineAwareBlock(content, block, "- ", "markdown-list",
                    22.0f, "MarkdownList");
            case CODE -> content.add(label(block.text(), "markdown-code", 21.0f,
                    "MarkdownCode").glow(GREEN, 4.0f));
        }
    }

    private static void addInlineAwareBlock(Panel content, MarkdownDocument.Block block,
                                            String prefix, String baseStyle,
                                            float height, String debugName) {
        boolean styled = block.inlines().stream()
                .anyMatch(run -> run.kind() != InlineKind.TEXT);
        if (!styled) {
            Label plain = label(prefix + block.text(), baseStyle, height, debugName);
            plain.wrap(Label.Wrap.WORD).maximumLines(3);
            if (block.kind() == BlockKind.QUOTE) {
                plain.dropShadow(INK, 2.0f, 2.0f, 3.0f);
            } else if (block.kind() == BlockKind.LIST) {
                plain.innerGlow(CYAN);
            }
            content.add(plain);
            return;
        }

        Panel line = panel("transparent", UiStyle.builder()
                .width(UiLength.percent(100.0f)).height(UiLength.points(height))
                .flexShrink(0.0f).flexDirection(UiStyle.FlexDirection.ROW)
                .alignItems(UiStyle.AlignItems.CENTER).build(), debugName);
        if (!prefix.isEmpty()) line.add(inlineLabel(prefix, baseStyle, height, debugName + "Prefix"));
        int index = 0;
        for (MarkdownDocument.Inline run : block.inlines()) {
            String styleClass = switch (run.kind()) {
                case TEXT -> baseStyle;
                case STRONG -> "markdown-strong";
                case EMPHASIS -> "markdown-emphasis";
                case CODE -> "markdown-inline-code";
            };
            String runName = switch (run.kind()) {
                case STRONG -> "MarkdownInline-strong";
                case CODE -> "MarkdownInline-code";
                default -> debugName + "Run" + index;
            };
            Label segment = inlineLabel(run.text(), styleClass, height, runName);
            if (run.kind() == InlineKind.STRONG) segment.outline(GOLD, 1.0f);
            if (run.kind() == InlineKind.CODE) segment.glow(GREEN, 2.2f);
            line.add(segment);
            index++;
        }
        content.add(line);
    }

    private static Label inlineLabel(String text, String styleClass,
                                     float height, String debugName) {
        Label label = new Label(text);
        label.addStyleClass(styleClass);
        label.debugName(debugName);
        label.style(UiStyle.builder().width(UiLength.AUTO)
                .height(UiLength.points(height)).flexShrink(0.0f).build());
        return label;
    }

    private static EffectGallery effectGallery() {
        Panel gallery = panel("effects-surface", UiStyle.builder()
                .width(UiLength.percent(100.0f)).height(UiLength.points(140.0f))
                .flexShrink(0.0f).padding(UiInsets.points(10.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN).gap(5.0f).build(), "TextEffectGallery");
        Panel header = panel("transparent", UiStyle.builder()
                .width(UiLength.percent(100.0f)).height(UiLength.points(22.0f))
                .flexShrink(0.0f).flexDirection(UiStyle.FlexDirection.ROW)
                .alignItems(UiStyle.AlignItems.CENTER).gap(8.0f).build(), "TextEffectHeader");
        Label heading = section("TEXT EFFECTS", "TextEffectHeading");
        heading.style(UiStyle.builder().width(UiLength.AUTO).height(UiLength.points(18.0f))
                .flexGrow(1.0f).build());
        Label sizeValue = label("SIZE 28", "effect-size-value",
                18.0f, "EffectFontSizeValue");
        sizeValue.alignment(Label.Alignment.END);
        sizeValue.style(UiStyle.builder().width(UiLength.points(66.0f))
                .height(UiLength.points(18.0f)).flexShrink(0.0f).build());
        Slider sizeSlider = new Slider(EFFECT_FONT_SIZE_MINIMUM,
                EFFECT_FONT_SIZE_MAXIMUM, EFFECT_FONT_SIZE_DEFAULT).step(1.0);
        sizeSlider.addStyleClass("effect-size-slider");
        sizeSlider.debugName("EffectFontSizeSlider");
        sizeSlider.style(UiStyle.builder().width(UiLength.points(146.0f))
                .height(UiLength.points(22.0f)).flexShrink(0.0f).build());
        header.add(heading).add(sizeValue).add(sizeSlider);
        gallery.add(header);
        Panel row = panel("transparent", UiStyle.builder()
                .width(UiLength.percent(100.0f)).flexGrow(1.0f)
                .flexDirection(UiStyle.FlexDirection.ROW).gap(7.0f).build(), "TextEffectRow");
        EffectCell plain = effectCell("plain", "Plain text", TextEffect.none());
        EffectCell gradient = effectCell("gradient", "Gradient",
                TextEffect.gradient(BLUE, MAGENTA, 45.0f));
        EffectCell outline = effectCell("outline", "Outline", TextEffect.outline(CYAN, 1.4f));
        EffectCell shadow = effectCell("shadow", "Drop shadow",
                TextEffect.dropShadow(SHADOW, 2.0f, 2.0f, 3.0f));
        EffectCell glow = effectCell("glow", "Glow", TextEffect.glow(GREEN, 4.0f));
        EffectCell inner = effectCell("inner", "Inner glow", TextEffect.innerGlow(GOLD));
        List<EffectCell> cells = List.of(plain, gradient, outline, shadow, glow, inner);
        for (EffectCell cell : cells) row.add(cell.panel());
        gallery.add(row);
        return new EffectGallery(gallery, cells.stream().map(EffectCell::label).toList(),
                sizeValue, sizeSlider);
    }

    private static EffectCell effectCell(String name, String caption, TextEffect effect) {
        Panel cell = panel("effect-cell", UiStyle.builder().flexGrow(1.0f).height(UiLength.points(92.0f))
                .padding(UiInsets.points(6.0f)).flexDirection(UiStyle.FlexDirection.COLUMN)
                .justifyContent(UiStyle.JustifyContent.CENTER).gap(3.0f).build(), "EffectCell-" + name);
        Label value = label("Aa", "effect-label", 50.0f, "EffectValue-" + name);
        value.alignment(Label.Alignment.CENTER);
        value.textEffect(effect);
        Label title = label(caption, "effect-caption", 16.0f, "EffectCaption-" + name);
        title.alignment(Label.Alignment.CENTER);
        cell.add(value).add(title);
        return new EffectCell(cell, value);
    }

    private record EffectGallery(Panel panel, List<Label> labels,
                                 Label sizeValue, Slider sizeSlider) {
    }

    private record EffectCell(Panel panel, Label label) {
    }

    private static Label section(String text, String debugName) {
        return label(text, "section-title", 18.0f, debugName);
    }

    private static Label label(String text, String styleClass, float height, String debugName) {
        Label label = new Label(text);
        label.addStyleClass(styleClass);
        label.debugName(debugName);
        label.style(UiStyle.builder().width(UiLength.percent(100.0f))
                .height(UiLength.points(height)).flexShrink(0.0f).build());
        return label;
    }

    private static Panel spacer(float height, String debugName) {
        return panel("transparent", UiStyle.builder()
                .width(UiLength.percent(100.0f)).height(UiLength.points(height))
                .flexShrink(0.0f).build(), debugName);
    }

    private static Panel panel(String styleClass, UiStyle style, String debugName) {
        Panel panel = new Panel();
        panel.addStyleClass(styleClass);
        panel.debugName(debugName);
        panel.style(style);
        return panel;
    }

    private static ComputedStyle computed(UiColor background, UiColor foreground,
                                          UiColor border, float borderWidth, float radius,
                                          float opacity, float fontSize, String family,
                                          TextEffect effect) {
        return new ComputedStyle(background, foreground, border, borderWidth, radius,
                opacity, fontSize, family, effect);
    }

    private static boolean has(Set<String> classes, String value) {
        return classes.contains(value);
    }

    private static UiColor color(int rgba) {
        return UiColor.fromSrgbHex(rgba);
    }
}
