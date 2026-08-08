package com.kaleblangley.haikalat.subsystems.ui.animation;

import com.kaleblangley.haikalat.subsystems.ui.UiDirtyFlag;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.style.ComputedStyle;
import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;
import com.kaleblangley.haikalat.subsystems.ui.widget.ScrollView;
import com.kaleblangley.haikalat.subsystems.ui.widget.Slider;

import java.util.Objects;

/** One explicitly typed property animation track. */
public final class UiPropertyTrack {
    private final Property property;
    private final double from;
    private final double to;
    private final UiColor fromColor;
    private final UiColor toColor;

    private UiPropertyTrack(Property property, double from, double to,
                            UiColor fromColor, UiColor toColor) {
        this.property = Objects.requireNonNull(property, "property");
        this.from = from;
        this.to = to;
        this.fromColor = fromColor;
        this.toColor = toColor;
    }

    public static UiPropertyTrack numeric(Property property, double from, double to) {
        Objects.requireNonNull(property, "property");
        if (property.color) throw new IllegalArgumentException(property + " requires color values");
        if (!Double.isFinite(from) || !Double.isFinite(to)) {
            throw new IllegalArgumentException("track endpoints must be finite");
        }
        if ((property == Property.SCALE_X || property == Property.SCALE_Y)
                && (from == 0.0 || to == 0.0 || Math.signum(from) != Math.signum(to))) {
            throw new IllegalArgumentException("scale tracks cannot cross a non-invertible value");
        }
        return new UiPropertyTrack(property, from, to, null, null);
    }

    public static UiPropertyTrack color(Property property, UiColor from, UiColor to) {
        Objects.requireNonNull(property, "property");
        if (!property.color) throw new IllegalArgumentException(property + " requires numeric values");
        return new UiPropertyTrack(property, 0.0, 0.0,
                Objects.requireNonNull(from, "from"), Objects.requireNonNull(to, "to"));
    }

    public Property property() { return property; }
    public DirtyScope dirtyScope() { return property.scope; }
    public boolean isColor() { return property.color; }

    public double sample(double progress) {
        if (isColor()) throw new IllegalStateException("color track has no scalar sample");
        double t = clamp(progress);
        return from + (to - from) * t;
    }

    public UiColor sampleColor(double progress) {
        if (!isColor()) throw new IllegalStateException("numeric track has no color sample");
        double t = clamp(progress);
        return new UiColor(
                (float) lerp(fromColor.red(), toColor.red(), t),
                (float) lerp(fromColor.green(), toColor.green(), t),
                (float) lerp(fromColor.blue(), toColor.blue(), t),
                (float) lerp(fromColor.alpha(), toColor.alpha(), t));
    }

    /** Applies the sampled value without modifying Yoga dimensions for visual properties. */
    public void apply(UiNode node, double progress) {
        Objects.requireNonNull(node, "node");
        if (property.color) {
            applyColor(node, sampleColor(progress));
            return;
        }
        double value = sample(progress);
        switch (property) {
            case OPACITY, RADIUS, BORDER_WIDTH -> applyStyleScalar(node, property, value);
            case TRANSLATION_X, TRANSLATION_Y, SCALE_X, SCALE_Y, ROTATION,
                    ORIGIN_X, ORIGIN_Y -> applyTransform(node, property, value);
            case SCROLL_X -> {
                if (!(node instanceof ScrollView scroll)) requireWidget("ScrollView", node);
                else scroll.scrollTo(value, scroll.scrollY());
            }
            case SCROLL_Y -> {
                if (!(node instanceof ScrollView scroll)) requireWidget("ScrollView", node);
                else scroll.scrollTo(scroll.scrollX(), value);
            }
            case VALUE, PROGRESS -> {
                if (node instanceof Slider slider) slider.value(value);
                else node.animatedValue(value);
            }
            case GRADIENT_OFFSET -> node.animatedGradientOffset(value);
            case SHADOW_STRENGTH -> node.animatedShadowStrength(value);
            case EFFECT_STRENGTH -> node.animatedEffectStrength(value);
            default -> throw new IllegalStateException("unhandled property " + property);
        }
    }

    private void applyColor(UiNode node, UiColor color) {
        ComputedStyle style = node.computedStyle();
        node.computedStyle(switch (property) {
            case FOREGROUND_COLOR -> style(style, style.background(), color, style.borderColor(),
                    style.borderWidth(), style.radius(), style.opacity());
            case BACKGROUND_COLOR -> style(style, color, style.foreground(), style.borderColor(),
                    style.borderWidth(), style.radius(), style.opacity());
            case BORDER_COLOR -> style(style, style.background(), style.foreground(), color,
                    style.borderWidth(), style.radius(), style.opacity());
            default -> throw new IllegalStateException("unhandled color property " + property);
        });
    }

    private static void applyStyleScalar(UiNode node, Property property, double value) {
        ComputedStyle style = node.computedStyle();
        float scalar = (float) value;
        node.computedStyle(switch (property) {
            case OPACITY -> style(style, style.background(), style.foreground(),
                    style.borderColor(), style.borderWidth(), style.radius(),
                    Math.max(0.0f, Math.min(1.0f, scalar)));
            case RADIUS -> style(style, style.background(), style.foreground(),
                    style.borderColor(), style.borderWidth(), Math.max(0.0f, scalar),
                    style.opacity());
            case BORDER_WIDTH -> style(style, style.background(), style.foreground(),
                    style.borderColor(), Math.max(0.0f, scalar), style.radius(),
                    style.opacity());
            default -> throw new IllegalStateException("unhandled style property " + property);
        });
    }

    private static ComputedStyle style(ComputedStyle base, UiColor background,
                                       UiColor foreground, UiColor border, float borderWidth,
                                       float radius, float opacity) {
        return new ComputedStyle(background, foreground, border, borderWidth, radius,
                opacity, base.fontSize(), base.fontFamily(), base.textEffect());
    }

    private static void applyTransform(UiNode node, Property property, double value) {
        UiVisualTransform transform = node.visualTransform();
        node.visualTransform(new UiVisualTransform(
                property == Property.TRANSLATION_X ? value : transform.translateX(),
                property == Property.TRANSLATION_Y ? value : transform.translateY(),
                property == Property.SCALE_X ? value : transform.scaleX(),
                property == Property.SCALE_Y ? value : transform.scaleY(),
                property == Property.ROTATION ? value : transform.rotationRadians(),
                property == Property.ORIGIN_X ? value : transform.originX(),
                property == Property.ORIGIN_Y ? value : transform.originY()));
    }

    private static double clamp(double progress) {
        if (!Double.isFinite(progress)) throw new IllegalArgumentException("progress must be finite");
        return Math.max(0.0, Math.min(1.0, progress));
    }

    private static double lerp(double start, double end, double progress) {
        return start + (end - start) * progress;
    }

    private static void requireWidget(String type, UiNode node) {
        throw new IllegalArgumentException(node.widgetType() + " does not support " + type + " property");
    }

    public enum DirtyScope { VISUAL, LAYOUT, HIT_TEST, COMPOSITOR }

    public enum Property {
        OPACITY(DirtyScope.COMPOSITOR, false),
        FOREGROUND_COLOR(DirtyScope.VISUAL, true),
        BACKGROUND_COLOR(DirtyScope.VISUAL, true),
        BORDER_COLOR(DirtyScope.VISUAL, true),
        RADIUS(DirtyScope.VISUAL, false),
        BORDER_WIDTH(DirtyScope.VISUAL, false),
        GRADIENT_OFFSET(DirtyScope.VISUAL, false),
        SHADOW_STRENGTH(DirtyScope.COMPOSITOR, false),
        EFFECT_STRENGTH(DirtyScope.COMPOSITOR, false),
        TRANSLATION_X(DirtyScope.HIT_TEST, false),
        TRANSLATION_Y(DirtyScope.HIT_TEST, false),
        SCALE_X(DirtyScope.HIT_TEST, false),
        SCALE_Y(DirtyScope.HIT_TEST, false),
        ROTATION(DirtyScope.HIT_TEST, false),
        ORIGIN_X(DirtyScope.HIT_TEST, false),
        ORIGIN_Y(DirtyScope.HIT_TEST, false),
        SCROLL_X(DirtyScope.HIT_TEST, false),
        SCROLL_Y(DirtyScope.HIT_TEST, false),
        PROGRESS(DirtyScope.VISUAL, false),
        VALUE(DirtyScope.VISUAL, false);

        private final DirtyScope scope;
        private final boolean color;

        Property(DirtyScope scope, boolean color) {
            this.scope = scope;
            this.color = color;
        }
    }
}
