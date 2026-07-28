package com.kaleblangley.haikalat.subsystems.ui.animation;

import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox;
import com.kaleblangley.haikalat.subsystems.ui.render.UiScreenRect;

/**
 * Layout-independent visual transform. Origins are normalized inside the node bounds,
 * where {@code (0.5, 0.5)} is the center.
 */
public record UiVisualTransform(
        double translateX,
        double translateY,
        double scaleX,
        double scaleY,
        double rotationRadians,
        double originX,
        double originY
) {
    public static final UiVisualTransform IDENTITY =
            new UiVisualTransform(0.0, 0.0, 1.0, 1.0, 0.0, 0.5, 0.5);

    public UiVisualTransform {
        requireFinite(translateX, "translateX");
        requireFinite(translateY, "translateY");
        requireFinite(scaleX, "scaleX");
        requireFinite(scaleY, "scaleY");
        requireFinite(rotationRadians, "rotationRadians");
        requireFinite(originX, "originX");
        requireFinite(originY, "originY");
        if (scaleX == 0.0 || scaleY == 0.0) {
            throw new IllegalArgumentException("visual transform scale must be non-zero");
        }
    }

    public static UiVisualTransform translation(double x, double y) {
        return new UiVisualTransform(x, y, 1.0, 1.0, 0.0, 0.5, 0.5);
    }

    public static UiVisualTransform scale(double x, double y) {
        return new UiVisualTransform(0.0, 0.0, x, y, 0.0, 0.5, 0.5);
    }

    public static UiVisualTransform rotation(double radians) {
        return new UiVisualTransform(0.0, 0.0, 1.0, 1.0, radians, 0.5, 0.5);
    }

    public boolean isIdentity() {
        return equals(IDENTITY);
    }

    public UiVisualTransform interpolate(UiVisualTransform target, double progress) {
        if (target == null) throw new NullPointerException("target");
        if (!Double.isFinite(progress)) throw new IllegalArgumentException("progress must be finite");
        double t = Math.max(0.0, Math.min(1.0, progress));
        return new UiVisualTransform(
                lerp(translateX, target.translateX, t),
                lerp(translateY, target.translateY, t),
                lerp(scaleX, target.scaleX, t),
                lerp(scaleY, target.scaleY, t),
                lerp(rotationRadians, target.rotationRadians, t),
                lerp(originX, target.originX, t),
                lerp(originY, target.originY, t));
    }

    public Matrix matrix(LayoutBox bounds) {
        if (bounds == null) throw new NullPointerException("bounds");
        return matrix(bounds.x(), bounds.y(), bounds.width(), bounds.height());
    }

    public Matrix matrix(UiScreenRect bounds) {
        if (bounds == null) throw new NullPointerException("bounds");
        return matrix(bounds.x(), bounds.y(), bounds.width(), bounds.height());
    }

    public Matrix matrix(double x, double y, double width, double height) {
        double pivotX = x + width * originX;
        double pivotY = y + height * originY;
        double cosine = Math.cos(rotationRadians);
        double sine = Math.sin(rotationRadians);
        double m00 = cosine * scaleX;
        double m01 = -sine * scaleY;
        double m10 = sine * scaleX;
        double m11 = cosine * scaleY;
        double tx = translateX + pivotX - m00 * pivotX - m01 * pivotY;
        double ty = translateY + pivotY - m10 * pivotX - m11 * pivotY;
        return new Matrix(m00, m01, m10, m11, tx, ty);
    }

    private static double lerp(double start, double end, double progress) {
        return start + (end - start) * progress;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }

    /** Immutable affine matrix using column-vector composition. */
    public record Matrix(double m00, double m01, double m10, double m11,
                         double translateX, double translateY) {
        public static final Matrix IDENTITY = new Matrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0);

        public Matrix {
            requireFinite(m00, "m00");
            requireFinite(m01, "m01");
            requireFinite(m10, "m10");
            requireFinite(m11, "m11");
            requireFinite(translateX, "translateX");
            requireFinite(translateY, "translateY");
            if (Math.abs(determinant(m00, m01, m10, m11)) < 1.0e-12) {
                throw new IllegalArgumentException("visual transform matrix is not invertible");
            }
        }

        /** Returns {@code this * child}, so the child transform is applied first. */
        public Matrix multiply(Matrix child) {
            if (child == null) throw new NullPointerException("child");
            return new Matrix(
                    m00 * child.m00 + m01 * child.m10,
                    m00 * child.m01 + m01 * child.m11,
                    m10 * child.m00 + m11 * child.m10,
                    m10 * child.m01 + m11 * child.m11,
                    m00 * child.translateX + m01 * child.translateY + translateX,
                    m10 * child.translateX + m11 * child.translateY + translateY);
        }

        public Point transform(double x, double y) {
            return new Point(m00 * x + m01 * y + translateX,
                    m10 * x + m11 * y + translateY);
        }

        public Point inverseTransform(double x, double y) {
            double determinant = determinant(m00, m01, m10, m11);
            double localX = x - translateX;
            double localY = y - translateY;
            return new Point((m11 * localX - m01 * localY) / determinant,
                    (-m10 * localX + m00 * localY) / determinant);
        }

        public UiScreenRect transformBounds(double x, double y, double width, double height) {
            Point p0 = transform(x, y);
            Point p1 = transform(x + width, y);
            Point p2 = transform(x + width, y + height);
            Point p3 = transform(x, y + height);
            double left = Math.min(Math.min(p0.x, p1.x), Math.min(p2.x, p3.x));
            double top = Math.min(Math.min(p0.y, p1.y), Math.min(p2.y, p3.y));
            double right = Math.max(Math.max(p0.x, p1.x), Math.max(p2.x, p3.x));
            double bottom = Math.max(Math.max(p0.y, p1.y), Math.max(p2.y, p3.y));
            return new UiScreenRect(left, top, right - left, bottom - top);
        }

        private static double determinant(double m00, double m01, double m10, double m11) {
            return m00 * m11 - m01 * m10;
        }
    }

    public record Point(double x, double y) {
        public Point {
            requireFinite(x, "x");
            requireFinite(y, "y");
        }
    }
}
