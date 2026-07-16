package com.kaleblangley.haikalat.demo.ui;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.FrameClock;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.ui.UiConfig;
import com.kaleblangley.haikalat.subsystems.ui.UiFrameStats;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.ui.text.UiTextEngine;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;

/**
 * v0.11 retained-mode UI 的交互式与确定性集成 Demo。
 *
 * <p>场景只使用引擎窗口、RenderDevice/RenderGraph 和 UiSystem；Present pass 负责清屏，
 * UI 通过正式 backbuffer overlay pass 追加到同一张 graph。</p>
 */
public final class UiDemo {
    static final String PRESENT_PASS = "Present";
    static final int DEFAULT_WIDTH = 960;
    static final int DEFAULT_HEIGHT = 600;
    static final String OPTIONAL_UNIFONT = "/ui/fonts/unifont-17.0.05.otf";
    private static final float DETERMINISTIC_DELTA_SECONDS = 1.0f / 60.0f;

    private UiDemo() {
    }

    public static void main(String[] arguments) {
        RunSummary result = run(UiDemoOptions.parse(arguments));
        System.out.println(result.format());
    }

    /**
     * 启动一个独立窗口，并在关闭前返回最后一帧的可验证摘要。
     */
    public static RunSummary run(UiDemoOptions options) {
        RenderSettings settings = RenderSettings.builder().vsync(options.vsync()).build();
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(DEFAULT_WIDTH, DEFAULT_HEIGHT)
                .title("Haikalat UiDemo")
                .visible(!options.hidden())
                .cursorMode(GlfwWindow.CursorMode.NORMAL)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(options.vsync());

            try (FrameDriver driver = new FrameDriver(settings);
                 RenderGraph graph = createGraph(window);
                 UiSystem ui = UiSystem.create(window, UiConfig.defaults())) {
                registerOptionalFonts(ui);
                ui.attachTo(graph, PRESENT_PASS);
                graph.compile();
                try (UiDemoScene scene = UiDemoScene.install(
                        ui.document(), window.clipboardService(), ui)) {
                    return runFrames(window, driver, graph, ui, scene, options);
                }
            }
        }
    }

    private static void registerOptionalFonts(UiSystem ui) {
        try (InputStream input = UiTextEngine.class.getResourceAsStream(OPTIONAL_UNIFONT)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled UI font " + OPTIONAL_UNIFONT);
            }
            ui.registerFont("Unifont", input.readAllBytes());
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to load bundled UI font "
                    + OPTIONAL_UNIFONT, failure);
        }
    }

    private static RenderGraph createGraph(GlfwWindow window) {
        RenderGraph graph = new RenderGraph(window.width(), window.height());
        graph.addPass(PRESENT_PASS)
                .writeToBackbuffer()
                .noClear()
                .execute((resources, commands) -> commands
                        .enableBlend(false)
                        .enableDepthTest(false)
                        .enableCullFace(false)
                        .clearColor(0.025f, 0.035f, 0.055f, 1.0f)
                        .clear(true, false));
        return graph;
    }

    private static RunSummary runFrames(GlfwWindow window, FrameDriver driver,
                                        RenderGraph graph, UiSystem ui, UiDemoScene scene,
                                        UiDemoOptions options) {
        FrameClock clock = new FrameClock();
        UiDemoInput scaledInput = options.contentScale() == null ? null : new UiDemoInput();
        UiFrameStats lastStatistics = UiFrameStats.EMPTY;
        long totalInputEvents = 0L;
        long totalDispatchedEvents = 0L;
        int frame = 0;
        int nonClearSamples = -1;

        while (!window.shouldClose()) {
            applyScheduledResize(window, options, frame);
            window.pollEvents();
            if (window.isKeyDown(GLFW_KEY_ESCAPE)) window.requestClose();
            if (window.consumeResize()) graph.resize(window.width(), window.height());

            WindowInputSnapshot platformInput = window.inputSnapshot();
            verifyCompletedResize(window, graph, options, platformInput, frame);
            WindowInputSnapshot uiInput = scaledInput == null ? platformInput
                    : scaledInput.adapt(platformInput, options.contentScale());
            if (options.scriptMode() == UiDemoOptions.ScriptMode.BUILTIN) {
                scene.applyBuiltinScript(frame);
            }
            if (frame <= 1 || frame % 30 == 0) {
                scene.updateStatistics(lastStatistics);
            }
            float delta = options.deterministic()
                    ? DETERMINISTIC_DELTA_SECONDS : clock.tick().deltaSeconds();
            ui.update(uiInput, delta);
            scene.refreshVirtualizedContent();

            driver.frame(graph);
            lastStatistics = ui.statistics();
            totalInputEvents += lastStatistics.inputEvents();
            totalDispatchedEvents += lastStatistics.dispatchedEvents();
            boolean finalFiniteFrame = options.maximumFrames() > 0
                    && frame + 1 >= options.maximumFrames();
            if (options.verifyPixels() && finalFiniteFrame) {
                nonClearSamples = countNonClearSamples(window.width(), window.height());
            }
            if ((frame & 15) == 0) {
                UiNode focused = ui.document().focusManager().focused();
                UiNode hit = platformInput.focused() && platformInput.cursorInside()
                        ? ui.document().hitTest(platformInput.cursorX(), platformInput.cursorY())
                        : null;
                window.setTitle(String.format(Locale.ROOT,
                        "Haikalat UiDemo | input %d/%d | %s/%s | cursor %.0f,%.0f"
                                + " | hit %s | focus %s",
                        totalInputEvents, totalDispatchedEvents,
                        platformInput.focused() ? "focused" : "blurred",
                        platformInput.cursorInside() ? "inside" : "outside",
                        platformInput.cursorX(), platformInput.cursorY(), focusName(hit),
                        focusName(focused)));
            }
            driver.present(window::swapBuffers);
            frame++;
            if (finalFiniteFrame) window.requestClose();
        }

        if (options.verifyPixels() && nonClearSamples <= 0) {
            throw new IllegalStateException(
                    "UiDemo deterministic framebuffer contains no UI pixels");
        }
        return new RunSummary(frame, nonClearSamples, lastStatistics,
                graph.width(), graph.height());
    }

    private static String focusName(UiNode node) {
        if (node == null) return "none";
        return node.debugName().isBlank() ? node.widgetType() : node.debugName();
    }

    private static void applyScheduledResize(GlfwWindow window, UiDemoOptions options, int frame) {
        for (UiDemoOptions.ResizeStep resize : options.resizeSteps()) {
            if (resize.frame() == frame) window.resize(resize.width(), resize.height());
        }
    }

    private static void verifyCompletedResize(GlfwWindow window, RenderGraph graph,
                                              UiDemoOptions options,
                                              WindowInputSnapshot input, int frame) {
        for (UiDemoOptions.ResizeStep resize : options.resizeSteps()) {
            if (frame != resize.frame() + 1) continue;
            if (input.windowWidth() != resize.width() || input.windowHeight() != resize.height()) {
                throw new IllegalStateException("UiDemo resize did not reach logical "
                        + resize.width() + "x" + resize.height());
            }
            if (graph.width() != window.width() || graph.height() != window.height()) {
                throw new IllegalStateException("UiDemo RenderGraph did not follow framebuffer resize");
            }
        }
    }

    private static int countNonClearSamples(int width, int height) {
        if (width <= 0 || height <= 0) return 0;
        ByteBuffer pixels = BufferUtils.createByteBuffer(Math.multiplyExact(
                Math.multiplyExact(width, height), 4));
        glReadBuffer(GL_BACK);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        int baselineRed = Byte.toUnsignedInt(pixels.get(0));
        int baselineGreen = Byte.toUnsignedInt(pixels.get(1));
        int baselineBlue = Byte.toUnsignedInt(pixels.get(2));
        int stepX = Math.max(1, width / 64);
        int stepY = Math.max(1, height / 64);
        int changed = 0;
        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                int offset = (y * width + x) * 4;
                int red = Byte.toUnsignedInt(pixels.get(offset));
                int green = Byte.toUnsignedInt(pixels.get(offset + 1));
                int blue = Byte.toUnsignedInt(pixels.get(offset + 2));
                if (Math.abs(red - baselineRed) > 2
                        || Math.abs(green - baselineGreen) > 2
                        || Math.abs(blue - baselineBlue) > 2) {
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * 一次有限运行的结构、像素和最终尺寸摘要。
     */
    public record RunSummary(int renderedFrames, int nonClearSamples,
                             UiFrameStats statistics,
                             int framebufferWidth, int framebufferHeight) {
        public String format() {
            return String.format(Locale.ROOT,
                    "UiDemo | frames %d | framebuffer %dx%d | nodes %d | quads %d | "
                            + "batches %d | draws %d | non-clear samples %s",
                    renderedFrames, framebufferWidth, framebufferHeight,
                    statistics.visibleNodes(), statistics.quads(), statistics.batches(),
                    statistics.drawCalls(), nonClearSamples < 0 ? "not captured" : nonClearSamples);
        }
    }
}
