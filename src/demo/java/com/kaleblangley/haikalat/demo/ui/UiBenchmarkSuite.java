package com.kaleblangley.haikalat.demo.ui;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.ui.UiConfig;
import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiFrameStats;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.ui.widget.ListView;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.UnavailableTextInputAdapter;
import org.lwjgl.opengl.GL;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** v0.11 UI 固定容量场景的 1080p/4K 五轮本机基准。 */
public final class UiBenchmarkSuite {
    private static final Resolution[] RESOLUTIONS = {
            new Resolution("1080p", 1920, 1080),
            new Resolution("4K", 3840, 2160)
    };

    private UiBenchmarkSuite() {
    }

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        List<RoundResult> results = new ArrayList<>();
        for (Resolution requested : RESOLUTIONS) {
            try (GlfwWindow window = new GlfwWindow.Builder()
                    .dimensions(requested.width(), requested.height())
                    .title("Haikalat UI Benchmark " + requested.name())
                    .visible(false)
                    .cursorMode(GlfwWindow.CursorMode.NORMAL)
                    .build()) {
                window.bindContext();
                GL.createCapabilities();
                GlDebug.enableDebugCallback();
                window.setVsync(false);
                Resolution actual = new Resolution(requested.name(), window.width(), window.height());
                for (Scenario scenario : Scenario.values()) {
                    for (int round = 1; round <= options.rounds(); round++) {
                        RoundResult result = runRound(window, actual, scenario, round, options);
                        results.add(result);
                        System.out.println(result.line());
                    }
                }
            }
        }
        printSummary(results);
    }

    private static RoundResult runRound(GlfwWindow window, Resolution resolution,
                                        Scenario scenario, int round, Options options) {
        RenderSettings settings = RenderSettings.builder().vsync(false).build();
        try (FrameDriver driver = new FrameDriver(settings);
             RenderGraph graph = createGraph(window);
             UiSystem ui = UiSystem.create(window, UiConfig.defaults(),
                     new UnavailableTextInputAdapter("UI benchmark"))) {
            ui.attachTo(graph, UiDemo.PRESENT_PASS);
            graph.compile();
            BenchmarkScene scene = scenario.install(ui.document());

            FrameSample cold = frame(window, driver, graph, ui, scene, false);
            for (int frame = 0; frame < options.warmupFrames(); frame++) {
                frame(window, driver, graph, ui, scene, false);
            }

            UiFrameStats baseline = ui.statistics();
            LongSamples update = new LongSamples(options.measuredFrames());
            LongSamples layout = new LongSamples(options.measuredFrames());
            LongSamples shaping = new LongSamples(options.measuredFrames());
            LongSamples paint = new LongSamples(options.measuredFrames());
            LongSamples render = new LongSamples(options.measuredFrames());
            LongSamples gpu = new LongSamples(options.measuredFrames());
            LongSamples draws = new LongSamples(options.measuredFrames());
            LongSamples allocations = new LongSamples(options.measuredFrames());
            long measurementStart = System.nanoTime();
            for (int frame = 0; frame < options.measuredFrames(); frame++) {
                FrameSample sample = frame(window, driver, graph, ui, scene, true);
                UiFrameStats stats = sample.statistics();
                update.add(stats.uiUpdateNanos());
                layout.add(stats.layoutNanos());
                shaping.add(stats.shapingNanos());
                paint.add(stats.paintNanos());
                render.add(stats.renderRecordNanos());
                if (sample.gpuNanos() > 0L) gpu.add(sample.gpuNanos());
                draws.add(stats.drawCalls());
                if (sample.allocatedBytes() >= 0L) allocations.add(sample.allocatedBytes());
            }
            long measurementNanos = System.nanoTime() - measurementStart;
            UiFrameStats end = ui.statistics();
            scene.verify(end);

            long atlasHits = Math.max(0L, end.glyphAtlasHits() - baseline.glyphAtlasHits());
            long atlasMisses = Math.max(0L, end.glyphAtlasMisses() - baseline.glyphAtlasMisses());
            long atlasLookups = atlasHits + atlasMisses;
            double hitRate = atlasLookups == 0L ? 1.0 : (double) atlasHits / atlasLookups;
            return new RoundResult(resolution, scenario, round,
                    options.measuredFrames() * 1_000_000_000.0 / measurementNanos,
                    update.medianMillis(), layout.medianMillis(), shaping.medianMillis(),
                    paint.medianMillis(), render.medianMillis(), gpu.medianMillis(),
                    draws.median(),
                    (end.atlasUploadBytes() - baseline.atlasUploadBytes())
                            / (double) options.measuredFrames(),
                    hitRate,
                    (end.ringWaitNanos() - baseline.ringWaitNanos())
                            / (double) options.measuredFrames() / 1_000_000.0,
                    allocations.isEmpty() ? Double.NaN : allocations.median() / 1024.0,
                    cold.statistics().uiUpdateNanos() / 1_000_000.0,
                    cold.statistics().shapingNanos() / 1_000_000.0,
                    baseline.atlasUploadBytes(), end.visibleNodes(), end.quads(),
                    end.glyphs());
        }
    }

    private static FrameSample frame(GlfwWindow window, FrameDriver driver,
                                     RenderGraph graph, UiSystem ui,
                                     BenchmarkScene scene, boolean measureAllocation) {
        window.pollEvents();
        long allocatedBefore = measureAllocation ? AllocationCounter.currentThreadBytes() : -1L;
        ui.update(window.inputSnapshot(), 1.0f / 60.0f);
        scene.afterUpdate();
        driver.frame(graph);
        UiFrameStats statistics = ui.statistics();
        long gpuNanos = graph.lastFrameProfile().totalGpuNanos();
        driver.present(window::swapBuffers);
        long allocatedAfter = measureAllocation ? AllocationCounter.currentThreadBytes() : -1L;
        long allocated = allocatedBefore < 0L || allocatedAfter < allocatedBefore
                ? -1L : allocatedAfter - allocatedBefore;
        return new FrameSample(statistics, gpuNanos, allocated);
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

    private static void printSummary(List<RoundResult> results) {
        System.out.println("\n| resolution | scenario | FPS | update ms | layout ms | shape ms | "
                + "paint ms | render ms | GPU ms | draws | upload B/frame | atlas hit | "
                + "ring wait ms | allocation KiB/frame |");
        System.out.println("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | "
                + "---: | ---: | ---: | ---: | ---: |");
        for (Resolution resolution : RESOLUTIONS) {
            for (Scenario scenario : Scenario.values()) {
                List<RoundResult> group = results.stream()
                        .filter(value -> value.resolution().name().equals(resolution.name())
                                && value.scenario() == scenario)
                        .toList();
                System.out.printf(Locale.ROOT,
                        "| %s | %s | %.1f | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f | "
                                + "%.0f | %.0f | %.2f%% | %.4f | %.1f |%n",
                        resolution.name(), scenario.label(), median(group, Metric.FPS),
                        median(group, Metric.UPDATE), median(group, Metric.LAYOUT),
                        median(group, Metric.SHAPE), median(group, Metric.PAINT),
                        median(group, Metric.RENDER), median(group, Metric.GPU),
                        median(group, Metric.DRAWS), median(group, Metric.UPLOAD),
                        median(group, Metric.HIT_RATE) * 100.0,
                        median(group, Metric.RING_WAIT), median(group, Metric.ALLOCATION));
            }
        }
        System.out.println("\n| resolution | scenario | cold update ms | cold shape ms | "
                + "cold atlas bytes | visible nodes | quads | glyphs |");
        System.out.println("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |");
        for (Resolution resolution : RESOLUTIONS) {
            for (Scenario scenario : Scenario.values()) {
                List<RoundResult> group = results.stream()
                        .filter(value -> value.resolution().name().equals(resolution.name())
                                && value.scenario() == scenario)
                        .toList();
                System.out.printf(Locale.ROOT,
                        "| %s | %s | %.3f | %.3f | %.0f | %.0f | %.0f | %.0f |%n",
                        resolution.name(), scenario.label(), median(group, Metric.COLD_UPDATE),
                        median(group, Metric.COLD_SHAPE), median(group, Metric.COLD_UPLOAD),
                        median(group, Metric.NODES), median(group, Metric.QUADS),
                        median(group, Metric.GLYPHS));
            }
        }
    }

    private static double median(List<RoundResult> values, Metric metric) {
        double[] sorted = values.stream().mapToDouble(metric::value)
                .filter(Double::isFinite).sorted().toArray();
        if (sorted.length == 0) return Double.NaN;
        int middle = sorted.length >>> 1;
        return (sorted.length & 1) == 0
                ? (sorted[middle - 1] + sorted[middle]) * 0.5 : sorted[middle];
    }

    private enum Scenario {
        HUD_100("100 nodes HUD") {
            @Override BenchmarkScene install(UiDocument document) {
                Panel root = document.root();
                UiStyle cell = absoluteCell(18.0f, 18.0f);
                for (int index = 0; index < 100; index++) root.add(new Panel().style(cell));
                return new BenchmarkScene(null, 100, 0, 0);
            }
        },
        SETTINGS_1000("1,000 nodes settings") {
            @Override BenchmarkScene install(UiDocument document) {
                Panel root = document.root();
                UiStyle cell = absoluteCell(12.0f, 12.0f);
                for (int index = 0; index < 1_000; index++) root.add(new Panel().style(cell));
                return new BenchmarkScene(null, 1_000, 0, 0);
            }
        },
        VIRTUAL_LIST_10000("10,000 logical virtual list") {
            @Override BenchmarkScene install(UiDocument document) {
                ListView list = new ListView();
                list.style(UiStyle.builder()
                        .width(UiLength.percent(100.0f))
                        .height(UiLength.percent(100.0f))
                        .build());
                list.model(10_000, index -> new Label("项目 " + index + " / Item " + index)
                        .style(UiStyle.builder().height(UiLength.points(24.0f)).build()));
                document.root().add(list);
                return new BenchmarkScene(list, 0, 0, 0);
            }
        },
        QUADS_10000("10,000 quads") {
            @Override BenchmarkScene install(UiDocument document) {
                Panel root = document.root();
                UiStyle cell = absoluteCell(8.0f, 8.0f);
                for (int index = 0; index < 2_000; index++) root.add(new Panel().style(cell));
                return new BenchmarkScene(null, 0, 10_000, 0);
            }
        },
        GLYPHS_2000("2,000 visible CJK/Latin glyphs") {
            @Override BenchmarkScene install(UiDocument document) {
                Label text = new Label("中文AB".repeat(500));
                text.wrap(Label.Wrap.GRAPHEME);
                text.style(UiStyle.builder().width(UiLength.percent(100.0f)).build());
                document.root().add(text);
                return new BenchmarkScene(null, 0, 0, 2_000);
            }
        };

        private final String label;

        Scenario(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        abstract BenchmarkScene install(UiDocument document);

        private static UiStyle absoluteCell(float width, float height) {
            return UiStyle.builder()
                    .width(UiLength.points(width))
                    .height(UiLength.points(height))
                    .positionType(UiStyle.PositionType.ABSOLUTE)
                    .flexShrink(0.0f)
                    .build();
        }
    }

    private record BenchmarkScene(ListView list, int minimumNodes,
                                  int minimumQuads, int minimumGlyphs) {
        private void afterUpdate() {
            if (list != null) list.refreshViewport();
        }

        private void verify(UiFrameStats statistics) {
            if (minimumNodes > 0 && statistics.visibleNodes() < minimumNodes) {
                throw new IllegalStateException("UI node benchmark did not retain requested nodes: "
                        + statistics.visibleNodes());
            }
            if (minimumQuads > 0 && statistics.quads() < minimumQuads) {
                throw new IllegalStateException("UI quad benchmark did not reach requested quads: "
                        + statistics.quads());
            }
            if (minimumGlyphs > 0 && statistics.glyphs() < minimumGlyphs) {
                throw new IllegalStateException("UI text benchmark did not draw requested glyphs: "
                        + statistics.glyphs());
            }
            if (list != null && (list.itemCount() != 10_000
                    || list.materializedItemCount() >= 256)) {
                throw new IllegalStateException("Virtual list materialized too many logical items: "
                        + list.materializedItemCount());
            }
        }
    }

    private record Resolution(String name, int width, int height) {
    }

    private record FrameSample(UiFrameStats statistics, long gpuNanos, long allocatedBytes) {
    }

    private record RoundResult(Resolution resolution, Scenario scenario, int round,
                               double fps, double updateMillis, double layoutMillis,
                               double shapeMillis, double paintMillis, double renderMillis,
                               double gpuMillis, double draws, double uploadBytesPerFrame,
                               double atlasHitRate, double ringWaitMillisPerFrame,
                               double allocationKibPerFrame, double coldUpdateMillis,
                               double coldShapeMillis, double coldUploadBytes,
                               double visibleNodes, double quads, double glyphs) {
        private String line() {
            return String.format(Locale.ROOT,
                    "UI bench %s | %s | round %d | FPS %.1f | update %.3f ms | "
                            + "GPU %.3f ms | draws %.0f | alloc %.1f KiB/frame",
                    resolution.name(), scenario.label(), round, fps, updateMillis,
                    gpuMillis, draws, allocationKibPerFrame);
        }
    }

    private enum Metric {
        FPS { double value(RoundResult value) { return value.fps(); } },
        UPDATE { double value(RoundResult value) { return value.updateMillis(); } },
        LAYOUT { double value(RoundResult value) { return value.layoutMillis(); } },
        SHAPE { double value(RoundResult value) { return value.shapeMillis(); } },
        PAINT { double value(RoundResult value) { return value.paintMillis(); } },
        RENDER { double value(RoundResult value) { return value.renderMillis(); } },
        GPU { double value(RoundResult value) { return value.gpuMillis(); } },
        DRAWS { double value(RoundResult value) { return value.draws(); } },
        UPLOAD { double value(RoundResult value) { return value.uploadBytesPerFrame(); } },
        HIT_RATE { double value(RoundResult value) { return value.atlasHitRate(); } },
        RING_WAIT { double value(RoundResult value) { return value.ringWaitMillisPerFrame(); } },
        ALLOCATION { double value(RoundResult value) { return value.allocationKibPerFrame(); } },
        COLD_UPDATE { double value(RoundResult value) { return value.coldUpdateMillis(); } },
        COLD_SHAPE { double value(RoundResult value) { return value.coldShapeMillis(); } },
        COLD_UPLOAD { double value(RoundResult value) { return value.coldUploadBytes(); } },
        NODES { double value(RoundResult value) { return value.visibleNodes(); } },
        QUADS { double value(RoundResult value) { return value.quads(); } },
        GLYPHS { double value(RoundResult value) { return value.glyphs(); } };

        abstract double value(RoundResult value);
    }

    private static final class LongSamples {
        private final long[] values;
        private int size;

        private LongSamples(int capacity) {
            values = new long[capacity];
        }

        private void add(long value) {
            values[size++] = value;
        }

        private boolean isEmpty() {
            return size == 0;
        }

        private double median() {
            if (size == 0) return Double.NaN;
            long[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
            int middle = size >>> 1;
            return (size & 1) == 0
                    ? (sorted[middle - 1] + (double) sorted[middle]) * 0.5
                    : sorted[middle];
        }

        private double medianMillis() {
            return median() / 1_000_000.0;
        }
    }

    private static final class AllocationCounter {
        private static final com.sun.management.ThreadMXBean BEAN = allocationBean();

        private AllocationCounter() {
        }

        private static long currentThreadBytes() {
            return BEAN == null ? -1L : BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }

        private static com.sun.management.ThreadMXBean allocationBean() {
            if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean)
                    || !bean.isThreadAllocatedMemorySupported()) return null;
            if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
            return bean;
        }
    }

    private record Options(int rounds, int warmupFrames, int measuredFrames) {
        private static Options parse(String[] arguments) {
            int rounds = 5;
            int warmup = 30;
            int frames = 120;
            for (String argument : arguments) {
                if (argument.startsWith("--rounds=")) {
                    rounds = positive(argument, "--rounds=");
                } else if (argument.startsWith("--warmup=")) {
                    warmup = nonNegative(argument, "--warmup=");
                } else if (argument.startsWith("--frames=")) {
                    frames = positive(argument, "--frames=");
                } else {
                    throw new IllegalArgumentException("Unknown UI benchmark argument: " + argument);
                }
            }
            return new Options(rounds, warmup, frames);
        }

        private static int positive(String argument, String prefix) {
            int value = Integer.parseInt(argument.substring(prefix.length()));
            if (value <= 0) throw new IllegalArgumentException(prefix + " value must be positive");
            return value;
        }

        private static int nonNegative(String argument, String prefix) {
            int value = Integer.parseInt(argument.substring(prefix.length()));
            if (value < 0) throw new IllegalArgumentException(prefix + " value must be non-negative");
            return value;
        }
    }
}
