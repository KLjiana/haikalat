package com.kaleblangley.haikalat.subsystems.ui.text;

/**
 * Defines the type of visual effect applied to text rendering.
 */
public enum TextEffectType {
    /** No effect applied */
    NONE,

    /** Outline around the text */
    OUTLINE,

    /** Drop shadow beneath the text */
    DROP_SHADOW,

    /** Outer glow effect */
    GLOW,

    /** Inner glow effect along text edges */
    INNER_GLOW,

    /** Gradient color fill */
    GRADIENT
}
