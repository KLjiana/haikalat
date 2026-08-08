package com.kaleblangley.haikalat.demo.ui;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.FrameClock;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.ui.UiFrameStats;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Objects;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;

/** Standalone launcher for {@link MarkdownTextDemo}. */
public final class MarkdownTextDemoMain {
    private static final String PRESENT_PASS = "MarkdownTextPresent";
    private static final int DEFAULT_WIDTH = 1180;
    private static final int DEFAULT_HEIGHT = 760;
    private static final float FIXED_DELTA_SECONDS = 1.0f / 60.0f;

    private MarkdownTextDemoMain() {
    }

    public static void main(String[] arguments) {
        RunSummary summary = run(Options.parse(arguments));
        System.out.println(summary.format());
    }

    public static RunSummary run(Options options) {
        Objects.requireNonNull(options, "options");
        RenderSettings settings = RenderSettings.builder().vsync(options.vsync()).build();
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(options.width(), options.height())
                .title("Haikalat // Markdown and Text Effects")
                .visible(false)
                .cursorMode(GlfwWindow.CursorMode.NORMAL)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(options.vsync());

            try (FrameDriver driver = new FrameDriver(settings);
                 RenderGraph graph = createGraph(window);
                 UiSystem ui = UiSystem.create(window, MarkdownTextDemo.config())) {
                MarkdownTextDemo scene = MarkdownTextDemo.install(ui);
                ui.attachTo(graph, PRESENT_PASS);
                graph.compile();
                ui.update(window.inputSnapshot(), 0.0f);
                driver.frame(graph);
                if (!options.hidden()) window.show();
                return runFrames(window, driver, graph, ui, scene, options);
            }
        }
    }

    private static RenderGraph createGraph(GlfwWindow window) {
        RenderGraph graph = new RenderGraph(window.width(), window.height());
        graph.addPass(PRESENT_PASS).writeToBackbuffer().noClear()
                .execute((resources, commands) -> commands
                        .enableBlend(false).enableDepthTest(false).enableCullFace(false)
                        .clearColor(0.018f, 0.028f, 0.040f, 1.0f)
                        .clear(true, false));
        return graph;
    }

    private static RunSummary runFrames(GlfwWindow window, FrameDriver driver,
                                         RenderGraph graph, UiSystem ui,
                                         MarkdownTextDemo scene, Options options) {
        FrameClock clock = new FrameClock();
        UiFrameStats lastStats = UiFrameStats.EMPTY;
        long maxFrameNanos = 0L;
        int frame = 0;
        int nonClearSamples = -1;
        while (!window.shouldClose()
                && (options.frames() <= 0 || frame < options.frames())) {
            long started = System.nanoTime();
            window.pollEvents();
            if (window.isKeyDown(GLFW_KEY_ESCAPE)) window.requestClose();
            if (window.consumeResize()) graph.resize(window.width(), window.height());
            WindowInputSnapshot input = window.inputSnapshot();
            float delta = options.deterministic() ? FIXED_DELTA_SECONDS : clock.tick().deltaSeconds();
            if (options.deterministic()) scene.applyDeterministicScript(frame);
            scene.update(delta, lastStats);
            ui.update(input, delta);
            driver.frame(graph);
            lastStats = ui.statistics();
            if (options.verifyPixels() && options.frames() > 0 && frame + 1 == options.frames()) {
                if (options.width() >= DEFAULT_WIDTH && options.height() >= DEFAULT_HEIGHT) {
                    scene.verifyDefaultLayout();
                }
                nonClearSamples = countNonClearSamples(window.width(), window.height());
            }
            driver.present(window::swapBuffers);
            maxFrameNanos = Math.max(maxFrameNanos, System.nanoTime() - started);
            frame++;
        }
        if (options.verifyPixels() && nonClearSamples <= 0) {
            throw new IllegalStateException("MarkdownTextDemo framebuffer contains no UI pixels");
        }
        return new RunSummary(frame, nonClearSamples, lastStats, graph.width(), graph.height(),
                maxFrameNanos, ui.activeFontFamily());
    }

    private static int countNonClearSamples(int width, int height) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(Math.multiplyExact(
                Math.multiplyExact(width, height), 4));
        glReadBuffer(GL_BACK);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        int red = Byte.toUnsignedInt(pixels.get(0));
        int green = Byte.toUnsignedInt(pixels.get(1));
        int blue = Byte.toUnsignedInt(pixels.get(2));
        int changed = 0;
        int stepX = Math.max(1, width / 64);
        int stepY = Math.max(1, height / 64);
        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                int offset = (y * width + x) * 4;
                if (Math.abs(Byte.toUnsignedInt(pixels.get(offset)) - red) > 2
                        || Math.abs(Byte.toUnsignedInt(pixels.get(offset + 1)) - green) > 2
                        || Math.abs(Byte.toUnsignedInt(pixels.get(offset + 2)) - blue) > 2) {
                    changed++;
                }
            }
        }
        return changed;
    }

    public record RunSummary(int renderedFrames, int nonClearSamples,
                             UiFrameStats statistics, int width, int height,
                             long maxFrameNanos, String fontFamily) {
        public String format() {
            return String.format(Locale.ROOT,
                    "MarkdownTextDemo | frames %d | framebuffer %dx%d | nodes %d | glyphs %d | "
                            + "quads %d | draws %d | font %s | max-frame %.2f ms | non-clear %s",
                    renderedFrames, width, height, statistics.visibleNodes(), statistics.glyphs(),
                    statistics.quads(), statistics.drawCalls(), fontFamily,
                    maxFrameNanos / 1_000_000.0,
                    nonClearSamples < 0 ? "not captured" : nonClearSamples);
        }
    }

    public record Options(boolean deterministic, boolean hidden, boolean vsync,
                          int frames, int width, int height, boolean verifyPixels) {
        public static Options parse(String... arguments) {
            boolean deterministic = false;
            boolean hidden = false;
            boolean vsync = true;
            boolean verifyPixels = false;
            int frames = -1;
            int width = DEFAULT_WIDTH;
            int height = DEFAULT_HEIGHT;
            for (String argument : Objects.requireNonNull(arguments, "arguments")) {
                if (argument.equals("--deterministic")) deterministic = true;
                else if (argument.equals("--hidden")) hidden = true;
                else if (argument.equals("--no-vsync")) vsync = false;
                else if (argument.equals("--verify-pixels")) verifyPixels = true;
                else if (argument.startsWith("--frames=")) frames = positive(argument.substring(9), "--frames");
                else if (argument.startsWith("--size=")) {
                    int[] size = parseSize(argument.substring(7));
                    width = size[0];
                    height = size[1];
                } else {
                    throw new IllegalArgumentException("Unknown MarkdownTextDemo argument: " + argument);
                }
            }
            if (deterministic) {
                hidden = true;
                vsync = false;
                verifyPixels = true;
                if (frames < 0) frames = 60;
            }
            if (hidden && frames < 0) frames = 180;
            return new Options(deterministic, hidden, vsync, frames, width, height, verifyPixels);
        }

        private static int positive(String value, String name) {
            try {
                int result = Integer.parseInt(value);
                if (result <= 0) throw new NumberFormatException();
                return result;
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(name + " must be positive", failure);
            }
        }

        private static int[] parseSize(String value) {
            String[] parts = value.toLowerCase(Locale.ROOT).split("x", -1);
            if (parts.length != 2) throw new IllegalArgumentException("--size expects WIDTHxHEIGHT");
            return new int[]{positive(parts[0], "width"), positive(parts[1], "height")};
        }
    }
}
