package com.kaleblangley.haikalat.demo.ui;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.text.BundledFonts;
import com.kaleblangley.haikalat.subsystems.text.TextAlignment;
import com.kaleblangley.haikalat.subsystems.text.TextSystem;
import com.kaleblangley.haikalat.subsystems.text.markdown.MarkdownDocument;
import com.kaleblangley.haikalat.subsystems.text.markdown.MarkdownParser;
import com.kaleblangley.haikalat.subsystems.ui.UiBatchBreakStats;
import com.kaleblangley.haikalat.subsystems.ui.UiConfig;
import com.kaleblangley.haikalat.subsystems.ui.UiFrameStats;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.text.TextEffect;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.UnavailableTextInputAdapter;
import org.lwjgl.opengl.GL;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Reproducible v0.22.1 Markdown, layout, atlas and text-effect baseline. */
public final class TextBenchmarkSuite {
    private static final String PRESENT_PASS = "TextBenchmarkPresent";
    private static final String GLYPH_UNIT = "Haikalat文字";
    private static final String TEXT_1000 = GLYPH_UNIT.repeat(100);
    private static final String TEXT_10000 = GLYPH_UNIT.repeat(1_000);
    private static final UiColor CYAN = UiColor.fromSrgbHex(0x57e5d6ff);
    private static final UiColor MAGENTA = UiColor.fromSrgbHex(0xf178c5ff);
    private static final UiColor DARK = UiColor.fromSrgbHex(0x07111bff);
    private static final Resolution[] RESOLUTIONS = {
            new Resolution("1080p", 1920, 1080),
            new Resolution("4K", 3840, 2160)
    };

    private TextBenchmarkSuite() {
    }

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        System.out.printf(Locale.ROOT,
                "Text benchmark config rounds=%d warmup=%d frames=%d parseSamples=%d%n",
                options.rounds(), options.warmupFrames(), options.measuredFrames(),
                options.parseSamples());
        runMarkdownBenchmarks(options);
        runLayoutBenchmarks(options);
        List<GpuResult> gpu = runGpuBenchmarks(options);
        printGpuSummary(gpu);
    }

    private static void runMarkdownBenchmarks(Options options) {
        String ordinary = MarkdownTextDemo.MARKDOWN_SOURCE;
        String large = (ordinary + "\n\n").repeat(400);
        List<MarkdownCase> cases = List.of(
                new MarkdownCase("small", "# Title\n\nOne **short** paragraph."),
                new MarkdownCase("ordinary", ordinary),
                new MarkdownCase("large", large));
        MarkdownParser parser = new MarkdownParser();
        System.out.println("\n| Markdown | chars | blocks | parse p50 ms | parse p95 ms | allocation KiB/parse |");
        System.out.println("| --- | ---: | ---: | ---: | ---: | ---: |");
        for (MarkdownCase benchmark : cases) {
            for (int warmup = 0; warmup < 12; warmup++) parser.parse(benchmark.source());
            Samples nanos = new Samples(options.parseSamples());
            Samples allocations = new Samples(options.parseSamples());
            int blocks = 0;
            for (int sample = 0; sample < options.parseSamples(); sample++) {
                long bytesBefore = AllocationCounter.currentThreadBytes();
                long start = System.nanoTime();
                MarkdownDocument parsed = parser.parse(benchmark.source());
                nanos.add(System.nanoTime() - start);
                long bytesAfter = AllocationCounter.currentThreadBytes();
                if (bytesBefore >= 0L && bytesAfter >= bytesBefore) {
                    allocations.add(bytesAfter - bytesBefore);
                }
                blocks ^= parsed.blocks().size();
            }
            // Parse once outside the black-hole fold for a human-readable block count.
            blocks = parser.parse(benchmark.source()).blocks().size();
            System.out.printf(Locale.ROOT, "| %s | %d | %d | %.3f | %.3f | %.1f |%n",
                    benchmark.name(), benchmark.source().length(), blocks,
                    nanos.percentileMillis(0.50), nanos.percentileMillis(0.95),
                    allocations.isEmpty() ? Double.NaN : allocations.percentile(0.50) / 1024.0);
        }
    }

    private static void runLayoutBenchmarks(Options options) {
        System.out.println("\n| layout | glyphs | cold ms | cold KiB | hit p50 ms | hit p95 ms | hit KiB | hits/misses |");
        System.out.println("| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | ");
        measureLayout("cold/cache", TEXT_1000, options);
        measureLayout("cold/cache", TEXT_10000, options);
        measureFontSwitch(options);
    }

    private static void measureLayout(String name, String source, Options options) {
        try (TextSystem text = TextSystem.createBundled(1_024, 1_024, 8, 8, 16)) {
            long coldBytesBefore = AllocationCounter.currentThreadBytes();
            long coldStart = System.nanoTime();
            var cold = text.layoutSingleLine(22, source, Float.POSITIVE_INFINITY,
                    TextAlignment.START, false);
            long coldNanos = System.nanoTime() - coldStart;
            long coldBytesAfter = AllocationCounter.currentThreadBytes();
            Samples hits = new Samples(options.measuredFrames());
            Samples allocations = new Samples(options.measuredFrames());
            for (int sample = 0; sample < options.measuredFrames(); sample++) {
                long before = AllocationCounter.currentThreadBytes();
                long start = System.nanoTime();
                var cached = text.layoutSingleLine(22, source, Float.POSITIVE_INFINITY,
                        TextAlignment.START, false);
                hits.add(System.nanoTime() - start);
                if (cached != cold) throw new IllegalStateException("layout cache did not retain value identity");
                long after = AllocationCounter.currentThreadBytes();
                if (before >= 0L && after >= before) allocations.add(after - before);
            }
            TextSystem.LayoutStatistics stats = text.layoutStatistics();
            if (stats.cacheMisses() != 1L || stats.cacheHits() != options.measuredFrames()) {
                throw new IllegalStateException("static layout was reshaped: " + stats);
            }
            System.out.printf(Locale.ROOT, "| %s | %d | %.3f | %.1f | %.4f | %.4f | %.3f | %d/%d |%n",
                    name, cold.glyphCount(), coldNanos / 1_000_000.0,
                    allocatedKiB(coldBytesBefore, coldBytesAfter),
                    hits.percentileMillis(0.50), hits.percentileMillis(0.95),
                    allocations.isEmpty() ? Double.NaN : allocations.percentile(0.50) / 1024.0,
                    stats.cacheHits(), stats.cacheMisses());
        }
    }

    private static void measureFontSwitch(Options options) {
        try (TextSystem text = TextSystem.createBundled(1_024, 1_024, 8, 8, 16)) {
            text.layoutSingleLine(22, TEXT_1000, Float.POSITIVE_INFINITY,
                    TextAlignment.START, false);
            text.selectFontFamily(BundledFonts.JETBRAINS_MONO_FAMILY);
            long before = AllocationCounter.currentThreadBytes();
            long start = System.nanoTime();
            var rebuilt = text.layoutSingleLine(22, TEXT_1000, Float.POSITIVE_INFINITY,
                    TextAlignment.START, false);
            long rebuildNanos = System.nanoTime() - start;
            long after = AllocationCounter.currentThreadBytes();
            Samples hits = new Samples(options.measuredFrames());
            Samples allocations = new Samples(options.measuredFrames());
            for (int sample = 0; sample < options.measuredFrames(); sample++) {
                long bytesBefore = AllocationCounter.currentThreadBytes();
                long hitStart = System.nanoTime();
                var cached = text.layoutSingleLine(22, TEXT_1000, Float.POSITIVE_INFINITY,
                        TextAlignment.START, false);
                hits.add(System.nanoTime() - hitStart);
                if (cached != rebuilt) throw new IllegalStateException("font-switch layout stayed unstable");
                long bytesAfter = AllocationCounter.currentThreadBytes();
                if (bytesBefore >= 0L && bytesAfter >= bytesBefore) {
                    allocations.add(bytesAfter - bytesBefore);
                }
            }
            TextSystem.LayoutStatistics stats = text.layoutStatistics();
            System.out.printf(Locale.ROOT,
                    "| font switch/rebuild | %d | %.3f | %.1f | %.4f | %.4f | %.3f | %d/%d |%n",
                    rebuilt.glyphCount(), rebuildNanos / 1_000_000.0, allocatedKiB(before, after),
                    hits.percentileMillis(0.50), hits.percentileMillis(0.95),
                    allocations.isEmpty() ? Double.NaN : allocations.percentile(0.50) / 1024.0,
                    stats.cacheHits(), stats.cacheMisses());
        }
    }

    private static List<GpuResult> runGpuBenchmarks(Options options) {
        List<GpuResult> results = new ArrayList<>();
        for (Resolution requested : RESOLUTIONS) {
            try (GlfwWindow window = new GlfwWindow.Builder()
                    .dimensions(requested.width(), requested.height())
                    .title("Haikalat text benchmark " + requested.name())
                    .visible(false).build()) {
                window.bindContext();
                GL.createCapabilities();
                GlDebug.enableDebugCallback();
                window.setVsync(false);
                Resolution actual = new Resolution(requested.name(), window.width(), window.height());
                for (TextScenario scenario : TextScenario.values()) {
                    for (int round = 1; round <= options.rounds(); round++) {
                        GpuResult result = runGpuRound(window, actual, scenario, round, options);
                        results.add(result);
                        System.out.println(result.line());
                    }
                }
                GlDebug.assertNoError("TextBenchmarkSuite." + requested.name());
                // Authorization is evaluated centrally by glDebugPolicyGuard against
                // config/gl-debug-policy.tsv, including vendor, id, task and message text.
            }
        }
        return List.copyOf(results);
    }

    private static GpuResult runGpuRound(GlfwWindow window, Resolution resolution,
                                         TextScenario scenario, int round, Options options) {
        RenderSettings settings = RenderSettings.builder().vsync(false).build();
        int messagesBefore = GlDebug.messages().messages().size();
        try (FrameDriver driver = new FrameDriver(settings);
             RenderGraph graph = createGraph(window);
             UiSystem ui = UiSystem.create(window, UiConfig.builder()
                             .primitiveCapacity(16_384, 65_536)
                             .glyphAtlas(1_024, 1_024, 8).build(),
                     new UnavailableTextInputAdapter("text benchmark"))) {
            ui.attachTo(graph, PRESENT_PASS);
            graph.compile();
            scenario.install(ui.document().root());

            UiFrameStats cold = UiFrameStats.EMPTY;
            for (int frame = 0; frame < 4; frame++) cold = frame(window, driver, graph, ui, false).statistics();
            for (int frame = 0; frame < options.warmupFrames(); frame++) {
                frame(window, driver, graph, ui, false);
            }
            UiFrameStats baseline = ui.statistics();
            Samples cpu = new Samples(options.measuredFrames());
            Samples gpu = new Samples(options.measuredFrames());
            Samples allocations = new Samples(options.measuredFrames());
            for (int frame = 0; frame < options.measuredFrames(); frame++) {
                FrameSample sample = frame(window, driver, graph, ui, true);
                cpu.add(sample.cpuNanos());
                if (sample.gpuNanos() > 0L) gpu.add(sample.gpuNanos());
                if (sample.allocatedBytes() >= 0L) allocations.add(sample.allocatedBytes());
            }
            UiFrameStats end = ui.statistics();
            if (end.shapingCacheMisses() != baseline.shapingCacheMisses()
                    || end.atlasUploadBytes() != baseline.atlasUploadBytes()
                    || end.layoutPasses() != 0L || end.shapedRuns() != 0L) {
                throw new IllegalStateException("static text did not reach stable reuse: " + scenario
                        + " baseline=" + baseline + " end=" + end);
            }
            if (end.glyphs() < scenario.minimumGlyphs()) {
                throw new IllegalStateException("text scenario did not retain expected glyphs: "
                        + scenario + " actual=" + end.glyphs());
            }
            GlDebug.assertNoError("TextBenchmarkSuite." + resolution.name() + "." + scenario);
            return new GpuResult(resolution, scenario, round,
                    cpu.percentileMillis(0.50), cpu.percentileMillis(0.95),
                    gpu.percentileMillis(0.50), gpu.percentileMillis(0.95),
                    allocations.isEmpty() ? Double.NaN : allocations.percentile(0.50) / 1024.0,
                    allocations.isEmpty() ? Double.NaN : allocations.percentile(0.95) / 1024.0,
                    baseline.shapingCacheHits(), baseline.shapingCacheMisses(),
                    cold.glyphAtlasMisses(), cold.atlasUploadBytes(), cold.glyphAtlasPages(),
                    end.glyphs(), end.batches(), end.drawCalls(), end.batchBreaks(),
                    Math.max(0, GlDebug.messages().messages().size() - messagesBefore));
        }
    }

    private static FrameSample frame(GlfwWindow window, FrameDriver driver,
                                     RenderGraph graph, UiSystem ui, boolean measureAllocation) {
        window.pollEvents();
        long bytesBefore = measureAllocation ? AllocationCounter.currentThreadBytes() : -1L;
        long start = System.nanoTime();
        ui.update(window.inputSnapshot(), 1.0f / 60.0f);
        driver.frame(graph);
        long gpuNanos = graph.lastFrameProfile().totalGpuNanos();
        driver.present(window::swapBuffers);
        long cpuNanos = System.nanoTime() - start;
        long bytesAfter = measureAllocation ? AllocationCounter.currentThreadBytes() : -1L;
        long allocated = bytesBefore < 0L || bytesAfter < bytesBefore ? -1L : bytesAfter - bytesBefore;
        return new FrameSample(ui.statistics(), cpuNanos, gpuNanos, allocated);
    }

    private static RenderGraph createGraph(GlfwWindow window) {
        RenderGraph graph = new RenderGraph(window.width(), window.height());
        graph.addPass(PRESENT_PASS).writeToBackbuffer().noClear()
                .execute((resources, commands) -> commands
                        .enableBlend(false).enableDepthTest(false).enableCullFace(false)
                        .clearColor(0.02f, 0.03f, 0.05f, 1.0f).clear(true, false));
        return graph;
    }

    private static void printGpuSummary(List<GpuResult> results) {
        System.out.println("\n| resolution | text path | CPU p50/p95 ms | GPU p50/p95 ms | "
                + "allocation p50/p95 KiB | shape hit/miss | upload requests/bytes/pages | glyphs | "
                + "batches/draws | breaks O/S/T/Sm/B/C | GL messages |");
        System.out.println("| --- | --- | --- | --- | --- | --- | --- | ---: | --- | --- | ---: |");
        for (Resolution resolution : RESOLUTIONS) {
            for (TextScenario scenario : TextScenario.values()) {
                List<GpuResult> group = results.stream().filter(result ->
                        result.resolution().name().equals(resolution.name())
                                && result.scenario() == scenario).toList();
                System.out.printf(Locale.ROOT,
                        "| %s | %s | %.3f / %.3f | %.3f / %.3f | %.1f / %.1f | %.0f / %.0f | "
                                + "%.0f / %.0f / %.0f | %.0f | %.0f / %.0f | %s | %.0f |%n",
                        resolution.name(), scenario.label(), median(group, GpuResult::cpuP50Millis),
                        median(group, GpuResult::cpuP95Millis), median(group, GpuResult::gpuP50Millis),
                        median(group, GpuResult::gpuP95Millis), median(group, GpuResult::allocationP50KiB),
                        median(group, GpuResult::allocationP95KiB), median(group, GpuResult::shapeHits),
                        median(group, GpuResult::shapeMisses), median(group, GpuResult::coldUploads),
                        median(group, GpuResult::coldUploadBytes), median(group, GpuResult::atlasPages),
                        median(group, GpuResult::glyphs), median(group, GpuResult::batches),
                        median(group, GpuResult::draws), group.getFirst().breaksText(),
                        median(group, GpuResult::glMessages));
            }
        }
    }

    private static double median(List<GpuResult> values, Metric metric) {
        double[] sorted = values.stream().mapToDouble(metric::value)
                .filter(Double::isFinite).sorted().toArray();
        if (sorted.length == 0) return Double.NaN;
        int middle = sorted.length >>> 1;
        return (sorted.length & 1) == 0
                ? (sorted[middle - 1] + sorted[middle]) * 0.5 : sorted[middle];
    }

    private static double allocatedKiB(long before, long after) {
        return before < 0L || after < before ? Double.NaN : (after - before) / 1024.0;
    }

    private enum TextScenario {
        PLAIN_1000("plain 1,000", TEXT_1000, TextEffect.none(), 1_000),
        PLAIN_10000("plain 10,000", TEXT_10000, TextEffect.none(), 10_000),
        GRADIENT("gradient", TEXT_1000, TextEffect.gradient(CYAN, MAGENTA, 45.0f), 1_000),
        OUTLINE("outline", TEXT_1000, TextEffect.outline(CYAN, 1.5f), 1_000),
        DROP_SHADOW("drop shadow", TEXT_1000, TextEffect.dropShadow(DARK, 2.0f, 2.0f, 3.0f), 1_000),
        OUTER_GLOW("outer glow", TEXT_1000, TextEffect.glow(CYAN, 4.0f), 1_000),
        INNER_GLOW("inner glow", TEXT_1000, TextEffect.innerGlow(MAGENTA), 1_000),
        COMBINED("combined runs", TEXT_1000, null, 960);

        private final String label;
        private final String source;
        private final TextEffect effect;
        private final int minimumGlyphs;

        TextScenario(String label, String source, TextEffect effect, int minimumGlyphs) {
            this.label = label;
            this.source = source;
            this.effect = effect;
            this.minimumGlyphs = minimumGlyphs;
        }

        String label() { return label; }
        int minimumGlyphs() { return minimumGlyphs; }

        void install(Panel root) {
            root.style(UiStyle.builder().width(UiLength.percent(100.0f))
                    .height(UiLength.percent(100.0f)).build());
            if (this != COMBINED) {
                root.add(label(source, effect));
                return;
            }
            int section = source.length() / 6;
            List<TextEffect> effects = List.of(TextEffect.none(),
                    TextEffect.gradient(CYAN, MAGENTA, 45.0f), TextEffect.outline(CYAN, 1.5f),
                    TextEffect.dropShadow(DARK, 2.0f, 2.0f, 3.0f),
                    TextEffect.glow(CYAN, 4.0f), TextEffect.innerGlow(MAGENTA));
            for (int index = 0; index < effects.size(); index++) {
                int start = index * section;
                int end = index == effects.size() - 1 ? source.length() : start + section;
                root.add(label(source.substring(start, end), effects.get(index)));
            }
        }

        private static Label label(String text, TextEffect effect) {
            Label result = new Label(text);
            result.wrap(Label.Wrap.GRAPHEME);
            result.textEffect(effect);
            result.style(UiStyle.builder().width(UiLength.percent(100.0f))
                    .flexShrink(0.0f).build());
            return result;
        }
    }

    private record MarkdownCase(String name, String source) { }
    private record Resolution(String name, int width, int height) { }
    private record FrameSample(UiFrameStats statistics, long cpuNanos,
                               long gpuNanos, long allocatedBytes) { }

    private record GpuResult(Resolution resolution, TextScenario scenario, int round,
                             double cpuP50Millis, double cpuP95Millis,
                             double gpuP50Millis, double gpuP95Millis,
                             double allocationP50KiB, double allocationP95KiB,
                             double shapeHits, double shapeMisses,
                             double coldUploads, double coldUploadBytes, double atlasPages,
                             double glyphs, double batches, double draws,
                             UiBatchBreakStats breaks, double glMessages) {
        String line() {
            return String.format(Locale.ROOT,
                    "Text bench %s | %s | round %d | CPU p50/p95 %.3f/%.3f ms | "
                            + "GPU p50/p95 %.3f/%.3f ms | alloc p50/p95 %.1f/%.1f KiB",
                    resolution.name(), scenario.label(), round, cpuP50Millis, cpuP95Millis,
                    gpuP50Millis, gpuP95Millis, allocationP50KiB, allocationP95KiB);
        }

        String breaksText() {
            return String.format(Locale.ROOT, "%d/%d/%d/%d/%d/%d",
                    breaks.orderBarriers(), breaks.shaderChanges(), breaks.textureChanges(),
                    breaks.samplerChanges(), breaks.blendChanges(), breaks.clipChanges());
        }
    }

    @FunctionalInterface
    private interface Metric { double value(GpuResult value); }

    private static final class Samples {
        private final long[] values;
        private int size;

        Samples(int capacity) { values = new long[capacity]; }
        void add(long value) { values[size++] = value; }
        boolean isEmpty() { return size == 0; }
        long percentile(double percentile) {
            if (size == 0) return 0L;
            long[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
            int index = Math.max(0, (int) Math.ceil(percentile * size) - 1);
            return sorted[index];
        }
        double percentileMillis(double percentile) { return percentile(percentile) / 1_000_000.0; }
    }

    private static final class AllocationCounter {
        private static final com.sun.management.ThreadMXBean BEAN = allocationBean();
        static long currentThreadBytes() {
            return BEAN == null ? -1L : BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }
        private static com.sun.management.ThreadMXBean allocationBean() {
            if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean)
                    || !bean.isThreadAllocatedMemorySupported()) return null;
            if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
            return bean;
        }
    }

    private record Options(int rounds, int warmupFrames, int measuredFrames, int parseSamples) {
        static Options parse(String[] arguments) {
            int rounds = 3;
            int warmup = 30;
            int frames = 120;
            int parse = 100;
            for (String argument : arguments) {
                if (argument.startsWith("--rounds=")) rounds = positive(argument, "--rounds=");
                else if (argument.startsWith("--warmup=")) warmup = nonNegative(argument, "--warmup=");
                else if (argument.startsWith("--frames=")) frames = positive(argument, "--frames=");
                else if (argument.startsWith("--parse-samples=")) parse = positive(argument, "--parse-samples=");
                else throw new IllegalArgumentException("Unknown text benchmark argument: " + argument);
            }
            return new Options(rounds, warmup, frames, parse);
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
