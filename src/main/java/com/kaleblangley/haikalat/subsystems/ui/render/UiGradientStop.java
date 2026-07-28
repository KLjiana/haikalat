package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;

/** Immutable stop used by SDF fill gradients. */
public record UiGradientStop(float offset, UiColor color) {
    public UiGradientStop {
        if (!Float.isFinite(offset) || offset < 0.0f || offset > 1.0f) {
            throw new IllegalArgumentException("gradient stop offset must be finite and in [0, 1]");
        }
        if (color == null) throw new NullPointerException("color");
    }
}
