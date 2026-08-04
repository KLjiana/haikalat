package com.kaleblangley.haikalat.demo.ui;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.FrameClock;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.ui.UiFrameStats;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.ui.render.UiAttachmentOptions;
import com.kaleblangley.haikalat.subsystems.ui.render.UiBackdropSource;
import com.kaleblangley.haikalat.subsystems.ui.render.UiCompositor;
import com.kaleblangley.haikalat.subsystems.ui.render.UiLayerDescription;
import com.kaleblangley.haikalat.subsystems.ui.render.UiScreenRect;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;

/**
 * 独立的现代游戏主界面，不创建或调用旧版 UiDemo 场景。
 *
 * <p>场景使用 retained layout、typed theme、SDF、timeline、compositor 与 UI VFX，
 * 同时提供战役总览和可交互设置页。</p>
 */
public final class ModernUiDemo {
    static final String PRESENT_PASS = "ModernUiPresent";
    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 720;
    private static final float FIXED_DELTA = 1.0f / 60.0f;

    private ModernUiDemo() {
    }

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        RunSummary summary = run(options);
        System.out.println(summary.format());
    }

    public static RunSummary run(Options options) {
        Objects.requireNonNull(options, "options");
        RenderSettings settings = RenderSettings.builder().vsync(options.vsync()).build();
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(options.width(), options.height())
                .title("Haikalat // Ashen Horizon")
                // Keep the native window hidden while font/shader/managed-target initialization runs.
                // Showing it only after the first graph is ready avoids a "frozen" blank window.
                .visible(false)
                .cursorMode(GlfwWindow.CursorMode.NORMAL)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(options.vsync());

            try (FrameDriver driver = new FrameDriver(settings);
                 RenderGraph graph = createGraph(window);
                 UiSystem ui = UiSystem.create(window, ModernGameTheme.config(options.sdf()))) {
                ModernGameMenuScene scene = ModernGameMenuScene.install(
                        ui, options, window::requestClose);
                if (options.compositor()) {
                    ui.attachTo(graph, PRESENT_PASS, UiAttachmentOptions.builder()
                            .enableCompositor(true)
                            .backdropSource(options.backdrop()
                                    ? UiBackdropSource.available("ModernUiSceneColor")
                                    : UiBackdropSource.unavailable())
                            .layers(List.of(layer(options.width(), options.height())))
                            .build());
                } else {
                    ui.attachTo(graph, PRESENT_PASS);
                }
                graph.compile();
                prewarm(ui, graph, driver, window);
                if (!options.hidden()) window.show();
                return runFrames(window, driver, graph, ui, scene, options);
            }
        }
    }

    /**
     * Forces first-use GL resources and initial glyph uploads while the native window is still
     * hidden. Without this frame the first visible present can include shader compilation and
     * persistent-buffer setup, which looks like a frozen window.
     */
    private static void prewarm(UiSystem ui, RenderGraph graph, FrameDriver driver,
                                GlfwWindow window) {
        ui.update(window.inputSnapshot(), 0.0f);
        driver.frame(graph);
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
                                        RenderGraph graph, UiSystem ui,
                                        ModernGameMenuScene scene,
                                        Options options) {
        FrameClock clock = new FrameClock();
        int frame = 0;
        int nonClearSamples = -1;
        UiFrameStats lastStats = UiFrameStats.EMPTY;
        long maxFrameNanos = 0L;
        long maxUiUpdateNanos = 0L;
        long maxPaintNanos = 0L;
        long maxRenderRecordNanos = 0L;
        long maxRingWaitNanos = 0L;
        long previousRingWaitNanos = 0L;
        int slowFrames = 0;
        while (!window.shouldClose()
                && (options.frames() <= 0 || frame < options.frames())) {
            long frameStartNanos = System.nanoTime();
            window.pollEvents();
            if (window.isKeyDown(GLFW_KEY_ESCAPE)) window.requestClose();
            if (window.consumeResize()) graph.resize(window.width(), window.height());
            WindowInputSnapshot input = window.inputSnapshot();
            float delta = options.deterministic() ? FIXED_DELTA : clock.tick().deltaSeconds();
            if (options.deterministic()) scene.runDeterministicScript(frame);
            ui.update(input, delta);
            if (options.deterministic()
                    && ui.effects().diagnostics().activeEffects() > 3) {
                throw new IllegalStateException(
                        "replayed transition accumulated more than three active effects");
            }
            lastStats = ui.statistics();
            scene.update(lastStats, (frame & 7) == 0);
            driver.frame(graph);
            if (options.verifyPixels() && options.frames() > 0
                    && frame + 1 == options.frames()) {
                nonClearSamples = countNonClearSamples(window.width(), window.height());
            }
            if (options.capturePath() != null && options.frames() > 0
                    && frame + 1 == options.frames()) {
                captureFrame(window.width(), window.height(), options.capturePath());
            }
            driver.present(window::swapBuffers);
            long frameNanos = System.nanoTime() - frameStartNanos;
            maxFrameNanos = Math.max(maxFrameNanos, frameNanos);
            maxUiUpdateNanos = Math.max(maxUiUpdateNanos, lastStats.uiUpdateNanos());
            maxPaintNanos = Math.max(maxPaintNanos, lastStats.paintNanos());
            if (frame > 0) {
                maxRenderRecordNanos = Math.max(maxRenderRecordNanos,
                        lastStats.renderRecordNanos());
            }
            long ringWaitNanos = lastStats.ringWaitNanos();
            maxRingWaitNanos = Math.max(maxRingWaitNanos,
                    Math.max(0L, ringWaitNanos - previousRingWaitNanos));
            previousRingWaitNanos = ringWaitNanos;
            if (frameNanos >= 25_000_000L) slowFrames++;
            if (!options.vsync()) {
                long remaining = 16_666_667L - frameNanos;
                if (remaining > 0L) LockSupport.parkNanos(remaining);
            }
            frame++;
        }
        if (options.verifyPixels() && nonClearSamples <= 0) {
            throw new IllegalStateException("ModernUiDemo framebuffer contains no UI pixels");
        }
        return new RunSummary(frame, nonClearSamples, lastStats, graph.width(), graph.height(),
                ui.compositorDiagnostics(), scene.effectSummary(), maxFrameNanos,
                maxUiUpdateNanos, maxPaintNanos, maxRenderRecordNanos, maxRingWaitNanos,
                slowFrames);
    }

    private static int countNonClearSamples(int width, int height) {
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
                if (Math.abs(Byte.toUnsignedInt(pixels.get(offset)) - baselineRed) > 2
                        || Math.abs(Byte.toUnsignedInt(pixels.get(offset + 1)) - baselineGreen) > 2
                        || Math.abs(Byte.toUnsignedInt(pixels.get(offset + 2)) - baselineBlue) > 2) {
                    changed++;
                }
            }
        }
        return changed;
    }

    private static void captureFrame(int width, int height, String capturePath) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(Math.multiplyExact(
                Math.multiplyExact(width, height), 4));
        glReadBuffer(GL_BACK);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                int red = Byte.toUnsignedInt(pixels.get(offset));
                int green = Byte.toUnsignedInt(pixels.get(offset + 1));
                int blue = Byte.toUnsignedInt(pixels.get(offset + 2));
                int alpha = Byte.toUnsignedInt(pixels.get(offset + 3));
                image.setRGB(x, height - 1 - y,
                        alpha << 24 | red << 16 | green << 8 | blue);
            }
        }
        Path output = Path.of(capturePath).toAbsolutePath().normalize();
        try {
            Path parent = output.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (!ImageIO.write(image, "png", output.toFile())) {
                throw new IllegalStateException("PNG writer is unavailable");
            }
        } catch (IOException failure) {
            throw new IllegalStateException("failed to capture ModernUiDemo to " + output,
                    failure);
        }
    }

    static UiLayerDescription layer(int width, int height) {
        return UiLayerDescription.builder("game_main_shell",
                        new UiScreenRect(24.0, 24.0,
                                Math.max(1.0, width - 48.0),
                                Math.max(1.0, height - 48.0)))
                .mask(UiLayerDescription.MaskMode.ROUNDED)
                .effect(UiLayerDescription.Effect.DROP_SHADOW)
                .effect(UiLayerDescription.Effect.GLOW)
                .blur(12.0f, 2)
                .cachePolicy(UiLayerDescription.CachePolicy.WHEN_CLEAN)
                .dirtyRevision(1L)
                .build();
    }

    public record RunSummary(int frames, int nonClearSamples, UiFrameStats statistics,
                             int width, int height,
                             UiCompositor.Diagnostics compositor,
                             String effects, long maxFrameNanos, long maxUiUpdateNanos,
                             long maxPaintNanos, long maxRenderRecordNanos,
                             long maxRingWaitNanos, int slowFrames) {
        public String format() {
            return String.format(Locale.ROOT,
                    "ModernUiDemo | frames %d | framebuffer %dx%d | nodes %d | quads %d | "
                            + "batches %d | draws %d | layers %d | effects %s | ring-wait %.2f ms "
                            + "| max-frame %.2f ms | slow>=25ms %d | ui %.2f ms | paint %.2f ms "
                            + "| record %.2f ms | ring-max %.2f ms | non-clear %s",
                    frames, width, height, statistics.visibleNodes(), statistics.quads(),
                    statistics.batches(), statistics.drawCalls(), compositor.activeLayers(),
                    effects, statistics.ringWaitNanos() / 1_000_000.0,
                    maxFrameNanos / 1_000_000.0, slowFrames,
                    maxUiUpdateNanos / 1_000_000.0, maxPaintNanos / 1_000_000.0,
                    maxRenderRecordNanos / 1_000_000.0, maxRingWaitNanos / 1_000_000.0,
                    nonClearSamples < 0 ? "not captured" : nonClearSamples);
        }
    }

    public record Options(boolean deterministic, boolean hidden, boolean vsync,
                          int frames, int width, int height, boolean sdf,
                          boolean animation, boolean compositor, boolean backdrop,
                          boolean uiVfx, String effects, boolean reducedMotion,
                          boolean verifyPixels, String capturePath) {
        public static Options parse(String... arguments) {
            boolean deterministic = false;
            boolean hidden = false;
            boolean vsync = true;
            int frames = -1;
            int width = DEFAULT_WIDTH;
            int height = DEFAULT_HEIGHT;
            boolean sdf = true;
            boolean animation = true;
            boolean compositor = true;
            boolean backdrop = false;
            boolean uiVfx = true;
            String effects = "all";
            boolean reducedMotion = false;
            boolean verifyPixels = false;
            String capturePath = null;
            for (String argument : Objects.requireNonNull(arguments, "arguments")) {
                if (argument.equals("--deterministic")) deterministic = true;
                else if (argument.equals("--hidden")) hidden = true;
                else if (argument.equals("--no-vsync")) vsync = false;
                else if (argument.equals("--verify-pixels")) verifyPixels = true;
                else if (argument.startsWith("--capture=")) {
                    capturePath = argument.substring("--capture=".length());
                    if (capturePath.isBlank()) {
                        throw new IllegalArgumentException("--capture path must not be blank");
                    }
                }
                else if (argument.startsWith("--frames=")) frames =
                        positive(argument.substring(9), "--frames");
                else if (argument.startsWith("--size=")) {
                    int[] size = parseSize(argument.substring(7));
                    width = size[0];
                    height = size[1];
                } else if (argument.startsWith("--sdf=")) sdf = toggle(argument, 6);
                else if (argument.startsWith("--animation=")) {
                    animation = toggle(argument, "--animation=".length());
                }
                else if (argument.startsWith("--compositor=")) compositor = toggle(argument, 13);
                else if (argument.startsWith("--backdrop=")) backdrop = toggle(argument, 11);
                else if (argument.startsWith("--ui-vfx=")) uiVfx = toggle(argument, 9);
                else if (argument.startsWith("--effects=")) effects = argument.substring(10);
                else if (argument.startsWith("--reduced-motion=")) {
                    reducedMotion = toggle(argument, "--reduced-motion=".length());
                } else {
                    throw new IllegalArgumentException("Unknown ModernUiDemo argument: " + argument);
                }
            }
            if (deterministic) {
                hidden = true;
                vsync = false;
                if (frames < 0) frames = 60;
                verifyPixels = true;
            }
            if (hidden && frames < 0) frames = 180;
            if (frames > 0) verifyPixels = verifyPixels || deterministic;
            if (capturePath != null && frames <= 0) {
                throw new IllegalArgumentException("--capture requires a positive --frames value");
            }
            return new Options(deterministic, hidden, vsync, frames, width, height, sdf,
                    animation, compositor, backdrop, uiVfx, effects, reducedMotion,
                    verifyPixels, capturePath);
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

        private static boolean toggle(String argument, int valueStart) {
            return switch (argument.substring(valueStart).toLowerCase(Locale.ROOT)) {
                case "on", "true" -> true;
                case "off", "false" -> false;
                default -> throw new IllegalArgumentException("expected on|off: " + argument);
            };
        }

        boolean effectEnabled(String name) {
            if (effects.equalsIgnoreCase("all")) {
                return true;
            }
            for (String effect : effects.split(",")) {
                if (effect.trim().equalsIgnoreCase(name)) {
                    return true;
                }
            }
            return false;
        }
    }

}
