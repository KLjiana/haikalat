package com.kaleblangley.haikalat.demo.pbr;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Same-scene native TAA A/B, with per-pass GPU samples and immutable run reports. */
public final class Render3dTemporalBenchmarkSuite {
    private Render3dTemporalBenchmarkSuite() {}

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        String candidate = fingerprint();
        Path directory = Path.of("build/reports/temporal", candidate,
                java.util.UUID.randomUUID().toString());
        List<Render3dTemporalDemo.BenchmarkResult> runs = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try {
            for (String size : options.sizes) {
                String[] extent = size.split("x");
                int width = Integer.parseInt(extent[0]), height = Integer.parseInt(extent[1]);
                List<Long> off50 = new ArrayList<>(), on50 = new ArrayList<>();
                List<Long> off95 = new ArrayList<>(), on95 = new ArrayList<>();
                List<Long> offRecord = new ArrayList<>(), onRecord = new ArrayList<>();
                List<Long> resolve = new ArrayList<>();
                for (int round = 0; round < options.rounds; round++) {
                    // Alternate ordering to expose warm-cache/thermal bias.
                    for (String mode : round % 2 == 0 ? List.of("NONE", "TAA") : List.of("TAA", "NONE")) {
                        Render3dTemporalDemo.main(new String[]{"--hidden", "--benchmark",
                                "--frames=" + options.frames, "--warmup=" + options.warmup,
                                "--size=" + size, "--aa=" + mode});
                        var result = Render3dTemporalDemo.lastBenchmarkResult();
                        if (result == null || result.measuredFrames() != options.frames) {
                            throw new IllegalStateException("incorrect measured frame count: " + size + "/" + mode);
                        }
                        runs.add(result);
                        boolean taa = mode.equals("TAA");
                        (taa ? on50 : off50).add(result.cpuP50Nanos());
                        (taa ? on95 : off95).add(result.cpuP95Nanos());
                        (taa ? onRecord : offRecord).add(result.recordP50Nanos());
                        for (var entry : result.gpuPassNanos().entrySet()) {
                            if (entry.getValue().size() < Math.ceil(options.frames * 0.9)) {
                                failures.add(size + "/" + mode + "/" + entry.getKey() + ": insufficient GPU samples");
                            }
                        }
                        if (result.gpuFrameNanos().size() < Math.ceil(options.frames * 0.9)) {
                            failures.add(size + "/" + mode + ": insufficient complete GPU frame samples");
                        }
                        if (taa) resolve.addAll(result.gpuPassNanos().getOrDefault("TaaPass", List.of()));
                    }
                }
                // CPU recording is resolution independent and is the budget that
                // belongs to the TAA feature.  The wall-clock window also
                // contains device.execute, which blocks on GPU back-pressure at
                // 4K, so it is reported separately and gated only as a sanity
                // bound.
                double wall = percentile(on50, .5) - percentile(off50, .5);
                double cpu95 = percentile(on95, .5) - percentile(off95, .5);
                double record = percentile(onRecord, .5) - percentile(offRecord, .5);
                double submit = wall - record;
                double gpu = percentile(resolve, .5);
                System.out.printf(Locale.ROOT,
                        "temporal A/B %s: recordDelta=%.4fms submitDelta=%.4fms wallDelta=%.4fms "
                                + "wallP95Delta=%.4fms resolveGpuP50=%.4fms%n",
                        size, record, submit, wall, cpu95, gpu);
                if (options.formal) {
                    check(record, options.maxCpuDeltaMs, size + " CPU record delta", failures);
                    check(wall, options.maxWallDeltaMs, size + " wall delta", failures);
                    check(cpu95, options.maxCpuP95DeltaMs, size + " wall p95 delta", failures);                    check(gpu, width * (long) height <= 1920L * 1080 ? 1.0 : 3.0,
                            size + " TAA resolve GPU", failures);
                }
            }
            if (!candidate.equals(fingerprint())) failures.add("source changed during measurement");
        } catch (RuntimeException failure) {
            failures.add(failure.toString());
            throw failure;
        } finally {
            writeReport(directory, candidate, options, runs, failures);
            System.out.println("temporal report: " + directory.toAbsolutePath());
        }
        if (!failures.isEmpty()) throw new IllegalStateException("temporal benchmark failed: " + failures);
    }

    private static void check(double value, double limit, String label, List<String> failures) {
        if (!Double.isFinite(value) || value > limit) failures.add(label + "=" + value + "ms, limit=" + limit);
    }

    private static double percentile(List<Long> values, double fraction) {
        if (values.isEmpty()) return Double.NaN;
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1)] / 1e6;
    }

    private static String fingerprint() {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            List<Path> paths = new ArrayList<>();
            for (String root : List.of("src", "gradle", "config")) {
                try (var stream = Files.walk(Path.of(root))) {
                    stream.filter(Files::isRegularFile).forEach(paths::add);
                }
            }
            paths.add(Path.of("build.gradle"));
            paths.sort(Comparator.comparing(Path::toString));
            for (Path path : paths) {
                digest.update(path.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(path));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new IllegalStateException("cannot fingerprint candidate", failure);
        }
    }

    private static void metric(JsonGenerator json, String name, List<Long> samples) throws java.io.IOException {
        json.writeObjectFieldStart(name);
        json.writeNumberField("samples", samples.size());
        if (samples.isEmpty()) {
            json.writeNullField("p50Ms"); json.writeNullField("p95Ms");
        } else {
            json.writeNumberField("p50Ms", percentile(samples, .5));
            json.writeNumberField("p95Ms", percentile(samples, .95));
        }
        json.writeArrayFieldStart("rawNanos");
        for (long value : samples) json.writeNumber(value);
        json.writeEndArray(); json.writeEndObject();
    }

    private static void writeReport(Path directory, String candidate, Options options,
                                    List<Render3dTemporalDemo.BenchmarkResult> runs, List<String> failures) {
        try {
            Files.createDirectories(directory);
            try (JsonGenerator json = new JsonFactory().createGenerator(directory.resolve("metrics.json").toFile(),
                    com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
                json.useDefaultPrettyPrinter(); json.writeStartObject();
                json.writeStringField("candidateSha256", candidate);
                json.writeStringField("generatedAt", java.time.Instant.now().toString());
                json.writeStringField("java", System.getProperty("java.version"));
                json.writeBooleanField("formal", options.formal);
                json.writeStringField("status", failures.isEmpty() ? "passed" : "failed");
                json.writeStringField("scene", "temporal checkerboard + translating foreground; moving camera; fixed 1/60s");
                json.writeStringField("color", "linear HDR / ACES for both modes");
                json.writeStringField("jitter", "TAA four-phase +/-0.25 pixel; NONE zero");
                json.writeNumberField("rounds", options.rounds);
                json.writeNumberField("warmupFrames", options.warmup);
                json.writeNumberField("measuredFramesPerRound", options.frames);
                json.writeNumberField("gpuDrainFrames", 16);
                json.writeStringField("cpuIsolation",
                        "glFinish before each measured frame; CPU window excludes driver back-pressure");
                json.writeNumberField("maxCpuDeltaMs", options.maxCpuDeltaMs);
                json.writeNumberField("maxCpuP95DeltaMs", options.maxCpuP95DeltaMs);
                json.writeNumberField("maxWallDeltaMs", options.maxWallDeltaMs);
                json.writeNumberField("maxResolveGpu1080pMs", 1);
                json.writeNumberField("maxResolveGpu4kMs", 3);
                json.writeNullField("driverAllocationPeakBytes");
                json.writeStringField("resourceMeasurement", "driver peak unavailable; graph layout recorded per run");
                json.writeArrayFieldStart("failures");
                for (String failure : failures) json.writeString(failure);
                json.writeEndArray();
                json.writeArrayFieldStart("runs");
                for (var run : runs) {
                    json.writeStartObject();
                    json.writeStringField("mode", run.mode());
                    json.writeNumberField("width", run.width()); json.writeNumberField("height", run.height());
                    json.writeStringField("renderer", run.renderer()); json.writeStringField("glVersion", run.glVersion());
                    json.writeStringField("graph", run.graphDescription());
                    json.writeNumberField("measuredFrames", run.measuredFrames());
                    json.writeNumberField("cpuP50Ms", run.cpuP50Nanos() / 1e6);
                    json.writeNumberField("cpuP95Ms", run.cpuP95Nanos() / 1e6);
                    json.writeNumberField("recordP50Ms", run.recordP50Nanos() / 1e6);
                    json.writeNumberField("submitP50Ms",
                            (run.cpuP50Nanos() - run.recordP50Nanos()) / 1e6);
                    json.writeObjectFieldStart("cpuPasses");
                    for (var entry : new TreeMap<>(run.cpuPassNanos()).entrySet()) {
                        json.writeNumberField(entry.getKey(), entry.getValue() / 1e6);
                    }
                    json.writeEndObject();
                    metric(json, "gpuFrame", run.gpuFrameNanos());
                    json.writeObjectFieldStart("gpuPasses");
                    for (var entry : new TreeMap<>(run.gpuPassNanos()).entrySet()) metric(json, entry.getKey(), entry.getValue());
                    json.writeEndObject(); json.writeEndObject();
                }
                json.writeEndArray(); json.writeEndObject();
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("failed to preserve benchmark evidence", failure);
        }
    }
    private static final class Options {
        private int rounds = 3;
        private int frames = 60;
        private int warmup = 30;
        private String[] sizes = {"1920x1080", "3840x2160"};
        private double maxCpuDeltaMs = 0.5;
        private double maxCpuP95DeltaMs = 6.0;
        private double maxWallDeltaMs = 3.0;
        private boolean formal = true;

        private static Options parse(String[] arguments) {
            Options options = new Options();
            for (String argument : arguments) {
                if (argument.startsWith("--rounds=")) {
                    options.rounds = Integer.parseInt(argument.substring("--rounds=".length()));
                } else if (argument.startsWith("--frames=")) {
                    options.frames = Integer.parseInt(argument.substring("--frames=".length()));
                } else if (argument.startsWith("--warmup=")) {
                    options.warmup = Integer.parseInt(argument.substring("--warmup=".length()));
                } else if (argument.startsWith("--sizes=")) {
                    options.sizes = argument.substring("--sizes=".length()).split(",");
                } else if (argument.startsWith("--max-cpu-delta-ms=")) {
                    options.maxCpuDeltaMs = Double.parseDouble(
                            argument.substring("--max-cpu-delta-ms=".length()));
                } else if (argument.startsWith("--max-cpu-p95-delta-ms=")) {
                    options.maxCpuP95DeltaMs = Double.parseDouble(
                            argument.substring("--max-cpu-p95-delta-ms=".length()));
                } else if (argument.startsWith("--max-wall-delta-ms=")) {
                    options.maxWallDeltaMs = Double.parseDouble(
                            argument.substring("--max-wall-delta-ms=".length()));
                } else if (argument.equals("--informal")) {
                    options.formal = false;
                } else {
                    throw new IllegalArgumentException("Unknown temporal benchmark argument: "
                            + argument);
                }
            }
            if (options.rounds < 1 || options.frames < 1 || options.warmup < 0) {
                throw new IllegalArgumentException("invalid temporal benchmark counts");
            }
            if (!Double.isFinite(options.maxCpuDeltaMs) || options.maxCpuDeltaMs < 0
                    || !Double.isFinite(options.maxCpuP95DeltaMs) || options.maxCpuP95DeltaMs < 0) {
                throw new IllegalArgumentException("budgets must be finite and nonnegative");
            }
            if (options.formal && (options.rounds < 3 || options.frames < 30 || options.warmup < 30)) {
                throw new IllegalArgumentException("formal measurement requires 3 rounds, 30 measured and 30 warmup frames");
            }
            for (String size : options.sizes) {
                if (!size.matches("[1-9][0-9]*x[1-9][0-9]*")) {
                    throw new IllegalArgumentException("invalid extent: " + size);
                }
            }
            return options;
        }
    }
}
