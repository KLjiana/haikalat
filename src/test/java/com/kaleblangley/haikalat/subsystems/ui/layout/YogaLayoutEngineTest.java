package com.kaleblangley.haikalat.subsystems.ui.layout;

import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.UiVisibility;
import com.kaleblangley.haikalat.subsystems.ui.style.ComputedStyle;
import com.kaleblangley.haikalat.subsystems.ui.style.UiInsets;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.ui.widget.ListView;
import com.kaleblangley.haikalat.subsystems.ui.widget.ScrollView;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YogaLayoutEngineTest {
    private static final float EPSILON = 0.001f;

    @Test
    void rowMapsPaddingPercentMinMaxAndGrow() {
        Panel root = new Panel();
        borderless(root);
        root.style(UiStyle.builder()
                .flexDirection(UiStyle.FlexDirection.ROW)
                .padding(UiInsets.points(10.0f))
                .build());
        Panel bounded = new Panel();
        borderless(bounded);
        bounded.style(UiStyle.builder()
                .width(UiLength.percent(25.0f))
                .minWidth(UiLength.points(60.0f))
                .maxWidth(UiLength.points(80.0f))
                .height(UiLength.points(20.0f))
                .build());
        Panel growing = new Panel();
        borderless(growing);
        growing.style(UiStyle.builder()
                .height(UiLength.points(20.0f))
                .flexGrow(1.0f)
                .build());
        root.add(bounded).add(growing);

        try (YogaLayoutEngine engine = new YogaLayoutEngine()) {
            engine.layout(root, 200.0f, 50.0f);

            assertBox(root, 0.0f, 0.0f, 200.0f, 50.0f);
            assertBox(bounded, 10.0f, 10.0f, 60.0f, 20.0f);
            assertBox(growing, 70.0f, 10.0f, 120.0f, 20.0f);
        }
    }

    @Test
    void percentMarginAndComputedBorderUseTheYogaBoxModel() {
        Panel root = new Panel();
        ComputedStyle rootStyle = root.computedStyle();
        root.computedStyle(new ComputedStyle(rootStyle.background(), rootStyle.foreground(),
                rootStyle.borderColor(), 2.0f, rootStyle.radius(), rootStyle.opacity(),
                rootStyle.fontSize(), rootStyle.fontFamily()));
        root.style(UiStyle.builder()
                .flexDirection(UiStyle.FlexDirection.ROW)
                .alignItems(UiStyle.AlignItems.FLEX_START)
                .build());
        Panel child = sized(20.0f, 10.0f);
        child.style(UiStyle.builder()
                .width(UiLength.points(20.0f))
                .height(UiLength.points(10.0f))
                .margin(new UiInsets(UiLength.percent(10.0f), UiLength.points(0.0f),
                        UiLength.points(0.0f), UiLength.points(0.0f)))
                .build());
        root.add(child);

        try (YogaLayoutEngine engine = new YogaLayoutEngine()) {
            engine.layout(root, 200.0f, 50.0f);

            assertEquals(21.6f, child.layoutBox().x(), EPSILON,
                    "2px border 加内容宽度 196px 的 10% margin 应进入盒模型");
        }
    }

    @Test
    void rootAlwaysDefinesTheLogicalViewport() {
        Panel root = new Panel();
        borderless(root);
        root.style(UiStyle.builder()
                .width(UiLength.points(20.0f))
                .height(UiLength.percent(10.0f))
                .maxWidth(UiLength.points(25.0f))
                .build());

        try (YogaLayoutEngine engine = new YogaLayoutEngine()) {
            engine.layout(root, 321.5f, 123.25f);

            assertBox(root, 0.0f, 0.0f, 321.5f, 123.25f);
        }
    }

    @Test
    void rowShrinkSharesDeficitWithoutRoundingDrift() {
        Panel root = new Panel();
        borderless(root);
        root.style(UiStyle.builder()
                .flexDirection(UiStyle.FlexDirection.ROW)
                .build());
        Panel first = sized(80.0f, 20.0f);
        Panel second = sized(80.0f, 20.0f);
        root.add(first).add(second);

        try (YogaLayoutEngine engine = new YogaLayoutEngine()) {
            engine.layout(root, 99.5f, 20.0f);

            assertEquals(49.75f, first.layoutBox().width(), EPSILON);
            assertEquals(49.75f, second.layoutBox().width(), EPSILON);
            assertEquals(49.75f, second.layoutBox().x(), EPSILON);
            assertEquals(99.5f, second.layoutBox().right(), EPSILON);
        }
    }

    @Test
    void columnGrowAndHiddenCollapsedHaveDifferentLayoutSemantics() {
        Panel root = new Panel();
        borderless(root);
        root.style(UiStyle.builder()
                .flexDirection(UiStyle.FlexDirection.COLUMN)
                .alignItems(UiStyle.AlignItems.FLEX_START)
                .build());
        Panel hidden = sized(30.0f, 20.0f);
        hidden.visibility(UiVisibility.HIDDEN);
        Panel collapsed = sized(40.0f, 25.0f);
        collapsed.visibility(UiVisibility.COLLAPSED);
        Panel growing = new Panel();
        borderless(growing);
        growing.style(UiStyle.builder().width(UiLength.points(30.0f)).flexGrow(1.0f).build());
        root.add(hidden).add(collapsed).add(growing);

        try (YogaLayoutEngine engine = new YogaLayoutEngine()) {
            engine.layout(root, 100.0f, 80.0f);

            assertEquals(20.0f, hidden.layoutBox().height(), EPSILON,
                    "HIDDEN 节点仍应占据布局空间");
            assertEquals(0.0f, collapsed.layoutBox().width(), EPSILON);
            assertEquals(0.0f, collapsed.layoutBox().height(), EPSILON,
                    "COLLAPSED 节点不参与布局");
            assertBox(growing, 0.0f, 20.0f, 30.0f, 60.0f);
        }
    }

    @Test
    void overriddenLeafMeasureReceivesNeutralConstraints() {
        Panel root = new Panel();
        borderless(root);
        root.style(UiStyle.builder().alignItems(UiStyle.AlignItems.FLEX_START).build());
        AtomicInteger calls = new AtomicInteger();
        MeasuredNode measured = new MeasuredNode(calls);
        borderless(measured);
        root.add(measured);

        try (YogaLayoutEngine engine = new YogaLayoutEngine()) {
            engine.layout(root, 200.0f, 100.0f);

            assertTrue(calls.get() > 0);
            assertEquals(73.0f, measured.layoutBox().width(), EPSILON);
            assertEquals(19.0f, measured.layoutBox().height(), EPSILON);
            assertSame(MeasureContext.Mode.AT_MOST, measured.lastContext.heightMode());
        }
    }

    @Test
    void measureFailureIsRethrownAfterNativeLayoutWithNodeIdentity() {
        Panel root = new Panel();
        borderless(root);
        root.style(UiStyle.builder().alignItems(UiStyle.AlignItems.FLEX_START).build());
        FailingNode failing = new FailingNode();
        borderless(failing);
        failing.debugName("explosive-label");
        root.add(failing);

        try (YogaLayoutEngine engine = new YogaLayoutEngine()) {
            UiLayoutException exception = assertThrows(UiLayoutException.class,
                    () -> engine.layout(root, 100.0f, 50.0f));

            assertTrue(exception.getMessage().contains("nodeId=" + failing.id().value()));
            assertTrue(exception.getMessage().contains("explosive-label"));
            assertEquals("boom", exception.getCause().getMessage());
            assertEquals(LayoutBox.EMPTY, failing.layoutBox(),
                    "失败布局不得发布 Yoga 的部分结果");
        }
    }

    @Test
    void documentRootsShareOneEngineAndCloseIsIdempotent() {
        YogaLayoutEngine engine = new YogaLayoutEngine();
        try (UiDocument document = new UiDocument()) {
            document.root().add(new Panel());
            document.overlayRoot().add(new Panel());

            engine.layout(document, 320.0f, 180.0f);

            assertEquals(4, engine.managedNodeCount());
            assertBox(document.root(), 0.0f, 0.0f, 320.0f, 180.0f);
            assertBox(document.overlayRoot(), 0.0f, 0.0f, 320.0f, 180.0f);
        } finally {
            engine.close();
            engine.close();
        }
        assertEquals(0, engine.managedNodeCount());
        assertThrows(IllegalStateException.class,
                () -> engine.layout(new Panel(), 1.0f, 1.0f));
    }

    @Test
    void detachedSubtreeReleasesItsNativeNodesAndCanBeRecreated() {
        Panel root = new Panel();
        borderless(root);
        Panel parent = new Panel();
        Panel child = new Panel();
        root.add(parent);
        parent.add(child);

        try (YogaLayoutEngine engine = new YogaLayoutEngine()) {
            engine.layout(root, 100.0f, 100.0f);
            assertEquals(3, engine.managedNodeCount());

            root.remove(parent);
            engine.layout(root, 100.0f, 100.0f);
            assertEquals(1, engine.managedNodeCount(),
                    "detached subtree 应按 child 到 parent 的顺序从 native forest 清理");

            root.add(parent);
            engine.layout(root, 100.0f, 100.0f);
            assertEquals(3, engine.managedNodeCount());
        }
    }

    @Test
    void layoutPostProcessingClampsScrollAndStabilizesVirtualCells() {
        Panel root = new Panel();
        borderless(root);
        root.style(UiStyle.builder()
                .flexDirection(UiStyle.FlexDirection.COLUMN)
                .alignItems(UiStyle.AlignItems.FLEX_START)
                .build());
        ScrollView scroll = new ScrollView();
        borderless(scroll);
        scroll.style(UiStyle.builder()
                .width(UiLength.points(100.0f))
                .height(UiLength.points(80.0f))
                .flexShrink(0.0f)
                .build());
        Panel content = new Panel();
        borderless(content);
        content.style(UiStyle.builder()
                .width(UiLength.points(100.0f))
                .height(UiLength.points(200.0f))
                .flexShrink(0.0f)
                .build());
        scroll.content(content).scrollTo(0.0, 999.0);
        ListView list = new ListView();
        borderless(list);
        list.style(UiStyle.builder()
                .width(UiLength.points(200.0f))
                .height(UiLength.points(90.0f))
                .flexShrink(0.0f)
                .build());
        list.estimatedItemHeight(30.0f).model(1000, ignored -> sized(200.0f, 30.0f));
        root.add(scroll).add(list);

        try (YogaLayoutEngine engine = new YogaLayoutEngine()) {
            engine.layout(root, 300.0f, 300.0f);

            assertEquals(120.0, scroll.maxScrollY(), EPSILON);
            assertEquals(120.0, scroll.scrollY(), EPSILON);
            list.scrollTo(0.0, Double.MAX_VALUE);
            engine.layout(root, 300.0f, 300.0f);
            assertEquals(29_910.0, list.scrollY(), EPSILON);
            assertTrue(list.materializedIndices().contains(999));

            scroll.style(UiStyle.builder()
                    .width(UiLength.points(100.0f))
                    .height(UiLength.points(150.0f))
                    .flexShrink(0.0f)
                    .build());
            engine.layout(root, 300.0f, 300.0f);
            assertEquals(50.0, scroll.maxScrollY(), EPSILON);
            assertEquals(50.0, scroll.scrollY(), EPSILON);
        }
    }

    private static Panel sized(float width, float height) {
        Panel panel = new Panel();
        borderless(panel);
        panel.style(UiStyle.builder()
                .width(UiLength.points(width))
                .height(UiLength.points(height))
                .build());
        return panel;
    }

    private static void borderless(UiNode node) {
        ComputedStyle style = node.computedStyle();
        node.computedStyle(new ComputedStyle(style.background(), style.foreground(),
                style.borderColor(), 0.0f, style.radius(), style.opacity(),
                style.fontSize(), style.fontFamily()));
    }

    private static void assertBox(UiNode node, float x, float y, float width, float height) {
        assertEquals(x, node.layoutBox().x(), EPSILON, "x");
        assertEquals(y, node.layoutBox().y(), EPSILON, "y");
        assertEquals(width, node.layoutBox().width(), EPSILON, "width");
        assertEquals(height, node.layoutBox().height(), EPSILON, "height");
    }

    private static final class MeasuredNode extends UiNode {
        private final AtomicInteger calls;
        private MeasureContext lastContext;

        private MeasuredNode(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public MeasureResult measure(MeasureContext context) {
            calls.incrementAndGet();
            lastContext = context;
            return new MeasureResult(73.0f, 19.0f);
        }
    }

    private static final class FailingNode extends UiNode {
        @Override
        public MeasureResult measure(MeasureContext context) {
            throw new IllegalStateException("boom");
        }
    }
}
