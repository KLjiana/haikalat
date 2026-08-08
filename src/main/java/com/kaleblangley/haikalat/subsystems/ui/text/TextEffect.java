package com.kaleblangley.haikalat.subsystems.ui.text;

import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;
import org.joml.Vector2f;
import org.joml.Vector2fc;

import java.util.Objects;

/** Immutable visual effect applied to a text run. Gradient angles are degrees. */
public final class TextEffect {
    /** Maximum logical-pixel reach supported by the zero-padded glyph atlas. */
    public static final float MAXIMUM_EXTENT = 8.0f;

    private static final TextEffect NONE = new TextEffect(TextEffectType.NONE,
            UiColor.TRANSPARENT, UiColor.TRANSPARENT, 0.0f, 0.0f, 0.0f,
            0.0f, 0.0f);

    private final TextEffectType type;
    private final UiColor primaryColor;
    private final UiColor secondaryColor;
    private final float thickness;
    private final float offsetX;
    private final float offsetY;
    private final float blur;
    private final float angleDegrees;

    private TextEffect(TextEffectType type, UiColor primaryColor, UiColor secondaryColor,
                       float thickness, float offsetX, float offsetY,
                       float blur, float angleDegrees) {
        this.type = Objects.requireNonNull(type, "type");
        this.primaryColor = Objects.requireNonNull(primaryColor, "primaryColor");
        this.secondaryColor = Objects.requireNonNull(secondaryColor, "secondaryColor");
        this.thickness = thickness;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.blur = blur;
        this.angleDegrees = angleDegrees;
    }

    public static TextEffect none() {
        return NONE;
    }

    public static TextEffect outline(UiColor color, float thickness) {
        requireSupportedExtent(thickness, "thickness");
        return new TextEffect(TextEffectType.OUTLINE, color, UiColor.TRANSPARENT,
                thickness, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    public static TextEffect dropShadow(UiColor color, float offsetX, float offsetY, float blur) {
        requireFinite(offsetX, "offsetX");
        requireFinite(offsetY, "offsetY");
        requireSupportedExtent(blur, "blur");
        requireSupportedExtent(Math.abs(offsetX) + blur, "horizontal shadow extent");
        requireSupportedExtent(Math.abs(offsetY) + blur, "vertical shadow extent");
        return new TextEffect(TextEffectType.DROP_SHADOW, color, UiColor.TRANSPARENT,
                0.0f, offsetX, offsetY, blur, 0.0f);
    }

    public static TextEffect glow(UiColor color, float radius) {
        requireSupportedExtent(radius, "radius");
        return new TextEffect(TextEffectType.GLOW, color, UiColor.TRANSPARENT,
                radius, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    public static TextEffect innerGlow(UiColor color) {
        return new TextEffect(TextEffectType.INNER_GLOW, color, UiColor.TRANSPARENT,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    public static TextEffect gradient(UiColor startColor, UiColor endColor,
                                      float angleDegrees) {
        requireFinite(angleDegrees, "angleDegrees");
        return new TextEffect(TextEffectType.GRADIENT, startColor, endColor,
                0.0f, 0.0f, 0.0f, 0.0f, angleDegrees);
    }

    public TextEffectType type() { return type; }
    public UiColor primaryColor() { return primaryColor; }
    public UiColor secondaryColor() { return secondaryColor; }
    public float thickness() { return thickness; }

    /** Returns a defensive copy so callers cannot mutate this effect. */
    public Vector2fc offset() { return new Vector2f(offsetX, offsetY); }
    public float offsetX() { return offsetX; }
    public float offsetY() { return offsetY; }
    public float blur() { return blur; }
    public float angle() { return angleDegrees; }
    public float angleDegrees() { return angleDegrees; }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof TextEffect other)) return false;
        return type == other.type
                && primaryColor.equals(other.primaryColor)
                && secondaryColor.equals(other.secondaryColor)
                && Float.compare(thickness, other.thickness) == 0
                && Float.compare(offsetX, other.offsetX) == 0
                && Float.compare(offsetY, other.offsetY) == 0
                && Float.compare(blur, other.blur) == 0
                && Float.compare(angleDegrees, other.angleDegrees) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, primaryColor, secondaryColor, thickness,
                offsetX, offsetY, blur, angleDegrees);
    }

    @Override
    public String toString() {
        return "TextEffect{type=" + type + ", primaryColor=" + primaryColor
                + ", thickness=" + thickness + ", offset=(" + offsetX + ", " + offsetY
                + "), blur=" + blur + ", angleDegrees=" + angleDegrees + "}";
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }

    private static void requireSupportedExtent(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > MAXIMUM_EXTENT) {
            throw new IllegalArgumentException(name + " must be finite and within [0, "
                    + MAXIMUM_EXTENT + "]");
        }
    }
}
