package com.kaleblangley.haikalat.demo.ui;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.ui.UiConfig;
import com.kaleblangley.haikalat.subsystems.ui.UiFrameStats;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.UnavailableTextInputAdapter;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputCollector;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;
import org.lwjgl.opengl.GL;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 在独立 update 线程构建 UI snapshot、在窗口线程消费并渲染的确定性集成入口。
 *
 * <p>每个 render frame 前连续发布三张 snapshot，故三槽 exchange 必须按 latest-wins
 * 丢弃旧快照。退出时先在 render/context 线程释放 GPU 资源，再回到 update owner 线程
 * 关闭 retained tree、字体原生资源和 snapshot exchange。</p>
 */
public final class UiAsyncIntegration {
    private static final int WIDTH = 720;
    private static final int HEIGHT = 480;
    private static final int FRAMES = 12;
    private static final int UPDATES_PER_FRAME = 3;
    private static final float DELTA_SECONDS = 1.0f / 180.0f;

    private UiAsyncIntegration() {
    }

    public static void main(String[] arguments) {
        System.out.println(run().format());
    }

    /** 执行有限帧异步集成，并在所有 owner-thread 资源关闭后返回摘要。 */
    public static RunSummary run() {
        ExecutorService updateExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ui-integration-update");
            thread.setDaemon(false);
            return thread;
        });
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(WIDTH, HEIGHT)
                .title("Haikalat UiAsyncIntegration")
                .visible(false)
                .cursorMode(GlfwWindow.CursorMode.NORMAL)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(false);

            try (FrameDriver driver = new FrameDriver(
                    RenderSettings.builder().vsync(false).build());
                 RenderGraph graph = createGraph(window)) {
                UiWorker worker = call(updateExecutor, () -> UiWorker.create(window, graph));
                try {
                    graph.compile();
                    UiFrameStats statistics = UiFrameStats.EMPTY;
                    for (int frame = 0; frame < FRAMES; frame++) {
                        window.pollEvents();
                        WindowInputSnapshot input = window.inputSnapshot();
                        int currentFrame = frame;
                        statistics = call(updateExecutor,
                                () -> worker.update(input, currentFrame));
                        driver.frame(graph);
                        driver.present(window::swapBuffers);
                    }

                    UiFrameStats renderedStatistics = worker.ui().statistics();
                    long published = worker.ui().publishedSnapshotCount();
                    long dropped = worker.ui().droppedSnapshotCount();
                    verify(renderedStatistics, published, dropped);
                    return new RunSummary(FRAMES, published, dropped, renderedStatistics);
                } finally {
                    RuntimeException failure = null;
                    try {
                        worker.ui().closeRenderResources();
                    } catch (RuntimeException closeFailure) {
                        failure = closeFailure;
                    }
                    try {
                        call(updateExecutor, () -> {
                            worker.close();
                            return null;
                        });
                    } catch (RuntimeException closeFailure) {
                        if (failure == null) failure = closeFailure;
                        else failure.addSuppressed(closeFailure);
                    }
                    if (failure != null) throw failure;
                }
            }
        } finally {
            updateExecutor.shutdown();
            try {
                if (!updateExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    updateExecutor.shutdownNow();
                    throw new IllegalStateException("UI update executor did not terminate");
                }
            } catch (InterruptedException interrupted) {
                updateExecutor.shutdownNow();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while closing UI update executor",
                        interrupted);
            }
        }
    }

    private static RenderGraph createGraph(GlfwWindow window) {
        RenderGraph graph = new RenderGraph(window.width(), window.height());
        graph.addPass(UiDemo.PRESENT_PASS)
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

    private static void verify(UiFrameStats statistics, long published, long dropped) {
        long expectedPublications = (long) FRAMES * UPDATES_PER_FRAME;
        if (published != expectedPublications) {
            throw new IllegalStateException("Expected " + expectedPublications
                    + " async UI snapshots, received " + published);
        }
        if (dropped < (long) FRAMES * (UPDATES_PER_FRAME - 1)) {
            throw new IllegalStateException("Async latest-wins path did not drop stale snapshots: "
                    + dropped);
        }
        if (statistics.glyphs() <= 0 || statistics.drawCalls() <= 0
                || statistics.glyphAtlasPages() <= 0) {
            throw new IllegalStateException("Async UI did not render real atlas-backed text: "
                    + statistics);
        }
    }

    private static <T> T call(ExecutorService executor, Callable<T> operation) {
        Future<T> future = executor.submit(operation);
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for UI update thread",
                    interrupted);
        } catch (ExecutionException executionFailure) {
            Throwable cause = executionFailure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("UI update thread failed", cause);
        }
    }

    /** 异步 producer/consumer 验收摘要。 */
    public record RunSummary(int renderedFrames, long publishedSnapshots,
                             long droppedSnapshots, UiFrameStats statistics) {
        public String format() {
            return String.format(Locale.ROOT,
                    "UiAsyncIntegration | frames %d | published %d | dropped %d | "
                            + "glyphs %d | atlas pages %d | draws %d",
                    renderedFrames, publishedSnapshots, droppedSnapshots,
                    statistics.glyphs(), statistics.glyphAtlasPages(),
                    statistics.drawCalls());
        }
    }

    /** 只允许由单线程 executor 调用的 mutable UI owner。 */
    private record UiWorker(UiSystem ui, UiDemoScene scene,
                            WindowInputCollector inputCollector) implements AutoCloseable {
        private static UiWorker create(GlfwWindow window, RenderGraph graph) {
            UiConfig config = UiConfig.builder()
                    .snapshotSlots(3)
                    .asynchronousSnapshots(true)
                    .build();
            UiSystem ui = UiSystem.create(window, config,
                    new UnavailableTextInputAdapter("deterministic async integration"));
            try {
                ui.attachTo(graph, UiDemo.PRESENT_PASS);
                return new UiWorker(ui, UiDemoScene.install(ui.document(), null),
                        new WindowInputCollector());
            } catch (RuntimeException | Error failure) {
                ui.close();
                throw failure;
            }
        }

        private UiFrameStats update(WindowInputSnapshot input, int renderFrame) {
            inputCollector.windowSize(input.windowWidth(), input.windowHeight());
            inputCollector.framebufferSize(input.framebufferWidth(), input.framebufferHeight());
            inputCollector.contentScale(input.contentScaleX(), input.contentScaleY());
            inputCollector.focused(input.focused());
            inputCollector.cursorInside(input.cursorInside());
            inputCollector.cursorPosition(input.cursorX(), input.cursorY());
            for (int update = 0; update < UPDATES_PER_FRAME; update++) {
                if (update == 0) scene.applyBuiltinScript(renderFrame);
                scene.updateStatistics(ui.statistics());
                ui.update(inputCollector.snapshot(), DELTA_SECONDS);
                scene.refreshVirtualizedContent();
            }
            return ui.statistics();
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            try {
                scene.close();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
            try {
                ui.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
            if (failure != null) throw failure;
        }
    }
}
