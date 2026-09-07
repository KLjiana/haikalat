package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.CullMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.FogSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoQuality;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.LocalFogVolume;
import com.kaleblangley.haikalat.subsystems.render3d.OutdoorEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.VolumetricSunSettings;
import com.kaleblangley.haikalat.subsystems.render3d.StylizedSkySettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.BufferUtils;

import java.util.ArrayList;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;
import java.util.Properties;
import java.lang.management.ManagementFactory;
import com.sun.management.ThreadMXBean;

import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;

/** Deterministic v0.24 “morning fog woodland” sample and verification runner. */
public final class Render3dOutdoorEnvironmentDemo {
    private Render3dOutdoorEnvironmentDemo() { }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(options.width, options.height)
                .title("Haikalat v0.24 Outdoor Environment")
                .visible(!options.hidden).decorated(!options.hidden)
                .cursorMode(GlfwWindow.CursorMode.NORMAL).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(!options.noVsync && !options.hidden);
            run(window, options);
        }
    }

    private static void run(GlfwWindow window, Options options) {
        RenderSettings renderSettings = RenderSettings.builder()
                .vsync(!options.noVsync && !options.hidden)
                .antiAliasingMode(options.aa)
                .toneMappingMode(ToneMappingMode.ACES)
                .exposureMode(options.autoExposure ? ExposureMode.AUTO : ExposureMode.MANUAL)
                .exposure(options.preset.equals("golden_hour") ? 0.95f : 1.05f)
                .bloomSettings(BloomSettings.builder().enabled(options.bloom).build())
                .build();
        List<Material> materials = new ArrayList<>();
        try (FrameDriver driver = new FrameDriver(renderSettings);
             PbrEnvironment environment = PbrEnvironmentLoader.load(driver.device(),
                     Render3dOutdoorEnvironmentDemo.class, "/environments/pbr/studio-small.hdr",
                     PbrEnvironmentSettings.quality(options.environmentQuality));
             PbrFallbackTextures fallbacks = new PbrFallbackTextures();
             ShaderProgram shader = ShaderProgram.fromResource(Render3dOutdoorEnvironmentDemo.class,
                     "/shaders/render3d/pbr/pbr-forward.vert", "/shaders/render3d/pbr/pbr-forward.frag");
             Mesh sphere = Mesh.from(PbrSphereMesh.create(28, 18))) {
            Scene scene = createScene(sphere, shader, fallbacks, materials);
            OutdoorEnvironmentSettings environmentSettings = applyVolumeQuality(
                    preset(options.preset), options.volumeQuality);
            if (options.loadConfigPath != null) {
                environmentSettings = loadEnvironmentConfig(options.loadConfigPath, environmentSettings);
            }
            if (!options.volume) {
                environmentSettings = OutdoorEnvironmentSettings.disabled();
            }
            environment.intensity(environmentSettings.sky().environmentIntensity());
            RenderPipeline pipeline = new RenderPipeline(window, scene, null, renderSettings, environment)
                    .postProcessSettings(PostProcessSettings.builder()
                            .gtao(options.gtao ? GtaoSettings.quality(GtaoQuality.MEDIUM)
                                    : GtaoSettings.disabled())
                            .build())
                    .directionalCascades(new DirectionalCascadeSettings(4, options.csmAtlas, 0.62f, 0.08f))
                    .outdoorEnvironment(environmentSettings);
            try {
                pipeline.build();
                if (options.insideVolume) {
                    pipeline.scene().camera().setPosition(new Vector3f(-2.2f, -0.7f, -9.0f));
                }
                if (options.saveConfigPath != null) {
                    saveEnvironmentConfig(options.saveConfigPath, environmentSettings, options.route);
                }
                if (options.benchmark && options.rounds > 1) {
                    runBenchmarkMatrix(window, driver, pipeline, options, environmentSettings);
                } else {
                    if (!options.hidden) {
                        try (OutdoorEnvironmentOverlay overlay = OutdoorEnvironmentOverlay.attach(
                                window, pipeline, environmentSettings, options.configPath)) {
                            renderLoop(window, driver, pipeline, options, overlay);
                        }
                    } else {
                        renderLoop(window, driver, pipeline, options, null);
                    }
                }
            } finally {
                pipeline.close();
            }
        } finally {
            for (int index = materials.size() - 1; index >= 0; index--) materials.get(index).close();
        }
    }

    private static Scene createScene(Mesh mesh, ShaderProgram shader,
                                     PbrFallbackTextures fallbacks, List<Material> owner) {
        Material ground = own(owner, material(shader, fallbacks,
                new Vector4f(0.28f, 0.36f, 0.23f, 1.0f), 0.0f, 0.92f));
        Material bark = own(owner, material(shader, fallbacks,
                new Vector4f(0.38f, 0.16f, 0.07f, 1.0f), 0.0f, 0.82f));
        Material leaf = own(owner, material(shader, fallbacks,
                new Vector4f(0.18f, 0.58f, 0.20f, 1.0f), 0.0f, 0.68f));
        Material stone = own(owner, material(shader, fallbacks,
                new Vector4f(0.50f, 0.55f, 0.60f, 1.0f), 0.05f, 0.72f));
        Camera camera = new Camera(new Vector3f(0.0f, 2.4f, 14.0f));
        Scene scene = new Scene(camera);
        scene.add(SceneObject.fixed(mesh, ground,
                new Matrix4f().translation(0.0f, -2.6f, -17.0f).scale(15.0f, 0.28f, 30.0f), true));
        // Near trunks, a mid-distance path and a distant canopy give the CSM
        // and volume a repeatable occluder/depth gradient.
        for (int i = -3; i <= 3; i++) {
            float x = i * 3.2f;
            scene.add(SceneObject.fixed(mesh, bark,
                    new Matrix4f().translation(x, -0.3f, -5.0f - Math.abs(i) * 2.0f)
                            .scale(0.72f, 3.4f, 0.72f), true));
            scene.add(SceneObject.fixed(mesh, leaf,
                    new Matrix4f().translation(x, 3.0f, -5.0f - Math.abs(i) * 2.0f)
                            .scale(2.2f, 1.4f, 2.2f), true));
        }
        for (int i = 0; i < 7; i++) {
            float x = (i - 3) * 2.4f;
            scene.add(SceneObject.fixed(mesh, stone,
                    new Matrix4f().translation(x, -1.6f, -8.0f - (i % 3) * 7.0f)
                            .scale(0.9f + (i % 2) * 0.45f, 0.65f, 0.8f), true));
        }
        // Broad low-poly ridges close the horizon so the finite background
        // integration and the sky/ground transition are visible in captures.
        for (int i = -2; i <= 2; i++) {
            scene.add(SceneObject.fixed(mesh, stone,
                    new Matrix4f().translation(i * 8.0f, -1.0f, -38.0f - Math.abs(i) * 3.0f)
                            .scale(6.0f + Math.abs(i), 2.4f, 3.2f), true));
        }
        scene.addLight(SceneLight.shadowedDirectional(new Vector3f(-0.45f, -0.78f, -0.28f),
                new Vector3f(1.0f, 0.62f, 0.32f), 3.2f));
        scene.addLight(SceneLight.point(new Vector3f(0.0f, 4.0f, 4.0f),
                new Vector3f(0.24f, 0.38f, 1.0f), 7.0f, 18.0f));
        return scene;
    }

    private static Material material(ShaderProgram shader, PbrFallbackTextures fallbacks,
                                     Vector4f color, float metallic, float roughness) {
        return PbrMaterials.createWithBindings(shader,
                new com.kaleblangley.haikalat.core.assets.PbrMaterialProperties(
                        color, metallic, roughness, 1.0f, 1.0f, new Vector3f(), Map.of()),
                Map.of(), fallbacks, CullMode.BACK, false, false, 0.0f, BlendMode.OPAQUE);
    }

    private static Material own(List<Material> owner, Material material) {
        owner.add(material);
        return material;
    }

    static OutdoorEnvironmentSettings preset(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "clear_day", "clear", "day" -> OutdoorEnvironmentSettings.clearDay();
            case "golden_hour", "golden", "dusk" -> OutdoorEnvironmentSettings.goldenHour();
            case "morning_fog", "morning", "fog" -> OutdoorEnvironmentSettings.morningFog()
                    .withLocalFogVolumes(List.of(
                            new LocalFogVolume(LocalFogVolume.Shape.SPHERE,
                                    new Vector3f(-2.2f, -0.7f, -9.0f), new Vector3f(4.0f),
                                    0.045f, new Vector3f(0.62f, 0.72f, 0.80f), 0.16f, 0.22f),
                            new LocalFogVolume(LocalFogVolume.Shape.BOX,
                                    new Vector3f(3.0f, -0.8f, -18.0f), new Vector3f(4.5f, 2.0f, 6.0f),
                                    0.025f, new Vector3f(0.55f, 0.65f, 0.75f), 0.12f, 0.18f)));
            default -> throw new IllegalArgumentException("unknown outdoor preset: " + value);
        };
    }

    static OutdoorEnvironmentSettings applyVolumeQuality(OutdoorEnvironmentSettings settings,
                                                         String quality) {
        if (quality == null || quality.equals("balanced")) return settings;
        VolumetricSunSettings base = settings.volumetricSun();
        VolumetricSunSettings replacement = switch (quality.toLowerCase(Locale.ROOT)) {
            case "low" -> new VolumetricSunSettings(true, 16, 4, base.maximumDistance(),
                    base.density(), base.scatteringColor(), base.anisotropy(), 0.72f,
                    base.depthRejectThreshold(), base.noiseStrength());
            case "high", "reference" -> new VolumetricSunSettings(true, 96, 1,
                    base.maximumDistance(), base.density(), base.scatteringColor(), base.anisotropy(),
                    0.0f, base.depthRejectThreshold() * 0.75f, base.noiseStrength() * 0.5f);
            default -> throw new IllegalArgumentException("unknown volume quality: " + quality);
        };
        return settings.withVolumetricSun(replacement);
    }

    private static void renderLoop(GlfwWindow window, FrameDriver driver,
                                   RenderPipeline pipeline, Options options,
                                   OutdoorEnvironmentOverlay overlay) {
        int frame = 0;
        Camera routeCamera = pipeline.scene().camera();
        Vector3f routePosition = new Vector3f();
        int environmentSwitches = 0;
        int failureInjections = 0;
        List<Long> cpuSamples = new ArrayList<>();
        List<Long> gpuSamples = new ArrayList<>();
        List<Long> allocationSamples = new ArrayList<>();
        ThreadMXBean allocationBean = allocationBean();
        while (!window.shouldClose()) {
            if (overlay != null) overlay.update(window.inputSnapshot(), 1.0f / 60.0f);
            if (options.route) {
                float routeProgress = options.frames > 1
                        ? Math.min(1.0f, frame / (float) (options.frames - 1))
                        : 0.0f;
                float angle = routeProgress * (float) (Math.PI * 0.9) - (float) (Math.PI * 0.45);
                routePosition.set((float) Math.sin(angle) * 4.8f,
                        2.2f + (float) Math.sin(routeProgress * Math.PI) * 0.6f,
                        12.5f - routeProgress * 4.0f);
                routeCamera.setPosition(routePosition);
                routeCamera.setYaw(-90.0f + (float) Math.sin(angle) * 13.0f);
                routeCamera.setPitch(-4.0f + (float) Math.cos(angle) * 2.0f);
            }
            if (options.switches > 0 && environmentSwitches < options.switches
                    && frame > 0 && frame % Math.max(1, options.switchInterval) == 0) {
                String[] cycle = {"morning_fog", "clear_day", "golden_hour"};
                pipeline.applyOutdoorEnvironment(applyVolumeQuality(
                        preset(cycle[environmentSwitches % cycle.length]), options.volumeQuality));
                environmentSwitches++;
            }
            if (options.failureInjections > 0 && failureInjections < options.failureInjections
                    && frame > 0 && frame % Math.max(1, options.failureInterval) == 0) {
                int width = (failureInjections & 1) == 0 ? 352 : 368;
                int height = (failureInjections & 1) == 0 ? 198 : 208;
                System.setProperty("haikalat.test.failOutdoorHistoryAllocation",
                        (failureInjections & 1) == 0 ? "color" : "depth");
                window.resize(width, height);
            }
            if (options.resizeFrame == frame) window.resize(options.resizeWidth, options.resizeHeight);
            if (window.consumeResize()) {
                try {
                    pipeline.resize(window.width(), window.height());
                } catch (IllegalStateException expectedFailure) {
                    if (!expectedFailure.getMessage().contains("injected outdoor history allocation")
                            && !expectedFailure.getMessage().contains("injected outdoor depth history allocation")) {
                        throw expectedFailure;
                    }
                    failureInjections++;
                }
            }
            long allocatedBefore = options.benchmark ? allocatedBytes(allocationBean) : -1L;
            driver.beginFrame();
            try {
                pipeline.execute(driver.device(), 1.0f / 60.0f);
                driver.recordGraph(pipeline.graph());
                driver.endFrame();
            } catch (RuntimeException | Error failure) {
                driver.failFrame(pipeline.graph(), failure);
                throw failure;
            }
            if (options.capturePath != null
                    && (options.frames <= 0 || frame + 1 >= options.frames)) {
                captureFrame(window.width(), window.height(), options.capturePath);
            }
            driver.present(window::swapBuffers);
            window.pollEvents();
            long allocatedAfter = options.benchmark ? allocatedBytes(allocationBean) : -1L;
            if (options.benchmark && frame >= options.warmup) {
                cpuSamples.add(driver.statistics().lastFrameDurationNanos());
                gpuSamples.add(pipeline.graph().lastFrameProfile().totalGpuNanos());
                if (allocatedBefore >= 0L && allocatedAfter >= allocatedBefore) {
                    allocationSamples.add(allocatedAfter - allocatedBefore);
                }
            }
            if (!options.hidden && frame % 30 == 0) {
                window.setTitle("v0.24 Outdoor " + options.preset + " | volume=" + options.volume
                        + " " + options.volumeQuality + " | AA=" + options.aa
                        + " | GTAO=" + options.gtao + " AUTO=" + options.autoExposure
                        + " | " + pipeline.lastShadowCasterDrawCount() + " shadow draws");
            }
            frame++;
            if (options.frames > 0 && frame >= options.frames) window.requestClose();
        }
        if (options.verify) {
            if (pipeline.lastShadowCasterDrawCount() <= 0) {
                throw new IllegalStateException("outdoor verification produced no shadow casters");
            }
            System.out.println("OUTDOOR_VERIFY preset=" + options.preset + " volume=" + options.volume
                    + " frames=" + frame + " passes=" + pipeline.graph().description().executionOrder());
            if (options.switches > 0 || options.failureInjections > 0) {
                if (environmentSwitches != options.switches
                        || failureInjections != options.failureInjections) {
                    throw new IllegalStateException("outdoor stability schedule incomplete: switches="
                            + environmentSwitches + "/" + options.switches + " failures="
                            + failureInjections + "/" + options.failureInjections);
                }
                System.out.println("OUTDOOR_STABILITY switches=" + environmentSwitches
                        + " failures=" + failureInjections + " frames=" + frame);
            }
        }
        if (options.benchmark && !cpuSamples.isEmpty()) {
            System.out.println("OUTDOOR_BENCHMARK preset=" + options.preset
                    + " frames=" + cpuSamples.size()
                    + " cpu_p50_ms=" + percentileMillis(cpuSamples, 0.50)
                    + " cpu_p95_ms=" + percentileMillis(cpuSamples, 0.95)
                    + " gpu_p50_ms=" + percentileMillis(gpuSamples, 0.50)
                    + " gpu_p95_ms=" + percentileMillis(gpuSamples, 0.95)
                    + " allocation_kib_p50=" + percentileKib(allocationSamples, 0.50)
                    + " allocation_kib_p95=" + percentileKib(allocationSamples, 0.95)
                    + " allocation_supported=" + (allocationBean != null));
        }
    }

    private static void runBenchmarkMatrix(GlfwWindow window, FrameDriver driver,
                                            RenderPipeline pipeline, Options options,
                                            OutdoorEnvironmentSettings enabledEnvironment) {
        List<int[]> sizes = options.benchmarkSizes();
        List<BenchmarkSample> enabled = new ArrayList<>();
        List<BenchmarkSample> disabled = new ArrayList<>();
        window.setVsync(false);
        for (int round = 1; round <= options.rounds; round++) {
            for (int[] size : sizes) {
                window.resize(size[0], size[1]);
                if (window.consumeResize()) pipeline.resize(window.width(), window.height());
                pipeline.applyOutdoorEnvironment(OutdoorEnvironmentSettings.disabled());
                BenchmarkSample off = measurePhase(window, driver, pipeline, options,
                        round, size[0], size[1], false);
                pipeline.applyOutdoorEnvironment(environmentForSize(enabledEnvironment,
                        options.volumeQuality, size[0], size[1]));
                BenchmarkSample on = measurePhase(window, driver, pipeline, options,
                        round, size[0], size[1], true);
                disabled.add(off);
                enabled.add(on);
                System.out.println("OUTDOOR_BENCHMARK round=" + round + " size="
                        + size[0] + "x" + size[1] + " enabled=false " + off.format());
                System.out.println("OUTDOOR_BENCHMARK round=" + round + " size="
                        + size[0] + "x" + size[1] + " enabled=true " + on.format());
            }
        }
        for (int[] size : sizes) {
            BenchmarkSample off = medianSample(disabled, size[0], size[1]);
            BenchmarkSample on = medianSample(enabled, size[0], size[1]);
            System.out.println("OUTDOOR_BENCHMARK_SUMMARY size=" + size[0] + "x" + size[1]
                    + " rounds=" + options.rounds + " enabled=" + on.format()
                    + " disabled=" + off.format()
                    + " cpu_delta_p95_ms=" + (on.cpuP95Ms - off.cpuP95Ms)
                    + " gpu_delta_p95_ms=" + (on.gpuP95Ms - off.gpuP95Ms));
        }
    }

    private static BenchmarkSample measurePhase(GlfwWindow window, FrameDriver driver,
                                                RenderPipeline pipeline, Options options,
                                                int round, int width, int height, boolean enabled) {
        List<Long> cpu = new ArrayList<>();
        List<Long> gpu = new ArrayList<>();
        List<Long> allocation = new ArrayList<>();
        ThreadMXBean allocationBean = allocationBean();
        int totalFrames = Math.max(options.frames, options.warmup + 1);
        for (int frame = 0; frame < totalFrames; frame++) {
            long before = allocatedBytes(allocationBean);
            driver.beginFrame();
            try {
                pipeline.execute(driver.device(), 1.0f / 60.0f);
                driver.recordGraph(pipeline.graph());
                driver.endFrame();
            } catch (RuntimeException | Error failure) {
                driver.failFrame(pipeline.graph(), failure);
                throw failure;
            }
            driver.present(window::swapBuffers);
            window.pollEvents();
            long after = allocatedBytes(allocationBean);
            if (frame >= options.warmup) {
                cpu.add(driver.statistics().lastFrameDurationNanos());
                gpu.add(pipeline.graph().lastFrameProfile().totalGpuNanos());
                if (before >= 0L && after >= before) allocation.add(after - before);
            }
        }
        return new BenchmarkSample(round, width, height, enabled,
                percentileMillis(cpu, 0.50), percentileMillis(cpu, 0.95),
                percentileMillis(gpu, 0.50), percentileMillis(gpu, 0.95),
                percentileKib(allocation, 0.50), percentileKib(allocation, 0.95));
    }

    private static OutdoorEnvironmentSettings environmentForSize(OutdoorEnvironmentSettings base,
                                                                String quality, int width, int height) {
        if (!base.volumetricSun().enabled() || !quality.equalsIgnoreCase("balanced")) return base;
        int downsample = width >= 3000 || height >= 1800 ? 4 : 2;
        VolumetricSunSettings current = base.volumetricSun();
        if (current.downsample() == downsample) return base;
        return base.withVolumetricSun(new VolumetricSunSettings(true, current.steps(), downsample,
                current.maximumDistance(), current.density(), current.scatteringColor(),
                current.anisotropy(), current.historyWeight(), current.depthRejectThreshold(),
                current.noiseStrength()));
    }

    private static BenchmarkSample medianSample(List<BenchmarkSample> samples, int width, int height) {
        List<BenchmarkSample> matching = samples.stream()
                .filter(sample -> sample.width == width && sample.height == height).toList();
        return new BenchmarkSample(0, width, height, true,
                median(matching, sample -> sample.cpuP50Ms), median(matching, sample -> sample.cpuP95Ms),
                median(matching, sample -> sample.gpuP50Ms), median(matching, sample -> sample.gpuP95Ms),
                median(matching, sample -> sample.allocationP50KiB),
                median(matching, sample -> sample.allocationP95KiB));
    }

    private static double median(List<BenchmarkSample> samples,
                                 java.util.function.ToDoubleFunction<BenchmarkSample> value) {
        List<Double> sorted = samples.stream().map(value::applyAsDouble).sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private record BenchmarkSample(int round, int width, int height, boolean enabled,
                                   double cpuP50Ms, double cpuP95Ms,
                                   double gpuP50Ms, double gpuP95Ms,
                                   double allocationP50KiB, double allocationP95KiB) {
        String format() {
            return "cpu_p50_ms=" + cpuP50Ms + " cpu_p95_ms=" + cpuP95Ms
                    + " gpu_p50_ms=" + gpuP50Ms + " gpu_p95_ms=" + gpuP95Ms
                    + " allocation_kib_p50=" + allocationP50KiB
                    + " allocation_kib_p95=" + allocationP95KiB;
        }
    }

    private static double percentileMillis(List<Long> values, double percentile) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = Math.min(sorted.size() - 1,
                Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1));
        return sorted.get(index) / 1_000_000.0;
    }

    private static double percentileKib(List<Long> values, double percentile) {
        if (values.isEmpty()) return -1.0;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = Math.min(sorted.size() - 1,
                Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1));
        return sorted.get(index) / 1024.0;
    }

    private static ThreadMXBean allocationBean() {
        if (!(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean)
                || !bean.isThreadAllocatedMemorySupported()) {
            return null;
        }
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            try {
                bean.setThreadAllocatedMemoryEnabled(true);
            } catch (SecurityException ignored) {
                return null;
            }
        }
        return bean;
    }

    private static long allocatedBytes(ThreadMXBean bean) {
        return bean == null ? -1L : bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    /** Writes the complete deterministic environment snapshot used by the sample. */
    static void saveEnvironmentConfig(Path path, OutdoorEnvironmentSettings settings,
                                       boolean routeEnabled) {
        Properties properties = new Properties();
        properties.setProperty("version", "0.24");
        properties.setProperty("preset", settings.preset());
        properties.setProperty("enabled", Boolean.toString(settings.enabled()));
        properties.setProperty("route_enabled", Boolean.toString(routeEnabled));
        properties.setProperty("camera_path", routeEnabled ? "deterministic_arc" : "fixed");
        properties.setProperty("scene_time_seconds", "0.0");
        properties.setProperty("noise_seed", Integer.toString(settings.noiseSeed()));
        properties.setProperty("wind_speed", Float.toString(settings.windSpeed()));
        var sky = settings.sky();
        putVector(properties, "sky.zenith", sky.zenithColor());
        putVector(properties, "sky.horizon", sky.horizonColor());
        putVector(properties, "sky.nadir", sky.nadirColor());
        putVector(properties, "sky.sun_direction", sky.sunDirection());
        putVector(properties, "sky.sun_color", sky.sunColor());
        properties.setProperty("sky.sun_intensity", Float.toString(sky.sunIntensity()));
        properties.setProperty("sky.sun_angular_radius", Float.toString(sky.sunAngularRadius()));
        properties.setProperty("sky.halo_intensity", Float.toString(sky.haloIntensity()));
        properties.setProperty("sky.environment_intensity", Float.toString(sky.environmentIntensity()));
        var sun = settings.volumetricSun();
        properties.setProperty("volume.enabled", Boolean.toString(sun.enabled()));
        properties.setProperty("volume.steps", Integer.toString(sun.steps()));
        properties.setProperty("volume.downsample", Integer.toString(sun.downsample()));
        properties.setProperty("volume.maximum_distance", Float.toString(sun.maximumDistance()));
        properties.setProperty("volume.density", Float.toString(sun.density()));
        putVector(properties, "volume.scattering_color", sun.scatteringColor());
        properties.setProperty("volume.anisotropy", Float.toString(sun.anisotropy()));
        properties.setProperty("volume.history_weight", Float.toString(sun.historyWeight()));
        properties.setProperty("volume.depth_reject", Float.toString(sun.depthRejectThreshold()));
        properties.setProperty("volume.noise_strength", Float.toString(sun.noiseStrength()));
        var fog = settings.globalFog();
        properties.setProperty("fog.enabled", Boolean.toString(fog.enabled()));
        properties.setProperty("fog.color", fog.red() + "," + fog.green() + "," + fog.blue());
        properties.setProperty("fog.distance_density", Float.toString(fog.distanceDensity()));
        properties.setProperty("fog.height_density", Float.toString(fog.heightDensity()));
        properties.setProperty("fog.height_falloff", Float.toString(fog.heightFalloff()));
        properties.setProperty("fog.base_height", Float.toString(fog.baseHeight()));
        properties.setProperty("fog.maximum_opacity", Float.toString(fog.maximumOpacity()));
        properties.setProperty("local.count", Integer.toString(settings.localFogVolumes().size()));
        for (int index = 0; index < settings.localFogVolumes().size(); index++) {
            var volume = settings.localFogVolumes().get(index);
            properties.setProperty("local." + index + ".shape", volume.shape().name());
            putVector(properties, "local." + index + ".center", volume.center());
            putVector(properties, "local." + index + ".extent", volume.extent());
            putVector(properties, "local." + index + ".color", volume.color());
            properties.setProperty("local." + index + ".density", Float.toString(volume.density()));
            properties.setProperty("local." + index + ".noise_scale", Float.toString(volume.noiseScale()));
            properties.setProperty("local." + index + ".noise_amount", Float.toString(volume.noiseAmount()));
        }
        Path output = path.toAbsolutePath().normalize();
        try {
            Path parent = output.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (var writer = Files.newBufferedWriter(output)) {
                properties.store(writer, "Haikalat v0.24 outdoor environment");
            }
        } catch (IOException failure) {
            throw new IllegalStateException("failed to save outdoor config " + output, failure);
        }
    }

    /** Loads a config written by {@link #saveEnvironmentConfig}, preserving a fallback on old files. */
    static OutdoorEnvironmentSettings loadEnvironmentConfig(Path path,
                                                             OutdoorEnvironmentSettings fallback) {
        Properties properties = new Properties();
        Path input = path.toAbsolutePath().normalize();
        try (var reader = Files.newBufferedReader(input)) {
            properties.load(reader);
        } catch (IOException failure) {
            throw new IllegalStateException("failed to load outdoor config " + input, failure);
        }
        String presetName = properties.getProperty("preset", fallback.preset());
        OutdoorEnvironmentSettings base = preset(presetName);
        Vector3f zenith = vector(properties, "sky.zenith", base.sky().zenithColor());
        Vector3f horizon = vector(properties, "sky.horizon", base.sky().horizonColor());
        Vector3f nadir = vector(properties, "sky.nadir", base.sky().nadirColor());
        Vector3f direction = vector(properties, "sky.sun_direction", base.sky().sunDirection());
        Vector3f sunColor = vector(properties, "sky.sun_color", base.sky().sunColor());
        StylizedSkySettings sky = new StylizedSkySettings(zenith, horizon, nadir, direction, sunColor,
                floatProperty(properties, "sky.sun_intensity", base.sky().sunIntensity()),
                floatProperty(properties, "sky.sun_angular_radius", base.sky().sunAngularRadius()),
                floatProperty(properties, "sky.halo_intensity", base.sky().haloIntensity()),
                floatProperty(properties, "sky.environment_intensity", base.sky().environmentIntensity()));
        VolumetricSunSettings defaultSun = base.volumetricSun();
        VolumetricSunSettings sun = new VolumetricSunSettings(
                booleanProperty(properties, "volume.enabled", defaultSun.enabled()),
                intProperty(properties, "volume.steps", defaultSun.steps()),
                intProperty(properties, "volume.downsample", defaultSun.downsample()),
                floatProperty(properties, "volume.maximum_distance", defaultSun.maximumDistance()),
                floatProperty(properties, "volume.density", defaultSun.density()),
                vector(properties, "volume.scattering_color", defaultSun.scatteringColor()),
                floatProperty(properties, "volume.anisotropy", defaultSun.anisotropy()),
                floatProperty(properties, "volume.history_weight", defaultSun.historyWeight()),
                floatProperty(properties, "volume.depth_reject", defaultSun.depthRejectThreshold()),
                floatProperty(properties, "volume.noise_strength", defaultSun.noiseStrength()));
        FogSettings baseFog = base.globalFog();
        float[] fogColor = csv(properties.getProperty("fog.color"),
                new float[]{baseFog.red(), baseFog.green(), baseFog.blue()});
        FogSettings fog = new FogSettings(booleanProperty(properties, "fog.enabled", baseFog.enabled()),
                fogColor[0], fogColor[1], fogColor[2],
                floatProperty(properties, "fog.distance_density", baseFog.distanceDensity()),
                floatProperty(properties, "fog.height_density", baseFog.heightDensity()),
                floatProperty(properties, "fog.height_falloff", baseFog.heightFalloff()),
                floatProperty(properties, "fog.base_height", baseFog.baseHeight()),
                floatProperty(properties, "fog.maximum_opacity", baseFog.maximumOpacity()));
        int localCount = Math.max(0, Math.min(OutdoorEnvironmentSettings.MAX_LOCAL_VOLUMES,
                intProperty(properties, "local.count", base.localFogVolumes().size())));
        List<com.kaleblangley.haikalat.subsystems.render3d.LocalFogVolume> local = new ArrayList<>();
        for (int index = 0; index < localCount; index++) {
            var defaultVolume = index < base.localFogVolumes().size()
                    ? base.localFogVolumes().get(index) : null;
            var shape = com.kaleblangley.haikalat.subsystems.render3d.LocalFogVolume.Shape.valueOf(
                    properties.getProperty("local." + index + ".shape",
                            defaultVolume == null ? "SPHERE" : defaultVolume.shape().name()));
            local.add(new com.kaleblangley.haikalat.subsystems.render3d.LocalFogVolume(shape,
                    vector(properties, "local." + index + ".center",
                            defaultVolume == null ? new Vector3f() : defaultVolume.center()),
                    vector(properties, "local." + index + ".extent",
                            defaultVolume == null ? new Vector3f(1.0f) : defaultVolume.extent()),
                    floatProperty(properties, "local." + index + ".density",
                            defaultVolume == null ? 0.0f : defaultVolume.density()),
                    vector(properties, "local." + index + ".color",
                            defaultVolume == null ? new Vector3f(1.0f) : defaultVolume.color()),
                    floatProperty(properties, "local." + index + ".noise_scale",
                            defaultVolume == null ? 0.0f : defaultVolume.noiseScale()),
                    floatProperty(properties, "local." + index + ".noise_amount",
                            defaultVolume == null ? 0.0f : defaultVolume.noiseAmount())));
        }
        boolean enabled = booleanProperty(properties, "enabled", fallback.enabled());
        if (!enabled) return OutdoorEnvironmentSettings.disabled();
        return new OutdoorEnvironmentSettings(true, presetName, sky, sun, fog, local,
                intProperty(properties, "noise_seed", base.noiseSeed()),
                floatProperty(properties, "wind_speed", base.windSpeed()));
    }

    private static void putVector(Properties properties, String key, Vector3f value) {
        properties.setProperty(key, value.x + "," + value.y + "," + value.z);
    }

    private static Vector3f vector(Properties properties, String key, Vector3f fallback) {
        float[] value = csv(properties.getProperty(key), new float[]{fallback.x, fallback.y, fallback.z});
        return new Vector3f(value[0], value[1], value[2]);
    }

    private static float[] csv(String value, float[] fallback) {
        if (value == null) return fallback;
        try {
            String[] parts = value.split(",", 3);
            if (parts.length != 3) return fallback;
            return new float[]{Float.parseFloat(parts[0]), Float.parseFloat(parts[1]),
                    Float.parseFloat(parts[2])};
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static float floatProperty(Properties properties, String key, float fallback) {
        try { return Float.parseFloat(properties.getProperty(key, Float.toString(fallback))); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static int intProperty(Properties properties, String key, int fallback) {
        try { return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback))); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static boolean booleanProperty(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static void captureFrame(int width, int height, String capturePath) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(Math.multiplyExact(
                Math.multiplyExact(width, height), 4));
        glReadBuffer(GL_BACK);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                int red = Byte.toUnsignedInt(pixels.get(offset));
                int green = Byte.toUnsignedInt(pixels.get(offset + 1));
                int blue = Byte.toUnsignedInt(pixels.get(offset + 2));
                int alpha = Byte.toUnsignedInt(pixels.get(offset + 3));
                image.setRGB(x, height - 1 - y,
                        alpha << 24 | red << 16 | green << 8 | blue);
            }
        }
        Path output = Path.of(capturePath).toAbsolutePath().normalize();
        try {
            Path parent = output.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (!ImageIO.write(image, "png", output.toFile())) {
                throw new IllegalStateException("PNG writer is unavailable");
            }
            System.out.println("Outdoor environment capture: " + output);
        } catch (IOException failure) {
            throw new IllegalStateException("failed to capture outdoor environment to " + output,
                    failure);
        }
    }

    private static final class Options {
        private int width = 1280, height = 720, frames = 0, csmAtlas = 2048;
        private int resizeFrame = -1, resizeWidth = 0, resizeHeight = 0;
        private boolean hidden, noVsync, volume = true, bloom, verify, benchmark,
                autoExposure, gtao, route, insideVolume;
        private int warmup = 120;
        private String preset = "morning_fog", environmentQuality = "test";
        private String volumeQuality = "balanced";
        private String capturePath;
        private Path configPath = Path.of("build/reports/outdoor-environment.properties");
        private Path saveConfigPath;
        private Path loadConfigPath;
        private AntiAliasingMode aa = AntiAliasingMode.FXAA;
        private int switches, failureInjections;
        private int switchInterval = 20, failureInterval = 30;
        private int rounds = 1;
        private String benchmarkSizes = "1920x1080,3840x2160";

        static Options parse(String[] args) {
            Options value = new Options();
            for (String arg : args) {
                if (arg.equals("--hidden")) value.hidden = true;
                else if (arg.equals("--no-vsync")) value.noVsync = true;
                else if (arg.equals("--volume=off")) value.volume = false;
                else if (arg.equals("--bloom")) value.bloom = true;
                else if (arg.equals("--auto-exposure")) value.autoExposure = true;
                else if (arg.equals("--gtao")) value.gtao = true;
                else if (arg.equals("--route")) value.route = true;
                else if (arg.equals("--inside-volume")) value.insideVolume = true;
                else if (arg.equals("--verify")) value.verify = true;
                else if (arg.equals("--benchmark")) value.benchmark = true;
                else if (arg.equals("--stability")) {
                    value.switches = 100;
                    value.failureInjections = 10;
                }
                else if (arg.equals("--reference")) value.volumeQuality = "reference";
                else if (arg.startsWith("--capture=")) value.capturePath = arg.substring(10);
                else if (arg.startsWith("--config=")) value.configPath = Path.of(arg.substring(9));
                else if (arg.startsWith("--save-config=")) value.saveConfigPath = Path.of(arg.substring(14));
                else if (arg.startsWith("--load-config=")) value.loadConfigPath = Path.of(arg.substring(14));
                else if (arg.startsWith("--warmup=")) value.warmup = Integer.parseInt(arg.substring(9));
                else if (arg.startsWith("--rounds=")) value.rounds = Integer.parseInt(arg.substring(9));
                else if (arg.startsWith("--benchmark-sizes=")) value.benchmarkSizes = arg.substring(18);
                else if (arg.startsWith("--preset=")) value.preset = arg.substring(9);
                else if (arg.startsWith("--volume-quality=")) value.volumeQuality = arg.substring(17);
                else if (arg.startsWith("--switches=")) value.switches = Integer.parseInt(arg.substring(11));
                else if (arg.startsWith("--failure-injections=")) {
                    value.failureInjections = Integer.parseInt(arg.substring(21));
                }
                else if (arg.startsWith("--frames=")) value.frames = Integer.parseInt(arg.substring(9));
                else if (arg.startsWith("--csm-atlas=")) value.csmAtlas = Integer.parseInt(arg.substring(12));
                else if (arg.startsWith("--environment-quality=")) value.environmentQuality = arg.substring(22);
                else if (arg.startsWith("--size=")) {
                    String[] size = arg.substring(7).split("x", 2);
                    value.width = Integer.parseInt(size[0]); value.height = Integer.parseInt(size[1]);
                } else if (arg.startsWith("--resize=")) {
                    String[] resize = arg.substring(9).split(":", 2);
                    String[] size = resize[1].split("x", 2);
                    value.resizeFrame = Integer.parseInt(resize[0]);
                    value.resizeWidth = Integer.parseInt(size[0]);
                    value.resizeHeight = Integer.parseInt(size[1]);
                } else if (arg.startsWith("--aa=")) value.aa = AntiAliasingMode.valueOf(arg.substring(5).toUpperCase(Locale.ROOT));
            }
            return value;
        }

        List<int[]> benchmarkSizes() {
            List<int[]> result = new ArrayList<>();
            for (String token : benchmarkSizes.split(",")) {
                String[] size = token.trim().split("x", 2);
                if (size.length != 2) throw new IllegalArgumentException("invalid benchmark size: " + token);
                result.add(new int[]{Integer.parseInt(size[0]), Integer.parseInt(size[1])});
            }
            return result;
        }
    }
}
