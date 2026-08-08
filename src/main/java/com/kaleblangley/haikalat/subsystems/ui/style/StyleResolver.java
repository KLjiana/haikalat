package com.kaleblangley.haikalat.subsystems.ui.style;

import com.kaleblangley.haikalat.subsystems.ui.text.TextEffect;

import java.util.Set;

/** 将 typed theme 与控件伪状态解析为视觉样式的替换边界。 */
@FunctionalInterface
public interface StyleResolver {
    enum PseudoState { HOVER, PRESSED, FOCUSED, DISABLED, CHECKED, SELECTED, INVALID }

    ComputedStyle resolve(String widgetType, Set<String> classes,
                          Set<PseudoState> states, ComputedStyle inherited);

    static StyleResolver defaults(Theme theme) {
        ThemeTokens token = theme.tokens();
        return (widgetType, classes, states, inherited) -> {
            boolean visualSurface = switch (widgetType) {
                case "Label", "Image" -> false;
                default -> true;
            };
            UiColor background = !visualSurface ? UiColor.TRANSPARENT
                    : states.contains(PseudoState.PRESSED) ? token.surfacePressed()
                    : states.contains(PseudoState.HOVER) ? token.surfaceHover() : token.surface();
            UiColor foreground = states.contains(PseudoState.DISABLED)
                    ? token.disabledText() : token.text();
            TextEffect textEffect = inherited != null ? inherited.textEffect() : TextEffect.none();
            return new ComputedStyle(background, foreground,
                    !visualSurface ? UiColor.TRANSPARENT
                            : states.contains(PseudoState.FOCUSED) ? token.accent() : token.border(),
                    visualSurface ? 1.0f : 0.0f, visualSurface ? token.radius() : 0.0f, 1.0f,
                    inherited == null ? token.fontSize() : inherited.fontSize(),
                    inherited == null ? token.fontFamily() : inherited.fontFamily(),
                    textEffect);
        };
    }
}
