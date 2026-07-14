package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.FrameBenchmarkSession;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.PeriodicTimer;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.lwjgl.opengl.GL;

import java.time.Duration;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

/** 不创建 shader、VAO、buffer、texture，也不发出 draw 命令的 clear/present 基线。 */
public final class EmptyWindowDemo {
    private EmptyWindowDemo() {
    }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        RenderSettings settings = RenderSettings.builder().vsync(options.vsync()).build();
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(1280, 720)
                .title("Haikalat Empty Window")
                .visible(!options.hidden())
                .cursorMode(GlfwWindow.CursorMode.NORMAL)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(options.vsync());

            try (FrameDriver driver = new FrameDriver(settings);
                 RenderGraph graph = createGraph(window)) {
                runLoop(window, driver, graph, options);
            }
        }
    }

    private static RenderGraph createGraph(GlfwWindow window) {
        RenderGraph graph = new RenderGraph(window.width(), window.height());
        graph.addPass("EmptyClear")
                .writeToBackbuffer()
                .noClear()
                .execute((resources, commands) -> commands
                        .clearColor(0.025f, 0.03f, 0.045f, 1.0f)
                        .clear(true, true)
                        .enableDepthTest(true)
                        .depthMask(true)
                        .enableCullFace(true));
        graph.compile();
        return graph;
    }

    private static void runLoop(GlfwWindow window, FrameDriver driver,
                                RenderGraph graph, Options options) {
        PeriodicTimer titleUpdate = new PeriodicTimer(Duration.ofMillis(250));
        FrameBenchmarkSession benchmark = new FrameBenchmarkSession(
                options.maxFrames(), options.warmupFrames());
        while (!window.shouldClose()) {
            if (window.consumeResize()) graph.resize(window.width(), window.height());
            driver.frame(graph);
            driver.present(window::swapBuffers);
            window.pollEvents();
            if (window.isKeyDown(GLFW_KEY_ESCAPE)) window.requestClose();

            benchmark.recordFrame(driver, graph.lastFrameProfile().totalGpuNanos());
            if (titleUpdate.poll()) {
                window.setTitle(formatStatistics(driver, benchmark.snapshot()));
            }
            if (benchmark.isComplete()) window.requestClose();
        }
        if (options.maxFrames() > 0) {
            System.out.println(formatStatistics(driver, benchmark.snapshot()));
        }
    }

    private static String formatStatistics(FrameDriver driver,
                                           FrameBenchmarkSession.Snapshot benchmark) {
        StateCache.Statistics state = driver.stateStatistics();
        long checks = state.appliedChanges() + state.avoidedChanges();
        double skip = checks == 0L ? 0.0 : state.avoidedChanges() * 100.0 / checks;
        var timings = benchmark.timings();
        return String.format("EmptyWindow | present FPS %.1f | "
                        + "CPU avg/median %.3f/%.3f ms | GPU avg/median %.3f/%.3f ms | "
                        + "draws 0 | state skip %.1f%% | VS invocations N/A",
                benchmark.presentFps(), timings.averageCpuMillis(), timings.medianCpuMillis(),
                timings.averageGpuMillis(), timings.medianGpuMillis(), skip);
    }

    private record Options(boolean hidden, boolean vsync, int maxFrames, int warmupFrames) {
        static Options parse(String[] args) {
            boolean hidden = false;
            boolean vsync = false;
            int maxFrames = -1;
            int warmupFrames = -1;
            for (String arg : args) {
                if (arg.startsWith("--frames=")) {
                    maxFrames = Integer.parseInt(arg.substring("--frames=".length()));
                } else if (arg.startsWith("--warmup=")) {
                    warmupFrames = Integer.parseInt(arg.substring("--warmup=".length()));
                } else if ("--hidden".equals(arg)) {
                    hidden = true;
                } else if ("--vsync".equals(arg)) {
                    vsync = true;
                } else if ("--deterministic".equals(arg)) {
                    hidden = true;
                    vsync = false;
                    if (maxFrames < 0) maxFrames = 3;
                } else {
                    throw new IllegalArgumentException("Unknown empty-window argument: " + arg);
                }
            }
            if (maxFrames == 0 || maxFrames < -1) {
                throw new IllegalArgumentException("--frames must be positive");
            }
            if (warmupFrames < -1) throw new IllegalArgumentException("--warmup must be non-negative");
            if (warmupFrames < 0) warmupFrames = maxFrames >= 120 ? 100 : 0;
            return new Options(hidden, vsync, maxFrames, warmupFrames);
        }
    }
}
