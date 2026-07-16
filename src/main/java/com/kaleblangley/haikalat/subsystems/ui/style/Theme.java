package com.kaleblangley.haikalat.subsystems.ui.style;

import java.util.Objects;

/** 不持有 GL/Yoga/font native 对象的不可变主题。 */
public record Theme(ThemeTokens tokens, boolean reducedMotion) {
    public Theme {
        Objects.requireNonNull(tokens, "tokens");
    }

    public static Theme dark() {
        return new Theme(new ThemeTokens(
                UiColor.fromSrgbHex(0x20252dff),
                UiColor.fromSrgbHex(0x2b3440ff),
                UiColor.fromSrgbHex(0x151a20ff),
                UiColor.fromSrgbHex(0x4c9affff),
                UiColor.fromSrgbHex(0xf2f5f7ff),
                UiColor.fromSrgbHex(0x858c95ff),
                UiColor.fromSrgbHex(0x4b5563ff),
                8.0f, 4.0f, 32.0f, 16.0f, "HaikalatSans"), false);
    }
}
