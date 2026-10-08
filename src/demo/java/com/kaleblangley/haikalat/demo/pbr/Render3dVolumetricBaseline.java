package com.kaleblangley.haikalat.demo.pbr;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonEncoding;
import com.kaleblangley.haikalat.backend.GlCapabilityContract;
import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.lwjgl.opengl.GL;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.lwjgl.opengl.GL46.*;

/** M0 hardware/contract proof. It does not claim volumetric rendering or performance acceptance. */
public final class Render3dVolumetricBaseline {
    private static final Path CONFIG = Path.of("config/volumetric-fog");

    private Render3dVolumetricBaseline() { }

    public static void main(String[] args) throws IOException {
        String fingerprint = Render3dClusteredBenchmarkSuite.fingerprint();
        Path output = Path.of("build/reports/render3d-v0250", fingerprint, UUID.randomUUID().toString());
        Files.createDirectories(output);
        Properties settings = settings();
        List<String> failures = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", 1);
        report.put("kind", "volumetric-m0-baseline");
        report.put("capturedAt", OffsetDateTime.now(ZoneOffset.ofHours(8)).toString());
        report.put("candidateFingerprint", fingerprint);
        report.put("head", command("git", "-c", "safe.directory=" + Path.of("").toAbsolutePath(),
                "rev-parse", "HEAD"));
        report.put("branch", command("git", "-c", "safe.directory=" + Path.of("").toAbsolutePath(),
                "branch", "--show-current"));
        report.put("releaseReady", false);
        report.put("historicalReleaseStatus", "v0243-gtao-performance-failed-no-accepted-exemption");
        report.put("qualityStatus", "not-measured");
        report.put("performanceStatus", "not-measured");
        report.put("aaRenderingStatus", "capacity-and-budget-only; see candidate stage-verification for rendering evidence");
        report.put("startingSnapshotAvailable", Files.exists(Path.of("build/v0250-bootstrap/starting-baseline.json")));
        report.put("cpu", System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unavailable"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        report.put("java", System.getProperty("java.runtime.version"));
        report.put("powerSnapshotColumns", "name,driver_version,pstate,power.draw,power.limit,temperature.gpu,utilization.gpu,memory.total");
        report.put("powerSnapshot", command("nvidia-smi",
                "--query-gpu=name,driver_version,pstate,power.draw,power.limit,temperature.gpu,utilization.gpu,memory.total",
                "--format=csv,noheader"));
        report.put("powerConditions", "instantaneous snapshot; fixed power mode and background load not controlled");
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(32, 32)
                .title("Volumetric M0 baseline").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlCapabilityContract.requireCurrent();
            report.put("vendor", glGetString(GL_VENDOR));
            report.put("renderer", glGetString(GL_RENDERER));
            report.put("openGl", glGetString(GL_VERSION));
            Map<String, Integer> limits = limits();
            report.put("limits", limits);
            failures.addAll(checkBindings(Files.readAllLines(CONFIG.resolve("bindings.tsv")), limits));
            for (int format : new int[]{GL_RGBA16F, GL_R8}) {
                if (glGetInternalformati(GL_TEXTURE_3D, format, GL_INTERNALFORMAT_SUPPORTED) == GL_FALSE
                        || glGetInternalformati(GL_TEXTURE_3D, format, GL_SHADER_IMAGE_LOAD) == GL_NONE
                        || glGetInternalformati(GL_TEXTURE_3D, format, GL_SHADER_IMAGE_STORE) == GL_NONE) {
                    failures.add("3D storage/image format unsupported: " + format);
                }
            }
            List<Map<String, Object>> budgets = new ArrayList<>();
            for (String quality : List.of("low", "balanced", "high")) {
                for (int[] size : List.of(new int[]{1920, 1080}, new int[]{3840, 2160}, new int[]{1, 1}, new int[]{1919, 1079})) {
                    for (int samples : new int[]{1, 2, 4}) {
                        Map<String, Object> budget = budget(settings, quality, size[0], size[1], samples);
                        budgets.add(budget);
                        if ((int) budget.get("nx") > limits.get("max3dDimension")
                                || (int) budget.get("ny") > limits.get("max3dDimension")
                                || (int) budget.get("nz") + 1 > limits.get("max3dDimension")) {
                            failures.add("3D grid exceeds device limit: " + budget);
                        }
                        if (!quality.equals("high") && !(boolean) budget.get("withinBudget")) {
                            failures.add("default quality exceeds resource budget: " + budget);
                        }
                    }
                }
            }
            report.put("resourceBudgets", budgets);
            report.put("resourceBudgetKind", "projected storage bytes including emission; driver/graph overhead still to measure");
            GlDebug.assertNoError("volumetric M0 limits");
        } catch (RuntimeException failure) {
            failures.add(failure.toString());
        }
        if (!fingerprint.equals(Render3dClusteredBenchmarkSuite.fingerprint())) {
            failures.add("source/config changed during baseline capture");
        }
        report.put("failures", failures);
        report.put("status", failures.isEmpty() ? "passed" : "failed");
        try (JsonGenerator json = new JsonFactory().createGenerator(output.resolve("manifest.json").toFile(), JsonEncoding.UTF8)) {
            json.useDefaultPrettyPrinter();
            write(json, report);
        }
        for (String name : List.of("verification.properties", "bindings.tsv")) {
            Files.copy(CONFIG.resolve(name), output.resolve(name));
        }
        Path starting = Path.of("build/v0250-bootstrap/starting-baseline.json");
        if (Files.exists(starting)) Files.copy(starting, output.resolve("starting-baseline.json"));
        System.out.println("Volumetric M0 " + report.get("status") + ": " + output.toAbsolutePath());
        if (!failures.isEmpty()) throw new IllegalStateException(String.join("; ", failures));
    }

    static Properties settings() throws IOException {
        Properties result = new Properties();
        try (var reader = Files.newBufferedReader(CONFIG.resolve("verification.properties"))) {
            result.load(reader);
        }
        if (!"1".equals(result.getProperty("schema"))) throw new IllegalArgumentException("unsupported volumetric contract schema");
        for (String key : result.stringPropertyNames()) {
            if (key.equals("quality.referenceSteps") || key.equals("history.sampleXySequenceBases")) continue;
            double value = Double.parseDouble(result.getProperty(key));
            if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("invalid contract value: " + key);
        }
        if (number(result, "history.weight") > number(result, "history.maxWeight")
                || number(result, "history.maxWeight") > 0.95
                || number(result, "limits.integratedScattering") >= number(result, "limits.halfFinite")) {
            throw new IllegalArgumentException("invalid history/FP16 limits");
        }
        return result;
    }

    static Map<String, Object> budget(Properties settings, String quality, int width, int height, int samples) {
        if (width <= 0 || height <= 0 || (samples != 1 && samples != 2 && samples != 4)) {
            throw new IllegalArgumentException("invalid resource dimensions/sample count");
        }
        int tile = integer(settings, "quality." + quality + ".tilePixels");
        int nz = integer(settings, "quality." + quality + ".depthSlices");
        int nx = Math.toIntExact(((long) width + tile - 1) / tile);
        int ny = Math.toIntExact(((long) height + tile - 1) / tile);
        long columns = Math.multiplyExact((long) nx, ny);
        long voxels = Math.multiplyExact(columns, nz);
        // Current source is RGBA32F for native-column reuse; the other five RGBA volumes remain half.
        // reject/reactive-prefix/light-off reactive are R8. Color prefix has its extra boundary and
        // Reconstruction guide, interval bytes and exact light indices are separate from transport boundaries.
        long prefixExtras=1L+integer(settings,"reconstruction.surfaceGuideLayers")
                +integer(settings,"reconstruction.nearLightIntervalMaskLayers")+integer(settings,"reconstruction.nearLightIndexLayers");
        long owned = Math.addExact(Math.addExact(Math.multiplyExact(voxels, 59), columns * (8*prefixExtras+1)),
                integer(settings, "limits.parameterBytes"));
        long pixels = Math.multiplyExact((long) width, height);
        long fullSize = Math.multiplyExact(pixels, 1L + 8L * samples);
        long total = Math.addExact(owned, fullSize);
        long peak = Math.multiplyExact(total, 2);
        long steadyBudget = integer(settings, samples == 1 ? "budget.singleSampleBytes" : "budget.msaaBytes");
        long peakBudget = integer(settings, samples == 1 ? "budget.singleSampleTransactionBytes" : "budget.msaaTransactionBytes");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("quality", quality);
        row.put("width", width); row.put("height", height); row.put("samples", samples);
        row.put("nx", nx); row.put("ny", ny); row.put("nz", nz);
        row.put("owned3dAndParametersBytes", owned);
        row.put("fullSizeReactiveCompositeBytes", fullSize);
        row.put("steadyBytes", total); row.put("transactionPeakBytes", peak);
        row.put("withinBudget", owned <= integer(settings, "budget.owned3dBytes")
                && total <= steadyBudget && peak <= peakBudget);
        return row;
    }

    static List<String> checkBindings(List<String> rows, Map<String, Integer> limits) {
        List<String> failures = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        var slots = new HashSet<String>();
        for (String row : rows) {
            if (row.isBlank() || row.stripLeading().startsWith("#")) continue;
            String[] fields = row.strip().split("\\s+");
            if (fields.length != 6 || !List.of("existing", "planned").contains(fields[5])) {
                failures.add("invalid binding row: " + row); continue;
            }
            String slot = fields[0] + ":" + fields[2] + ":" + fields[3];
            if (!slots.add(slot)) failures.add("duplicate shader binding: " + slot);
            Integer limit = limits.get(fields[2] + ".bindings");
            Integer stageLimit = limits.get(fields[1] + "." + fields[2] + ".count");
            int binding = Integer.parseInt(fields[3]);
            if (limit == null || stageLimit == null || binding < 0 || binding >= limit) {
                failures.add("binding exceeds namespace capacity: " + row); continue;
            }
            String countKey = fields[0] + ":" + fields[1] + ":" + fields[2];
            int count = counts.merge(countKey, 1, Integer::sum);
            if (count > stageLimit) failures.add("active resources exceed stage capacity: " + countKey);
        }
        return failures;
    }

    private static Map<String, Integer> limits() {
        Map<String, Integer> limits = new LinkedHashMap<>();
        limits.put("max3dDimension", glGetInteger(GL_MAX_3D_TEXTURE_SIZE));
        limits.put("sampler.bindings", glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS));
        limits.put("image.bindings", glGetInteger(GL_MAX_IMAGE_UNITS));
        limits.put("ssbo.bindings", glGetInteger(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS));
        limits.put("ubo.bindings", glGetInteger(GL_MAX_UNIFORM_BUFFER_BINDINGS));
        limits.put("fragment.sampler.count", glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS));
        limits.put("fragment.image.count", glGetInteger(GL_MAX_FRAGMENT_IMAGE_UNIFORMS));
        limits.put("fragment.ssbo.count", glGetInteger(GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS));
        limits.put("fragment.ubo.count", glGetInteger(GL_MAX_FRAGMENT_UNIFORM_BLOCKS));
        limits.put("vertex.sampler.count", glGetInteger(GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS));
        limits.put("vertex.image.count", glGetInteger(GL_MAX_VERTEX_IMAGE_UNIFORMS));
        limits.put("vertex.ssbo.count", glGetInteger(GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS));
        limits.put("vertex.ubo.count", glGetInteger(GL_MAX_VERTEX_UNIFORM_BLOCKS));
        limits.put("compute.sampler.count", glGetInteger(GL_MAX_COMPUTE_TEXTURE_IMAGE_UNITS));
        limits.put("compute.image.count", glGetInteger(GL_MAX_COMPUTE_IMAGE_UNIFORMS));
        limits.put("compute.ssbo.count", glGetInteger(GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS));
        limits.put("compute.ubo.count", glGetInteger(GL_MAX_COMPUTE_UNIFORM_BLOCKS));
        limits.put("compute.maxInvocations", glGetInteger(GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS));
        for (int axis = 0; axis < 3; axis++) {
            limits.put("compute.maxGroupSize." + axis, glGetIntegeri(GL_MAX_COMPUTE_WORK_GROUP_SIZE, axis));
            limits.put("compute.maxGroupCount." + axis, glGetIntegeri(GL_MAX_COMPUTE_WORK_GROUP_COUNT, axis));
        }
        return limits;
    }

    private static int integer(Properties settings, String key) {
        int value = Integer.parseInt(settings.getProperty(key));
        if (value <= 0) throw new IllegalArgumentException("invalid positive integer: " + key);
        return value;
    }

    private static double number(Properties settings, String key) {
        return Double.parseDouble(settings.getProperty(key));
    }

    private static String command(String... arguments) {
        try {
            Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "unavailable: command timed out";
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return process.exitValue() == 0 ? output : "unavailable: " + output;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return "unavailable: interrupted";
        } catch (IOException failure) {
            return "unavailable: " + failure.getMessage();
        }
    }

    private static void write(JsonGenerator json, Object value) throws IOException {
        if (value instanceof Map<?, ?> map) {
            json.writeStartObject();
            for (var entry : map.entrySet()) { json.writeFieldName(entry.getKey().toString()); write(json, entry.getValue()); }
            json.writeEndObject();
        } else if (value instanceof List<?> list) {
            json.writeStartArray(); for (Object item : list) write(json, item); json.writeEndArray();
        } else if (value instanceof Number number) {
            json.writeNumber(number.toString());
        } else if (value instanceof Boolean bool) {
            json.writeBoolean(bool);
        } else if (value == null) {
            json.writeNull();
        } else {
            json.writeString(value.toString());
        }
    }
}
