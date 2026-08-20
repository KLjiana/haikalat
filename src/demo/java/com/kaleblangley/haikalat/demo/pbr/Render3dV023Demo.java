package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.core.graph.FrameProfile;
import com.kaleblangley.haikalat.core.graph.PassProfile;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.CullMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.demo.DemoSupport;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.FrameClock;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.FrameBenchmarkSession;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.FogSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoQuality;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Render3dDiagnostics;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrTextureBinding;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Interactive proof scene for the Render3D v0.23 M3-M7 contracts. */
public final class Render3dV023Demo {
    private static final int CASCADE_COUNT = 4;
    private static final int CASCADE_ATLAS_SIZE = 4096;
    private static volatile BenchmarkResult lastBenchmarkResult;

    private Render3dV023Demo() {
    }

    static BenchmarkResult lastBenchmarkResult() {
        return lastBenchmarkResult;
    }

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(options.width(), options.height())
                .title("Haikalat Render3D v0.23.2")
                .visible(!options.hidden())
                .decorated(!options.hidden())
                .cursorMode(options.hidden()
                        ? GlfwWindow.CursorMode.NORMAL : GlfwWindow.CursorMode.DISABLED)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(!options.hidden());
            String previousDisableDebugGroups = System.getProperty(
                    "haikalat.render.disableDebugGroups");
            String previousAggregateGtaoTimer = System.getProperty(
                    "haikalat.render.aggregateGtaoGpuTimer");
            if (options.benchmark()) {
                // Debug groups are valuable for interactive RenderDoc/Nsight
                // captures, but their per-pass GL calls distort a CPU-frame
                // benchmark.  Keep them enabled for normal runs and disable
                // only for the post-warmup benchmark contract.
                System.setProperty("haikalat.render.disableDebugGroups", "true");
                // Keep a valid GPU sample for the complete GTAO chain while
                // avoiding one query ring and availability poll per fullscreen
                // pass.  Interactive diagnostics retain per-pass timings.
                System.setProperty("haikalat.render.aggregateGtaoGpuTimer", "true");
            }
            try {
                run(window, options);
            } finally {
                if (previousDisableDebugGroups == null) {
                    System.clearProperty("haikalat.render.disableDebugGroups");
                } else {
                    System.setProperty("haikalat.render.disableDebugGroups",
                            previousDisableDebugGroups);
                }
                if (previousAggregateGtaoTimer == null) {
                    System.clearProperty("haikalat.render.aggregateGtaoGpuTimer");
                } else {
                    System.setProperty("haikalat.render.aggregateGtaoGpuTimer",
                            previousAggregateGtaoTimer);
                }
            }
        }
    }

    private static void run(GlfwWindow window, Options options) {
        RenderSettings settings = RenderSettings.builder()
                .vsync(!options.hidden())
                .antiAliasingMode(AntiAliasingMode.MSAA)
                .msaaSamples(4)
                .toneMappingMode(ToneMappingMode.ACES)
                .exposureMode(ExposureMode.MANUAL)
                .exposure(1.1f)
                .bloomSettings(BloomSettings.builder().enabled(false).build())
                .sceneVisibility(true)
                .build();

        List<Material> materials = new ArrayList<>();
        Throwable primaryFailure = null;
        try (FrameDriver driver = new FrameDriver(settings);
             PbrEnvironment environment = PbrEnvironmentLoader.load(driver.device(),
                     Render3dV023Demo.class, "/environments/pbr/studio-small.hdr",
                     PbrEnvironmentSettings.quality(options.environmentQuality()));
             PbrFallbackTextures fallbacks = new PbrFallbackTextures();
             ShaderProgram pbrShader = ShaderProgram.fromResource(Render3dV023Demo.class,
                     "/shaders/render3d/pbr/pbr-forward.vert",
                     "/shaders/render3d/pbr/pbr-forward.frag");
             Mesh sphere = Mesh.from(PbrSphereMesh.create(32, 20));
             Texture2D cutoutTexture = createCutoutTexture()) {
            Scene scene = createScene(sphere, pbrShader, fallbacks, cutoutTexture, materials);
            RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings, environment)
                    .postProcessSettings(PostProcessSettings.builder()
                            .gtao(options.gtaoSettings())
                            .fog(FogSettings.builder()
                                    .color(0.28f, 0.34f, 0.44f)
                                    .distanceDensity(0.012f)
                                    .heightDensity(0.018f)
                                    .heightFalloff(0.16f)
                                    .baseHeight(-2.2f)
                                    .maximumOpacity(0.78f)
                                    .build())
                            .build())
                    .directionalCascades(new DirectionalCascadeSettings(
                            CASCADE_COUNT, CASCADE_ATLAS_SIZE, 0.62f, 0.08f));
            try {
                pipeline.build();
                renderLoop(window, driver, pipeline, scene.camera(), options);
            } finally {
                pipeline.close();
            }
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            RuntimeException cleanupFailure = closeReverse(materials);
            if (cleanupFailure != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    private static Scene createScene(Mesh sphere, ShaderProgram shader,
                                     PbrFallbackTextures fallbacks, Texture2D cutoutTexture,
                                     List<Material> owner) {
        Material floor = own(owner, material(shader, fallbacks,
                new Vector4f(0.24f, 0.27f, 0.32f, 1.0f), 0.0f, 0.82f,
                BlendMode.OPAQUE, 0.0f, null));
        Material opaque = own(owner, material(shader, fallbacks,
                new Vector4f(0.90f, 0.24f, 0.08f, 1.0f), 0.15f, 0.28f,
                BlendMode.OPAQUE, 0.0f, null));
        Material gold = own(owner, material(shader, fallbacks,
                new Vector4f(0.95f, 0.68f, 0.12f, 1.0f), 0.72f, 0.24f,
                BlendMode.OPAQUE, 0.0f, null));
        Material mask = own(owner, material(shader, fallbacks,
                new Vector4f(0.24f, 0.90f, 0.34f, 1.0f), 0.0f, 0.55f,
                BlendMode.OPAQUE, 0.50f, cutoutTexture));
        Material nearAlpha = own(owner, material(shader, fallbacks,
                new Vector4f(1.0f, 0.22f, 0.08f, 0.46f), 0.0f, 0.20f,
                BlendMode.ALPHA, 0.0f, null));
        Material farAlpha = own(owner, material(shader, fallbacks,
                new Vector4f(0.08f, 0.28f, 1.0f, 0.58f), 0.0f, 0.24f,
                BlendMode.ALPHA, 0.0f, null));
        Material additive = own(owner, material(shader, fallbacks,
                new Vector4f(0.10f, 0.85f, 1.0f, 1.0f), 0.0f, 0.18f,
                BlendMode.ADDITIVE, 0.0f, null));

        Camera camera = new Camera(new Vector3f(0.0f, 2.1f, 13.0f));
        Scene scene = new Scene(camera);
        addFixed(scene, sphere, floor, false,
                new Matrix4f().translation(0.0f, -2.6f, -15.0f).scale(12.0f, 0.22f, 34.0f));

        // Insert the near alpha object first on purpose. Correct output therefore depends on
        // the v0.23 camera-space back-to-front queue, not insertion order.
        addFixed(scene, sphere, nearAlpha, false,
                new Matrix4f().translation(1.65f, 0.0f, 0.1f).scale(1.35f));
        addFixed(scene, sphere, opaque, true,
                new Matrix4f().translation(-4.8f, 0.0f, 0.0f).scale(1.35f));
        addFixed(scene, sphere, mask, true,
                new Matrix4f().translation(-1.7f, 0.0f, -0.4f).scale(1.35f));
        addFixed(scene, sphere, additive, false,
                new Matrix4f().translation(4.8f, 0.0f, -0.2f).scale(1.25f));
        addFixed(scene, sphere, farAlpha, false,
                new Matrix4f().translation(1.65f, 0.0f, -2.2f).scale(1.55f));

        // Depth landmarks make cascade transitions and distance fog visible while moving.
        addFixed(scene, sphere, gold, true,
                new Matrix4f().translation(-3.8f, -0.2f, -12.0f).scale(1.6f));
        addFixed(scene, sphere, opaque, true,
                new Matrix4f().translation(0.0f, 0.2f, -22.0f).scale(2.0f));
        addFixed(scene, sphere, mask, true,
                new Matrix4f().translation(4.5f, 0.3f, -36.0f).scale(2.4f));

        // This finite object is intentionally outside the camera frustum for M7 culling stats.
        addFixed(scene, sphere, opaque, true,
                new Matrix4f().translation(80.0f, 0.0f, -8.0f).scale(1.5f));

        scene.addLight(SceneLight.shadowedDirectional(
                new Vector3f(-0.45f, -1.0f, -0.35f),
                new Vector3f(1.0f, 0.94f, 0.84f), 3.2f));
        scene.addLight(SceneLight.point(new Vector3f(4.0f, 4.5f, 5.0f),
                new Vector3f(0.22f, 0.42f, 1.0f), 18.0f, 18.0f));
        return scene;
    }

    private static Material material(ShaderProgram shader, PbrFallbackTextures fallbacks,
                                     Vector4f color, float metallic, float roughness,
                                     BlendMode blendMode, float alphaCutoff,
                                     Texture2D baseColorTexture) {
        PbrMaterialProperties properties = new PbrMaterialProperties(color, metallic, roughness,
                1.0f, 1.0f, new Vector3f(), Map.of());
        Map<PbrTextureRole, PbrTextureBinding> supplied = baseColorTexture == null ? Map.of()
                : Map.of(PbrTextureRole.BASE_COLOR,
                        new PbrTextureBinding(baseColorTexture, fallbacks.sampler()));
        return PbrMaterials.createWithBindings(shader, properties, supplied, fallbacks,
                CullMode.BACK, false, false, alphaCutoff, blendMode);
    }

    private static void addFixed(Scene scene, Mesh mesh, Material material,
                                 boolean castShadows, Matrix4f model) {
        scene.add(SceneObject.fixed(mesh, material, model, castShadows));
    }

    private static void renderLoop(GlfwWindow window, FrameDriver driver,
                                   RenderPipeline pipeline, Camera camera, Options options) {
        lastBenchmarkResult = null;
        FrameClock clock = new FrameClock();
        FrameBenchmarkSession benchmark = options.benchmark()
                ? new FrameBenchmarkSession(options.maximumFrames(), options.warmupFrames()) : null;
        StableFrameSamples stableSamples = options.benchmark()
                ? new StableFrameSamples(options.warmupFrames()) : null;
        int frameLimit = options.benchmark() && options.maximumFrames() > 0
                ? Math.addExact(options.maximumFrames(), options.warmupFrames())
                : options.maximumFrames();
        int frame = 0;
        while (!window.shouldClose()) {
            if (options.resizeFrame() == frame) {
                window.resize(options.resizeWidth(), options.resizeHeight());
            }
            float deltaSeconds = options.hidden() ? 1.0f / 60.0f : clock.tick().deltaSeconds();
            if (!options.hidden()) DemoSupport.updateFreeCamera(window, camera, deltaSeconds);
            if (window.consumeResize()) pipeline.resize(window.width(), window.height());

            if (stableSamples != null) stableSamples.beginFrame();
            driver.beginFrame();
            try {
                pipeline.execute(driver.device(), deltaSeconds);
                driver.recordGraph(pipeline.graph());
                driver.endFrame();
                if (stableSamples != null) {
                    stableSamples.record(driver.statistics().lastFrameDurationNanos(),
                            pipeline.graph().lastFrameProfile());
                }
            } catch (RuntimeException | Error failure) {
                driver.failFrame(pipeline.graph(), failure);
                throw failure;
            }
            driver.present(window::swapBuffers);
            window.pollEvents();
            if (benchmark != null) {
                benchmark.recordFrame(driver, pipeline.graph().lastFrameProfile().totalGpuNanos());
            }
            GlDebug.checkError("Render3dV023Demo.frame");

            frame++;
            if (!options.hidden() && frame % 20 == 0) {
                updateTitle(window, pipeline.lastRender3dDiagnostics());
            }
            if (benchmark != null && benchmark.isComplete()) {
                window.requestClose();
            } else if (frameLimit > 0 && frame >= frameLimit) {
                window.requestClose();
            }
        }

        Render3dDiagnostics diagnostics = pipeline.lastRender3dDiagnostics();
        if (options.verify()) verify(diagnostics, options.gtao());
        printSummary(diagnostics, frame);
        if (benchmark != null) {
            BenchmarkResult result = stableSamples == null ? null
                    : stableSamples.snapshot(options.gtao(), options.width(), options.height());
            lastBenchmarkResult = result;
            printBenchmark(benchmark.snapshot(), diagnostics, pipeline.graph().lastFrameProfile(), result);
        }
        GlDebug.assertNoError("Render3dV023Demo");
    }

    private static void printBenchmark(FrameBenchmarkSession.Snapshot snapshot,
                                       Render3dDiagnostics diagnostics, FrameProfile profile,
                                       BenchmarkResult stable) {
        var timings = snapshot.timings();
        System.out.printf(Locale.ROOT,
                "Render3D benchmark mode=%s measured=%d warmupRemaining=%d fps=%.3f "
                        + "cpuAvgMedianMs=%.3f/%.3f gpuAvgMedianMs=%.3f/%.3f gpuSamples=%d "
                        + "aoHistory=%s aoDepthDraws=%d%n",
                diagnostics.ambientOcclusion().enabled() ? "gtao" : "disabled",
                snapshot.measuredFrames(), snapshot.warmupRemaining(), snapshot.presentFps(),
                timings.averageCpuMillis(), timings.medianCpuMillis(),
                timings.averageGpuMillis(), timings.medianGpuMillis(), timings.gpuSamples(),
                diagnostics.ambientOcclusion().historyValid(),
                diagnostics.ambientOcclusion().depthPrepassDraws());
        if (stable != null) {
            System.out.printf(Locale.ROOT,
                    "Render3D benchmark stable mode=%s size=%dx%d measured=%d "
                            + "cpuP50Ms=%.6f cpuP95Ms=%.6f gpuP50Ms=%.6f gpuP95Ms=%.6f "
                            + "gtaoCpuP50Ms=%.6f gtaoCpuP95Ms=%.6f "
                            + "gtaoGpuP50Ms=%.6f gtaoGpuP95Ms=%.6f gpuSamples=%d "
                            + "gtaoGpuSamples=%d allocationKiBPerFrame=%.6f "
                            + "allocationTrendKiBPerFrame=%.6f allocationSamples=%d%n",
                    stable.gtao() ? "gtao" : "disabled", stable.width(), stable.height(),
                    stable.measuredFrames(), stable.cpuP50Millis(), stable.cpuP95Millis(),
                    stable.gpuP50Millis(), stable.gpuP95Millis(), stable.gtaoCpuP50Millis(),
                    stable.gtaoCpuP95Millis(), stable.gtaoGpuP50Millis(), stable.gtaoGpuP95Millis(),
                    stable.gpuSamples(), stable.gtaoGpuSamples(), stable.allocationKiBPerFrame(),
                    stable.allocationTrendKiBPerFrame(), stable.allocationSamples());
        }
        StringBuilder passSummary = new StringBuilder("Render3D benchmark passes:");
        for (PassProfile pass : profile.passes()) {
            passSummary.append(' ').append(pass.passName()).append('=')
                    .append(String.format(Locale.ROOT, "cpu=%.3fms/gpu=%.3fms",
                            pass.cpuRecordNanos() / 1_000_000.0, pass.gpuMillis()));
        }
        System.out.println(passSummary);
    }

    private static void updateTitle(GlfwWindow window, Render3dDiagnostics diagnostics) {
        if (!diagnostics.available()) return;
        var queues = diagnostics.queues();
        var visibility = diagnostics.visibility();
        window.setTitle(String.format(Locale.ROOT,
                "Render3D v0.23.2 | O/M/A/+ %d/%d/%d/%d | visible %d culled %d | "
                        + "CSM %d | depth %dx->%dx | queue cache %s | WASD mouse ESC",
                queues.opaque(), queues.masked(), queues.alpha(), queues.additive(),
                visibility.visible(), visibility.frustumCulled(),
                diagnostics.shadows().cascadeCount(),
                diagnostics.depthResolve().sourceSamples(),
                diagnostics.depthResolve().targetSamples(),
                diagnostics.caches().forwardQueueHit() ? "hit" : "rebuild"));
    }

    private static void verify(Render3dDiagnostics diagnostics, boolean gtaoEnabled) {
        require(diagnostics.available(), "Render3D diagnostics are unavailable");
        require(diagnostics.activeGenerationId() > 0L, "active generation was not published");
        require(diagnostics.failureStage().isEmpty(),
                "pipeline recorded failure stage " + diagnostics.failureStage());
        require(diagnostics.queues().opaque() >= 4, "opaque queue did not render the landmarks");
        require(diagnostics.queues().masked() >= 1, "MASKED queue is missing");
        require(diagnostics.queues().alpha() == 2, "ALPHA queue must contain both overlap probes");
        require(diagnostics.queues().additive() == 1, "ADDITIVE queue must contain its probe");
        require(diagnostics.visibility().frustumCulled() >= 1,
                "off-frustum visibility probe was not culled");
        require(diagnostics.shadows().directionalSelection().equals("selected"),
                "directional shadow light was not selected");
        require(diagnostics.shadows().cascadeCount() == CASCADE_COUNT,
                "expected four directional cascades");
        require(diagnostics.shadows().cascadeSplits().size() == CASCADE_COUNT,
                "cascade split diagnostics are incomplete");
        require(diagnostics.shadows().cascadeCasters().stream().allMatch(count -> count > 0),
                "one or more cascade tiles rendered no casters");
        require(diagnostics.depthResolve().executed(), "Fog + MSAA depth resolve did not execute");
        require(diagnostics.depthResolve().sourceSamples() == 4
                        && diagnostics.depthResolve().targetSamples() == 1,
                "depth resolve sample contract is not 4x -> 1x");
        require(diagnostics.caches().forwardQueueHit(),
                "static final frame did not reuse its forward queue");
        if (gtaoEnabled) {
            require(diagnostics.ambientOcclusion().enabled(), "GTAO diagnostics are disabled");
            require(diagnostics.ambientOcclusion().historyValid(),
                    "GTAO history was not committed after a successful frame");
        }
    }

    private static void printSummary(Render3dDiagnostics diagnostics, int frames) {
        if (!diagnostics.available()) {
            System.out.printf("Render3D v0.23.2 demo: frames=%d diagnostics=unavailable%n", frames);
            return;
        }
        var queues = diagnostics.queues();
        var visibility = diagnostics.visibility();
        System.out.printf(Locale.ROOT,
                "Render3D v0.23.2 demo: frames=%d generation=%d queues(O/M/A/+)= %d/%d/%d/%d "
                        + "visible=%d culled=%d cascades=%d splits=%s depthResolve=%dx->%dx "
                        + "extent=%dx%d queueCache=%s failure=%s%n",
                frames, diagnostics.activeGenerationId(), queues.opaque(), queues.masked(),
                queues.alpha(), queues.additive(), visibility.visible(), visibility.frustumCulled(),
                diagnostics.shadows().cascadeCount(), diagnostics.shadows().cascadeSplits(),
                diagnostics.depthResolve().sourceSamples(), diagnostics.depthResolve().targetSamples(),
                diagnostics.depthResolve().width(), diagnostics.depthResolve().height(),
                diagnostics.caches().forwardQueueHit() ? "hit" : "rebuild",
                diagnostics.failureStage().isEmpty() ? "none" : diagnostics.failureStage());
    }

    private static Texture2D createCutoutTexture() {
        int size = 64;
        byte[] pixels = new byte[size * size * 4];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int offset = (y * size + x) * 4;
                boolean solid = ((x / 8) + (y / 8)) % 2 == 0;
                pixels[offset] = (byte) 220;
                pixels[offset + 1] = (byte) 255;
                pixels[offset + 2] = (byte) 220;
                pixels[offset + 3] = (byte) (solid ? 255 : 0);
            }
        }
        return Texture2D.fromRgba8(size, size, pixels, TextureColorSpace.SRGB);
    }

    private static Material own(List<Material> owner, Material material) {
        owner.add(material);
        return material;
    }

    private static RuntimeException closeReverse(List<? extends AutoCloseable> resources) {
        RuntimeException failure = null;
        for (int index = resources.size() - 1; index >= 0; index--) {
            try {
                resources.get(index).close();
            } catch (Exception closeFailure) {
                RuntimeException wrapped = closeFailure instanceof RuntimeException runtime
                        ? runtime : new IllegalStateException("Failed to close v0.23 demo resource",
                        closeFailure);
                if (failure == null) failure = wrapped;
                else failure.addSuppressed(wrapped);
            }
        }
        return failure;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    /** Stable post-warmup samples used by the GTAO A/B benchmark, excluding context startup. */
    private static final class StableFrameSamples {
        private final int warmupFrames;
        private final List<Long> cpuNanos = new ArrayList<>();
        private final List<Long> gpuNanos = new ArrayList<>();
        private final List<Long> gtaoCpuNanos = new ArrayList<>();
        private final List<Long> gtaoGpuNanos = new ArrayList<>();
        private final List<Long> allocationBytes = new ArrayList<>();
        private final ThreadMXBean allocationBean;
        private final long threadId;
        private long allocationStart = -1L;
        private int frame;

        private StableFrameSamples(int warmupFrames) {
            this.warmupFrames = warmupFrames;
            java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            ThreadMXBean allocation = null;
            if (bean instanceof ThreadMXBean candidate && candidate.isThreadAllocatedMemorySupported()) {
                if (!candidate.isThreadAllocatedMemoryEnabled()) {
                    try {
                        candidate.setThreadAllocatedMemoryEnabled(true);
                    } catch (RuntimeException ignored) {
                        candidate = null;
                    }
                }
                allocation = candidate;
            }
            allocationBean = allocation;
            threadId = Thread.currentThread().threadId();
        }

        private void beginFrame() {
            allocationStart = allocationBean == null ? -1L
                    : allocationBean.getThreadAllocatedBytes(threadId);
        }

        private void record(long cpu, FrameProfile profile) {
            long allocationSample = -1L;
            if (allocationStart >= 0L && allocationBean != null) {
                long end = allocationBean.getThreadAllocatedBytes(threadId);
                if (end >= allocationStart) allocationSample = end - allocationStart;
            }
            if (frame++ < warmupFrames) return;
            cpuNanos.add(cpu);
            long gpu = profile.totalGpuNanos();
            if (gpu > 0L) gpuNanos.add(gpu);
            long gtaoCpu = profile.passes().stream()
                    .filter(pass -> pass.passName().startsWith("Gtao"))
                    .mapToLong(pass -> pass.cpuRecordNanos())
                    .sum();
            if (gtaoCpu > 0L) gtaoCpuNanos.add(gtaoCpu);
            long gtaoGpu = profile.passes().stream()
                    .filter(pass -> pass.passName().startsWith("Gtao"))
                    .mapToLong(pass -> pass.gpuNanos())
                    .sum();
            if (gtaoGpu > 0L) gtaoGpuNanos.add(gtaoGpu);
            if (allocationSample >= 0L) allocationBytes.add(allocationSample);
        }

        private BenchmarkResult snapshot(boolean gtao, int width, int height) {
            return new BenchmarkResult(gtao, width, height, cpuNanos, gpuNanos,
                    gtaoCpuNanos, gtaoGpuNanos, allocationBytes);
        }
    }

    record BenchmarkResult(boolean gtao, int width, int height,
                           List<Long> cpuNanos, List<Long> gpuNanos,
                           List<Long> gtaoCpuNanos, List<Long> gtaoGpuNanos,
                           List<Long> allocationBytes) {
        BenchmarkResult {
            cpuNanos = List.copyOf(cpuNanos);
            gpuNanos = List.copyOf(gpuNanos);
            gtaoCpuNanos = List.copyOf(gtaoCpuNanos);
            gtaoGpuNanos = List.copyOf(gtaoGpuNanos);
            allocationBytes = List.copyOf(allocationBytes);
        }

        int measuredFrames() { return cpuNanos.size(); }

        int gpuSamples() { return gpuNanos.size(); }

        int gtaoGpuSamples() { return gtaoGpuNanos.size(); }

        int allocationSamples() { return allocationBytes.size(); }

        double cpuP50Millis() { return percentileMillis(cpuNanos, 0.50); }

        double cpuP95Millis() { return percentileMillis(cpuNanos, 0.95); }

        double gpuP50Millis() { return percentileMillis(gpuNanos, 0.50); }

        double gpuP95Millis() { return percentileMillis(gpuNanos, 0.95); }

        double gtaoCpuP50Millis() { return percentileMillis(gtaoCpuNanos, 0.50); }

        double gtaoCpuP95Millis() { return percentileMillis(gtaoCpuNanos, 0.95); }

        double gtaoGpuP50Millis() { return percentileMillis(gtaoGpuNanos, 0.50); }

        double gtaoGpuP95Millis() { return percentileMillis(gtaoGpuNanos, 0.95); }

        double allocationKiBPerFrame() {
            if (allocationBytes.isEmpty() || measuredFrames() == 0) return Double.NaN;
            return allocationBytes.stream().mapToLong(Long::longValue).average().orElse(0.0)
                    / 1024.0;
        }

        double allocationTrendKiBPerFrame() {
            if (allocationBytes.size() < 4) return Double.NaN;
            int split = allocationBytes.size() / 2;
            double first = allocationBytes.subList(0, split).stream()
                    .mapToLong(Long::longValue).average().orElse(Double.NaN);
            double last = allocationBytes.subList(split, allocationBytes.size()).stream()
                    .mapToLong(Long::longValue).average().orElse(Double.NaN);
            return (last - first) / 1024.0;
        }

        private static double percentileMillis(List<Long> values, double percentile) {
            if (values.isEmpty()) return Double.NaN;
            long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
            int index = (int) Math.ceil(percentile * sorted.length) - 1;
            index = Math.max(0, Math.min(index, sorted.length - 1));
            return sorted[index] / 1_000_000.0;
        }
    }

    record Options(boolean hidden, int maximumFrames, int width, int height,
                   int resizeFrame, int resizeWidth, int resizeHeight,
                   String environmentQuality, boolean verify, boolean gtao,
                   boolean benchmark, int warmupFrames, GtaoQuality gtaoQuality,
                   float gtaoRadius, float gtaoStrength, float gtaoThickness) {
        static Options parse(String[] arguments) {
            boolean hidden = false;
            int maximumFrames = -1;
            int width = 1280;
            int height = 720;
            int resizeFrame = -1;
            int resizeWidth = 0;
            int resizeHeight = 0;
            String environmentQuality = "default";
            boolean verify = false;
            boolean gtao = false;
            boolean benchmark = false;
            int warmupFrames = 0;
            GtaoQuality gtaoQuality = GtaoQuality.MEDIUM;
            float gtaoRadius = 1.0f;
            float gtaoStrength = 1.0f;
            float gtaoThickness = 0.25f;
            for (String argument : arguments) {
                if (argument.equals("--hidden") || argument.equals("--deterministic")) {
                    hidden = true;
                } else if (argument.equals("--benchmark")) {
                    benchmark = true;
                    hidden = true;
                } else if (argument.startsWith("--frames=")) {
                    maximumFrames = positive(argument, "--frames=");
                } else if (argument.startsWith("--warmup=")) {
                    warmupFrames = nonNegative(argument, "--warmup=");
                } else if (argument.startsWith("--gtao-quality=")) {
                    gtaoQuality = quality(argument.substring("--gtao-quality=".length()));
                } else if (argument.startsWith("--gtao-radius=")) {
                    gtaoRadius = finitePositive(argument, "--gtao-radius=");
                } else if (argument.startsWith("--gtao-strength=")) {
                    gtaoStrength = finiteRange(argument, "--gtao-strength=", 0.0f, 4.0f);
                } else if (argument.startsWith("--gtao-thickness=")) {
                    gtaoThickness = finiteRange(argument, "--gtao-thickness=", 0.0f, gtaoRadius);
                } else if (argument.startsWith("--size=")) {
                    int[] size = size(argument.substring("--size=".length()));
                    width = size[0];
                    height = size[1];
                } else if (argument.startsWith("--resize=")) {
                    String value = argument.substring("--resize=".length());
                    String[] parts = value.split(":", -1);
                    if (parts.length != 2) {
                        throw new IllegalArgumentException("--resize requires FRAME:WIDTHxHEIGHT");
                    }
                    resizeFrame = Integer.parseInt(parts[0]);
                    if (resizeFrame < 0) {
                        throw new IllegalArgumentException("resize frame must be non-negative");
                    }
                    int[] size = size(parts[1]);
                    resizeWidth = size[0];
                    resizeHeight = size[1];
                } else if (argument.startsWith("--environment-quality=")) {
                    environmentQuality = argument.substring("--environment-quality=".length());
                    PbrEnvironmentSettings.quality(environmentQuality);
                } else if (argument.equals("--verify")) {
                    verify = true;
                } else if (argument.equals("--gtao")) {
                    gtao = true;
                } else {
                    throw new IllegalArgumentException("Unknown Render3dV023Demo option: " + argument);
                }
            }
            if (hidden && maximumFrames < 0) maximumFrames = 12;
            int frameLimit = benchmark && maximumFrames > 0
                    ? Math.addExact(maximumFrames, warmupFrames) : maximumFrames;
            if (resizeFrame >= frameLimit && frameLimit > 0) {
                throw new IllegalArgumentException("resize frame must be before the final frame");
            }
            return new Options(hidden, maximumFrames, width, height,
                    resizeFrame, resizeWidth, resizeHeight, environmentQuality, verify, gtao,
                    benchmark, warmupFrames, gtaoQuality, gtaoRadius, gtaoStrength, gtaoThickness);
        }

        GtaoSettings gtaoSettings() {
            return new GtaoSettings(gtao, gtaoQuality, gtaoRadius, gtaoStrength, gtaoThickness,
                    true, 0.90f, 0.02f);
        }

        private static int positive(String argument, String prefix) {
            int value = Integer.parseInt(argument.substring(prefix.length()));
            if (value <= 0) throw new IllegalArgumentException(prefix + " requires a positive integer");
            return value;
        }

        private static int nonNegative(String argument, String prefix) {
            int value = Integer.parseInt(argument.substring(prefix.length()));
            if (value < 0) throw new IllegalArgumentException(prefix + " requires a non-negative integer");
            return value;
        }

        private static GtaoQuality quality(String value) {
            try {
                return GtaoQuality.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("gtao-quality must be LOW, MEDIUM or HIGH", failure);
            }
        }

        private static float finitePositive(String argument, String prefix) {
            float value = Float.parseFloat(argument.substring(prefix.length()));
            if (!Float.isFinite(value) || value <= 0.0f) {
                throw new IllegalArgumentException(prefix + " requires a finite positive value");
            }
            return value;
        }

        private static float finiteRange(String argument, String prefix,
                                         float minimum, float maximum) {
            float value = Float.parseFloat(argument.substring(prefix.length()));
            if (!Float.isFinite(value) || value < minimum || value > maximum) {
                throw new IllegalArgumentException(prefix + " requires a finite value in ["
                        + minimum + ", " + maximum + "]");
            }
            return value;
        }

        private static int[] size(String value) {
            String[] parts = value.toLowerCase(Locale.ROOT).split("x", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("size must be WIDTHxHEIGHT");
            }
            int width = Integer.parseInt(parts[0]);
            int height = Integer.parseInt(parts[1]);
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("size dimensions must be positive");
            }
            return new int[]{width, height};
        }
    }
}
