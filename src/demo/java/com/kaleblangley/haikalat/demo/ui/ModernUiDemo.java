package com.kaleblangley.haikalat.demo.ui;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.FrameClock;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.ui.UiConfig;
import com.kaleblangley.haikalat.subsystems.ui.UiFrameStats;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiAnimationSequence;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiAnimationSignal;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiAnimationTrigger;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiEasing;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiPropertyTrack;
import com.kaleblangley.haikalat.subsystems.ui.render.UiAttachmentOptions;
import com.kaleblangley.haikalat.subsystems.ui.render.UiBackdropSource;
import com.kaleblangley.haikalat.subsystems.ui.render.UiCompositor;
import com.kaleblangley.haikalat.subsystems.ui.render.UiLayerDescription;
import com.kaleblangley.haikalat.subsystems.ui.render.UiScreenRect;
import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;
import com.kaleblangley.haikalat.subsystems.ui.style.UiInsets;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.style.Theme;
import com.kaleblangley.haikalat.subsystems.ui.style.ThemeTokens;
import com.kaleblangley.haikalat.subsystems.ui.text.UiTextEngine;
import com.kaleblangley.haikalat.subsystems.ui.vfx.UiEffectDefinition;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.ArrayList;
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
 * 独立的 v0.19 UI 展示，不创建或调用旧版 UiDemo 场景。
 *
 * <p>场景刻意保持很小：一个 layer、两张卡片、一个按钮和少量文本，用来让
 * SDF、property timeline、interaction state、compositor topology 和 UI VFX 的启动
 * 成本可见且可控。</p>
 */
public final class ModernUiDemo {
    static final String PRESENT_PASS = "ModernUiPresent";
    private static final int DEFAULT_WIDTH = 800;
    private static final int DEFAULT_HEIGHT = 520;
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
                .title("Haikalat Modern UI")
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
                 UiSystem ui = UiSystem.create(window, compactConfig(options))) {
                Scene scene = Scene.install(ui, options);
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

    private static UiConfig compactConfig(Options options) {
        Theme baseTheme = Theme.dark();
        ThemeTokens tokens = baseTheme.tokens();
        Theme theme = new Theme(new ThemeTokens(tokens.surface(), tokens.surfaceHover(),
                tokens.surfacePressed(), tokens.accent(), tokens.text(),
                tokens.disabledText(), tokens.border(), tokens.spacing(),
                options.sdf() ? 18.0f : 0.0f,
                tokens.controlHeight(), tokens.fontSize(), UiTextEngine.MONOSPACE_FONT_FAMILY),
                baseTheme.reducedMotion());
        return UiConfig.builder()
                .theme(theme)
                .primitiveCapacity(256, 8_192)
                .glyphAtlas(512, 512, 2)
                .build();
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
                                        RenderGraph graph, UiSystem ui, Scene scene,
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
            if (options.deterministic() && frame > 0 && frame % 15 == 0) {
                scene.replay();
            }
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

    private static UiLayerDescription layer(int width, int height) {
        return UiLayerDescription.builder("modern_header",
                        new UiScreenRect(24.0, 24.0, Math.max(1.0, width - 48.0), 82.0))
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
                          boolean verifyPixels) {
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
            for (String argument : Objects.requireNonNull(arguments, "arguments")) {
                if (argument.equals("--deterministic")) deterministic = true;
                else if (argument.equals("--hidden")) hidden = true;
                else if (argument.equals("--no-vsync")) vsync = false;
                else if (argument.equals("--verify-pixels")) verifyPixels = true;
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
            return new Options(deterministic, hidden, vsync, frames, width, height, sdf,
                    animation, compositor, backdrop, uiVfx, effects, reducedMotion, verifyPixels);
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

        private boolean effectEnabled(String name) {
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

    private static final class Scene {
        private final UiSystem ui;
        private final Options options;
        private final com.kaleblangley.haikalat.subsystems.ui.widget.Label diagnostics;
        private final com.kaleblangley.haikalat.subsystems.ui.widget.Button action;
        private final com.kaleblangley.haikalat.subsystems.ui.widget.Label interactionState;
        private final Runnable replay;
        private String previousInteractionState = "";

        private Scene(UiSystem ui, Options options,
                      com.kaleblangley.haikalat.subsystems.ui.widget.Label diagnostics,
                      com.kaleblangley.haikalat.subsystems.ui.widget.Button action,
                      com.kaleblangley.haikalat.subsystems.ui.widget.Label interactionState,
                      Runnable replay) {
            this.ui = ui;
            this.options = options;
            this.diagnostics = diagnostics;
            this.action = action;
            this.interactionState = interactionState;
            this.replay = replay;
        }

        private static Scene install(UiSystem ui, Options options) {
            ui.selectFontFamily(UiTextEngine.MONOSPACE_FONT_FAMILY);
            var document = ui.document();
            var root = document.root();
            root.style(UiStyle.builder().width(UiLength.percent(100.0f))
                    .height(UiLength.percent(100.0f)).padding(UiInsets.points(24.0f))
                    .flexDirection(UiStyle.FlexDirection.COLUMN).gap(14.0f).build());

            var header = new com.kaleblangley.haikalat.subsystems.ui.widget.Panel();
            header.debugName("ModernHeader");
            header.style(UiStyle.builder().width(UiLength.percent(100.0f))
                    .height(UiLength.points(82.0f)).padding(UiInsets.points(14.0f))
                    .flexDirection(UiStyle.FlexDirection.COLUMN).gap(4.0f).build());
            header.layerDescription(layer(options.width(), options.height()));
            var title = new com.kaleblangley.haikalat.subsystems.ui.widget.Label(
                    "0.19 Modern UI / 现代界面");
            title.style(UiStyle.builder().height(UiLength.points(28.0f)).build());
            var subtitle = new com.kaleblangley.haikalat.subsystems.ui.widget.Label(
                    "SDF · Transform · Timeline · Compositor · Screen-space VFX");
            header.add(title).add(subtitle);

            var body = new com.kaleblangley.haikalat.subsystems.ui.widget.Panel();
            body.style(UiStyle.builder().width(UiLength.percent(100.0f)).flexGrow(1.0f)
                    .flexDirection(UiStyle.FlexDirection.ROW).gap(14.0f).build());
            var card = new com.kaleblangley.haikalat.subsystems.ui.widget.Panel();
            card.debugName("ModernCard");
            card.style(UiStyle.builder().width(UiLength.percent(62.0f)).height(UiLength.percent(100.0f))
                    .padding(UiInsets.points(18.0f)).flexDirection(UiStyle.FlexDirection.COLUMN)
                    .gap(10.0f).build());
            var cardTitle = new com.kaleblangley.haikalat.subsystems.ui.widget.Label(
                    "Analytic surfaces / 动态卡片");
            cardTitle.style(UiStyle.builder().height(UiLength.points(30.0f)).build());
            var cardText = new com.kaleblangley.haikalat.subsystems.ui.widget.Label(
                    "Hover or click the action button to emit a ripple and reward effect.");
            cardText.style(UiStyle.builder().flexGrow(1.0f).build());
            var action = new com.kaleblangley.haikalat.subsystems.ui.widget.Button(
                    "Play transition / 播放过渡");
            action.debugName("ModernAction");
            action.style(UiStyle.builder().width(UiLength.percent(100.0f))
                    .height(UiLength.points(42.0f)).build());
            card.add(cardTitle).add(cardText).add(action);

            var side = new com.kaleblangley.haikalat.subsystems.ui.widget.Panel();
            side.style(UiStyle.builder().flexGrow(1.0f).height(UiLength.percent(100.0f))
                    .padding(UiInsets.points(14.0f)).flexDirection(UiStyle.FlexDirection.COLUMN)
                    .gap(8.0f).build());
            side.add(new com.kaleblangley.haikalat.subsystems.ui.widget.Label(
                    "Interaction state / 交互状态"));
            var interactionState = new com.kaleblangley.haikalat.subsystems.ui.widget.Label(
                    "CURRENT · NORMAL  |  hover=0  pressed=0  focused=0");
            interactionState.style(UiStyle.builder().height(UiLength.points(30.0f)).build());
            side.add(interactionState);
            body.add(card).add(side);

            var diagnostics = new com.kaleblangley.haikalat.subsystems.ui.widget.Label(
                    "ModernUiDemo starting…");
            diagnostics.debugName("ModernDiagnostics");
            diagnostics.style(UiStyle.builder().width(UiLength.percent(100.0f))
                    .height(UiLength.points(24.0f)).build());
            root.add(header).add(body).add(diagnostics);

            ui.timeline().reducedMotion(options.reducedMotion());
            ui.effects().reducedMotion(options.reducedMotion())
                    .register(header).register(card).register(action);
            boolean shimmerEnabled = options.uiVfx() && options.effectEnabled("shimmer");
            boolean rippleEnabled = options.uiVfx() && options.effectEnabled("ripple");
            boolean confettiEnabled = options.uiVfx() && options.effectEnabled("confetti");
            UiAnimationSequence transition = transition(card, options.sdf() ? 18.0 : 0.0);
            long[] transitionSequence = {0L};
            if (options.animation()) {
                if (shimmerEnabled) {
                    ui.effects().bind(UiAnimationSignal.Type.START, "",
                            effect(UiEffectDefinition.Type.SHIMMER, 0x5348494d4d45524cL));
                }
                if (confettiEnabled) {
                    ui.effects().bindMarker("reward",
                            effect(UiEffectDefinition.Type.CONFETTI, 0x434f4e4645545449L));
                }
                transitionSequence[0] = ui.timeline().play(transition);
            }
            long[] effectSequence = {1L};
            if (rippleEnabled) {
                long initialSequence = transitionSequence[0] > 0L
                        ? transitionSequence[0] : effectSequence[0];
                ui.effects().start(effect(UiEffectDefinition.Type.RIPPLE, 0x524950504c45L),
                        action, initialSequence);
            }
            Runnable replay = () -> {
                long next;
                if (options.animation()) {
                    if (transitionSequence[0] > 0L) {
                        ui.timeline().cancel(transitionSequence[0]);
                    }
                    transitionSequence[0] = ui.timeline().play(transition);
                    next = transitionSequence[0];
                } else {
                    next = ++effectSequence[0];
                }
                if (rippleEnabled) {
                    ui.effects().start(effect(UiEffectDefinition.Type.RIPPLE, next),
                            action, next);
                }
                if (!options.animation() && confettiEnabled) {
                    ui.effects().start(effect(UiEffectDefinition.Type.CONFETTI, next),
                            card, next);
                }
            };
            action.onClick(replay);
            return new Scene(ui, options, diagnostics, action, interactionState, replay);
        }

        private static UiAnimationSequence transition(
                com.kaleblangley.haikalat.subsystems.ui.UiNode target, double baseRadius) {
            double peakRadius = baseRadius > 0.0 ? baseRadius + 12.0 : 0.0;
            return UiAnimationSequence.builder()
                    .then(target, 0.32f, UiEasing.EASE_OUT_CUBIC,
                            List.of(new UiAnimationTrigger(0.55f, "reward")),
                            UiPropertyTrack.numeric(UiPropertyTrack.Property.TRANSLATION_Y, 0.0, -10.0),
                            UiPropertyTrack.numeric(UiPropertyTrack.Property.SCALE_X, 1.0, 1.025),
                            UiPropertyTrack.numeric(UiPropertyTrack.Property.SCALE_Y, 1.0, 1.025),
                            UiPropertyTrack.numeric(UiPropertyTrack.Property.RADIUS,
                                    baseRadius, peakRadius))
                    .then(target, 0.52f, UiEasing.EASE_IN_OUT_CUBIC,
                            UiPropertyTrack.numeric(UiPropertyTrack.Property.TRANSLATION_Y, -10.0, 0.0),
                            UiPropertyTrack.numeric(UiPropertyTrack.Property.SCALE_X, 1.025, 1.0),
                            UiPropertyTrack.numeric(UiPropertyTrack.Property.SCALE_Y, 1.025, 1.0),
                            UiPropertyTrack.numeric(UiPropertyTrack.Property.RADIUS,
                                    peakRadius, baseRadius))
                    .build();
        }

        private static UiEffectDefinition effect(UiEffectDefinition.Type type, long seed) {
            var builder = UiEffectDefinition.builder(type)
                    .duration(type == UiEffectDefinition.Type.CONFETTI ? 1.6f : 1.0f)
                    .particleLifetime(type == UiEffectDefinition.Type.CONFETTI ? 1.6f : 1.0f)
                    .seed(seed)
                    .colors(UiColor.fromSrgbHex(0x7dd3fcff), UiColor.TRANSPARENT);
            if (type == UiEffectDefinition.Type.CONFETTI) builder.maximumParticles(48);
            return builder.build();
        }

        private void update(UiFrameStats stats, boolean updateStatistics) {
            String state = action.pressed() ? "PRESSED"
                    : action.hovered() ? "HOVERED"
                    : action.focused() ? "FOCUSED" : "NORMAL";
            if (!state.equals(previousInteractionState)) {
                previousInteractionState = state;
                interactionState.text("CURRENT · " + state
                        + "  |  hover=" + (action.hovered() ? "1" : "0")
                        + "  pressed=" + (action.pressed() ? "1" : "0")
                        + "  focused=" + (action.focused() ? "1" : "0"));
            }
            if (updateStatistics) {
                diagnostics.text(String.format(Locale.ROOT,
                        "nodes=%d  quads=%d  batches=%d  effects=%s",
                        stats.visibleNodes(), stats.quads(), stats.batches(), effectSummary()));
            }
        }

        private void replay() {
            replay.run();
        }

        private String effectSummary() {
            if (!options.uiVfx()) return "off";
            return ui.effects().diagnostics().activeEffects() + " active";
        }
    }

}
