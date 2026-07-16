package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.core.graph.FrameProfile;
import com.kaleblangley.haikalat.runtime.DebugOverlaySnapshot;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.render3d.LightType;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.ui.UiConfig;
import com.kaleblangley.haikalat.subsystems.ui.UiFrameStats;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.ui.style.UiInsets;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.ui.widget.Slider;
import com.kaleblangley.haikalat.subsystems.ui.widget.Toggle;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.Key;
import com.kaleblangley.haikalat.subsystems.windowing.input.MouseButton;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputCollector;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;

import java.nio.IntBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.lwjgl.glfw.GLFW.GLFW_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPos;
import static org.lwjgl.glfw.GLFW.glfwSetInputMode;

/**
 * LearnOpenGlDemo 的轻量 retained UI overlay。
 *
 * <p>UI 只追加到 {@link RenderPipeline#finalPassName()}，动态控件只修改不影响
 * pipeline topology 的点光源强度。F1 在相机捕获与 UI 交互间切换。</p>
 */
final class LearnOpenGlOverlay implements AutoCloseable {
    private final GlfwWindow window;
    private final RenderSettings settings;
    private final Scene scene;
    private final UiSystem ui;
    private final FilteredInput input = new FilteredInput();
    private final Label telemetry;
    private final Label modes;
    private final Label interactionHint;
    private final Label lightValue;
    private final Slider lightSlider;
    private final Toggle lightToggle;
    private final String dependencyPass;
    private final int dynamicLightIndex;
    private final float initialLightIntensity;
    private final boolean bloomTargetPreserved;
    private final boolean exposureTargetPreserved;
    private float requestedLightIntensity;
    private boolean interactive;
    private int cameraResumeGuard;
    private boolean closed;

    private LearnOpenGlOverlay(GlfwWindow window, RenderPipeline pipeline,
                               RenderSettings settings, UiSystem ui) {
        this.window = window;
        this.settings = settings;
        this.scene = pipeline.scene();
        this.ui = ui;
        dependencyPass = pipeline.finalPassName();

        boolean bloomBefore = pipeline.graph().hasPass(PostProcessTargets.BLOOM_EXTRACT_PASS);
        boolean exposureBefore = pipeline.graph().hasPass(
                PostProcessTargets.AUTO_EXPOSURE_LUMINANCE_PASS);

        Panel root = ui.document().root();
        root.style(UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.percent(100.0f))
                .padding(UiInsets.points(12.0f))
                .alignItems(UiStyle.AlignItems.FLEX_START)
                .build());
        Panel hud = new Panel();
        hud.debugName("LearnOpenGlHud");
        hud.style(UiStyle.builder()
                .width(UiLength.points(540.0f))
                .height(UiLength.points(188.0f))
                .padding(UiInsets.points(10.0f))
                .flexDirection(UiStyle.FlexDirection.COLUMN)
                .gap(6.0f)
                .build());

        telemetry = line("FPS -- | CPU -- ms | GPU -- ms");
        modes = line(formatModes(settings));
        interactionHint = line("");
        Label bilingual = line("Scene overlay is after final pass / 场景 UI 位于最终后处理之后");

        Panel controls = new Panel();
        controls.debugName("LearnOpenGlDynamicControls");
        controls.style(UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.points(42.0f))
                .padding(UiInsets.points(4.0f))
                .flexDirection(UiStyle.FlexDirection.ROW)
                .alignItems(UiStyle.AlignItems.CENTER)
                .gap(8.0f)
                .build());

        dynamicLightIndex = findDynamicLight(scene);
        initialLightIntensity = dynamicLightIndex < 0
                ? 0.0f : scene.lights().get(dynamicLightIndex).intensity();
        requestedLightIntensity = initialLightIntensity;
        float sliderMaximum = Math.max(4.0f, initialLightIntensity * 2.0f);
        lightSlider = new Slider(0.0, sliderMaximum, initialLightIntensity).step(0.05);
        lightSlider.debugName("LearnOpenGlFillLightSlider");
        lightSlider.style(UiStyle.builder()
                .width(UiLength.points(260.0f))
                .height(UiLength.points(30.0f))
                .build());
        lightValue = line(formatLightValue(initialLightIntensity));
        lightValue.style(UiStyle.builder()
                .width(UiLength.points(112.0f))
                .height(UiLength.points(26.0f))
                .build());
        lightToggle = new Toggle("Fill light / 填充光").value(dynamicLightIndex >= 0);
        lightToggle.style(UiStyle.builder()
                .width(UiLength.points(138.0f))
                .height(UiLength.points(32.0f))
                .padding(UiInsets.points(4.0f))
                .build());
        if (dynamicLightIndex < 0) {
            lightSlider.enabled(false);
            lightToggle.enabled(false);
            lightValue.text("Light N/A / 无动态灯");
        } else {
            lightSlider.onValueChanged(value -> {
                requestedLightIntensity = (float) value;
                if (lightToggle.value()) applyLightIntensity(requestedLightIntensity);
                lightValue.text(formatLightValue(requestedLightIntensity));
            });
            lightToggle.onValueChanged(enabled -> applyLightIntensity(
                    enabled ? requestedLightIntensity : 0.0f));
        }
        controls.add(lightSlider).add(lightValue).add(lightToggle);
        hud.add(telemetry).add(modes).add(interactionHint).add(bilingual).add(controls);
        root.add(hud);
        updateInteractionHint();

        ui.attachTo(pipeline.graph(), dependencyPass);
        bloomTargetPreserved = bloomBefore
                == pipeline.graph().hasPass(PostProcessTargets.BLOOM_EXTRACT_PASS);
        exposureTargetPreserved = exposureBefore
                == pipeline.graph().hasPass(PostProcessTargets.AUTO_EXPOSURE_LUMINANCE_PASS);
        if (!bloomTargetPreserved || !exposureTargetPreserved) {
            throw new IllegalStateException("UI attachment changed postprocess target topology");
        }
    }

    /** 创建并附加 overlay；部分初始化失败时关闭已创建的 UiSystem。 */
    static LearnOpenGlOverlay attach(GlfwWindow window, RenderPipeline pipeline,
                                     RenderSettings settings) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(settings, "settings");
        UiSystem ui = UiSystem.create(window, UiConfig.defaults());
        try {
            return new LearnOpenGlOverlay(window, pipeline, settings, ui);
        } catch (RuntimeException | Error failure) {
            try {
                ui.close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** 消费一次窗口输入并发布本帧 UI snapshot。 */
    void update(WindowInputSnapshot platformInput, float deltaSeconds,
                DebugOverlaySnapshot overlay, int frame, boolean deterministic) {
        ensureOpen();
        Objects.requireNonNull(platformInput, "platformInput");
        Objects.requireNonNull(overlay, "overlay");
        if (platformInput.keyPressed(Key.F1)) {
            setInteractive(!interactive, platformInput.windowWidth(), platformInput.windowHeight());
        }
        if (deterministic) applyDeterministicControls(frame);
        if (frame <= 1 || frame % 15 == 0) {
            telemetry.text(String.format(Locale.ROOT,
                    "FPS %.1f | CPU %.3f ms | GPU %.3f ms | draw %d | inst %d",
                    overlay.fps(), overlay.cpuSubmitMillis(), overlay.gpuMillis(),
                    overlay.drawCalls(), overlay.instanceCount()));
            modes.text(formatModes(settings));
        }
        ui.update(input.filter(platformInput, interactive), deltaSeconds);
    }

    /**
     * @return 本帧是否允许把 GLFW mouse delta/WASD 交给自由相机
     */
    boolean consumeCameraInputPermission() {
        if (interactive) return false;
        if (cameraResumeGuard > 0) {
            cameraResumeGuard--;
            return false;
        }
        return true;
    }

    boolean interactive() {
        return interactive;
    }

    String inputModeName() {
        return interactive ? "UI" : "CAMERA";
    }

    /** 汇总有限帧集成所需的 pass 顺序、动态参数和 target 不变量。 */
    Result result(FrameProfile profile) {
        ensureOpen();
        List<String> passes = profile.passes().stream().map(pass -> pass.passName()).toList();
        int dependencyIndex = passes.indexOf(dependencyPass);
        int uiIndex = passes.indexOf(UiSystem.OVERLAY_PASS_NAME);
        boolean finalOrder = dependencyIndex >= 0 && uiIndex > dependencyIndex
                && uiIndex == passes.size() - 1;
        UiFrameStats statistics = ui.statistics();
        float finalIntensity = dynamicLightIndex < 0
                ? 0.0f : scene.lights().get(dynamicLightIndex).intensity();
        return new Result(dependencyPass, finalOrder, bloomTargetPreserved,
                exposureTargetPreserved, statistics.drawCalls(), dynamicLightIndex,
                initialLightIntensity, finalIntensity, List.copyOf(passes));
    }

    @Override
    public void close() {
        if (closed) return;
        if (interactive) {
            glfwSetInputMode(window.handle(), GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            interactive = false;
        }
        ui.close();
        closed = true;
    }

    private void applyDeterministicControls(int frame) {
        if (dynamicLightIndex < 0) return;
        if (frame == 2) lightSlider.value(Math.max(0.1, initialLightIntensity * 0.65));
        if (frame == 4) lightToggle.value(false);
        if (frame == 6) lightToggle.value(true);
    }

    private void setInteractive(boolean value, int logicalWidth, int logicalHeight) {
        if (interactive == value) return;
        interactive = value;
        glfwSetInputMode(window.handle(), GLFW_CURSOR,
                value ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
        if (value && logicalWidth > 0 && logicalHeight > 0) {
            glfwSetCursorPos(window.handle(), logicalWidth * 0.5, logicalHeight * 0.5);
        } else if (!value) {
            cameraResumeGuard = 2;
        }
        updateInteractionHint();
    }

    private void updateInteractionHint() {
        interactionHint.text(interactive
                ? "F1: return to camera capture / 返回相机捕获（当前 UI 交互）"
                : "F1: enable UI interaction / 启用 UI 交互（当前相机捕获）");
    }

    private void applyLightIntensity(float intensity) {
        if (dynamicLightIndex < 0) return;
        SceneLight light = scene.lights().get(dynamicLightIndex);
        scene.setLight(dynamicLightIndex, new SceneLight(light.type(), light.color(), intensity,
                light.direction(), light.position(), light.range(), light.innerConeRadians(),
                light.outerConeRadians(), light.castShadows()));
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("LearnOpenGlOverlay is closed");
    }

    private static int findDynamicLight(Scene scene) {
        List<SceneLight> lights = scene.lights();
        for (int index = 0; index < lights.size(); index++) {
            if (lights.get(index).type() == LightType.POINT) return index;
        }
        return lights.isEmpty() ? -1 : 0;
    }

    private static Label line(String text) {
        Label label = new Label(text);
        label.style(UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.points(24.0f))
                .build());
        return label;
    }

    private static String formatModes(RenderSettings settings) {
        String exposure = settings.exposureMode().name();
        if (settings.exposureMode() == com.kaleblangley.haikalat.runtime.ExposureMode.MANUAL) {
            exposure += String.format(Locale.ROOT, " %.2f", settings.exposure());
        }
        return "AA " + settings.antiAliasingMode()
                + " | Bloom " + (settings.bloomSettings().enabled() ? "ON" : "OFF")
                + " | Exposure " + exposure + " / 曝光";
    }

    private static String formatLightValue(float intensity) {
        return String.format(Locale.ROOT, "Light %.2f / 光 %.2f", intensity, intensity);
    }

    /** 有限帧 overlay 集成结果。 */
    record Result(String dependencyPass, boolean finalPassOrderCorrect,
                  boolean bloomTargetPreserved, boolean exposureTargetPreserved,
                  long uiDrawCalls, int dynamicLightIndex,
                  float initialLightIntensity, float finalLightIntensity,
                  List<String> passOrder) {
    }

    /** 为相机模式屏蔽 UI 交互，同时持续发布严格递增的尺寸快照。 */
    private static final class FilteredInput {
        private final WindowInputCollector collector = new WindowInputCollector();

        WindowInputSnapshot filter(WindowInputSnapshot source, boolean interactive) {
            collector.windowSize(source.windowWidth(), source.windowHeight());
            collector.framebufferSize(source.framebufferWidth(), source.framebufferHeight());
            collector.contentScale(source.contentScaleX(), source.contentScaleY());
            collector.cursorPosition(source.cursorX(), source.cursorY());
            collector.cursorInside(interactive && source.cursorInside());
            collector.focused(interactive && source.focused());
            if (!interactive || !source.focused()) {
                collector.clearComposition();
                return collector.snapshot();
            }

            if (source.scrollX() != 0.0 || source.scrollY() != 0.0) {
                collector.scroll(source.scrollX(), source.scrollY());
            }
            for (Key key : Key.values()) {
                if (source.keyPressed(key)) collector.pressKey(key, source.modifiers());
                if (source.keyReleased(key)) collector.releaseKey(key, source.modifiers());
            }
            for (MouseButton button : MouseButton.values()) {
                if (source.mousePressed(button)) collector.pressMouse(button, source.modifiers());
                if (source.mouseReleased(button)) collector.releaseMouse(button, source.modifiers());
            }
            IntBuffer committed = source.committedCodePoints();
            while (committed.hasRemaining()) collector.committedCodePoint(committed.get());
            source.composition().ifPresentOrElse(collector::composition, collector::clearComposition);
            return collector.snapshot();
        }
    }
}
