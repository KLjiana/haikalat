package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;

/** Fill or border paint for an SDF primitive. */
public record UiSdfPaint(UiColor color) {
    public UiSdfPaint {
        if (color == null) throw new NullPointerException("color");
    }

    public static UiSdfPaint solid(UiColor color) {
        return new UiSdfPaint(color);
    }
}
