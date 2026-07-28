package com.kaleblangley.haikalat.subsystems.ui.render;

/** Analytic SDF shape parameters; no renderer or OpenGL state is retained. */
public record UiSdfShape(Kind kind, float radiusTopLeft, float radiusTopRight,
                          float radiusBottomRight, float radiusBottomLeft,
                          float innerRadius, float startRadians, float endRadians,
                          float thickness) {
    public enum Kind {
        ROUNDED_RECT,
        PILL,
        ELLIPSE,
        CIRCLE,
        RING,
        PROGRESS_ARC,
        ROUNDED_LINE
    }

    public UiSdfShape {
        if (kind == null) throw new NullPointerException("kind");
        float[] radii = {radiusTopLeft, radiusTopRight, radiusBottomRight, radiusBottomLeft};
        for (float radius : radii) {
            if (!Float.isFinite(radius) || radius < 0.0f) {
                throw new IllegalArgumentException("corner radii must be finite and non-negative");
            }
        }
        if (!Float.isFinite(innerRadius) || innerRadius < 0.0f
                || !Float.isFinite(startRadians) || !Float.isFinite(endRadians)
                || !Float.isFinite(thickness) || thickness < 0.0f) {
            throw new IllegalArgumentException("invalid SDF shape parameters");
        }
    }

    public static UiSdfShape roundedRect(float radius) {
        return roundedRect(radius, radius, radius, radius);
    }

    public static UiSdfShape roundedRect(float topLeft, float topRight,
                                         float bottomRight, float bottomLeft) {
        return new UiSdfShape(Kind.ROUNDED_RECT, topLeft, topRight, bottomRight,
                bottomLeft, 0.0f, 0.0f, (float) (Math.PI * 2.0), 0.0f);
    }

    public static UiSdfShape pill() {
        return new UiSdfShape(Kind.PILL, 0, 0, 0, 0, 0, 0,
                (float) (Math.PI * 2.0), 0);
    }

    public static UiSdfShape ellipse() {
        return new UiSdfShape(Kind.ELLIPSE, 0, 0, 0, 0, 0, 0,
                (float) (Math.PI * 2.0), 0);
    }

    public static UiSdfShape circle() {
        return new UiSdfShape(Kind.CIRCLE, 0, 0, 0, 0, 0, 0,
                (float) (Math.PI * 2.0), 0);
    }

    public static UiSdfShape ring(float innerRadius, float thickness) {
        return new UiSdfShape(Kind.RING, 0, 0, 0, 0, innerRadius, 0,
                (float) (Math.PI * 2.0), thickness);
    }

    public static UiSdfShape progressArc(float radius, float thickness,
                                          float startRadians, float endRadians) {
        return new UiSdfShape(Kind.PROGRESS_ARC, radius, radius, radius, radius,
                radius, startRadians, endRadians, thickness);
    }

    public static UiSdfShape roundedLine(float thickness) {
        return new UiSdfShape(Kind.ROUNDED_LINE, 0, 0, 0, 0, 0, 0,
                (float) (Math.PI * 2.0), thickness);
    }

    public UiSdfShape normalizedFor(float width, float height) {
        float maxRadius = Math.min(Math.max(0.0f, width), Math.max(0.0f, height)) * 0.5f;
        if (kind == Kind.PILL) {
            return roundedRect(maxRadius);
        }
        if (kind != Kind.ROUNDED_RECT) return this;
        return new UiSdfShape(kind,
                Math.min(radiusTopLeft, maxRadius), Math.min(radiusTopRight, maxRadius),
                Math.min(radiusBottomRight, maxRadius), Math.min(radiusBottomLeft, maxRadius),
                innerRadius, startRadians, endRadians, thickness);
    }
}
