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
import java.util.TreeMap;
import java.util.UUID;

/**
 * v0.24.2 clustered-forward benchmark and gate matrix.
 *
 * <p>GPU samples use the explicit {@code enableGpuSampleCollection} ring and are
 * filtered by submission sequence and deduplicated; a pass that did not run in
 * a frame contributes no sample rather than inheriting an earlier value.
 * Raw samples and the source fingerprint are preserved next to the report.</p>
 */
public final class Render3dClusteredBenchmarkSuite {
    private static final String ASSIGN_PASS = "ClusteredClusterAssign";
    private static final double GPU_COVERAGE_MIN = 0.95;

    private Render3dClusteredBenchmarkSuite() {
    }

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        String candidate = fingerprint();
        Path directory = Path.of("build/reports/clustered", candidate, UUID.randomUUID().toString());
        List<ConfigResult> results = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try {
            for (Config config : configs()) {
                if (options.only != null && !config.name().equals(options.only)) {
                    continue;
                }
                try {
                    ConfigResult result = run(config, options);
                    results.add(result);
                    gate(config, result, options, failures);
                    System.out.printf(Locale.ROOT,
                            "CLUSTERED BENCH %s lights=%d grid=%dx%dx%d clusters=%d K=%d "
                                    + "wallP50=%.3fms wallP95=%.3fms submitP50=%.3fms "
                                    + "recordP50=%.3fms assignGpuP50=%.3fms assignGpuP95=%.3fms "
                                    + "assignCoverage=%.2f overflow=%d maxInline=%d ext=%dx%d%n",
                            config.name(), config.lights(), config.tileSize(), config.tileSize(),
                            config.zSlices(), result.clusterCount, result.inlineCapacity,
                            SampleStatistics.percentileMillis(result.wallMillis, 0.50),
                            SampleStatistics.percentileMillis(result.wallMillis, 0.95),
                            SampleStatistics.percentileMillis(result.submitMillis, 0.50),
                            SampleStatistics.percentileMillis(result.recordMillis, 0.50),
                            percentileOrNaN(result.gpuSamples.get(ASSIGN_PASS), 0.50),
                            percentileOrNaN(result.gpuSamples.get(ASSIGN_PASS), 0.95),
                            result.assignCoverage, result.overflowClusters, result.maxInlineCount,
                            result.width, result.height);
                } catch (RuntimeException failure) {
                    failures.add(config.name() + ": " + failure);
                }
            }
            if (!candidate.equals(fingerprint())) {
                failures.add("source changed during measurement");
            }
        } finally {
            writeReport(directory, candidate, options, results, failures);
            System.out.println("clustered benchmark report: " + directory.toAbsolutePath());
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("clustered benchmark failed: " + failures);
        }
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
        if (result.assignCoverage < GPU_COVERAGE_MIN) {
            failures.add(config.name() + ": assign GPU coverage " + result.assignCoverage
                    + " below " + GPU_COVERAGE_MIN);
        }
        StressMode mode = config.mode();
        if (mode == StressMode.OVERLAP) {
            if (result.overflowClusters <= 0) {
                failures.add(config.name() + ": overlap must report overflow clusters");
            }
        } else if (config.requireNoOverflow() && result.overflowClusters != 0) {
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

    private static ConfigResult run(Config config, Options options) {
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
                         "/shaders/render3d/pbr/pbr-forward.frag")) {
                Bundle bundle = ClusteredDemoSceneFactory.create(
                        Request.stress(config.lights(), config.mode(), 4242L), shader, fallbacks);
                try {
                    RenderPipeline pipeline = new RenderPipeline(target.target(), bundle.scene, null,
                            settings, environment).clusteredLighting(clustered);
                    try {
                        pipeline.build();
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
                        Samples samples = measure(pipeline, driver, options, camera, target.target());
                        // Explicit acceptance path: drain the GPU before reading
                        // the diagnostic counters.  This is never measured.
                        GL11.glFinish();
                        ClusteredLightingDiagnostics clusteredDiagnostics =
                                pipeline.lastRender3dDiagnostics().clustered();
                        if (!Boolean.getBoolean("haikalat.cluster.skipAssignFence")
                                && !clusteredDiagnostics.gpuCountersAvailable()) {
                            throw new IllegalStateException(
                                    "GPU counters unavailable after glFinish drain");
                        }
                        String renderer = glString(GL11.GL_RENDERER);
                        String glVersion = glString(GL11.GL_VERSION);
                        return new ConfigResult(config.name(),
                                pipeline.graph().width(), pipeline.graph().height(),
                                samples.wallMillis, samples.recordMillis, samples.submitMillis,
                                samples.gpuSamples, samples.assignCoverage,
                                clusteredDiagnostics.overflowClusters(),
                                clusteredDiagnostics.maxInlineCount(),
                                clusteredDiagnostics.clusterCount(),
                                clusteredDiagnostics.inlineCapacity(),
                                renderer, glVersion);
                    } finally {
                        pipeline.close();
                    }
                } finally {
                    bundle.close();
                }
            }
        }
    }

    private static Samples measure(RenderPipeline pipeline, FrameDriver driver, Options options,
                                   com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera camera,
                                   com.kaleblangley.haikalat.core.presentation.PresentationTarget target) {
        int measuredPerRound = options.frames;
        int total = options.rounds * (options.warmup + measuredPerRound);
        float[] wall = new float[options.rounds * measuredPerRound];
        float[] record = new float[wall.length];
        float[] submit = new float[wall.length];
        Map<String, Map<Long, Long>> gpuByPass = new TreeMap<>();
        int measuredIndex = 0;
        int measuredGpuSlots = options.rounds * measuredPerRound;
        for (int frame = 0; frame < total; frame++) {
            int round = frame / (options.warmup + measuredPerRound);
            int frameInRound = frame % (options.warmup + measuredPerRound);
            boolean measured = frameInRound >= options.warmup;
            long windowStart = (long) round * (options.warmup + measuredPerRound) + options.warmup;
            long windowEnd = windowStart + measuredPerRound;
            if (measured && options.cpuIsolation) {
                // Isolated attribution only; the normal run never blocks here.
                GL11.glFinish();
            }
            driver.beginFrame();
            long start = System.nanoTime();
            pipeline.execute(driver.device(), camera, target, 1.0f / 60.0f);
            long elapsed = System.nanoTime() - start;
            FrameProfile profile = pipeline.graph().lastFrameProfile();
            driver.recordGraph(pipeline.graph());
            driver.endFrame();
            long recordNanos = cpuRecordNanos(profile);
            if (measured) {
                wall[measuredIndex] = elapsed / 1_000_000.0f;
                record[measuredIndex] = recordNanos / 1_000_000.0f;
                submit[measuredIndex] = (elapsed - recordNanos) / 1_000_000.0f;
                measuredIndex++;
            }
            for (PassProfile pass : pipeline.graph().drainGpuSamples()) {
                if (pass.gpuStatus() != PassProfile.GpuTimingStatus.AVAILABLE) {
                    continue;
                }
                long sequence = pass.sampleFrameSequence();
                if (sequence < windowStart || sequence >= windowEnd) {
                    continue;
                }
                gpuByPass.computeIfAbsent(pass.passName(), ignored -> new TreeMap<>())
                        .putIfAbsent(sequence, pass.gpuNanos());
            }
        }
        Map<String, float[]> gpuMillis = new LinkedHashMap<>();
        for (Map.Entry<String, Map<Long, Long>> entry : gpuByPass.entrySet()) {
            float[] values = new float[entry.getValue().size()];
            int index = 0;
            for (long nanos : entry.getValue().values()) {
                values[index++] = nanos / 1_000_000.0f;
            }
            gpuMillis.put(entry.getKey(), values);
        }
        float[] assign = gpuMillis.getOrDefault(ASSIGN_PASS, new float[0]);
        double coverage = SampleStatistics.coverage(assign.length, measuredGpuSlots);
        return new Samples(wall, record, submit, gpuMillis, coverage);
    }

    private static long cpuRecordNanos(FrameProfile profile) {
        long nanos = 0L;
        for (PassProfile pass : profile.passes()) {
            nanos = Math.addExact(nanos, pass.cpuRecordNanos());
        }
        return nanos;
    }

    private static float percentileOrNaN(float[] samples, double fraction) {
        if (samples == null || samples.length == 0) {
            return Float.NaN;
        }
        return SampleStatistics.percentileMillis(samples, fraction);
    }

    private static List<Config> configs() {
        return List.of(
                new Config("sparse-8-1080p", StressMode.SPARSE, 8, 1920, 1080, 64, 24, 0.0, true),
                new Config("sparse-128-1080p", StressMode.SPARSE, 128, 1920, 1080, 64, 24, 0.75, true),
                new Config("sparse-256-1080p", StressMode.SPARSE, 256, 1920, 1080, 64, 24, 0.0, true),
                new Config("sparse-512-1080p", StressMode.SPARSE, 512, 1920, 1080, 64, 24, 0.0, false),
                new Config("sparse-1024-1080p", StressMode.SPARSE, 1024, 1920, 1080, 64, 24, 0.0, false),
                new Config("sparse-256-4k", StressMode.SPARSE, 256, 3840, 2160, 64, 24, 1.50, true),
                new Config("overlap-256-1080p", StressMode.OVERLAP, 256, 1920, 1080, 64, 24, 0.0, false),
                new Config("coverage-256-1080p", StressMode.COVERAGE, 256, 1920, 1080, 64, 24, 0.0, true)
        );
    }

    private static String glString(int name) {
        String value = GL11.glGetString(name);
        return value == null ? "unavailable" : value;
    }

    private static String fingerprint() {
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
                                    List<ConfigResult> results, List<String> failures) {
        try {
            Files.createDirectories(directory);
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
        json.writeNumberField("width", result.width);
        json.writeNumberField("height", result.height);
        json.writeStringField("renderer", result.renderer);
        json.writeStringField("glVersion", result.glVersion);
        json.writeNumberField("clusterCount", result.clusterCount);
        json.writeNumberField("inlineCapacity", result.inlineCapacity);
        json.writeNumberField("overflowClusters", result.overflowClusters);
        json.writeNumberField("maxInlineCount", result.maxInlineCount);
        json.writeNumberField("assignCoverage", result.assignCoverage);
        json.writeObjectFieldStart("cpu");
        writeSamples(json, "wallMs", result.wallMillis);
        writeSamples(json, "recordMs", result.recordMillis);
        writeSamples(json, "submitMs", result.submitMillis);
        json.writeEndObject();
        json.writeObjectFieldStart("gpuPasses");
        for (Map.Entry<String, float[]> entry : result.gpuSamples.entrySet()) {
            json.writeObjectFieldStart(entry.getKey());
            writeSamples(json, "millis", entry.getValue());
            json.writeEndObject();
        }
        json.writeEndObject();
        json.writeEndObject();
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

    private record Config(String name, StressMode mode, int lights, int width, int height,
                          int tileSize, int zSlices, double assignBudgetP95Millis,
                          boolean requireNoOverflow) { }

    private record Samples(float[] wallMillis, float[] recordMillis, float[] submitMillis,
                           Map<String, float[]> gpuSamples, double assignCoverage) { }

    private record ConfigResult(String name, int width, int height, float[] wallMillis,
                                float[] recordMillis, float[] submitMillis,
                                Map<String, float[]> gpuSamples, double assignCoverage,
                                int overflowClusters, int maxInlineCount, int clusterCount,
                                int inlineCapacity, String renderer, String glVersion) { }

    private static final class Options {
        private int rounds = 3;
        private int frames = 300;
        private int warmup = 120;
        private boolean formal = true;
        private boolean cpuIsolation = false;
        private String only;

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
                } else {
                    throw new IllegalArgumentException(
                            "Unknown clustered benchmark argument: " + argument);
                }
            }
            if (options.rounds < 1 || options.frames < 1 || options.warmup < 0) {
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
