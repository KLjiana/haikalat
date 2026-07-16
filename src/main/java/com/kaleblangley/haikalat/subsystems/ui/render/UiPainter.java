package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.subsystems.ui.UiDebugOptions;
import com.kaleblangley.haikalat.subsystems.ui.UiDirtyFlag;
import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.UiVisibility;
import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox;
import com.kaleblangley.haikalat.subsystems.ui.style.ComputedStyle;
import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.widget.Image;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.ui.widget.TextField;

import java.util.Objects;

/**
 * 把 retained UI 文档转换成稳定、与图形 API 无关的 display list。
 *
 * <p>遍历顺序与 {@link UiDocument#paintOrder()} 相同：普通 root 深度优先，其后为 overlay root。
 * 文档的两个内部 root 只承担结构职责，不绘制自身主题背景。</p>
 */
public final class UiPainter {
    private static final int DEBUG_BOUNDS_COLOR = 0x00ff00a0;
    private static final float FOCUSED_SELECTION_OPACITY = 0.55f;
    private static final float UNFOCUSED_SELECTION_OPACITY = 0.30f;

    private final UiImageResolver imageResolver;
    private final UiGlyphPainter glyphPainter;
    private final UiDebugOptions debugOptions;

    /** 创建使用确定性文字占位和空图片注册表的 painter。 */
    public UiPainter() {
        this(UiImageResolver.empty(), UiGlyphPainter.placeholder(), UiDebugOptions.NONE);
    }

    public UiPainter(UiImageResolver imageResolver, UiGlyphPainter glyphPainter,
                     UiDebugOptions debugOptions) {
        this.imageResolver = Objects.requireNonNull(imageResolver, "imageResolver");
        this.glyphPainter = Objects.requireNonNull(glyphPainter, "glyphPainter");
        this.debugOptions = Objects.requireNonNull(debugOptions, "debugOptions");
    }

    /** 创建并填充新的 display list。 */
    public UiDisplayList paint(UiDocument document) {
        return paint(document, new UiDisplayList());
    }

    /** 清空并复用调用方提供的 display list。 */
    public UiDisplayList paint(UiDocument document, UiDisplayList output) {
        Objects.requireNonNull(document, "document").ensureOpen();
        Objects.requireNonNull(output, "output").clear();

        paintStructuralRoot(document, document.root(), output);
        output.paintBoundary();
        paintStructuralRoot(document, document.overlayRoot(), output);
        return output;
    }

    /** 把线性 RGBA 与节点 opacity 合成为 0xRRGGBBAA 预乘颜色。 */
    static int premultipliedRgba8(UiColor color, float opacity) {
        Objects.requireNonNull(color, "color");
        if (!Float.isFinite(opacity) || opacity < 0.0f || opacity > 1.0f) {
            throw new IllegalArgumentException("opacity must be finite and in [0, 1]");
        }
        float alpha = color.alpha() * opacity;
        int red = unitByte(color.red() * alpha);
        int green = unitByte(color.green() * alpha);
        int blue = unitByte(color.blue() * alpha);
        int packedAlpha = unitByte(alpha);
        return red << 24 | green << 16 | blue << 8 | packedAlpha;
    }

    private void paintStructuralRoot(UiDocument document, UiNode root, UiDisplayList output) {
        if (root.visibility() != UiVisibility.VISIBLE) return;
        UiScreenRect bounds = bounds(root, 0.0, 0.0);
        boolean clipped = clipsChildren(root);
        if (clipped) output.pushClip(bounds);
        try {
            for (UiNode child : root.children()) {
                paintNode(document, child, 0.0, 0.0, output);
            }
        } finally {
            if (clipped) output.popClip();
        }
        root.clearDirty(UiDirtyFlag.PAINT);
    }

    private void paintNode(UiDocument document, UiNode node,
                           double offsetX, double offsetY, UiDisplayList output) {
        if (node.visibility() != UiVisibility.VISIBLE) return;
        UiScreenRect nodeBounds = bounds(node, offsetX, offsetY);
        paintVisual(document, node, nodeBounds, output);
        if (debugOptions.layoutBounds() && !nodeBounds.isEmpty()) {
            output.addDebugOutline(nodeBounds, DEBUG_BOUNDS_COLOR);
        }

        boolean clipped = clipsChildren(node);
        if (clipped) output.pushClip(nodeBounds);
        try {
            double childOffsetX = offsetX + node.childVisualOffsetX();
            double childOffsetY = offsetY + node.childVisualOffsetY();
            for (UiNode child : node.children()) {
                paintNode(document, child, childOffsetX, childOffsetY, output);
            }
        } finally {
            if (clipped) output.popClip();
        }
        node.clearDirty(UiDirtyFlag.PAINT);
    }

    private void paintVisual(UiDocument document, UiNode node,
                             UiScreenRect bounds, UiDisplayList output) {
        if (node instanceof Panel || node instanceof TextField) {
            paintBox(node.computedStyle(), bounds, output);
        }
        if (node instanceof Image image) {
            paintImage(image, bounds, output);
        } else if (node instanceof Label label) {
            paintText(node, label.text(), label.alignment(), bounds, false, output);
        } else if (node instanceof TextField field) {
            paintTextField(document, field, bounds, output);
        }
    }

    private static void paintBox(ComputedStyle style, UiScreenRect bounds,
                                 UiDisplayList output) {
        int background = premultipliedRgba8(style.background(), style.opacity());
        if (!bounds.isEmpty() && alpha(background) != 0) {
            output.addSolidQuad(bounds, background, UiBlendMode.PREMULTIPLIED_ALPHA);
        }
        double border = Math.min(style.borderWidth(),
                Math.min(bounds.width() * 0.5, bounds.height() * 0.5));
        int borderColor = premultipliedRgba8(style.borderColor(), style.opacity());
        if (border <= 0.0 || alpha(borderColor) == 0 || bounds.isEmpty()) return;

        addSolid(output, bounds.x(), bounds.y(), bounds.width(), border, borderColor);
        addSolid(output, bounds.x(), bounds.bottom() - border,
                bounds.width(), border, borderColor);
        double sideHeight = Math.max(0.0, bounds.height() - border * 2.0);
        addSolid(output, bounds.x(), bounds.y() + border,
                border, sideHeight, borderColor);
        addSolid(output, bounds.right() - border, bounds.y() + border,
                border, sideHeight, borderColor);
    }

    private void paintImage(Image image, UiScreenRect bounds, UiDisplayList output) {
        if (bounds.isEmpty()) return;
        UiImageRegion region = imageResolver.resolve(image.imageId()).orElse(null);
        if (region == null) return;
        ImagePlacement placement = imagePlacement(image.objectFit(), bounds, region);
        int tint = premultipliedRgba8(image.tint(), image.computedStyle().opacity());
        if (alpha(tint) == 0 || placement.bounds().isEmpty()) return;

        output.pushClip(bounds);
        try {
            output.addTexturedQuad(placement.bounds(), placement.uv(),
                    region.textureId(), region.samplerId(), tint,
                    UiBlendMode.PREMULTIPLIED_ALPHA);
        } finally {
            output.popClip();
        }
    }

    private void paintTextField(UiDocument document, TextField field,
                                UiScreenRect bounds, UiDisplayList output) {
        LayoutBox nodeBox = field.layoutBox();
        LayoutBox logicalContent = field.textContentBox();
        UiScreenRect contentBounds = new UiScreenRect(
                bounds.x() + logicalContent.x() - nodeBox.x(),
                bounds.y() + logicalContent.y() - nodeBox.y(),
                logicalContent.width(), logicalContent.height());
        String text;
        boolean placeholder = field.value().isEmpty() && field.composition() == null;
        if (placeholder) {
            text = field.placeholder();
        } else if (field.password()) {
            text = "\u2022".repeat(field.value().codePointCount(0, field.value().length()));
        } else {
            text = field.value();
        }
        if (field.composition() != null && !field.password()) {
            text += field.composition().text();
        }
        UiTextLineMetrics committedMetrics = glyphPainter.measureLine(field, field.value());
        field.updateTextMetrics(committedMetrics, contentBounds.width());
        UiTextLineMetrics paintedMetrics = text.equals(field.value())
                ? committedMetrics : glyphPainter.measureLine(field, text);
        double textWidth = Math.max(contentBounds.width(), paintedMetrics.width());
        UiScreenRect textBounds = new UiScreenRect(
                contentBounds.x() - field.horizontalScroll(), contentBounds.y(),
                textWidth, contentBounds.height());
        boolean focused = document.focusManager().focused() == field;
        paintTextSelection(field, paintedMetrics, contentBounds, focused, output);
        paintText(field, text, Label.Alignment.START, textBounds, contentBounds,
                placeholder, output);

        if (focused && !field.hasSelection() && !contentBounds.isEmpty()) {
            double x = Math.min(contentBounds.right() - 1.0,
                    contentBounds.x() + field.visibleCaretX());
            int color = premultipliedRgba8(field.computedStyle().foreground(),
                    field.computedStyle().opacity());
            double requestedHeight = field.computedStyle().fontSize() * 1.1;
            double caretHeight = Math.min(requestedHeight, contentBounds.height());
            double caretY = contentBounds.y() + (contentBounds.height() - caretHeight) * 0.5;
            addSolid(output, x, caretY, 1.0, caretHeight, color);
        }
    }

    private static void paintTextSelection(TextField field, UiTextLineMetrics metrics,
                                           UiScreenRect contentBounds, boolean focused,
                                           UiDisplayList output) {
        if (!field.hasSelection() || contentBounds.isEmpty()) return;
        int start = field.selectionStart();
        int end = field.selectionEnd();
        if (field.password()) {
            start = field.value().codePointCount(0, start);
            end = field.value().codePointCount(0, end);
        }
        double first = contentBounds.x() + metrics.xAt(start) - field.horizontalScroll();
        double second = contentBounds.x() + metrics.xAt(end) - field.horizontalScroll();
        double left = Math.max(contentBounds.x(), Math.min(first, second));
        double right = Math.min(contentBounds.right(), Math.max(first, second));
        if (right <= left) return;

        double requestedHeight = field.computedStyle().fontSize() * 1.25;
        double height = Math.min(requestedHeight, contentBounds.height());
        double y = contentBounds.y() + (contentBounds.height() - height) * 0.5;
        float selectionOpacity = field.computedStyle().opacity()
                * (focused ? FOCUSED_SELECTION_OPACITY : UNFOCUSED_SELECTION_OPACITY);
        int color = premultipliedRgba8(field.computedStyle().borderColor(), selectionOpacity);
        output.pushClip(contentBounds);
        try {
            addSolid(output, left, y, right - left, height, color);
        } finally {
            output.popClip();
        }
    }

    private void paintText(UiNode node, String text, Label.Alignment alignment,
                           UiScreenRect bounds, boolean placeholder, UiDisplayList output) {
        paintText(node, text, alignment, bounds, bounds, placeholder, true, output);
    }

    private void paintText(UiNode node, String text, Label.Alignment alignment,
                           UiScreenRect bounds, UiScreenRect clipBounds,
                           boolean placeholder, UiDisplayList output) {
        paintText(node, text, alignment, bounds, clipBounds, placeholder, true, output);
    }

    private void paintText(UiNode node, String text, Label.Alignment alignment,
                           UiScreenRect bounds, UiScreenRect clipBounds,
                           boolean placeholder, boolean clipText, UiDisplayList output) {
        if (text.isEmpty() || bounds.isEmpty()) return;
        float opacity = node.computedStyle().opacity() * (placeholder ? 0.55f : 1.0f);
        int color = premultipliedRgba8(node.computedStyle().foreground(), opacity);
        if (alpha(color) == 0) return;

        if (clipText) output.pushClip(textClip(clipBounds));
        try {
            if (!glyphPainter.paint(output, node, text, bounds, color)) {
                paintPlaceholderGlyphs(node, text, alignment, bounds, color, output);
            }
        } finally {
            if (clipText) output.popClip();
        }
    }

    private static void paintPlaceholderGlyphs(UiNode node, String text, Label.Alignment alignment,
                                                UiScreenRect bounds, int color,
                                                UiDisplayList output) {
        double fontSize = node.computedStyle().fontSize();
        double advance = fontSize * 0.6;
        int codePoints = text.codePointCount(0, text.length());
        double textWidth = codePoints * advance;
        double x = switch (alignment) {
            case START -> bounds.x() + fontSize * 0.25;
            case CENTER -> bounds.x() + (bounds.width() - textWidth) * 0.5;
            case END -> bounds.right() - textWidth - fontSize * 0.25;
        };
        double height = Math.min(bounds.height(), fontSize * 0.75);
        double y = bounds.y() + Math.max(0.0, (bounds.height() - height) * 0.5);
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            if (!Character.isWhitespace(codePoint)) {
                addSolid(output, x, y, fontSize * 0.45, height, color);
            }
            x += advance;
            if (x >= bounds.right()) break;
            offset += Character.charCount(codePoint);
        }
    }

    private static ImagePlacement imagePlacement(Image.ObjectFit fit, UiScreenRect bounds,
                                                  UiImageRegion region) {
        double imageWidth = region.intrinsicWidth();
        double imageHeight = region.intrinsicHeight();
        if (fit == Image.ObjectFit.FILL) return new ImagePlacement(bounds, region.uv());
        if (fit == Image.ObjectFit.COVER) {
            double scale = Math.max(bounds.width() / imageWidth, bounds.height() / imageHeight);
            double visibleWidth = bounds.width() / scale;
            double visibleHeight = bounds.height() / scale;
            double cropX = (imageWidth - visibleWidth) / (imageWidth * 2.0);
            double cropY = (imageHeight - visibleHeight) / (imageHeight * 2.0);
            UiUvRect source = region.uv();
            float u0 = lerp(source.u0(), source.u1(), cropX);
            float u1 = lerp(source.u0(), source.u1(), 1.0 - cropX);
            float v0 = lerp(source.v0(), source.v1(), cropY);
            float v1 = lerp(source.v0(), source.v1(), 1.0 - cropY);
            return new ImagePlacement(bounds, new UiUvRect(u0, v0, u1, v1));
        }
        double scale = fit == Image.ObjectFit.CONTAIN
                ? Math.min(bounds.width() / imageWidth, bounds.height() / imageHeight)
                : 1.0;
        double width = imageWidth * scale;
        double height = imageHeight * scale;
        return new ImagePlacement(new UiScreenRect(
                bounds.x() + (bounds.width() - width) * 0.5,
                bounds.y() + (bounds.height() - height) * 0.5,
                width, height), region.uv());
    }

    private static UiScreenRect textClip(UiScreenRect bounds) {
        return bounds;
    }

    private static boolean clipsChildren(UiNode node) {
        return node.clipChildren() || node.style().overflow() != UiStyle.Overflow.VISIBLE;
    }

    private static UiScreenRect bounds(UiNode node, double offsetX, double offsetY) {
        LayoutBox box = node.layoutBox();
        return new UiScreenRect(box.x() + offsetX, box.y() + offsetY,
                box.width(), box.height());
    }

    private static void addSolid(UiDisplayList output, UiScreenRect rect, int color) {
        if (!rect.isEmpty() && alpha(color) != 0) {
            output.addSolidQuad(rect, color, UiBlendMode.PREMULTIPLIED_ALPHA);
        }
    }

    private static void addSolid(UiDisplayList output, double x, double y,
                                 double width, double height, int color) {
        if (width > 0.0 && height > 0.0 && alpha(color) != 0) {
            output.addSolidQuad(x, y, width, height, color,
                    UiBlendMode.PREMULTIPLIED_ALPHA);
        }
    }

    private static int alpha(int packed) {
        return packed & 0xff;
    }

    private static int unitByte(float value) {
        return Math.min(255, Math.max(0, Math.round(value * 255.0f)));
    }

    private static float lerp(float start, float end, double amount) {
        return (float) (start + (end - start) * amount);
    }

    private record ImagePlacement(UiScreenRect bounds, UiUvRect uv) {
    }
}
