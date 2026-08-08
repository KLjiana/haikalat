package com.kaleblangley.haikalat.demo.ui;

import com.kaleblangley.haikalat.subsystems.ui.UiConfig;
import com.kaleblangley.haikalat.subsystems.ui.style.ComputedStyle;
import com.kaleblangley.haikalat.subsystems.ui.style.StyleResolver;
import com.kaleblangley.haikalat.subsystems.ui.style.Theme;
import com.kaleblangley.haikalat.subsystems.ui.style.ThemeTokens;
import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;
import com.kaleblangley.haikalat.subsystems.ui.text.TextEffect;
import com.kaleblangley.haikalat.subsystems.ui.text.UiTextEngine;

import java.util.Set;

/** Visual language for the standalone modern game main-menu scene. */
final class ModernGameTheme {
    static final UiColor ACCENT = color(0x55e6d8ff);
    static final UiColor ACCENT_HOVER = color(0x88fff4ff);
    static final UiColor TEXT = color(0xf2f7f7ff);
    static final UiColor TEXT_MUTED = color(0x91a4adff);
    static final UiColor TEXT_DIM = color(0x647680ff);

    private static final UiColor INK = color(0x061114ff);
    private static final UiColor SCREEN = color(0x071019f2);
    private static final UiColor SURFACE = color(0x0d1a27e8);
    private static final UiColor SURFACE_HIGH = color(0x132536f2);
    private static final UiColor SURFACE_HOVER = color(0x19364bfa);
    private static final UiColor SURFACE_PRESSED = color(0x0a1722ff);
    private static final UiColor BORDER = color(0x294355d9);
    private static final UiColor BORDER_SOFT = color(0x1c3442a8);
    private static final UiColor DANGER = color(0xff6f76ff);

    private ModernGameTheme() {
    }

    static UiConfig config(boolean sdf) {
        Theme theme = new Theme(new ThemeTokens(SURFACE, SURFACE_HOVER, SURFACE_PRESSED,
                ACCENT, TEXT, TEXT_DIM, BORDER, 8.0f, sdf ? 18.0f : 0.0f,
                44.0f, 15.0f, UiTextEngine.DEFAULT_FONT_FAMILY), false);
        return UiConfig.builder()
                .theme(theme)
                .styleResolver(resolver(theme, sdf))
                .primitiveCapacity(1_024, 16_384)
                .glyphAtlas(1_024, 1_024, 4)
                .build();
    }

    private static StyleResolver resolver(Theme theme, boolean sdf) {
        StyleResolver fallback = StyleResolver.defaults(theme);
        float round = sdf ? 18.0f : 0.0f;
        return (widget, classes, states, inherited) -> {
            ComputedStyle base = fallback.resolve(widget, classes, states, inherited);
            float inheritedSize = inherited == null ? base.fontSize() : inherited.fontSize();
            String inheritedFamily = inherited == null
                    ? base.fontFamily() : inherited.fontFamily();
            UiColor inheritedForeground = inherited == null
                    ? TEXT : inherited.foreground();

            if (widget.equals("Label")) {
                UiColor foreground = inheritedForeground;
                float size = inheritedSize;
                String family = inheritedFamily;
                if (has(classes, "display-title")) {
                    foreground = TEXT;
                    size = 46.0f;
                } else if (has(classes, "screen-title")) {
                    foreground = TEXT;
                    size = 27.0f;
                } else if (has(classes, "section-title")) {
                    foreground = TEXT;
                    size = 21.0f;
                } else if (has(classes, "eyebrow")) {
                    foreground = ACCENT;
                    size = 12.0f;
                    family = UiTextEngine.MONOSPACE_FONT_FAMILY;
                } else if (has(classes, "muted")) {
                    foreground = TEXT_MUTED;
                    size = 14.0f;
                } else if (has(classes, "dim")) {
                    foreground = TEXT_DIM;
                    size = 12.0f;
                } else if (has(classes, "mono")) {
                    size = 12.0f;
                    family = UiTextEngine.MONOSPACE_FONT_FAMILY;
                } else if (has(classes, "stat-value")) {
                    foreground = TEXT;
                    size = 25.0f;
                    family = UiTextEngine.MONOSPACE_FONT_FAMILY;
                } else if (has(classes, "orb-number")) {
                    foreground = TEXT;
                    size = 34.0f;
                    family = UiTextEngine.MONOSPACE_FONT_FAMILY;
                }
                return style(UiColor.TRANSPARENT, foreground, UiColor.TRANSPARENT,
                        0.0f, 0.0f, 1.0f, size, family);
            }

            if (has(classes, "primary-button")) {
                UiColor background = states.contains(StyleResolver.PseudoState.PRESSED)
                        ? color(0x2fb9b0ff)
                        : states.contains(StyleResolver.PseudoState.HOVER)
                        ? ACCENT_HOVER : ACCENT;
                UiColor border = states.contains(StyleResolver.PseudoState.FOCUSED)
                        ? TEXT : background;
                return style(background, INK, border, 1.0f, round * 0.85f,
                        enabledOpacity(states), 16.0f, UiTextEngine.MONOSPACE_FONT_FAMILY);
            }
            if (has(classes, "nav-button") || has(classes, "danger-button")) {
                boolean danger = has(classes, "danger-button");
                UiColor foreground = danger && states.contains(StyleResolver.PseudoState.HOVER)
                        ? DANGER : TEXT;
                UiColor background = states.contains(StyleResolver.PseudoState.PRESSED)
                        ? SURFACE_PRESSED
                        : states.contains(StyleResolver.PseudoState.HOVER)
                        ? SURFACE_HOVER : UiColor.TRANSPARENT;
                UiColor border = states.contains(StyleResolver.PseudoState.FOCUSED)
                        ? (danger ? DANGER : ACCENT) : BORDER_SOFT;
                return style(background, foreground, border,
                        states.contains(StyleResolver.PseudoState.FOCUSED) ? 1.5f : 1.0f,
                        round * 0.72f, enabledOpacity(states), 15.0f,
                        UiTextEngine.MONOSPACE_FONT_FAMILY);
            }
            if (has(classes, "settings-toggle")) {
                boolean checked = states.contains(StyleResolver.PseudoState.CHECKED);
                UiColor background = checked ? ACCENT
                        : states.contains(StyleResolver.PseudoState.HOVER)
                        ? SURFACE_HOVER : SURFACE;
                UiColor foreground = checked ? INK : TEXT;
                return style(background, foreground,
                        states.contains(StyleResolver.PseudoState.FOCUSED) ? ACCENT : BORDER,
                        1.0f, round * 0.72f, enabledOpacity(states), 12.0f,
                        UiTextEngine.MONOSPACE_FONT_FAMILY);
            }
            if (widget.equals("Slider") || has(classes, "settings-slider")) {
                return style(UiColor.TRANSPARENT, ACCENT, BORDER, 1.0f,
                        round * 0.5f, enabledOpacity(states), inheritedSize, inheritedFamily);
            }

            if (has(classes, "screen")) {
                return style(SCREEN, TEXT, BORDER_SOFT, 1.0f, round * 1.55f,
                        1.0f, inheritedSize, inheritedFamily);
            }
            if (has(classes, "top-bar")) {
                return style(color(0x0b1824e8), TEXT, BORDER_SOFT, 1.0f, round,
                        1.0f, inheritedSize, inheritedFamily);
            }
            if (has(classes, "brand-mark")) {
                return style(ACCENT, INK, ACCENT_HOVER, 1.0f, round * 0.65f,
                        1.0f, 20.0f, UiTextEngine.MONOSPACE_FONT_FAMILY);
            }
            if (has(classes, "chip")) {
                return style(color(0x12333acc), ACCENT, color(0x32706fe0),
                        1.0f, round, 1.0f, 11.0f,
                        UiTextEngine.MONOSPACE_FONT_FAMILY);
            }
            if (has(classes, "hero-card") || has(classes, "settings-panel")) {
                return style(SURFACE, TEXT, BORDER, 1.0f, round * 1.35f,
                        1.0f, inheritedSize, inheritedFamily);
            }
            if (has(classes, "art-frame")) {
                return style(color(0x081620f5), TEXT, color(0x1d4653e8),
                        1.0f, round * 1.15f, 1.0f, inheritedSize, inheritedFamily);
            }
            if (has(classes, "orb")) {
                return style(color(0x103e49e8), ACCENT, color(0x58e6dbff),
                        2.0f, sdf ? 120.0f : 0.0f, 1.0f, inheritedSize, inheritedFamily);
            }
            if (has(classes, "orb-core")) {
                return style(color(0x55e6d82b), TEXT, color(0xa2fff8e8),
                        1.5f, sdf ? 72.0f : 0.0f, 1.0f, inheritedSize, inheritedFamily);
            }
            if (has(classes, "stat-card") || has(classes, "settings-row")) {
                return style(color(0x102131d9), TEXT, BORDER_SOFT,
                        1.0f, round * 0.82f, 1.0f, inheritedSize, inheritedFamily);
            }
            if (has(classes, "progress-track")) {
                return style(color(0x07121cff), TEXT, BORDER_SOFT,
                        1.0f, sdf ? 6.0f : 0.0f, 1.0f, inheritedSize, inheritedFamily);
            }
            if (has(classes, "progress-fill") || has(classes, "accent-line")) {
                return style(ACCENT, INK, ACCENT, 0.0f,
                        sdf ? 6.0f : 0.0f, 1.0f, inheritedSize, inheritedFamily);
            }
            if (has(classes, "profile-card")) {
                return style(SURFACE_HIGH, TEXT, BORDER, 1.0f, round * 0.8f,
                        1.0f, 13.0f, inheritedFamily);
            }
            if (has(classes, "footer")) {
                return style(UiColor.TRANSPARENT, TEXT_MUTED, UiColor.TRANSPARENT,
                        0.0f, 0.0f, 1.0f, 12.0f,
                        UiTextEngine.MONOSPACE_FONT_FAMILY);
            }
            if (has(classes, "transparent")) {
                return style(UiColor.TRANSPARENT, inheritedForeground, UiColor.TRANSPARENT,
                        0.0f, 0.0f, 1.0f, inheritedSize, inheritedFamily);
            }
            return base;
        };
    }

    private static float enabledOpacity(Set<StyleResolver.PseudoState> states) {
        return states.contains(StyleResolver.PseudoState.DISABLED) ? 0.45f : 1.0f;
    }

    private static boolean has(Set<String> classes, String value) {
        return classes.contains(value);
    }

    private static ComputedStyle style(UiColor background, UiColor foreground,
                                       UiColor border, float borderWidth, float radius,
                                       float opacity, float fontSize, String fontFamily) {
        return new ComputedStyle(background, foreground, border, borderWidth, radius,
                opacity, fontSize, fontFamily, TextEffect.none());
    }

    private static UiColor color(int rgba) {
        return UiColor.fromSrgbHex(rgba);
    }
}
