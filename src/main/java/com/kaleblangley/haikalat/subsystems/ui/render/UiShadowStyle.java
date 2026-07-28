package com.kaleblangley.haikalat.subsystems.ui.render;

/** Describes a future compositor shadow without owning GL resources. */
public record UiShadowStyle(float offsetX, float offsetY, float blurRadius,
                            float spread, float strength) {
    public static final UiShadowStyle NONE = new UiShadowStyle(0, 0, 0, 0, 0);

    public UiShadowStyle {
        if (!Float.isFinite(offsetX) || !Float.isFinite(offsetY)
                || !Float.isFinite(blurRadius) || blurRadius < 0.0f
                || !Float.isFinite(spread) || spread < 0.0f
                || !Float.isFinite(strength) || strength < 0.0f || strength > 1.0f) {
            throw new IllegalArgumentException("invalid shadow style");
        }
    }
}
