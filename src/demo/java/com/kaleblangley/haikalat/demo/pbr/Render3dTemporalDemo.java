package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.Render3dDiagnostics;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;

import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glReadPixels;

/**
 * v0.24.1 temporal demonstration and verification runner.
 *
 * <p>Select AA and independent camera/animation motion with command-line
 * options. Reset, resize and capture use deterministic frame indices.</p>
 */
public final class Render3dTemporalDemo {
    private static volatile BenchmarkResult lastBenchmarkResult;

    /** Per-frame timing exposed to {@code Render3dTemporalBenchmarkSuite}. */
    public record BenchmarkResult(String mode, int width, int height, int measuredFrames,
                                  long cpuP50Nanos, long cpuP95Nanos,
                                  long recordP50Nanos,
                                  Map<String, Long> cpuPassNanos,
                                  Map<String, List<Long>> gpuPassNanos,
                                  List<Long> gpuFrameNanos,
                                  String renderer, String glVersion, String graphDescription) {
    }

    public static BenchmarkResult lastBenchmarkResult() {
        return lastBenchmarkResult;
    }
    private static final String VERTEX_SOURCE = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec2 aTexCoord;
            layout (std140) uniform CameraBlock {
                mat4 uProjection;
                mat4 uView;
            };
            uniform mat4 uModel;
            out vec2 vUv;
            void main() {
                vUv = aTexCoord;
                // Match SceneSurfacePass exactly for shared-depth LEQUAL.
                vec4 world = uModel * vec4(aPos, 1.0);
                gl_Position = uProjection * uView * world;
            }
            """;

    private static final String FRAGMENT_SOURCE = """
            #version 330 core
            in vec2 vUv;
            out vec4 FragColor;
            void main() {
                float checker = mod(floor(vUv.x * 32.0) + floor(vUv.y * 32.0), 2.0);
                FragColor = vec4(mix(vec3(0.06, 0.12, 0.2), vec3(0.9, 0.72, 0.3), checker), 1.0);
            }
            """;

    private Render3dTemporalDemo() {
    }

    public static void main(String[] arguments) {
        lastBenchmarkResult = null;
        Options options = Options.parse(arguments);
        if (options.benchmark) {
            // Match the GTAO benchmark: per-pass debug groups are diagnostics,
            // not production work, and must not pollute the CPU A/B.
            System.setProperty("haikalat.render.disableDebugGroups", "true");
        }
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(options.width, options.height)
                .title("Haikalat v0.24.1 Temporal")
                .visible(!options.hidden)
                .decorated(!options.hidden)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            run(window, options);
        }
    }

    private static void run(GlfwWindow window, Options options) {
        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(options.antiAliasingMode)
                .toneMappingMode(com.kaleblangley.haikalat.runtime.ToneMappingMode.ACES)
                .vsync(!options.hidden && !options.benchmark)
                .build();
        ShaderProgram shader = ShaderProgram.fromSources(VERTEX_SOURCE, FRAGMENT_SOURCE);
        Mesh mesh = Mesh.from(BuiltinMeshData.texturedQuad("temporal-demo"));
        Material material = Material.builder(shader).build();
        Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 5.0f));
        float[] animationSeconds = {0.0f};
        Scene scene = new Scene(camera);
        scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()
                .translate(0.0f, 0.0f, -2.0f).scale(3.2f), false));
        scene.add(new SceneObject(mesh, material, (model, frame) -> {
            float time = options.pauseAnimation ? 0.0f : animationSeconds[0];
            model.identity().translate((float) Math.sin(time) * 1.2f, 0.0f, -1.0f)
                    .scale(0.6f);
        }, false));

        RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings);
        try (FrameDriver driver = new FrameDriver(settings)) {
            pipeline.build();
            if (options.benchmark) pipeline.graph().enableGpuSampleCollection();
            int frames = options.benchmark ? Math.addExact(Math.addExact(options.warmup, options.frames), 16)
                    : options.frames > 0 ? options.frames : Integer.MAX_VALUE;
            int frame = 0;
            List<Long> cpuSamples = new ArrayList<>();
            List<Long> recordSamples = new ArrayList<>();
            Map<String, Long> cpuPassTotals = new TreeMap<>();
            Map<String, Map<Long, Long>> gpuSamples = new TreeMap<>();
            long lastNanos = System.nanoTime();
            while (frame < frames && !window.shouldClose()) {
                long nowNanos = System.nanoTime();
                // Deterministic runs (hidden, benchmark or sequence capture) use
                // a fixed step; interactive runs use the real wall-clock delta.
                boolean deterministic = options.hidden || options.captureSequence != null;
                float deltaSeconds = deterministic
                        ? 1.0f / 60.0f
                        : Math.min(0.1f, (nowNanos - lastNanos) / 1.0e9f);
                lastNanos = nowNanos;
                animationSeconds[0] += deltaSeconds;
                if (options.cutFrame >= 0 && frame >= options.cutFrame) {
                    // Hard camera cut: exercises automatic history invalidation
                    // and the ghost-decay acceptance metric.
                    camera.setPosition(new Vector3f(3.5f, 1.5f, 5.5f));
                    camera.setYaw(-60.0f);
                    camera.setPitch(-10.0f);
                } else if (!options.pauseCamera) {
                    float time = animationSeconds[0];
                    camera.setPosition(new Vector3f((float) Math.cos(time) * 1.5f, 0.4f, 5.0f));
                    camera.setYaw(-90.0f + (float) Math.sin(time) * 10.0f);
                }
                if (options.resizeFrame == frame) {
                    window.resize(options.resizeWidth, options.resizeHeight);
                }
                if (window.consumeResize()) {
                    pipeline.resize(window.width(), window.height());
                }
                if (options.resetFrame == frame) {
                    pipeline.resetTemporalHistory();
                }
                if (options.benchmark) {
                    // Drain the driver queue outside the measured window so the
                    // CPU A/B is not inflated by GPU back-pressure at 4K.
                    org.lwjgl.opengl.GL11.glFinish();
                }
                driver.beginFrame();
                try {
                    pipeline.execute(driver.device(), deltaSeconds);
                    driver.recordGraph(pipeline.graph());
                    driver.endFrame();
                } catch (RuntimeException | Error failure) {
                    driver.failFrame(pipeline.graph(), failure);
                    throw failure;
                }
                if (options.capturePath != null && frame + 1 == frames) {
                    captureFrame(window.width(), window.height(), options.capturePath);
                }
                if (options.captureSequence != null) {
                    captureFrame(window.width(), window.height(),
                            options.captureSequence + "/frame_%04d.png".formatted(frame));
                }
                driver.present(window::swapBuffers);
                window.pollEvents();
                if (options.benchmark && frame >= options.warmup
                        && frame < options.warmup + options.frames) {
                    cpuSamples.add(driver.statistics().lastFrameDurationNanos());
                    long recordNanos = 0L;
                    for (var pass : pipeline.graph().lastFrameProfile().passes()) {
                        recordNanos += pass.cpuRecordNanos();
                        cpuPassTotals.merge(pass.passName(), pass.cpuRecordNanos(), Long::sum);
                    }
                    recordSamples.add(recordNanos);
                }
                if (options.benchmark) {
                    for (var pass : pipeline.graph().drainGpuSamples()) {
                        if (pass.gpuStatus() == com.kaleblangley.haikalat.core.graph.PassProfile.GpuTimingStatus.AVAILABLE
                                && pass.sampleFrameSequence() >= options.warmup
                                && pass.sampleFrameSequence() < options.warmup + options.frames) {
                            gpuSamples.computeIfAbsent(pass.passName(), ignored -> new TreeMap<>())
                                    .putIfAbsent(pass.sampleFrameSequence(), pass.gpuNanos());
                        }
                    }
                }
                frame++;
            }
            if (options.benchmark) {
                publishBenchmark(options, cpuSamples, recordSamples, cpuPassTotals, gpuSamples, pipeline);
            }
            if (options.verify) {
                verify(pipeline, options, frame);
            }
        } finally {
            pipeline.close();
            material.close();
            mesh.close();
            shader.close();
        }
    }

    private static void publishBenchmark(Options options, List<Long> samples,
                                         List<Long> recordSamples,
                                         Map<String, Long> cpuPassTotals,
                                         Map<String, Map<Long, Long>> gpu,
                                         RenderPipeline pipeline) {
        if (samples.size() != options.frames) {
            throw new IllegalStateException("temporal benchmark expected " + options.frames
                    + " measured frames, got " + samples.size());
        }
        long[] sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
        long p50 = sorted[(int) Math.min(sorted.length - 1L, sorted.length / 2L)];
        long p95 = sorted[(int) Math.min(sorted.length - 1L,
                Math.round(sorted.length * 0.95) - 1L)];
        long[] recordSorted = recordSamples.stream().mapToLong(Long::longValue).sorted().toArray();
        long recordP50 = recordSorted.length == 0 ? 0L
                : recordSorted[recordSorted.length / 2];
        Map<String, List<Long>> passSamples = new TreeMap<>();
        for (var pass : pipeline.graph().description().passes()) {
            var values = gpu.getOrDefault(pass.name(), Map.of());
            passSamples.put(pass.name(), List.copyOf(values.values()));
        }
        List<Long> frameSamples = new ArrayList<>();
        for (long sequence = options.warmup; sequence < options.warmup + options.frames; sequence++) {
            long total = 0;
            boolean complete = true;
            for (String name : passSamples.keySet()) {
                Long duration = gpu.getOrDefault(name, Map.of()).get(sequence);
                if (duration == null) { complete = false; break; }
                total += duration;
            }
            if (complete) frameSamples.add(total);
        }
        Map<String, Long> cpuPassNanos = new TreeMap<>();
        cpuPassTotals.forEach((name, total) -> cpuPassNanos.put(name, total / options.frames));
        BenchmarkResult result = new BenchmarkResult(options.antiAliasingMode.name(),
                options.width, options.height, sorted.length, p50, p95, recordP50,
                Map.copyOf(cpuPassNanos), Map.copyOf(passSamples), List.copyOf(frameSamples),
                org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER),
                org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION),
                pipeline.graph().description().toString());
        lastBenchmarkResult = result;
        System.out.printf(Locale.ROOT,
                "TEMPORAL_BENCH mode=%s size=%dx%d frames=%d cpuP50Ms=%.4f cpuP95Ms=%.4f "
                        + "recordP50Ms=%.4f submitP50Ms=%.4f%n",
                result.mode(), result.width(), result.height(), result.measuredFrames(),
                result.cpuP50Nanos() / 1.0e6, result.cpuP95Nanos() / 1.0e6,
                result.recordP50Nanos() / 1.0e6,
                (result.cpuP50Nanos() - result.recordP50Nanos()) / 1.0e6);
        result.cpuPassNanos().entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(8)
                .forEach(entry -> System.out.printf(Locale.ROOT,
                        "TEMPORAL_CPU_PASS %s %.4f ms%n",
                        entry.getKey(), entry.getValue() / 1.0e6));
    }

    private static void verify(RenderPipeline pipeline, Options options, int frames) {
        Render3dDiagnostics diagnostics = pipeline.lastRender3dDiagnostics();
        if (!diagnostics.available()) {
            throw new IllegalStateException("temporal demo diagnostics are unavailable");
        }
        if (!diagnostics.failureStage().isEmpty()) {
            throw new IllegalStateException("temporal demo frame failed at "
                    + diagnostics.failureStage());
        }
        boolean taaPass = pipeline.graph().description().passes().stream()
                .anyMatch(pass -> pass.name().equals("TaaPass"));
        if (options.antiAliasingMode == AntiAliasingMode.TAA) {
            if (!taaPass) {
                throw new IllegalStateException("TAA mode must register the TaaPass");
            }
            boolean surfacePass = pipeline.graph().description().passes().stream()
                    .anyMatch(pass -> pass.name().equals("SceneSurfacePass"));
            if (!surfacePass) {
                throw new IllegalStateException("TAA mode must register the SceneSurfacePass");
            }
        } else if (taaPass) {
            throw new IllegalStateException("non-TAA mode must not register the TaaPass");
        }
        if (frames <= 0) {
            throw new IllegalStateException("temporal demo executed no frames");
        }
        System.out.printf(Locale.ROOT,
                "temporal demo ok: aa=%s frames=%d generation=%d topologyRebuilt=%s%n",
                options.antiAliasingMode, frames, diagnostics.activeGenerationId(),
                diagnostics.topologyRebuilt());
    }

    private static void captureFrame(int width, int height, String path) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = (x + (height - 1 - y) * width) * 4;
                int r = Byte.toUnsignedInt(pixels.get(index));
                int g = Byte.toUnsignedInt(pixels.get(index + 1));
                int b = Byte.toUnsignedInt(pixels.get(index + 2));
                image.setRGB(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
            }
        }
        try {
            Path output = Path.of(path);
            if (output.getParent() != null) {
                java.nio.file.Files.createDirectories(output.getParent());
            }
            ImageIO.write(image, "png", output.toFile());
        } catch (IOException failure) {
            throw new IllegalStateException("failed to capture " + path, failure);
        }
    }

    private static final class Options {
        private boolean hidden;
        private boolean verify;
        private boolean benchmark;
        private int warmup;
        private boolean pauseCamera;
        private boolean pauseAnimation;
        private int width = 1280;
        private int height = 720;
        private int frames = -1;
        private int resizeFrame = -1;
        private int resizeWidth;
        private int resizeHeight;
        private int resetFrame = -1;
        private int cutFrame = -1;
        private String capturePath;
        private String captureSequence;
        private AntiAliasingMode antiAliasingMode = AntiAliasingMode.TAA;

        private static Options parse(String[] arguments) {
            Options options = new Options();
            for (String argument : arguments) {
                if (argument.equals("--hidden") || argument.equals("--deterministic")) {
                    options.hidden = true;
                } else if (argument.equals("--verify")) {
                    options.verify = true;
                } else if (argument.equals("--benchmark")) {
                    options.benchmark = true;
                    options.hidden = true;
                } else if (argument.startsWith("--warmup=")) {
                    options.warmup = Integer.parseInt(argument.substring("--warmup=".length()));
                } else if (argument.equals("--pause-camera")) {
                    options.pauseCamera = true;
                } else if (argument.equals("--pause-animation")) {
                    options.pauseAnimation = true;
                } else if (argument.startsWith("--frames=")) {
                    options.frames = Integer.parseInt(argument.substring("--frames=".length()));
                } else if (argument.startsWith("--size=")) {
                    String[] size = argument.substring("--size=".length()).split("x");
                    options.width = Integer.parseInt(size[0]);
                    options.height = Integer.parseInt(size[1]);
                } else if (argument.startsWith("--resize=")) {
                    String[] parts = argument.substring("--resize=".length()).split(":");
                    options.resizeFrame = Integer.parseInt(parts[0]);
                    String[] size = parts[1].split("x");
                    options.resizeWidth = Integer.parseInt(size[0]);
                    options.resizeHeight = Integer.parseInt(size[1]);
                } else if (argument.startsWith("--reset=")) {
                    options.resetFrame = Integer.parseInt(argument.substring("--reset=".length()));
                } else if (argument.startsWith("--cut=")) {
                    options.cutFrame = Integer.parseInt(argument.substring("--cut=".length()));
                } else if (argument.startsWith("--capture-sequence=")) {
                    options.captureSequence = argument.substring("--capture-sequence=".length());
                } else if (argument.startsWith("--capture=")) {
                    options.capturePath = argument.substring("--capture=".length());
                } else if (argument.startsWith("--aa=")) {
                    options.antiAliasingMode = AntiAliasingMode.valueOf(
                            argument.substring("--aa=".length()).toUpperCase(Locale.ROOT));
                } else {
                    throw new IllegalArgumentException("Unknown temporal demo argument: " + argument);
                }
            }
            if (options.width <= 0 || options.height <= 0) {
                throw new IllegalArgumentException("temporal demo extent must be positive");
            }
            if (options.warmup < 0 || options.frames == 0 || options.frames < -1) {
                throw new IllegalArgumentException("invalid frame counts");
            }
            if (options.benchmark && (options.frames <= 0 || options.resizeFrame >= 0
                    || options.resetFrame >= 0 || options.capturePath != null)) {
                throw new IllegalArgumentException("benchmark requires positive measured frames and no resize/reset/capture");
            }
            if (options.resizeFrame >= 0 && (options.resizeWidth <= 0 || options.resizeHeight <= 0)) {
                throw new IllegalArgumentException("resize extent must be positive");
            }
            if (options.capturePath != null && options.frames <= 0) {
                throw new IllegalArgumentException("capture requires a finite positive --frames count");
            }
            if (options.captureSequence != null && options.frames <= 0) {
                throw new IllegalArgumentException(
                        "capture-sequence requires a finite positive --frames count");
            }
            return options;
        }
    }
}
