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
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.FogSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoQuality;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.LocalFogVolume;
import com.kaleblangley.haikalat.subsystems.render3d.OutdoorEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.VolumetricFogSettings;
import com.kaleblangley.haikalat.subsystems.render3d.StylizedSkySettings;
import com.kaleblangley.haikalat.subsystems.render3d.VisualSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
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
import java.lang.management.ManagementFactory;
import com.sun.management.ThreadMXBean;

import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;

/** Forest baseline capture adapter using the production physical-fog pipeline. */
public final class Render3dForestCaptureDemo {
    private Render3dForestCaptureDemo() { }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(options.width, options.height)
                .title("Haikalat v0.25 Forest Capture")
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
        OutdoorEnvironmentSettings environmentSettings = preset(options.preset);
        VisualProfile profile = options.loadConfigPath == null
                ? VolumetricDemoSceneFactory.profile(VolumetricDemoSceneFactory.SceneId.FOREST_MORNING)
                : VisualProfileCodec.load(options.loadConfigPath,
                VisualProfile.outdoorCompatibility(environmentSettings));
        VisualSettings visual = options.loadConfigPath == null
                ? new VisualSettings(1.05f, environmentSettings.sky().environmentIntensity(), 0.0f,
                        options.aa, BloomSettings.builder().enabled(options.bloom).build(), environmentSettings)
                        .withVolumetricFog(profile.settings().volumetricFog())
                : profile.settings();
        visual = visual.withVolumetricFog(applyVolumeQuality(visual.volumetricFog(), options.volumeQuality));
        if (!options.volume) visual = visual.withVolumetricFog(VolumetricFogSettings.disabled());
        if (options.bloom && !visual.bloom().enabled()) {
            visual = withBloom(visual, new BloomSettings(true, visual.bloom().threshold(),
                    visual.bloom().softKnee(), visual.bloom().intensity(),
                    visual.bloom().maxLevels()));
        }
        if (options.bloomOff && visual.bloom().enabled()) {
            visual = withBloom(visual, new BloomSettings(false, visual.bloom().threshold(),
                    visual.bloom().softKnee(), visual.bloom().intensity(),
                    visual.bloom().maxLevels()));
        }
        if (options.fogOff) visual = visual.withVolumetricFog(VolumetricFogSettings.disabled());
        if (options.grayModel) {
            visual = new VisualSettings(1.0f, visual.iblIntensity(),
                    visual.iblRotationRadians(), visual.directionalLightDirection(),
                    visual.directionalLightColor(), visual.directionalLightIntensity(),
                    visual.antiAliasingMode(),
                    new BloomSettings(false, visual.bloom().threshold(),
                            visual.bloom().softKnee(), visual.bloom().intensity(),
                            visual.bloom().maxLevels()),
                    visual.outdoor(), VolumetricFogSettings.disabled());
        }
        environmentSettings = visual.outdoor();
        RenderSettings renderSettings = RenderSettings.builder()
                .vsync(!options.noVsync && !options.hidden)
                .antiAliasingMode(visual.antiAliasingMode())
                .toneMappingMode(ToneMappingMode.ACES)
                .exposureMode(options.autoExposure ? ExposureMode.AUTO : ExposureMode.MANUAL)
                .exposure(visual.manualExposure())
                .bloomSettings(visual.bloom())
                .build();
        List<Material> materials = new ArrayList<>();
        try (FrameDriver driver = new FrameDriver(renderSettings);
             OutdoorEnvironmentResources outdoorResources = new OutdoorEnvironmentResources(
                     driver.device(), options.environmentQuality);
             PbrFallbackTextures fallbacks = new PbrFallbackTextures();
             ShaderProgram shader = ShaderProgram.fromResource(Render3dForestCaptureDemo.class,
                     "/shaders/render3d/pbr/pbr-forward.vert", "/shaders/render3d/pbr/pbr-forward.frag");
             WoodlandMeshes meshes = new WoodlandMeshes()) {
            Scene scene = WoodlandSceneFactory.create(meshes, shader, fallbacks, materials, options.grayModel);
            if (options.loadConfigPath != null) VolumetricDemoSceneFactory.applyLights(scene, profile, false);
            PbrEnvironment environment = outdoorResources.environmentFor(environmentSettings);
            RenderPipeline pipeline = new RenderPipeline(window, scene, null, renderSettings, environment)
                    .postProcessSettings(PostProcessSettings.builder()
                            .gtao(options.gtao ? GtaoSettings.quality(GtaoQuality.MEDIUM)
                                    : GtaoSettings.disabled())
                            .build())
                    .directionalCascades(new DirectionalCascadeSettings(4, options.csmAtlas, 0.62f, 0.08f, Float.MAX_VALUE))
                    .outdoorEnvironment(environmentSettings);
            try {
                pipeline.applyVisualSettings(visual);
                pipeline.build();
                if (options.insideVolume) {
                    pipeline.scene().camera().setPosition(new Vector3f(-2.2f, -0.7f, -9.0f));
                }
                if (options.saveConfigPath != null) {
                    saveEnvironmentConfig(options.saveConfigPath, pipeline.visualSettings(), options.route);
                }
                if (!options.hidden || options.overlay) {
                    VisualProfile applied = new VisualProfile(2, profile.profileId(), profile.description(),
                            profile.author(), visual, profile.lightVolumes());
                    try (VolumetricFogOverlay overlay = new VolumetricFogOverlay(window, pipeline,
                            outdoorResources, applied, options.configPath,
                            options.loadConfigPath == null ? options.configPath : options.loadConfigPath)) {
                        renderLoop(window, driver, pipeline, options, overlay, outdoorResources);
                    }
                } else renderLoop(window, driver, pipeline, options, null, outdoorResources);
            } finally {
                pipeline.close();
            }
        } finally {
            for (int index = materials.size() - 1; index >= 0; index--) materials.get(index).close();
        }
    }

    private static VisualSettings withOutdoor(VisualSettings source,
                                               OutdoorEnvironmentSettings outdoor) {
        return new VisualSettings(source.manualExposure(), source.iblIntensity(),
                source.iblRotationRadians(), source.directionalLightDirection(),
                source.directionalLightColor(), source.directionalLightIntensity(),
                source.antiAliasingMode(), source.bloom(), outdoor, source.volumetricFog());
    }

    private static VisualSettings withBloom(VisualSettings source, BloomSettings bloom) {
        return new VisualSettings(source.manualExposure(), source.iblIntensity(),
                source.iblRotationRadians(), source.directionalLightDirection(),
                source.directionalLightColor(), source.directionalLightIntensity(),
                source.antiAliasingMode(), bloom, source.outdoor(), source.volumetricFog());
    }

    private static VisualSettings withOutdoorSky(VisualSettings source,
                                                 OutdoorEnvironmentSettings outdoor) {
        return new VisualSettings(source.manualExposure(), source.iblIntensity(),
                source.iblRotationRadians(), outdoor.sky().sunDirection(),
                outdoor.sky().sunColor(), outdoor.sky().sunIntensity(),
                source.antiAliasingMode(), source.bloom(), outdoor, source.volumetricFog());
    }

    static OutdoorEnvironmentSettings preset(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "clear_day", "clear", "day" -> OutdoorEnvironmentSettings.clearDay();
            case "golden_hour", "golden", "dusk" -> OutdoorEnvironmentSettings.goldenHour();
            case "morning_fog", "morning", "fog" -> OutdoorEnvironmentSettings.morningFog();
            default -> throw new IllegalArgumentException("unknown outdoor preset: " + value);
        };
    }

    static VolumetricFogSettings applyVolumeQuality(VolumetricFogSettings base, String quality) {
        VolumetricFogSettings.Quality selected = VolumetricFogSettings.Quality.valueOf(quality.toUpperCase(Locale.ROOT));
        return new VolumetricFogSettings(base.enabled(), base.fogDistance(), selected,
                base.globalMedium(), base.localVolumes(), base.anisotropy(), base.history(),
                base.historyWeight(), base.noiseSeed(), base.wind());
    }

    private static void renderLoop(GlfwWindow window, FrameDriver driver,
                                   RenderPipeline pipeline, Options options,
                                   VolumetricFogOverlay overlay, OutdoorEnvironmentResources outdoorResources) {
        int frame = 0;
        int routeFrame = 0;
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
            if (options.route && (overlay == null || !overlay.routePaused())) {
                float routeProgress = options.frames > 1
                        ? Math.min(1.0f, routeFrame / (float) (options.frames - 1))
                        : 0.0f;
                SceneCaptureRoutes.forest(routeCamera, routeProgress);
                routeFrame++;
            }
            if (options.switches > 0 && environmentSwitches < options.switches
                    && frame > 0 && frame % Math.max(1, options.switchInterval) == 0) {
                String[] cycle = {"morning_fog", "clear_day", "golden_hour"};
                OutdoorEnvironmentSettings next = preset(cycle[environmentSwitches % cycle.length]);
                outdoorResources.apply(pipeline, next);
                environmentSwitches++;
            }
            if (options.failureInjections > 0 && failureInjections < options.failureInjections
                    && frame > 0 && frame % Math.max(1, options.failureInterval) == 0) {
                int width = (failureInjections & 1) == 0 ? 352 : 368;
                int height = (failureInjections & 1) == 0 ? 198 : 208;
                System.setProperty("haikalat.test.failVolumeAllocation",
                        Integer.toString(failureInjections % 14));
                window.resize(width, height);
            }
            if (options.resizeFrame == frame) window.resize(options.resizeWidth, options.resizeHeight);
            if (window.consumeResize()) {
                try {
                    pipeline.resize(window.width(), window.height());
                } catch (IllegalStateException expectedFailure) {
                    if (!expectedFailure.getMessage().contains("injected volume allocation")) {
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
                SceneCaptureIO.display(window.width(), window.height(), options.capturePath);
            }
            for (CaptureAt capture : options.captureAt) {
                if (capture.frame == frame) {
                    SceneCaptureIO.display(window.width(), window.height(), capture.path);
                }
            }
            for (CaptureAt capture : options.captureHdrAt) {
                if (capture.frame == frame) {
                    VisualBaselineHdr.save(Path.of(capture.path), VisualBaselineHdr.downsample(
                            pipeline.captureLinearHdrRgbaFloat(), window.width(), window.height()));
                }
            }
            for (CaptureAt comparison : options.compareHdrAt) {
                if (comparison.frame == frame) {
                    VisualBaselineHdr.Image actual = VisualBaselineHdr.downsample(
                            pipeline.captureLinearHdrRgbaFloat(), window.width(), window.height());
                    Path reference = Path.of(comparison.path);
                    Path errorMap = Path.of("build/reports/render3d-v0243/visual/hdr-error",
                            reference.getFileName().toString() + ".png");
                    System.out.println("Outdoor linear HDR " + reference + ": "
                            + VisualBaselineHdr.compare(VisualBaselineHdr.load(reference), actual, errorMap));
                }
            }
            if (overlay != null && overlay.consumeCaptureRequest()) {
                SceneCaptureIO.display(window.width(), window.height(),
                        "build/reports/render3d-v0243/visual/manual/outdoor-" + frame + ".png");
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
                window.setTitle("v0.25 Forest " + options.preset + " | volume=" + options.volume
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

    /** Writes the visual profile and a separate deterministic capture specification. */
    static void saveEnvironmentConfig(Path path, VisualSettings settings,
                                       boolean routeEnabled) {
        VisualProfileCodec.save(path, new VisualProfile(2, settings.outdoor().preset(),
                "Forest capture profile", "Haikalat", settings));
        Path capturePath = path.toAbsolutePath().normalize().resolveSibling(
                path.getFileName().toString() + ".capture.properties");
        SceneCaptureSpecCodec.save(capturePath, new SceneCaptureSpec(
                "forest-v0243-r1", routeEnabled ? "deterministic_arc" : "static",
                settings.volumetricFog().noiseSeed(), List.of(60, 240, 420)));
    }

    /** Loads either a v0.24.3 visual profile or the explicit v0.24 migration format. */
    static OutdoorEnvironmentSettings loadEnvironmentConfig(Path path,
                                                             OutdoorEnvironmentSettings fallback) {
        return VisualProfileCodec.load(path, VisualProfile.outdoorCompatibility(fallback))
                .settings().outdoor();
    }

    private static final class Options {
        private int width = 1280, height = 720, frames = 0, csmAtlas = 2048;
        private int resizeFrame = -1, resizeWidth = 0, resizeHeight = 0;
        private boolean hidden, overlay, noVsync, volume = true, bloom, bloomOff, fogOff,
                grayModel,
                verify, benchmark,
                autoExposure, gtao, route, insideVolume;
        private int warmup = 120;
        private String preset = "morning_fog", environmentQuality = "test";
        private String volumeQuality = "balanced";
        private String capturePath;
        private Path configPath = Path.of("build/reports/outdoor-environment.properties");
        private Path saveConfigPath;
        private Path loadConfigPath;
        private final List<CaptureAt> captureAt = new ArrayList<>();
        private final List<CaptureAt> captureHdrAt = new ArrayList<>();
        private final List<CaptureAt> captureOutdoorDiagnosticsAt = new ArrayList<>();
        private final List<CaptureAt> compareHdrAt = new ArrayList<>();
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
                else if (arg.equals("--overlay")) value.overlay = true;
                else if (arg.equals("--bloom")) value.bloom = true;
                else if (arg.equals("--bloom=off")) value.bloomOff = true;
                else if (arg.equals("--fog=off")) value.fogOff = true;
                else if (arg.equals("--gray-model")) value.grayModel = true;
                else if (arg.equals("--auto-exposure")) value.autoExposure = true;
                else if (arg.equals("--gtao")) value.gtao = true;
                else if (arg.equals("--route")) value.route = true;
                else if (arg.equals("--inside-volume")) value.insideVolume = true;
                else if (arg.equals("--verify")) value.verify = true;
                else if (arg.equals("--benchmark")) throw new IllegalArgumentException("Use runRender3dVolumetricFogBenchmarks for paired physical-fog measurements");
                else if (arg.equals("--stability")) {
                    value.switches = 100;
                    value.failureInjections = 10;
                }
                else if (arg.equals("--reference")) throw new IllegalArgumentException("Use tools/reference/run-outdoor-reference.ps1 for the archived legacy reference");
                else if (arg.startsWith("--capture=")) value.capturePath = arg.substring(10);
                else if (arg.startsWith("--capture-at=")) {
                    String[] capture = arg.substring(13).split(":", 2);
                    if (capture.length != 2) {
                        throw new IllegalArgumentException("invalid --capture-at: " + arg);
                    }
                    value.captureAt.add(new CaptureAt(Integer.parseInt(capture[0]), capture[1]));
                }
                else if (arg.startsWith("--capture-hdr-at=")) {
                    String[] capture = arg.substring(17).split(":", 2);
                    if (capture.length != 2) throw new IllegalArgumentException("invalid --capture-hdr-at: " + arg);
                    value.captureHdrAt.add(new CaptureAt(Integer.parseInt(capture[0]), capture[1]));
                }
                else if (arg.startsWith("--capture-outdoor-diagnostics-at=")) {
                    throw new IllegalArgumentException("Use runRender3dVolumetricFogDemo --diagnostics for physical-fog diagnostics");
                }
                else if (arg.startsWith("--compare-hdr-at=")) {
                    String[] capture = arg.substring(17).split(":", 2);
                    if (capture.length != 2) throw new IllegalArgumentException("invalid --compare-hdr-at: " + arg);
                    value.compareHdrAt.add(new CaptureAt(Integer.parseInt(capture[0]), capture[1]));
                }
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
            VolumetricFogSettings.Quality.valueOf(value.volumeQuality.toUpperCase(Locale.ROOT));
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

    private record CaptureAt(int frame, String path) {
        private CaptureAt {
            if (frame < 0 || path == null || path.isBlank()) {
                throw new IllegalArgumentException("capture frame/path must be valid");
            }
        }
    }
}
