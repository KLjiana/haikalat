package com.kaleblangley.haikalat.demo.gltf;

import com.kaleblangley.haikalat.runtime.DebugOverlaySnapshot;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.ui.UiConfig;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.ui.UiVisibility;
import com.kaleblangley.haikalat.subsystems.ui.style.UiInsets;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.ui.widget.ScrollView;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.Key;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;

import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.GLFW_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPos;
import static org.lwjgl.glfw.GLFW.glfwSetInputMode;

/** 独立 glTF Demo 的 retained 只读资产检查器。 */
final class GltfDemoOverlay implements AutoCloseable {
    private static final double MINIMUM_PAGE_HEIGHT = 64.0;

    private final GlfwWindow window;
    private final UiSystem ui;
    private final Panel inspector;
    private final Label telemetry;
    private final Label scrollStatus;
    private final ScrollView scroll;
    private boolean interactive;
    private int cameraResumeGuard;

    private GltfDemoOverlay(GlfwWindow window, RenderPipeline pipeline, List<String> lines) {
        this.window = window;
        ui = UiSystem.create(window, UiConfig.defaults());
        Panel root = ui.document().root();
        root.style(UiStyle.builder().width(UiLength.percent(100)).height(UiLength.percent(100))
                .padding(UiInsets.points(12)).alignItems(UiStyle.AlignItems.FLEX_START).build());
        inspector = new Panel();
        inspector.debugName("GltfDemoInspector");
        inspector.style(UiStyle.builder().width(UiLength.points(590)).height(UiLength.points(390))
                .padding(UiInsets.points(9)).gap(5)
                .flexDirection(UiStyle.FlexDirection.COLUMN).build());
        inspector.add(line("glTF inspector | F1 UI/相机 | F2 隐藏 | 滚轮或 PageUp/PageDown"));
        telemetry = line("FPS -- | CPU -- ms | GPU -- ms");
        inspector.add(telemetry);
        scrollStatus = line("Page 1/1 | Home/End 跳到首尾");
        inspector.add(scrollStatus);

        Panel content = createInspectionContent(lines);
        scroll = new ScrollView();
        scroll.debugName("GltfDemoInspectorScroll");
        scroll.style(UiStyle.builder().width(UiLength.percent(100)).height(UiLength.points(300))
                .flexShrink(1).build());
        scroll.content(content);
        inspector.add(scroll);
        root.add(inspector);
        ui.attachTo(pipeline.graph(), pipeline.finalPassName());
    }

    static GltfDemoOverlay attach(GlfwWindow window, RenderPipeline pipeline, List<String> lines) {
        return new GltfDemoOverlay(window, pipeline, List.copyOf(lines));
    }

    void update(WindowInputSnapshot input, float deltaSeconds, DebugOverlaySnapshot statistics) {
        if (input.keyPressed(Key.F1)) {
            setInteractive(!interactive, input.windowWidth(), input.windowHeight());
        }
        if (input.keyPressed(Key.F2)) {
            boolean visible = inspector.visibility() != UiVisibility.VISIBLE;
            inspector.visibility(visible ? UiVisibility.VISIBLE : UiVisibility.COLLAPSED);
            if (!visible) setInteractive(false, input.windowWidth(), input.windowHeight());
        }
        applyPageNavigation(input);
        telemetry.text(String.format(Locale.ROOT,
                "FPS %.1f | CPU %.3f ms | GPU %.3f ms | draw %d",
                statistics.fps(), statistics.cpuSubmitMillis(), statistics.gpuMillis(),
                statistics.drawCalls()));
        ui.update(input, deltaSeconds);
        updateScrollStatus();
    }

    /** @return 本帧是否允许相机消费 mouse delta 与移动键。 */
    boolean consumeCameraInputPermission() {
        if (interactive) return false;
        if (cameraResumeGuard > 0) {
            cameraResumeGuard--;
            return false;
        }
        return true;
    }

    @Override
    public void close() {
        if (interactive) glfwSetInputMode(window.handle(), GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        ui.close();
    }

    private void applyPageNavigation(WindowInputSnapshot input) {
        if (pressedOrRepeated(input, Key.HOME)) {
            scroll.scrollTo(scroll.scrollX(), 0.0);
        } else if (pressedOrRepeated(input, Key.END)) {
            scroll.scrollTo(scroll.scrollX(), scroll.maxScrollY());
        } else if (pressedOrRepeated(input, Key.PAGE_UP)) {
            scroll.scrollTo(scroll.scrollX(), scroll.scrollY() - pageStep());
        } else if (pressedOrRepeated(input, Key.PAGE_DOWN)) {
            scroll.scrollTo(scroll.scrollX(), scroll.scrollY() + pageStep());
        }
    }

    private double pageStep() {
        return Math.max(MINIMUM_PAGE_HEIGHT, scroll.layoutBox().height() * 0.85);
    }

    private void updateScrollStatus() {
        double viewport = Math.max(MINIMUM_PAGE_HEIGHT, scroll.layoutBox().height());
        int pages = Math.max(1, (int) Math.ceil((scroll.maxScrollY() + viewport) / viewport));
        int page = Math.min(pages, 1 + (int) Math.floor(scroll.scrollY() / viewport));
        scrollStatus.text(String.format(Locale.ROOT,
                "Page %d/%d | offset %.0f/%.0f | %s",
                page, pages, scroll.scrollY(), scroll.maxScrollY(),
                interactive ? "UI 模式" : "F1 启用鼠标滚动"));
    }

    private void setInteractive(boolean value, int width, int height) {
        if (interactive == value) return;
        interactive = value;
        glfwSetInputMode(window.handle(), GLFW_CURSOR,
                value ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
        if (value && width > 0 && height > 0) {
            glfwSetCursorPos(window.handle(), Math.min(width * 0.5, 300.0), height * 0.5);
        } else if (!value) {
            cameraResumeGuard = 2;
        }
    }

    private static boolean pressedOrRepeated(WindowInputSnapshot input, Key key) {
        return input.keyPressed(key) || input.keyRepeated(key);
    }

    private static Label line(String text) {
        Label label = new Label(text);
        label.style(UiStyle.builder().width(UiLength.percent(100))
                .height(UiLength.points(20)).flexShrink(0).build());
        return label;
    }

    /** 创建保持真实内容高度的滚动页，禁止 Yoga 把长资产树压缩进视口。 */
    static Panel createInspectionContent(List<String> lines) {
        Panel content = new Panel();
        content.style(UiStyle.builder().width(UiLength.percent(100))
                .height(UiLength.points(Math.max(24, lines.size() * 21 + 8)))
                .padding(UiInsets.points(3)).gap(1)
                .flexDirection(UiStyle.FlexDirection.COLUMN)
                .flexShrink(0).build());
        lines.forEach(text -> content.add(line(text)));
        return content;
    }
}
