package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.subsystems.ui.UiDebugOptions;
import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiImageId;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.UiVisibility;
import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox;
import com.kaleblangley.haikalat.subsystems.ui.style.ComputedStyle;
import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;
import com.kaleblangley.haikalat.subsystems.ui.style.UiInsets;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.widget.Image;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.ui.widget.ListView;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.ui.widget.ScrollView;
import com.kaleblangley.haikalat.subsystems.ui.widget.TextField;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiPainterTest {
    private static final double EPSILON = 0.0001;

    @Test
    void paintsNormalTreeBeforeOverlayAndSkipsDocumentRootsAndHiddenNodes() {
        try (UiDocument document = new UiDocument()) {
            layout(document.root(), 0, 0, 100, 100);
            layout(document.overlayRoot(), 0, 0, 100, 100);
            Panel normal = panel(new UiColor(1.0f, 0.5f, 0.25f, 0.5f), 0.5f);
            layout(normal, 1, 2, 20, 10);
            Panel hidden = panel(new UiColor(0.0f, 1.0f, 0.0f, 1.0f), 1.0f);
            hidden.visibility(UiVisibility.HIDDEN);
            layout(hidden, 20, 20, 10, 10);
            Panel overlay = panel(new UiColor(0.0f, 0.0f, 1.0f, 1.0f), 1.0f);
            layout(overlay, 30, 40, 15, 12);
            document.root().add(normal).add(hidden);
            document.overlayRoot().add(overlay);

            UiDisplayList list = new UiPainter().paint(document);

            assertEquals(3, list.primitiveCount());
            assertSame(UiPrimitiveKind.SOLID_QUAD, list.primitiveKind(0));
            assertSame(UiPrimitiveKind.PAINT_BOUNDARY, list.primitiveKind(1));
            assertSame(UiPrimitiveKind.SOLID_QUAD, list.primitiveKind(2));
            assertEquals(0x40201040, list.quadColor(0),
                    "linear RGBA 与节点 opacity 应只预乘一次");
            assertEquals(0x0000ffff, list.quadColor(1));
            assertFalse(normal.isDirty(com.kaleblangley.haikalat.subsystems.ui.UiDirtyFlag.PAINT));
            assertTrue(hidden.isDirty(com.kaleblangley.haikalat.subsystems.ui.UiDirtyFlag.PAINT),
                    "隐藏 subtree 应保留 paint dirty，重新显示时再消费");
        }
    }

    @Test
    void nestedClipAndScrollOffsetAreRecordedWithoutEarlyPixelRounding() {
        try (UiDocument document = new UiDocument()) {
            ScrollView scroll = new ScrollView();
            scroll.computedStyle(style(UiColor.TRANSPARENT, UiColor.WHITE, 0.0f, 1.0f));
            scroll.scrollTo(0.0, 7.25);
            layout(scroll, 10.5f, 11.25f, 20.5f, 21.75f);
            Label label = new Label("A");
            label.style(UiStyle.builder().overflow(UiStyle.Overflow.HIDDEN).build());
            label.computedStyle(style(UiColor.TRANSPARENT, UiColor.WHITE, 0.0f, 1.0f));
            layout(label, 12.25f, 30.5f, 12.0f, 12.0f);
            scroll.content(label);
            document.root().add(scroll);

            UiDisplayList list = new UiPainter().paint(document);

            assertSame(UiPrimitiveKind.PUSH_CLIP, list.primitiveKind(0));
            assertEquals(new UiScreenRect(10.5, 11.25, 20.5, 21.75), list.primitiveClip(0));
            assertSame(UiPrimitiveKind.PUSH_CLIP, list.primitiveKind(1));
            assertEquals(22.25, list.primitiveClip(1).y(), EPSILON,
                    "child logical Y 应减去 7.25 scroll offset，且保留小数");
            assertSame(UiPrimitiveKind.SOLID_QUAD, list.primitiveKind(2));
            assertTrue(list.quadY(0) >= 23.25 && list.quadY(0) < 35.25);
            assertTrue(list.freeze().isFrozen(), "嵌套 clip 必须保持 push/pop 平衡");
        }
    }

    @Test
    void imageCoverProducesCenteredUvCropAndTexturePrimitive() {
        UiImageResolver resolver = id -> Optional.of(new UiImageRegion(
                77, 3, 200.0, 100.0, UiUvRect.FULL));
        try (UiDocument document = new UiDocument()) {
            Image image = new Image(new UiImageId(5));
            image.objectFit(Image.ObjectFit.COVER);
            layout(image, 0, 0, 100, 100);
            document.root().add(image);

            UiDisplayList list = new UiPainter(resolver, UiGlyphPainter.placeholder(),
                    UiDebugOptions.NONE).paint(document);

            assertSame(UiPrimitiveKind.PUSH_CLIP, list.primitiveKind(0));
            assertSame(UiPrimitiveKind.TEXTURED_QUAD, list.primitiveKind(1));
            assertEquals(77, list.primitiveTexture(1));
            assertEquals(3, list.primitiveSampler(1));
            assertEquals(0.25f, list.quadU0(0), 0.0001f);
            assertEquals(0.75f, list.quadU1(0), 0.0001f);
            assertEquals(0.0f, list.quadV0(0), 0.0001f);
            assertEquals(1.0f, list.quadV1(0), 0.0001f);
        }
    }

    @Test
    void listViewMapsMaterializedCellWindowAndAppliesScrollExactlyOnce() {
        try (UiDocument document = new UiDocument()) {
            ListView listView = new ListView();
            listView.computedStyle(style(UiColor.TRANSPARENT, UiColor.WHITE, 0.0f, 1.0f));
            layout(listView, 5, 10, 40, 20);
            listView.estimatedItemHeight(10.0f).overscan(1).model(8, index -> {
                Panel cell = panel(new UiColor(1.0f, 0.0f, 0.0f, 1.0f), 1.0f);
                return cell;
            });
            listView.scrollTo(0.0, 20.0);
            Panel stableCell = (Panel) listView.materializedCell(2);
            layout(stableCell, 5, 20, 40, 10);
            document.root().add(listView);

            UiDisplayList list = new UiPainter().paint(document);

            int stableCellQuad = findSolidQuadAt(list, 5.0, 10.0);
            assertTrue(stableCellQuad >= 0,
                    "index 2 cell 应叠加 materialized host offset 并只减一次 scrollY");
        }
    }

    @Test
    void visibleOverflowLabelsAvoidPerNodeClipAndKeepAdjacentGlyphRunsMergeable() {
        UiGlyphPainter glyphs = (list, node, text, bounds, color) -> {
            list.beginGlyphRun(9, 2, UiBlendMode.PREMULTIPLIED_ALPHA);
            list.addGlyph(new UiScreenRect(bounds.x(), bounds.y(), 4, 6), UiUvRect.FULL, color);
            list.endGlyphRun();
            return true;
        };
        try (UiDocument document = new UiDocument()) {
            Label label = new Label("字");
            label.computedStyle(style(UiColor.TRANSPARENT, UiColor.WHITE, 0.0f, 1.0f));
            layout(label, 2, 3, 20, 20);
            document.root().add(label);

            UiDisplayList list = new UiPainter(UiImageResolver.empty(), glyphs,
                    UiDebugOptions.NONE).paint(document);

            assertSame(UiPrimitiveKind.GLYPH_RUN, list.primitiveKind(0));
            assertEquals(9, list.primitiveTexture(0));
            assertEquals(1, list.primitiveQuadCount(0));
            assertSame(UiShaderVariant.GLYPH, list.primitiveShader(0));
        }
    }

    @Test
    void textFieldUsesOnePaddedContentBoxForTextAndCaret() {
        AtomicReference<UiScreenRect> paintedBounds = new AtomicReference<>();
        UiGlyphPainter glyphs = (list, node, text, bounds, color) -> {
            paintedBounds.set(bounds);
            return true;
        };
        try (UiDocument document = new UiDocument()) {
            TextField field = new TextField().value("A");
            field.style(UiStyle.builder().padding(UiInsets.points(6.0f)).build());
            field.computedStyle(style(UiColor.TRANSPARENT, UiColor.WHITE, 1.0f, 1.0f));
            layout(field, 10, 20, 100, 36);
            document.root().add(field);
            document.focusManager().requestFocus(field);

            UiDisplayList list = new UiPainter(UiImageResolver.empty(), glyphs,
                    UiDebugOptions.NONE).paint(document);

            assertEquals(new UiScreenRect(17.0, 27.0, 86.0, 22.0), paintedBounds.get());
            int caret = findSolidQuadAt(list, 23.0, 32.5);
            assertTrue(caret >= 0, "caret 应从 padding 后的文本起点计算并在内容框内垂直居中");
            assertEquals(11.0, list.quadHeight(caret), EPSILON);
        }
    }

    @Test
    void textFieldSelectionUsesShapedCaretStopsAndHidesCaret() {
        UiGlyphPainter glyphs = new UiGlyphPainter() {
            @Override
            public boolean paint(UiDisplayList list, UiNode node, String text,
                                 UiScreenRect bounds, int color) {
                return true;
            }

            @Override
            public UiTextLineMetrics measureLine(UiNode node, String text) {
                return new UiTextLineMetrics(text, java.util.List.of(
                        new UiTextLineMetrics.CaretStop(0, 0.0),
                        new UiTextLineMetrics.CaretStop(1, 8.0),
                        new UiTextLineMetrics.CaretStop(2, 24.0),
                        new UiTextLineMetrics.CaretStop(3, 32.0)), 32.0);
            }
        };
        try (UiDocument document = new UiDocument()) {
            TextField field = new TextField().value("A中B").select(1, 2);
            field.style(UiStyle.builder().padding(UiInsets.points(6.0f)).build());
            field.computedStyle(style(UiColor.TRANSPARENT, UiColor.WHITE, 1.0f, 1.0f));
            layout(field, 10, 20, 100, 36);
            document.root().add(field);
            document.focusManager().requestFocus(field);

            UiDisplayList list = new UiPainter(UiImageResolver.empty(), glyphs,
                    UiDebugOptions.NONE).paint(document);

            int selection = findSolidQuadAt(list, 25.0, 31.75);
            assertTrue(selection >= 0, "选区应从真实 shaping caret stop 1 延伸到 stop 2");
            assertEquals(16.0, list.quadWidth(selection), EPSILON);
            assertEquals(12.5, list.quadHeight(selection), EPSILON);
            assertTrue(findSolidQuadAt(list, 41.0, 32.5) < 0,
                    "存在选区时不应在选区末端叠加 caret");
        }
    }

    @Test
    void longTextScrollsHorizontallyToKeepRealCaretVisible() {
        AtomicReference<UiScreenRect> paintedBounds = new AtomicReference<>();
        UiGlyphPainter glyphs = new UiGlyphPainter() {
            @Override
            public boolean paint(UiDisplayList list, UiNode node, String text,
                                 UiScreenRect bounds, int color) {
                paintedBounds.set(bounds);
                return true;
            }

            @Override
            public UiTextLineMetrics measureLine(UiNode node, String text) {
                return new UiTextLineMetrics(text, java.util.List.of(
                        new UiTextLineMetrics.CaretStop(0, 0.0),
                        new UiTextLineMetrics.CaretStop(1, 12.0),
                        new UiTextLineMetrics.CaretStop(2, 24.0),
                        new UiTextLineMetrics.CaretStop(3, 36.0),
                        new UiTextLineMetrics.CaretStop(4, 48.0)), 48.0);
            }
        };
        try (UiDocument document = new UiDocument()) {
            TextField field = new TextField().value("中文中文");
            field.computedStyle(style(UiColor.TRANSPARENT, UiColor.WHITE, 0.0f, 1.0f));
            layout(field, 10, 20, 30, 20);
            document.root().add(field);
            document.focusManager().requestFocus(field);

            new UiPainter(UiImageResolver.empty(), glyphs, UiDebugOptions.NONE).paint(document);

            assertTrue(field.horizontalScroll() > 0.0);
            assertTrue(field.visibleCaretX() <= field.textContentBox().width());
            assertTrue(paintedBounds.get().x() < field.textContentBox().x(),
                    "长文本应向左平移，同时继续由输入框内容区域裁剪");
        }
    }

    @Test
    void debugBoundsRemainInNormalDisplayListPath() {
        try (UiDocument document = new UiDocument()) {
            Panel panel = panel(UiColor.TRANSPARENT, 1.0f);
            layout(panel, 1, 2, 3, 4);
            document.root().add(panel);
            UiDebugOptions debug = new UiDebugOptions(true, false, false,
                    false, false, false, false);

            UiDisplayList list = new UiPainter(UiImageResolver.empty(),
                    UiGlyphPainter.placeholder(), debug).paint(document);

            assertSame(UiPrimitiveKind.DEBUG_OUTLINE, list.primitiveKind(0));
            assertSame(UiShaderVariant.DEBUG_OUTLINE, list.primitiveShader(0));
        }
    }

    private static Panel panel(UiColor background, float opacity) {
        Panel panel = new Panel();
        panel.computedStyle(style(background, UiColor.WHITE, 0.0f, opacity));
        return panel;
    }

    private static ComputedStyle style(UiColor background, UiColor foreground,
                                       float borderWidth, float opacity) {
        return new ComputedStyle(background, foreground, UiColor.WHITE,
                borderWidth, 0.0f, opacity, 10.0f, "TestFont");
    }

    private static void layout(UiNode node, float x, float y, float width, float height) {
        node.applyLayout(new LayoutBox(x, y, width, height));
    }

    private static int findSolidQuadAt(UiDisplayList list, double x, double y) {
        for (int primitive = 0; primitive < list.primitiveCount(); primitive++) {
            if (list.primitiveKind(primitive) != UiPrimitiveKind.SOLID_QUAD) continue;
            int quad = list.primitiveFirstQuad(primitive);
            if (Math.abs(list.quadX(quad) - x) < EPSILON
                    && Math.abs(list.quadY(quad) - y) < EPSILON) {
                return quad;
            }
        }
        return -1;
    }
}
