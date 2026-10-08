package com.kaleblangley.haikalat.demo.pbr;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.graph.FrameProfile;
import com.kaleblangley.haikalat.core.graph.PassProfile;
import com.kaleblangley.haikalat.demo.pbr.ClusteredDemoSceneFactory.Bundle;
import com.kaleblangley.haikalat.demo.pbr.ClusteredDemoSceneFactory.Request;
import com.kaleblangley.haikalat.demo.pbr.ClusteredDemoSceneFactory.StressMode;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.ClusteredLightingDiagnostics;
import com.kaleblangley.haikalat.subsystems.render3d.ClusteredLightingSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Render3dDiagnostics;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

/**
 * v0.24.3 clustered-forward benchmark and gate matrix.
 *
 * <p>GPU samples use the explicit {@code enableGpuSampleCollection} ring and are
 * filtered by submission sequence and deduplicated; a pass that did not run in
 * a frame contributes no sample rather than inheriting an earlier value.
 * Raw samples and the source fingerprint are preserved next to the report.</p>
 */
public final class Render3dClusteredBenchmarkSuite {
    private static final String UPLOAD_PASS = "ClusteredLightUpload";
    private static final String BOUNDS_PASS = "ClusteredClusterBounds";
    private static final String ASSIGN_PASS = "ClusteredClusterAssign";
    private static final String STATS_PASS = "ClusteredClusterStats";
    private static final String GEOMETRY_PASS = "GeometryPass";
    private static final String FULL_SCAN_BENCHMARK_PROPERTY =
            "haikalat.internal.benchmark.fullScanReference";
    private static final Set<String> CLUSTERED_GPU_PASSES = Set.of(
            UPLOAD_PASS, BOUNDS_PASS, ASSIGN_PASS, STATS_PASS, GEOMETRY_PASS);
    private static final Set<String> FULL_SCAN_GPU_PASSES = Set.of(
            UPLOAD_PASS, GEOMETRY_PASS);
    private static final double GPU_COVERAGE_MIN = 0.95;
    // The benchmark GpuTimer retains 256 queries per pass.  A much smaller
    // batch also bounds queued work for the 4K full-scan oracle below the
    // normal ten-second collection deadline without using per-frame glFinish.
    private static final int GPU_DRAIN_BATCH_FRAMES = 32;

    private Render3dClusteredBenchmarkSuite() {
    }

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        String candidate = fingerprint();
        String runId = UUID.randomUUID().toString();
        Path directory = Path.of("build/reports/render3d-v0243", candidate, runId);
        List<ConfigResult> results = new ArrayList<>();
        List<ComparisonResult> comparisons = new ArrayList<>();
        List<GpuComparisonResult> gpuComparisons = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try {
            List<Config> selected = configs().stream()
                    .filter(config -> options.only == null || config.name().equals(options.only))
                    .toList();
            Map<String, List<ConfigResult>> rounds = new LinkedHashMap<>();
            Options singleRound = options.singleRound();
            // Run one complete warmup+measurement round per configuration, then
            // repeat the matrix.  Clustered/reference/zero-light variants are
            // therefore interleaved instead of measuring every A round before B.
            for (int round = 0; round < options.rounds; round++) {
                for (Config config : selected) {
                    try {
                        ConfigResult result = run(config, singleRound,
                                runId + "/round-" + round);
                        rounds.computeIfAbsent(config.name(), ignored -> new ArrayList<>())
                                .add(result);
                    } catch (RuntimeException failure) {
                        failures.add(config.name() + " round " + round + ": " + failure);
                    }
                }
            }
            for (Config config : selected) {
                List<ConfigResult> configRounds = rounds.getOrDefault(config.name(), List.of());
                if (configRounds.size() != options.rounds) continue;
                ConfigResult result = mergeRounds(runId, configRounds);
                results.add(result);
                gate(config, result, options, failures);
                printResult(config, result);
            }
            if (!candidate.equals(fingerprint())) {
                failures.add("source changed during measurement");
            }
            comparisons.addAll(compareCpuBaselines(results, failures));
            gpuComparisons.addAll(compareFullScanReferences(results, options, failures));
        } finally {
            writeReport(directory, candidate, options, results, comparisons, gpuComparisons,
                    failures);
            System.out.println("clustered benchmark report: " + directory.toAbsolutePath());
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("clustered benchmark failed: " + failures);
        }
    }

    private static void printResult(Config config, ConfigResult result) {
        System.out.printf(Locale.ROOT,
                "CLUSTERED BENCH %s lights=%d grid=%dx%dx%d clusters=%d K=%d "
                        + "wallP50=%.3fms wallP95=%.3fms unattributedP50=%.3fms "
                        + "recordP50=%.3fms submitP50=%.3fms assignGpuP50=%.3fms "
                        + "assignGpuP95=%.3fms assignCoverage=%.2f overflow=%d "
                        + "maxInline=%d ext=%dx%d%n",
                config.name(), config.lights(), config.tileSize(), config.tileSize(),
                config.zSlices(), result.clusterCount, result.inlineCapacity,
                SampleStatistics.percentileMillis(result.wallMillis, 0.50),
                SampleStatistics.percentileMillis(result.wallMillis, 0.95),
                SampleStatistics.percentileMillis(result.unattributedCpuMillis, 0.50),
                SampleStatistics.percentileMillis(result.graphRecordMillis, 0.50),
                SampleStatistics.percentileMillis(result.deviceSubmitMillis, 0.50),
                percentileOrNaN(result.gpuSamples.get(ASSIGN_PASS), 0.50),
                percentileOrNaN(result.gpuSamples.get(ASSIGN_PASS), 0.95),
                result.assignCoverage, result.overflowClusters, result.maxInlineCount,
                result.width, result.height);
    }

    private static void gate(Config config, ConfigResult result, Options options,
                             List<String> failures) {
        int expected = options.frames * options.rounds;
        if (result.wallMillis.length != expected) {
            failures.add(config.name() + ": measured " + result.wallMillis.length
                    + " frames, expected " + expected);
        }
        if (result.width != config.width() || result.height != config.height()) {
            failures.add(config.name() + ": rendered " + result.width + "x" + result.height
                    + ", expected " + config.width() + "x" + config.height());
        }
        if (config.width() == 3840 && config.height() == 2160
                && result.totalResourceBytes > 64L * 1024L * 1024L) {
            failures.add(config.name() + ": clustered resources " + result.totalResourceBytes
                    + " bytes exceed the 64 MiB default budget");
        }
        if (!config.fullScanReference() && result.assignCoverage < GPU_COVERAGE_MIN) {
            failures.add(config.name() + ": assign GPU coverage " + result.assignCoverage
                    + " below " + GPU_COVERAGE_MIN);
        }
        for (BenchmarkSampleCollector.Coverage coverage : result.gpuSnapshot.coverage()) {
            if (coverage.expected() > 0 && coverage.ratio() < GPU_COVERAGE_MIN) {
                failures.add(config.name() + ": " + coverage.passName() + " round "
                        + coverage.round() + " GPU coverage " + coverage.ratio()
                        + " below " + GPU_COVERAGE_MIN);
            }
        }
        for (String collectorFailure : result.gpuSnapshot.failures()) {
            failures.add(config.name() + ": " + collectorFailure);
        }
        if (result.tailTimedOut) {
            failures.add(config.name() + ": GPU tail collection timed out with missing required samples");
        }
        int expectedPairs = options.frames * options.rounds;
        int completePairs = result.gpuSnapshot.paired(result.requiredGpuPasses).size();
        if (SampleStatistics.coverage(completePairs, expectedPairs) < GPU_COVERAGE_MIN) {
            failures.add(config.name() + ": complete GPU pass-set coverage "
                    + SampleStatistics.coverage(completePairs, expectedPairs)
                    + " below " + GPU_COVERAGE_MIN);
        }
        StressMode mode = config.mode();
        if (config.fullScanReference()) {
            if (result.gpuSnapshot.measuredByPass().containsKey(BOUNDS_PASS)
                    || result.gpuSnapshot.measuredByPass().containsKey(ASSIGN_PASS)
                    || result.gpuSnapshot.measuredByPass().containsKey(STATS_PASS)) {
                failures.add(config.name()
                        + ": full-scan reference executed a clustered-only GPU pass");
            }
            return;
        }
        if (mode == StressMode.OVERLAP) {
            if (result.overflowClusters <= 0) {
                failures.add(config.name() + ": overlap must report overflow clusters");
            }
        } else if (config.lights() > 0 && config.requireNoOverflow()
                && result.overflowClusters != 0) {
            failures.add(config.name() + ": sparse/coverage must not overflow, got "
                    + result.overflowClusters);
        }
        if (config.assignBudgetP95Millis > 0 && options.formal) {
            double p95 = percentileOrNaN(result.gpuSamples.get(ASSIGN_PASS), 0.95);
            if (!Double.isFinite(p95) || p95 > config.assignBudgetP95Millis) {
                failures.add(config.name() + ": assign GPU p95 " + p95
                        + "ms exceeds budget " + config.assignBudgetP95Millis + "ms");
            }
        }
    }

    private static ConfigResult run(Config config, Options options, String runId) {
        RenderSettings settings = RenderSettings.builder()
                .vsync(false)
                .toneMappingMode(ToneMappingMode.ACES)
                .exposureMode(ExposureMode.MANUAL)
                .exposure(1.15f)
                .bloomSettings(BloomSettings.disabled())
                .sceneVisibility(true)
                .build();
        ClusteredLightingSettings clustered = ClusteredLightingSettings.builder()
                .tileSize(config.tileSize())
                .zSlices(config.zSlices())
                .maxLocalLights(Math.max(64, config.lights()))
                .build();
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(64, 64)
                .title("Clustered benchmark " + config.name())
                .visible(false)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            try (com.kaleblangley.haikalat.core.presentation.OwnedPresentationTarget target =
                         com.kaleblangley.haikalat.core.presentation.OwnedPresentationTarget.create(
                                 config.width(), config.height(),
                                 com.kaleblangley.haikalat.backend.RenderFormat.RGBA16F, true, 1);
                 FrameDriver driver = new FrameDriver(settings);
                 PbrEnvironment environment = PbrEnvironmentLoader.load(driver.device(),
                         Render3dClusteredBenchmarkSuite.class,
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality());
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 ShaderProgram shader = ShaderProgram.fromResource(
                         Render3dClusteredBenchmarkSuite.class,
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         config.fullScanReference()
                                 ? "/shaders/clustered/full-scan-reference.frag"
                                 : "/shaders/render3d/pbr/pbr-forward.frag")) {
                Bundle bundle = ClusteredDemoSceneFactory.create(
                        Request.stress(config.lights(), config.mode(), 4242L), shader, fallbacks);
                String previousFullScan = System.getProperty(FULL_SCAN_BENCHMARK_PROPERTY);
                try {
                    if (config.fullScanReference()) {
                        System.setProperty(FULL_SCAN_BENCHMARK_PROPERTY, "true");
                    } else {
                        System.clearProperty(FULL_SCAN_BENCHMARK_PROPERTY);
                    }
                    RenderPipeline pipeline = new RenderPipeline(target.target(), bundle.scene, null,
                            settings, environment).clusteredLighting(clustered);
                    try {
                        pipeline.build();
                        pipeline.enableBenchmarkCpuTiming();
                        pipeline.graph().enableGpuSampleCollection();
                        com.kaleblangley.haikalat.subsystems.render3d.Camera sceneCamera =
                                bundle.scene.camera();
                        org.joml.Matrix4f view = sceneCamera.getViewMatrix(new org.joml.Matrix4f());
                        org.joml.Matrix4f projection = new org.joml.Matrix4f().perspective(
                                (float) Math.toRadians(sceneCamera.zoom()),
                                config.width() / (float) config.height(), 0.1f, 100.0f);
                        com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera camera =
                                com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera.of(
                                        view, projection, sceneCamera.position(), 0.0f);
                        Samples samples = measure(pipeline, driver, options, camera, target.target(),
                                runId + "/" + config.name(), config.requiredGpuPasses());
                        // Explicit acceptance path: drain the GPU before reading
                        // the diagnostic counters.  This is never measured.
                        GL11.glFinish();
                        ClusteredLightingDiagnostics clusteredDiagnostics =
                                pipeline.lastRender3dDiagnostics().clustered();
                        if (!config.fullScanReference() && config.lights() > 0
                                && !Boolean.getBoolean("haikalat.cluster.skipAssignFence")
                                && !clusteredDiagnostics.gpuCountersAvailable()) {
                            throw new IllegalStateException(
                                    "GPU counters unavailable after glFinish drain");
                        }
                        String renderer = glString(GL11.GL_RENDERER);
                        String glVersion = glString(GL11.GL_VERSION);
                        return new ConfigResult(config.name(), config.fullScanReference(),
                                pipeline.graph().width(), pipeline.graph().height(),
                                samples.wallMillis, samples.framePreparationMillis,
                                samples.lightPackAndRecordMillis, samples.graphRecordMillis,
                                samples.deviceSubmitMillis,
                                samples.unattributedCpuMillis,
                                samples.gpuSamples, samples.assignCoverage,
                                samples.gpuSnapshot, config.requiredGpuPasses(),
                                samples.tailDrainMillis,
                                samples.tailTimedOut,
                                config.fullScanReference() ? -1
                                        : clusteredDiagnostics.overflowClusters(),
                                config.fullScanReference() ? -1
                                        : clusteredDiagnostics.maxInlineCount(),
                                clusteredDiagnostics.clusterCount(),
                                clusteredDiagnostics.inlineCapacity(),
                                clusteredDiagnostics.lightTableBytes(),
                                clusteredDiagnostics.clusterBoundsBytes(),
                                clusteredDiagnostics.clusterHeadersBytes(),
                                clusteredDiagnostics.clusterIndicesBytes(),
                                clusteredDiagnostics.totalResourceBytes(),
                                renderer, glVersion);
                    } finally {
                        pipeline.close();
                    }
                } finally {
                    if (previousFullScan == null) {
                        System.clearProperty(FULL_SCAN_BENCHMARK_PROPERTY);
                    } else {
                        System.setProperty(FULL_SCAN_BENCHMARK_PROPERTY, previousFullScan);
                    }
                    bundle.close();
                }
            }
        }
    }

    private static Samples measure(RenderPipeline pipeline, FrameDriver driver, Options options,
                                   com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera camera,
                                   com.kaleblangley.haikalat.core.presentation.PresentationTarget target,
                                   String runId, Set<String> requiredGpuPasses) {
        int measuredPerRound = options.frames;
        int total = options.rounds * (options.warmup + measuredPerRound);
        float[] wall = new float[options.rounds * measuredPerRound];
        float[] framePreparation = new float[wall.length];
        float[] lightPackAndRecord = new float[wall.length];
        float[] graphRecord = new float[wall.length];
        float[] deviceSubmit = new float[wall.length];
        float[] unattributedCpu = new float[wall.length];
        BenchmarkSampleCollector collector = new BenchmarkSampleCollector(runId);
        int measuredIndex = 0;
        long generationId = 0L;
        for (int frame = 0; frame < total; frame++) {
            int round = frame / (options.warmup + measuredPerRound);
            int frameInRound = frame % (options.warmup + measuredPerRound);
            boolean measured = frameInRound >= options.warmup;
            if (frameInRound == options.warmup && options.warmup > 0) {
                // Do not let an intentionally expensive warmup (notably the
                // 4K full-scan oracle) consume the bounded final-drain budget.
                // This is one synchronization at the measurement boundary,
                // never a per-frame synchronization in the formal run.
                GL11.glFinish();
                for (PassProfile pass : pipeline.graph().drainGpuSamples()) {
                    collector.acceptAvailable(generationId, pass);
                }
            }
            if (measured && options.cpuIsolation) {
                // Isolated attribution only; the normal run never blocks here.
                GL11.glFinish();
            }
            driver.beginFrame();
            long start = System.nanoTime();
            pipeline.execute(driver.device(), camera, target, 1.0f / 60.0f);
            long elapsed = System.nanoTime() - start;
            FrameProfile profile = pipeline.graph().lastFrameProfile();
            int ticketMeasuredIndex = measured ? measuredIndex : -1;
            collector.register(new BenchmarkSampleCollector.FrameTicket(generationId,
                    profile.frameSequence(), round,
                    measured ? BenchmarkSampleCollector.Phase.MEASURED
                            : BenchmarkSampleCollector.Phase.WARMUP,
                    ticketMeasuredIndex));
            for (PassProfile pass : profile.passes()) {
                collector.acceptAttempt(generationId, profile.frameSequence(), pass);
            }
            driver.recordGraph(pipeline.graph());
            driver.endFrame();
            RenderPipeline.BenchmarkCpuTiming cpuTiming = pipeline.lastBenchmarkCpuTiming();
            if (!cpuTiming.available()) {
                throw new IllegalStateException("benchmark CPU timing was not captured");
            }
            if (measured) {
                wall[measuredIndex] = elapsed / 1_000_000.0f;
                framePreparation[measuredIndex] = cpuTiming.framePreparationNanos() / 1_000_000.0f;
                lightPackAndRecord[measuredIndex] = cpuTiming.lightPackAndRecordNanos()
                        / 1_000_000.0f;
                graphRecord[measuredIndex] = cpuTiming.graphRecordNanos() / 1_000_000.0f;
                deviceSubmit[measuredIndex] = cpuTiming.deviceSubmitNanos() / 1_000_000.0f;
                long attributed = Math.addExact(cpuTiming.framePreparationNanos(),
                        Math.addExact(cpuTiming.lightPackAndRecordNanos(),
                                Math.addExact(cpuTiming.graphRecordNanos(),
                                        cpuTiming.deviceSubmitNanos())));
                unattributedCpu[measuredIndex] = Math.max(0L, elapsed - attributed)
                        / 1_000_000.0f;
                measuredIndex++;
            }
            for (PassProfile pass : pipeline.graph().drainGpuSamples()) {
                collector.acceptAvailable(generationId, pass);
            }
            boolean endOfRound = measured
                    && frameInRound == options.warmup + measuredPerRound - 1;
            if (measured && (measuredIndex % GPU_DRAIN_BATCH_FRAMES == 0 || endOfRound)
                    && frame + 1 < total) {
                drainCheckpoint(pipeline, collector, requiredGpuPasses,
                        generationId, measuredIndex - 1, options.drainTimeoutMillis);
            }
        }

        long tailStart = System.nanoTime();
        long deadline = tailStart + options.drainTimeoutMillis * 1_000_000L;
        GL11.glFlush();
        while (!collector.terminalFor(requiredGpuPasses) && System.nanoTime() < deadline) {
            for (PassProfile pass : pipeline.graph().drainGpuSamples()) {
                collector.acceptAvailable(generationId, pass);
            }
            if (!collector.terminalFor(requiredGpuPasses)) {
                LockSupport.parkNanos(1_000_000L);
            }
        }
        for (PassProfile pass : pipeline.graph().drainGpuSamples()) {
            collector.acceptAvailable(generationId, pass);
        }
        long tailDrainMillis = Math.max(0L, (System.nanoTime() - tailStart) / 1_000_000L);
        boolean tailTimedOut = !collector.terminalFor(requiredGpuPasses);
        BenchmarkSampleCollector.Snapshot gpuSnapshot = collector.snapshot(requiredGpuPasses);

        Map<String, float[]> gpuMillis = new LinkedHashMap<>();
        for (Map.Entry<String, List<BenchmarkSampleCollector.MeasuredSample>> entry
                : gpuSnapshot.measuredByPass().entrySet()) {
            float[] values = new float[entry.getValue().size()];
            int index = 0;
            for (BenchmarkSampleCollector.MeasuredSample sample : entry.getValue()) {
                values[index++] = sample.nanos() / 1_000_000.0f;
            }
            gpuMillis.put(entry.getKey(), values);
        }
        double coverage = requiredGpuPasses.contains(ASSIGN_PASS)
                ? gpuSnapshot.minimumCoverage(ASSIGN_PASS) : Double.NaN;
        return new Samples(wall, framePreparation, lightPackAndRecord, graphRecord,
                deviceSubmit, unattributedCpu, gpuMillis, coverage,
                gpuSnapshot, tailDrainMillis, tailTimedOut);
    }

    private static void drainCheckpoint(RenderPipeline pipeline,
                                        BenchmarkSampleCollector collector,
                                        Set<String> requiredGpuPasses,
                                        long generationId,
                                        int measuredIndexInclusive,
                                        long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        GL11.glFlush();
        while (!collector.terminalThroughMeasuredIndex(requiredGpuPasses,
                measuredIndexInclusive) && System.nanoTime() < deadline) {
            for (PassProfile pass : pipeline.graph().drainGpuSamples()) {
                collector.acceptAvailable(generationId, pass);
            }
            if (!collector.terminalThroughMeasuredIndex(requiredGpuPasses,
                    measuredIndexInclusive)) {
                LockSupport.parkNanos(1_000_000L);
            }
        }
        for (PassProfile pass : pipeline.graph().drainGpuSamples()) {
            collector.acceptAvailable(generationId, pass);
        }
        if (!collector.terminalThroughMeasuredIndex(requiredGpuPasses,
                measuredIndexInclusive)) {
            throw new IllegalStateException("GPU checkpoint collection timed out through measured frame "
                    + measuredIndexInclusive);
        }
    }

    private static ConfigResult mergeRounds(String runId, List<ConfigResult> rounds) {
        ConfigResult first = rounds.getFirst();
        List<BenchmarkSampleCollector.RawSample> raw = new ArrayList<>();
        Map<String, List<BenchmarkSampleCollector.MeasuredSample>> measured =
                new LinkedHashMap<>();
        List<BenchmarkSampleCollector.Coverage> coverage = new ArrayList<>();
        List<String> collectorFailures = new ArrayList<>();
        int duplicateSamples = 0;
        int conflictingSamples = 0;
        int unknownSamples = 0;
        int measuredTickets = 0;
        int measuredOffset = 0;
        for (int roundIndex = 0; roundIndex < rounds.size(); roundIndex++) {
            ConfigResult round = rounds.get(roundIndex);
            if (!round.requiredGpuPasses.equals(first.requiredGpuPasses)
                    || round.width != first.width || round.height != first.height) {
                throw new IllegalStateException("incompatible benchmark round for " + first.name);
            }
            for (BenchmarkSampleCollector.RawSample sample : round.gpuSnapshot.rawSamples()) {
                BenchmarkSampleCollector.SampleKey key = new BenchmarkSampleCollector.SampleKey(
                        sample.key().runId(), roundIndex, sample.key().passName(),
                        sample.key().sequence());
                raw.add(new BenchmarkSampleCollector.RawSample(key, roundIndex, sample.phase(),
                        sample.measuredIndex() < 0 ? -1 : sample.measuredIndex() + measuredOffset,
                        sample.status(), sample.nanos(), sample.skippedSubmissions(),
                        sample.duplicateCount()));
            }
            for (Map.Entry<String, List<BenchmarkSampleCollector.MeasuredSample>> entry
                    : round.gpuSnapshot.measuredByPass().entrySet()) {
                List<BenchmarkSampleCollector.MeasuredSample> target = measured.computeIfAbsent(
                        entry.getKey(), ignored -> new ArrayList<>());
                for (BenchmarkSampleCollector.MeasuredSample sample : entry.getValue()) {
                    target.add(new BenchmarkSampleCollector.MeasuredSample(roundIndex,
                            sample.sequence(), roundIndex,
                            sample.measuredIndex() + measuredOffset, sample.nanos()));
                }
            }
            for (BenchmarkSampleCollector.Coverage value : round.gpuSnapshot.coverage()) {
                coverage.add(new BenchmarkSampleCollector.Coverage(value.passName(), roundIndex,
                        value.available(), value.expected(), value.ratio()));
            }
            for (String failure : round.gpuSnapshot.failures()) {
                collectorFailures.add("round " + roundIndex + ": " + failure);
            }
            duplicateSamples += round.gpuSnapshot.duplicateSamples();
            conflictingSamples += round.gpuSnapshot.conflictingSamples();
            unknownSamples += round.gpuSnapshot.unknownSamples();
            measuredTickets += round.gpuSnapshot.measuredTickets();
            measuredOffset += round.wallMillis.length;
        }
        Map<String, List<BenchmarkSampleCollector.MeasuredSample>> immutableMeasured =
                new LinkedHashMap<>();
        measured.forEach((pass, values) -> immutableMeasured.put(pass, List.copyOf(values)));
        BenchmarkSampleCollector.Snapshot snapshot = new BenchmarkSampleCollector.Snapshot(
                runId, List.copyOf(raw), Map.copyOf(immutableMeasured), List.copyOf(coverage),
                List.copyOf(collectorFailures), duplicateSamples, conflictingSamples,
                unknownSamples, measuredTickets);
        return new ConfigResult(first.name, first.fullScanReference, first.width, first.height,
                join(rounds, value -> value.wallMillis),
                join(rounds, value -> value.framePreparationMillis),
                join(rounds, value -> value.lightPackAndRecordMillis),
                join(rounds, value -> value.graphRecordMillis),
                join(rounds, value -> value.deviceSubmitMillis),
                join(rounds, value -> value.unattributedCpuMillis),
                gpuMillis(snapshot),
                first.requiredGpuPasses.contains(ASSIGN_PASS)
                        ? snapshot.minimumCoverage(ASSIGN_PASS) : Double.NaN,
                snapshot, first.requiredGpuPasses,
                rounds.stream().mapToLong(value -> value.tailDrainMillis).sum(),
                rounds.stream().anyMatch(value -> value.tailTimedOut),
                rounds.stream().mapToInt(value -> value.overflowClusters).max().orElse(-1),
                rounds.stream().mapToInt(value -> value.maxInlineCount).max().orElse(-1),
                first.clusterCount, first.inlineCapacity,
                first.lightTableBytes, first.clusterBoundsBytes,
                first.clusterHeadersBytes, first.clusterIndicesBytes,
                first.totalResourceBytes, first.renderer, first.glVersion);
    }

    private static float[] join(List<ConfigResult> rounds,
                                java.util.function.Function<ConfigResult, float[]> values) {
        int length = rounds.stream().map(values).mapToInt(array -> array.length).sum();
        float[] result = new float[length];
        int offset = 0;
        for (ConfigResult round : rounds) {
            float[] source = values.apply(round);
            System.arraycopy(source, 0, result, offset, source.length);
            offset += source.length;
        }
        return result;
    }

    private static Map<String, float[]> gpuMillis(BenchmarkSampleCollector.Snapshot snapshot) {
        Map<String, float[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<BenchmarkSampleCollector.MeasuredSample>> entry
                : snapshot.measuredByPass().entrySet()) {
            float[] values = new float[entry.getValue().size()];
            for (int index = 0; index < values.length; index++) {
                values[index] = entry.getValue().get(index).nanos() / 1_000_000.0f;
            }
            result.put(entry.getKey(), values);
        }
        return Map.copyOf(result);
    }

    private static float percentileOrNaN(float[] samples, double fraction) {
        if (samples == null || samples.length == 0) {
            return Float.NaN;
        }
        return SampleStatistics.percentileMillis(samples, fraction);
    }

    private static List<Config> configs() {
        return List.of(
                new Config("sparse-0-1080p", StressMode.SPARSE, 0, 1920, 1080, 64, 24, 0.0, true),
                new Config("sparse-256-1080p", StressMode.SPARSE, 256, 1920, 1080, 64, 24, 0.0, true),
                new Config("full-scan-256-1080p", StressMode.SPARSE, 256, 1920, 1080,
                        64, 24, 0.0, false, true),
                new Config("sparse-8-1080p", StressMode.SPARSE, 8, 1920, 1080, 64, 24, 0.0, true),
                new Config("full-scan-8-1080p", StressMode.SPARSE, 8, 1920, 1080,
                        64, 24, 0.0, false, true),
                new Config("sparse-128-1080p", StressMode.SPARSE, 128, 1920, 1080, 64, 24, 0.75, true),
                new Config("sparse-512-1080p", StressMode.SPARSE, 512, 1920, 1080, 64, 24, 0.0, false),
                new Config("sparse-1024-1080p", StressMode.SPARSE, 1024, 1920, 1080, 64, 24, 0.0, false),
                new Config("sparse-0-4k", StressMode.SPARSE, 0, 3840, 2160, 64, 24, 0.0, true),
                new Config("sparse-256-4k", StressMode.SPARSE, 256, 3840, 2160, 64, 24, 1.50, true),
                new Config("full-scan-256-4k", StressMode.SPARSE, 256, 3840, 2160,
                        64, 24, 0.0, false, true),
                new Config("overlap-256-1080p", StressMode.OVERLAP, 256, 1920, 1080, 64, 24, 0.0, false),
                new Config("coverage-256-1080p", StressMode.COVERAGE, 256, 1920, 1080, 64, 24, 0.0, true)
        );
    }

    private static String glString(int name) {
        String value = GL11.glGetString(name);
        return value == null ? "unavailable" : value;
    }

    // Shared by the volumetric M0 report so candidate identities keep one definition.
    static String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> paths = new ArrayList<>();
            for (String root : List.of("src", "gradle", "config")) {
                try (var stream = Files.walk(Path.of(root))) {
                    stream.filter(Files::isRegularFile).forEach(paths::add);
                }
            }
            paths.add(Path.of("build.gradle"));
            paths.sort(Comparator.comparing(Path::toString));
            for (Path path : paths) {
                digest.update(path.toString().replace('\\', '/').getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(path));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new IllegalStateException("cannot fingerprint candidate", failure);
        }
    }

    private static void writeReport(Path directory, String candidate, Options options,
                                    List<ConfigResult> results,
                                    List<ComparisonResult> comparisons,
                                    List<GpuComparisonResult> gpuComparisons,
                                    List<String> failures) {
        try {
            Files.createDirectories(directory);
            try (JsonGenerator json = new JsonFactory().createGenerator(
                    directory.resolve("manifest.json").toFile(), JsonEncoding.UTF8)) {
                json.useDefaultPrettyPrinter();
                json.writeStartObject();
                json.writeStringField("schema", "haikalat.render3d-benchmark.v1");
                json.writeStringField("candidateSha256", candidate);
                json.writeStringField("generatedAt", Instant.now().toString());
                json.writeStringField("java", System.getProperty("java.version"));
                json.writeBooleanField("formal", options.formal);
                json.writeBooleanField("cpuIsolation", options.cpuIsolation);
                json.writeNumberField("tailDeadlineMillis", options.drainTimeoutMillis);
                json.writeStringField("mode", options.cpuIsolation
                        ? "cpu-attribution" : "normal-runtime");
                json.writeStringField("schedule",
                        "round-major interleaved; fixed configuration order per round");
                json.writeArrayFieldStart("clusteredRequiredGpuPasses");
                for (String pass : CLUSTERED_GPU_PASSES.stream().sorted().toList()) {
                    json.writeString(pass);
                }
                json.writeEndArray();
                json.writeArrayFieldStart("fullScanRequiredGpuPasses");
                for (String pass : FULL_SCAN_GPU_PASSES.stream().sorted().toList()) {
                    json.writeString(pass);
                }
                json.writeEndArray();
                json.writeArrayFieldStart("visualInputs");
                for (Path input : visualInputPaths()) {
                    json.writeStartObject();
                    json.writeStringField("path", input.toString().replace('\\', '/'));
                    json.writeStringField("sha256", sha256(input));
                    json.writeEndObject();
                    Path copy = directory.resolve("inputs").resolve(input);
                    Files.createDirectories(copy.getParent());
                    Files.copy(input, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                json.writeEndArray();
                json.writeEndObject();
            }
            try (JsonGenerator json = new JsonFactory().createGenerator(
                    directory.resolve("metrics.json").toFile(), JsonEncoding.UTF8)) {
                json.useDefaultPrettyPrinter();
                json.writeStartObject();
                json.writeStringField("candidateSha256", candidate);
                json.writeStringField("generatedAt", Instant.now().toString());
                json.writeStringField("java", System.getProperty("java.version"));
                json.writeStringField("status", failures.isEmpty() ? "passed" : "failed");
                json.writeBooleanField("formal", options.formal);
                json.writeBooleanField("cpuIsolation", options.cpuIsolation);
                json.writeNumberField("rounds", options.rounds);
                json.writeNumberField("warmupFramesPerRound", options.warmup);
                json.writeNumberField("measuredFramesPerRound", options.frames);
                json.writeNumberField("gpuCoverageMinimum", GPU_COVERAGE_MIN);
                json.writeArrayFieldStart("failures");
                for (String failure : failures) json.writeString(failure);
                json.writeEndArray();
                json.writeArrayFieldStart("configs");
                for (ConfigResult result : results) {
                    writeConfig(json, result);
                }
                json.writeEndArray();
                json.writeArrayFieldStart("comparisons");
                for (ComparisonResult comparison : comparisons) {
                    json.writeStartObject();
                    json.writeStringField("name", comparison.name());
                    json.writeStringField("baseline", comparison.baseline());
                    json.writeStringField("candidate", comparison.candidate());
                    json.writeNumberField("pairedFrames", comparison.pairedFrames());
                    json.writeNumberField("lightPackAndRecordDeltaP50Ms", comparison.p50Millis());
                    json.writeNumberField("lightPackAndRecordDeltaP95Ms", comparison.p95Millis());
                    json.writeNumberField("budgetP95Ms", comparison.budgetP95Millis());
                    json.writeEndObject();
                }
                json.writeEndArray();
                json.writeArrayFieldStart("gpuReferenceComparisons");
                for (GpuComparisonResult comparison : gpuComparisons) {
                    json.writeStartObject();
                    json.writeStringField("name", comparison.name());
                    json.writeStringField("baseline", comparison.baseline());
                    json.writeStringField("candidate", comparison.candidate());
                    json.writeStringField("metric", comparison.metric());
                    json.writeNumberField("baselineP50Ms", comparison.baselineP50Millis());
                    json.writeNumberField("baselineP95Ms", comparison.baselineP95Millis());
                    json.writeNumberField("candidateP50Ms", comparison.candidateP50Millis());
                    json.writeNumberField("candidateP95Ms", comparison.candidateP95Millis());
                    json.writeNumberField("deltaP50Ms", comparison.deltaP50Millis());
                    json.writeNumberField("deltaP95Ms", comparison.deltaP95Millis());
                    json.writeNumberField("ratioP50", comparison.ratioP50());
                    json.writeNumberField("ratioP95", comparison.ratioP95());
                    json.writeStringField("gate", comparison.gate());
                    json.writeNumberField("budget", comparison.budget());
                    json.writeEndObject();
                }
                json.writeEndArray();
                json.writeEndObject();
            }
            try (JsonGenerator json = new JsonFactory().createGenerator(
                    directory.resolve("raw-samples.jsonl").toFile(), JsonEncoding.UTF8)) {
                for (ConfigResult result : results) {
                    for (BenchmarkSampleCollector.RawSample sample
                            : result.gpuSnapshot.rawSamples()) {
                        json.writeStartObject();
                        json.writeStringField("config", result.name);
                        json.writeStringField("runId", sample.key().runId());
                        json.writeNumberField("generation", sample.key().generationId());
                        json.writeNumberField("sequence", sample.key().sequence());
                        json.writeNumberField("round", sample.round());
                        json.writeStringField("phase", sample.phase().name().toLowerCase(Locale.ROOT));
                        json.writeNumberField("measuredIndex", sample.measuredIndex());
                        json.writeStringField("pass", sample.key().passName());
                        json.writeStringField("status", sample.status().name().toLowerCase(Locale.ROOT));
                        json.writeNumberField("nanos", sample.nanos());
                        json.writeNumberField("skippedSubmissions", sample.skippedSubmissions());
                        json.writeNumberField("duplicateCount", sample.duplicateCount());
                        json.writeEndObject();
                        json.writeRaw('\n');
                    }
                }
            }
            try (JsonGenerator json = new JsonFactory().createGenerator(
                    directory.resolve("failures.json").toFile(), JsonEncoding.UTF8)) {
                json.useDefaultPrettyPrinter();
                json.writeStartObject();
                json.writeStringField("status", failures.isEmpty() ? "passed" : "failed");
                json.writeArrayFieldStart("failures");
                for (String failure : failures) json.writeString(failure);
                json.writeEndArray();
                json.writeEndObject();
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("failed to preserve clustered benchmark evidence",
                    failure);
        }
    }

    private static void writeConfig(JsonGenerator json, ConfigResult result)
            throws java.io.IOException {
        json.writeStartObject();
        json.writeStringField("name", result.name);
        json.writeBooleanField("fullScanReference", result.fullScanReference);
        json.writeNumberField("width", result.width);
        json.writeNumberField("height", result.height);
        json.writeStringField("renderer", result.renderer);
        json.writeStringField("glVersion", result.glVersion);
        json.writeNumberField("clusterCount", result.clusterCount);
        json.writeNumberField("inlineCapacity", result.inlineCapacity);
        json.writeObjectFieldStart("clusteredResourceBytes");
        json.writeNumberField("lightTable", result.lightTableBytes);
        json.writeNumberField("clusterBounds", result.clusterBoundsBytes);
        json.writeNumberField("clusterHeaders", result.clusterHeadersBytes);
        json.writeNumberField("clusterIndices", result.clusterIndicesBytes);
        json.writeNumberField("otherOwned", Math.max(0L, result.totalResourceBytes
                - result.lightTableBytes - result.clusterBoundsBytes
                - result.clusterHeadersBytes - result.clusterIndicesBytes));
        json.writeNumberField("total", result.totalResourceBytes);
        json.writeEndObject();
        json.writeNumberField("overflowClusters", result.overflowClusters);
        json.writeNumberField("maxInlineCount", result.maxInlineCount);
        json.writeNumberField("assignCoverage", result.assignCoverage);
        json.writeArrayFieldStart("requiredGpuPasses");
        for (String pass : result.requiredGpuPasses.stream().sorted().toList()) {
            json.writeString(pass);
        }
        json.writeEndArray();
        json.writeNumberField("tailDrainMillis", result.tailDrainMillis);
        json.writeBooleanField("tailTimedOut", result.tailTimedOut);
        json.writeNumberField("duplicateGpuSamples", result.gpuSnapshot.duplicateSamples());
        json.writeNumberField("conflictingGpuSamples", result.gpuSnapshot.conflictingSamples());
        json.writeNumberField("unknownGpuSamples", result.gpuSnapshot.unknownSamples());
        json.writeArrayFieldStart("gpuCoverageByRound");
        for (BenchmarkSampleCollector.Coverage coverage : result.gpuSnapshot.coverage()) {
            json.writeStartObject();
            json.writeStringField("pass", coverage.passName());
            json.writeNumberField("round", coverage.round());
            json.writeNumberField("available", coverage.available());
            json.writeNumberField("expected", coverage.expected());
            json.writeNumberField("ratio", coverage.ratio());
            json.writeEndObject();
        }
        json.writeEndArray();
        json.writeObjectFieldStart("cpu");
        writeSamples(json, "executeWallMs", result.wallMillis);
        writeSamples(json, "framePreparationMs", result.framePreparationMillis);
        writeSamples(json, "lightPackAndRecordMs", result.lightPackAndRecordMillis);
        writeSamples(json, "graphRecordMs", result.graphRecordMillis);
        writeSamples(json, "deviceSubmitMs", result.deviceSubmitMillis);
        writeSamples(json, "unattributedCpuMs", result.unattributedCpuMillis);
        json.writeStringField("presentWall", "N/A: hidden FBO benchmark");
        json.writeEndObject();
        json.writeObjectFieldStart("gpuPasses");
        for (Map.Entry<String, float[]> entry : result.gpuSamples.entrySet()) {
            json.writeObjectFieldStart(entry.getKey());
            writeSamples(json, "millis", entry.getValue());
            json.writeEndObject();
        }
        json.writeEndObject();
        writePairedGpuSamples(json, "assignPlusGeometryMs",
                result.gpuSnapshot.paired(Set.of(ASSIGN_PASS, GEOMETRY_PASS)));
        writePairedGpuSamples(json, "gpuPassSumMs",
                result.gpuSnapshot.paired(result.requiredGpuPasses));
        json.writeEndObject();
    }

    private static List<Path> visualInputPaths() throws java.io.IOException {
        List<Path> result = new ArrayList<>();
        for (Path root : List.of(Path.of("config/visual-profiles"),
                Path.of("config/visual-baselines"))) {
            if (!Files.isDirectory(root)) continue;
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).forEach(result::add);
            }
        }
        result.sort(Comparator.comparing(Path::toString));
        return List.copyOf(result);
    }

    private static String sha256(Path path) throws java.io.IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void writeSamples(JsonGenerator json, String field, float[] samples)
            throws java.io.IOException {
        json.writeObjectFieldStart(field);
        json.writeNumberField("count", samples.length);
        if (samples.length > 0) {
            json.writeNumberField("p50", SampleStatistics.percentileMillis(samples, 0.50));
            json.writeNumberField("p95", SampleStatistics.percentileMillis(samples, 0.95));
            json.writeNumberField("max", SampleStatistics.maxMillis(samples));
        }
        json.writeArrayFieldStart("raw");
        for (float sample : samples) json.writeNumber(sample);
        json.writeEndArray();
        json.writeEndObject();
    }

    private static void writePairedGpuSamples(JsonGenerator json, String field,
                                               List<BenchmarkSampleCollector.PairedSample> samples)
            throws java.io.IOException {
        float[] millis = new float[samples.size()];
        for (int index = 0; index < samples.size(); index++) {
            millis[index] = samples.get(index).nanos() / 1_000_000.0f;
        }
        writeSamples(json, field, millis);
    }

    private static List<ComparisonResult> compareCpuBaselines(List<ConfigResult> results,
                                                               List<String> failures) {
        List<ComparisonResult> comparisons = new ArrayList<>();
        compareCpuBaseline(results, "sparse-0-1080p", "sparse-256-1080p",
                0.50, comparisons, failures);
        compareCpuBaseline(results, "sparse-0-4k", "sparse-256-4k",
                0.50, comparisons, failures);
        return List.copyOf(comparisons);
    }

    private static void compareCpuBaseline(List<ConfigResult> results, String baselineName,
                                           String candidateName, double budgetP95,
                                           List<ComparisonResult> comparisons,
                                           List<String> failures) {
        ConfigResult baseline = results.stream().filter(value -> value.name.equals(baselineName))
                .findFirst().orElse(null);
        ConfigResult candidate = results.stream().filter(value -> value.name.equals(candidateName))
                .findFirst().orElse(null);
        if (baseline == null || candidate == null) return;
        if (baseline.lightPackAndRecordMillis.length != candidate.lightPackAndRecordMillis.length) {
            failures.add(candidateName + ": zero-light CPU baseline has a different frame count");
            return;
        }
        float[] deltas = new float[baseline.lightPackAndRecordMillis.length];
        for (int index = 0; index < deltas.length; index++) {
            deltas[index] = candidate.lightPackAndRecordMillis[index]
                    - baseline.lightPackAndRecordMillis[index];
        }
        float p50 = SampleStatistics.percentileMillis(deltas, 0.50);
        float p95 = SampleStatistics.percentileMillis(deltas, 0.95);
        comparisons.add(new ComparisonResult("zero-light-cpu-" + candidate.width + "x"
                + candidate.height, baselineName, candidateName, deltas.length, p50, p95,
                budgetP95));
        if (p95 > budgetP95) {
            failures.add(candidateName + ": paired lightPackAndRecord CPU delta p95 " + p95
                    + "ms exceeds zero-light budget " + budgetP95 + "ms");
        }
    }

    private static List<GpuComparisonResult> compareFullScanReferences(
            List<ConfigResult> results, Options options, List<String> failures) {
        List<GpuComparisonResult> comparisons = new ArrayList<>();
        compareGpuDelta(results, "full-scan-8-1080p", "sparse-8-1080p",
                CLUSTERED_GPU_PASSES, FULL_SCAN_GPU_PASSES, 0.30,
                "clustered-total-minus-full-scan-total-p50-ms", options, comparisons, failures);
        compareGpuRatio(results, "full-scan-256-1080p", "sparse-256-1080p",
                Set.of(ASSIGN_PASS, GEOMETRY_PASS), Set.of(GEOMETRY_PASS), 0.75,
                options, comparisons, failures);
        compareGpuRatio(results, "full-scan-256-4k", "sparse-256-4k",
                Set.of(ASSIGN_PASS, GEOMETRY_PASS), Set.of(GEOMETRY_PASS), 0.75,
                options, comparisons, failures);
        return List.copyOf(comparisons);
    }

    private static void compareGpuDelta(List<ConfigResult> results, String baselineName,
                                        String candidateName, Set<String> candidatePasses,
                                        Set<String> baselinePasses, double budget,
                                        String gate, Options options,
                                        List<GpuComparisonResult> comparisons,
                                        List<String> failures) {
        ConfigResult baseline = find(results, baselineName);
        ConfigResult candidate = find(results, candidateName);
        if (baseline == null || candidate == null) return;
        float[] baselineSamples = pairedMillis(baseline, baselinePasses);
        float[] candidateSamples = pairedMillis(candidate, candidatePasses);
        GpuComparisonResult result = gpuComparison(gate, baseline, candidate,
                "totalGpuPassSum", baselineSamples, candidateSamples, gate, budget);
        comparisons.add(result);
        if (options.formal && (!Double.isFinite(result.deltaP50Millis())
                || result.deltaP50Millis() > budget)) {
            failures.add(candidateName + ": clustered total GPU p50 increment "
                    + result.deltaP50Millis() + "ms exceeds full-scan budget " + budget + "ms");
        }
    }

    private static void compareGpuRatio(List<ConfigResult> results, String baselineName,
                                        String candidateName, Set<String> candidatePasses,
                                        Set<String> baselinePasses, double budget,
                                        Options options,
                                        List<GpuComparisonResult> comparisons,
                                        List<String> failures) {
        ConfigResult baseline = find(results, baselineName);
        ConfigResult candidate = find(results, candidateName);
        if (baseline == null || candidate == null) return;
        String gate = "clustered-assign-plus-geometry-over-full-scan-geometry-p50-ratio";
        GpuComparisonResult result = gpuComparison(
                gate + "-" + candidate.width + "x" + candidate.height,
                baseline, candidate, "assignPlusGeometryVsGeometry",
                pairedMillis(baseline, baselinePasses),
                pairedMillis(candidate, candidatePasses), gate, budget);
        comparisons.add(result);
        if (options.formal && (!Double.isFinite(result.ratioP50())
                || result.ratioP50() > budget)) {
            failures.add(candidateName + ": clustered Assign+Geometry/full-scan Geometry p50 ratio "
                    + result.ratioP50() + " exceeds " + budget);
        }
    }

    private static ConfigResult find(List<ConfigResult> results, String name) {
        return results.stream().filter(value -> value.name.equals(name)).findFirst().orElse(null);
    }

    private static float[] pairedMillis(ConfigResult result, Set<String> passes) {
        List<BenchmarkSampleCollector.PairedSample> paired = result.gpuSnapshot.paired(passes);
        float[] values = new float[paired.size()];
        for (int index = 0; index < paired.size(); index++) {
            values[index] = paired.get(index).nanos() / 1_000_000.0f;
        }
        return values;
    }

    private static GpuComparisonResult gpuComparison(String name, ConfigResult baseline,
                                                      ConfigResult candidate, String metric,
                                                      float[] baselineSamples,
                                                      float[] candidateSamples,
                                                      String gate, double budget) {
        float baselineP50 = percentileOrNaN(baselineSamples, 0.50);
        float baselineP95 = percentileOrNaN(baselineSamples, 0.95);
        float candidateP50 = percentileOrNaN(candidateSamples, 0.50);
        float candidateP95 = percentileOrNaN(candidateSamples, 0.95);
        return new GpuComparisonResult(name, baseline.name, candidate.name, metric,
                baselineP50, baselineP95, candidateP50, candidateP95,
                candidateP50 - baselineP50, candidateP95 - baselineP95,
                baselineP50 > 0.0f ? candidateP50 / baselineP50 : Float.NaN,
                baselineP95 > 0.0f ? candidateP95 / baselineP95 : Float.NaN,
                gate, budget);
    }

    private record Config(String name, StressMode mode, int lights, int width, int height,
                          int tileSize, int zSlices, double assignBudgetP95Millis,
                          boolean requireNoOverflow, boolean fullScanReference) {
        private Config(String name, StressMode mode, int lights, int width, int height,
                       int tileSize, int zSlices, double assignBudgetP95Millis,
                       boolean requireNoOverflow) {
            this(name, mode, lights, width, height, tileSize, zSlices,
                    assignBudgetP95Millis, requireNoOverflow, false);
        }

        private Set<String> requiredGpuPasses() {
            return fullScanReference ? FULL_SCAN_GPU_PASSES : CLUSTERED_GPU_PASSES;
        }
    }

    private record ComparisonResult(String name, String baseline, String candidate,
                                    int pairedFrames, float p50Millis, float p95Millis,
                                    double budgetP95Millis) { }

    private record GpuComparisonResult(String name, String baseline, String candidate,
                                       String metric, float baselineP50Millis,
                                       float baselineP95Millis, float candidateP50Millis,
                                       float candidateP95Millis, float deltaP50Millis,
                                       float deltaP95Millis, float ratioP50, float ratioP95,
                                       String gate, double budget) { }

    private record Samples(float[] wallMillis, float[] framePreparationMillis,
                           float[] lightPackAndRecordMillis, float[] graphRecordMillis,
                           float[] deviceSubmitMillis, float[] unattributedCpuMillis,
                           Map<String, float[]> gpuSamples, double assignCoverage,
                           BenchmarkSampleCollector.Snapshot gpuSnapshot,
                           long tailDrainMillis, boolean tailTimedOut) { }

    private record ConfigResult(String name, boolean fullScanReference,
                                int width, int height, float[] wallMillis,
                                float[] framePreparationMillis,
                                float[] lightPackAndRecordMillis, float[] graphRecordMillis,
                                float[] deviceSubmitMillis, float[] unattributedCpuMillis,
                                Map<String, float[]> gpuSamples, double assignCoverage,
                                BenchmarkSampleCollector.Snapshot gpuSnapshot,
                                Set<String> requiredGpuPasses,
                                long tailDrainMillis, boolean tailTimedOut,
                                int overflowClusters, int maxInlineCount, int clusterCount,
                                int inlineCapacity, long lightTableBytes,
                                long clusterBoundsBytes, long clusterHeadersBytes,
                                long clusterIndicesBytes, long totalResourceBytes,
                                String renderer, String glVersion) { }

    private static final class Options {
        private int rounds = 3;
        private int frames = 300;
        private int warmup = 120;
        private boolean formal = true;
        private boolean cpuIsolation = false;
        private long drainTimeoutMillis = 10_000L;
        private String only;

        private Options singleRound() {
            Options copy = new Options();
            copy.rounds = 1;
            copy.frames = frames;
            copy.warmup = warmup;
            copy.formal = formal;
            copy.cpuIsolation = cpuIsolation;
            copy.drainTimeoutMillis = drainTimeoutMillis;
            copy.only = only;
            return copy;
        }

        private static Options parse(String[] arguments) {
            Options options = new Options();
            for (String argument : arguments) {
                if (argument.startsWith("--rounds=")) {
                    options.rounds = Integer.parseInt(argument.substring("--rounds=".length()));
                } else if (argument.startsWith("--frames=")) {
                    options.frames = Integer.parseInt(argument.substring("--frames=".length()));
                } else if (argument.startsWith("--warmup=")) {
                    options.warmup = Integer.parseInt(argument.substring("--warmup=".length()));
                } else if (argument.startsWith("--only=")) {
                    options.only = argument.substring("--only=".length());
                } else if (argument.equals("--informal")) {
                    options.formal = false;
                } else if (argument.equals("--cpu-isolation")) {
                    options.cpuIsolation = true;
                } else if (argument.startsWith("--drain-timeout-ms=")) {
                    options.drainTimeoutMillis = Long.parseLong(
                            argument.substring("--drain-timeout-ms=".length()));
                } else {
                    throw new IllegalArgumentException(
                            "Unknown clustered benchmark argument: " + argument);
                }
            }
            if (options.rounds < 1 || options.frames < 1 || options.warmup < 0
                    || options.drainTimeoutMillis < 1L) {
                throw new IllegalArgumentException("invalid clustered benchmark counts");
            }
            if (options.formal && (options.rounds < 3 || options.frames < 300
                    || options.warmup < 120)) {
                throw new IllegalArgumentException(
                        "formal measurement requires 3 rounds, 300 measured and 120 warmup frames");
            }
            return options;
        }
    }
}
