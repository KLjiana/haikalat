package com.kaleblangley.haikalat.demo.gltf;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.graph.PassProfile;
import com.kaleblangley.haikalat.demo.DemoSupport;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.FrameClock;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.LocalShadowPipelineSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Render3dDiagnostics;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.ShadowLightHints;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Windowed v0.23.4 proof for bounded multi-light shadow culling and atlas caching. */
public final class Render3dShadowBudgetDemo {
    private static final int DIRECTIONAL_INDEX = 0;
    private static final int FIRST_POINT_INDEX = 1;
    private static final int FIRST_SPOT_INDEX = 4;
    private static final int POINT_MOVE_FRAME = 4;
    private static final int SPOT_MOVE_FRAME = 7;
    private static final int CAMERA_MOVE_FRAME = 10;
    private static final int DEFORMATION_FRAME = 12;

    private Render3dShadowBudgetDemo() {
    }

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(options.width(), options.height())
                .title("Haikalat Render3D v0.23.4 Shadow Culling")
                .visible(!options.hidden())
                .cursorMode(options.hidden()
                        ? GlfwWindow.CursorMode.NORMAL : GlfwWindow.CursorMode.DISABLED)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(false);
            run(window, options);
        }
    }

    private static void run(GlfwWindow window, Options options) {
        RenderSettings renderSettings = RenderSettings.builder()
                .vsync(false)
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .bloomSettings(BloomSettings.disabled())
                .sceneVisibility(true)
                .build();
        try (FrameDriver driver = new FrameDriver(renderSettings);
             PbrEnvironment environment = PbrEnvironmentLoader.load(driver.device(),
                     Render3dShadowBudgetDemo.class, "/environments/pbr/studio-small.hdr",
                     PbrEnvironmentSettings.quality(options.environmentQuality()));
             GltfDemoAssets assets = options.profile() == Profile.LEGACY
                     ? GltfDemoAssets.load(GltfDemo.Asset.DEFAULT)
                     : GltfDemoAssets.loadShadowBudget()) {
            Camera camera = new Camera(new Vector3f(0.0f, 1.0f, 8.0f));
            Scene scene = new Scene(camera);
            assets.objects().forEach(scene::add);
            addLights(scene, options.profile(), options.benchmark());
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    renderSettings, environment)
                    .directionalCascades(new DirectionalCascadeSettings(
                            4, 1_024, 0.6f, 0.08f))
                    .localShadows(options.profile() == Profile.LEGACY
                            ? LocalShadowPipelineSettings.legacyDefaults()
                            : LocalShadowPipelineSettings.balanced());
            try {
                pipeline.build();
                String previousFailureProperty = System.getProperty(
                        "haikalat.test.failShadowPassOnce");
                try {
                    renderLoop(window, driver, pipeline, scene, camera, assets, options);
                } finally {
                    if (previousFailureProperty == null) {
                        System.clearProperty("haikalat.test.failShadowPassOnce");
                    } else {
                        System.setProperty("haikalat.test.failShadowPassOnce",
                                previousFailureProperty);
                    }
                }
            } finally {
                pipeline.close();
            }
        }
    }

    private static void addLights(Scene scene, Profile profile, boolean benchmark) {
        scene.addLight(SceneLight.shadowedDirectional(
                new Vector3f(-0.45f, -1.0f, -0.35f),
                new Vector3f(1.0f, 0.94f, 0.84f), 3.0f), ShadowLightHints.priority(20));
        scene.addLight(point(-2.6f, 2.6f, 4.0f, 1.0f, 0.28f, 0.18f),
                ShadowLightHints.priority(15));
        if (profile == Profile.BALANCED) {
            scene.addLight(point(2.6f, 2.4f, 3.4f, 0.18f, 0.6f, 1.0f),
                    ShadowLightHints.priority(14));
            if (!benchmark) {
                scene.addLight(point(0.0f, 5.0f, -7.0f, 0.4f, 1.0f, 0.3f),
                        ShadowLightHints.priority(-10));
            }
        }
        int spotCount = profile == Profile.LEGACY ? 1 : 4;
        for (int index = 0; index < spotCount; index++) {
            float x = index % 2 == 0 ? -3.8f : 3.8f;
            float z = index < 2 ? 3.0f : -3.0f;
            scene.addLight(spot(x, 4.0f, z, index),
                    ShadowLightHints.priority(12 - index));
        }
        if (profile == Profile.BALANCED && !benchmark) {
            scene.addLight(spot(0.0f, 5.5f, -8.0f, 4), ShadowLightHints.priority(-12));
        }
    }

    private static SceneLight point(float x, float y, float z,
                                    float red, float green, float blue) {
        return SceneLight.shadowedPoint(new Vector3f(x, y, z),
                new Vector3f(red, green, blue), 34.0f, 16.0f);
    }

    private static SceneLight spot(float x, float y, float z, int index) {
        Vector3f color = switch (index & 3) {
            case 0 -> new Vector3f(1.0f, 0.35f, 0.18f);
            case 1 -> new Vector3f(0.2f, 0.55f, 1.0f);
            case 2 -> new Vector3f(0.35f, 1.0f, 0.4f);
            default -> new Vector3f(0.85f, 0.3f, 1.0f);
        };
        return SceneLight.shadowedSpot(new Vector3f(x, y, z),
                new Vector3f(-x * 0.18f, -0.75f, -z * 0.12f),
                color, 42.0f, 18.0f, 0.16f, 0.58f);
    }

    private static void renderLoop(GlfwWindow window, FrameDriver driver,
                                   RenderPipeline pipeline, Scene scene, Camera camera,
                                   GltfDemoAssets assets, Options options) {
        FrameClock clock = new FrameClock();
        Verification verification = new Verification();
        Samples samples = new Samples();
        boolean expectedFailure = options.dynamic() && options.verify();
        boolean recoveredFailure = false;
        int frame = 0;
        while (!window.shouldClose()) {
            if (!options.benchmark()) {
                applyDeterministicEvent(frame, scene, camera, assets, options.dynamic());
            }
            else if (!options.hidden()) assets.update(clock.tick().deltaSeconds());
            if (options.resizeFrame() == frame) {
                verification.generationBeforeResize =
                        pipeline.lastRender3dDiagnostics().activeGenerationId();
                window.resize(options.resizeWidth(), options.resizeHeight());
            }
            float deltaSeconds = options.hidden() ? 1.0f / 60.0f : clock.tick().deltaSeconds();
            if (!options.hidden()) DemoSupport.updateFreeCamera(window, camera, deltaSeconds);
            if (window.consumeResize()) pipeline.resize(window.width(), window.height());

            if (options.dynamic() && options.verify() && frame == 1 && !recoveredFailure) {
                // After the first static tile commit, inject one partial shadow pass
                // failure; the following frame must recover with FRAME_FAILURE rather
                // than trusting a partially written atlas tile.
                System.setProperty("haikalat.test.failShadowPassOnce", "true");
            }

            driver.beginFrame();
            boolean frameFailed = false;
            try {
                pipeline.execute(driver.device(), deltaSeconds);
                driver.recordGraph(pipeline.graph());
                driver.endFrame();
            } catch (RuntimeException | Error failure) {
                driver.failFrame(pipeline.graph(), failure);
                if (!expectedFailure || recoveredFailure
                        || !String.valueOf(failure.getMessage()).contains(
                        "injected shadow pass failure")) {
                    throw failure;
                }
                recoveredFailure = true;
                frameFailed = true;
                System.clearProperty("haikalat.test.failShadowPassOnce");
            }
            if (!frameFailed) driver.present(window::swapBuffers);
            window.pollEvents();
            if (frameFailed) verification.markFailureObserved();
            GlDebug.checkError("Render3dShadowBudgetDemo.frame");

            Render3dDiagnostics diagnostics = pipeline.lastRender3dDiagnostics();
            verification.observe(frame, diagnostics, pipeline);
            if (frame >= options.warmupFrames()) samples.add(driver, diagnostics);
            if (!options.hidden() && frame % 20 == 0) updateTitle(window, diagnostics);
            frame++;
            if (options.maximumFrames() > 0 && frame >= options.maximumFrames()) {
                window.requestClose();
            }
        }
        Render3dDiagnostics diagnostics = pipeline.lastRender3dDiagnostics();
        if (options.verify()) verification.verify(diagnostics, pipeline, options);
        printSummary(diagnostics, pipeline.lastShadowCullingStatistics(), samples, frame, options);
        GlDebug.assertNoError("Render3dShadowBudgetDemo");
    }

    private static void applyDeterministicEvent(int frame, Scene scene, Camera camera,
                                                GltfDemoAssets assets, boolean dynamic) {
        if (dynamic && frame == 1) {
            // Move one revisioned animated caster while it remains in the local-light
            // volumes. The planner must invalidate only the old/new membership union.
            assets.translateShadowCaster(0.8f, 0.0f, 0.0f);
        } else if (dynamic && frame == 2) {
            assets.translateShadowCaster(0.45f, 0.0f, 0.0f);
        } else if (dynamic && frame == 6) {
            // Cross a point-light cube-face seam; adjacent faces may both retain the
            // caster because the planner deliberately uses a conservative guard band.
            assets.translateShadowCaster(-2.5f, 0.0f, -0.8f);
        } else if (dynamic && frame == 9) {
            // Leave the small spot/range volume. The previous membership must still be
            // dirty so the old atlas tile is cleared rather than leaving a ghost.
            assets.translateShadowCaster(0.0f, 0.0f, -12.0f);
        } else if (frame == POINT_MOVE_FRAME) {
            scene.setLight(FIRST_POINT_INDEX, point(-2.15f, 2.8f, 3.7f,
                    1.0f, 0.28f, 0.18f));
        } else if (frame == SPOT_MOVE_FRAME) {
            scene.setLight(FIRST_SPOT_INDEX, spot(-3.35f, 4.25f, 2.7f, 0));
        } else if (frame == CAMERA_MOVE_FRAME) {
            camera.setPosition(new Vector3f(0.22f, 1.0f, 8.0f));
        } else if (frame == DEFORMATION_FRAME) {
            assets.update(1.0f / 30.0f);
        }
    }

    private static void updateTitle(GlfwWindow window, Render3dDiagnostics diagnostics) {
        var shadows = diagnostics.shadows();
        window.setTitle(String.format(Locale.ROOT,
                "Render3D v0.23.4 | D/P/S %d/%d/%d | tiles %d draw %d reuse | "
                        + "cache %d/%d | %s | WASD mouse ESC",
                shadows.directionalSelected(), shadows.pointSelected(), shadows.spotSelected(),
                shadows.tilesRendered(), shadows.tilesReused(), shadows.cacheHits(),
                shadows.cacheMisses(), shadows.filterMode()));
    }

    private static void printSummary(Render3dDiagnostics diagnostics,
                                     com.kaleblangley.haikalat.subsystems.render3d.ShadowCullingStatistics culling,
                                     Samples samples, int frames, Options options) {
        var shadow = diagnostics.shadows();
        System.out.printf(Locale.ROOT,
                "SHADOW_BUDGET profile=%s frames=%d selected=%d/%d/%d rejected=%d "
                        + "tiles=%d/%d cache=%d/%d atlas=%dx%d,%dx%d depthMiB=%.2f "
                        + "cpuMedian=%.3fms cpuP95=%.3fms gpuMedian=%.3fms "
                        + "shadowGpuMedian=%.3fms samples=%d failure=%s%n",
                options.profile().name().toLowerCase(Locale.ROOT), frames,
                shadow.directionalSelected(), shadow.pointSelected(), shadow.spotSelected(),
                shadow.rejectedLights().size(), shadow.tilesRendered(), shadow.tilesReused(),
                shadow.cacheHits(), shadow.cacheMisses(), shadow.pointAtlasWidth(),
                shadow.pointAtlasHeight(), shadow.spotAtlasWidth(), shadow.spotAtlasHeight(),
                shadow.estimatedDepthBytes() / 1_048_576.0,
                samples.percentile(samples.cpuMillis, 0.5),
                samples.percentile(samples.cpuMillis, 0.95),
                samples.percentile(samples.gpuMillis, 0.5),
                samples.percentile(samples.shadowGpuMillis, 0.5),
                samples.cpuMillis.size(),
                diagnostics.failureStage().isEmpty() ? "none" : diagnostics.failureStage());
        System.out.printf(Locale.ROOT,
                "SHADOW_CULLING available=%s reused=%s full=%s active=%d dirty=%d reusedViews=%d "
                        + "empty=%d candidates=%d volatile=%d tests=%d references=%d culled=%d "
                        + "directional=%d point=%d spot=%d buildNanos=%d%n",
                culling.available(), culling.planReused(), culling.fullRebuild(),
                culling.activeViews(), culling.dirtyViews(), culling.reusedViews(),
                culling.emptyViewsCleared(), culling.candidateCasters(), culling.volatileCasters(),
                culling.casterViewTests(), culling.casterViewReferences(), culling.culledReferences(),
                culling.directionalReferences(), culling.pointReferences(), culling.spotReferences(),
                culling.buildNanos());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class Verification {
        private int initialTiles = -1;
        private int pointMoveTiles = -1;
        private int spotMoveTiles = -1;
        private int cameraMoveTiles = -1;
        private int deformationTiles = -1;
        private int resizeTiles = -1;
        private long generationBeforeResize = -1L;
        private long generationAfterResize = -1L;
        private boolean failureObserved;
        private boolean failureRecoveryObserved;

        private void markFailureObserved() {
            failureObserved = true;
        }

        private void observe(int frame, Render3dDiagnostics diagnostics,
                             RenderPipeline pipeline) {
            int tiles = diagnostics.shadows().tilesRendered();
            if (failureObserved && diagnostics.shadows().missReasons().contains("FRAME_FAILURE")) {
                failureRecoveryObserved = true;
            }
            if (frame == 0) initialTiles = tiles;
            else if (frame == POINT_MOVE_FRAME) pointMoveTiles = tiles;
            else if (frame == SPOT_MOVE_FRAME) spotMoveTiles = tiles;
            else if (frame == CAMERA_MOVE_FRAME) cameraMoveTiles = tiles;
            else if (frame == DEFORMATION_FRAME) deformationTiles = tiles;
            if (generationBeforeResize > 0 && generationAfterResize < 0
                    && diagnostics.activeGenerationId() == generationBeforeResize) {
                generationAfterResize = diagnostics.activeGenerationId();
                resizeTiles = tiles;
            }
        }

        private void verify(Render3dDiagnostics diagnostics, RenderPipeline pipeline,
                            Options options) {
            var shadows = diagnostics.shadows();
            require(diagnostics.available(), "Render3D diagnostics are unavailable");
            require(diagnostics.failureStage().isEmpty(),
                    "pipeline failure: " + diagnostics.failureStage());
            require(shadows.directionalSelected() == 1 && shadows.pointSelected() == 2
                            && shadows.spotSelected() == 4,
                    "balanced selection is not 1 directional + 2 point + 4 spot");
            require(shadows.selectedLights().size() == 7,
                    "selected-light diagnostics do not expose all seven owners");
            require(shadows.rejectedLights().size() >= 2,
                    "budget demo did not expose deterministic budget rejection");
            require(initialTiles == 20, "first frame must initialize 20 shadow tiles");
            require(pointMoveTiles == 6, "one point-light move must redraw six faces");
            require(spotMoveTiles == 1, "one spot-light move must redraw one tile");
            require(cameraMoveTiles == 4,
                    "camera move must redraw only four directional cascade tiles");
            require(deformationTiles == 20,
                    "skinned deformation must invalidate every selected shadow tile");
            require(shadows.tilesRendered() == 0 && shadows.tilesReused() == 20,
                    "final static frame must fully reuse the shadow atlas");
            require(shadows.pointAtlasWidth() == 1_536 && shadows.pointAtlasHeight() == 2_048,
                    "point atlas dimensions are not the 3x4 balanced layout");
            require(shadows.spotAtlasWidth() == 1_024 && shadows.spotAtlasHeight() == 1_024,
                    "spot atlas dimensions are not the 2x2 balanced layout");
            var culling = pipeline.lastShadowCullingStatistics();
            require(culling.available(), "shadow culling diagnostics are unavailable");
            require(culling.activeViews() == 20,
                    "balanced shadow plan must publish all twenty view slots");
            require(culling.candidateCasters() > 0 && culling.casterViewReferences() > 0,
                    "shadow planner did not publish caster/view references");
            require(culling.culledReferences() > 0,
                    "shadow planner did not cull any non-intersecting caster/view pairs");
            require(culling.planReused(),
                    "final static frame must reuse the published shadow culling plan");
            if (options.dynamic()) {
                require(failureObserved && failureRecoveryObserved,
                        "dynamic integration did not prove shadow failure recovery");
            }
            if (options.resizeFrame() >= 0) {
                require(generationBeforeResize > 0
                                && generationAfterResize == generationBeforeResize,
                        "window resize replaced the fixed shadow generation");
                require(resizeTiles == 0,
                        "same-aspect resize unnecessarily invalidated shadow tiles");
            }
            require(diagnostics.activeGenerationId() > 0, "no active pipeline generation");
        }
    }

    private static final class Samples {
        private final List<Double> cpuMillis = new ArrayList<>();
        private final List<Double> gpuMillis = new ArrayList<>();
        private final List<Double> shadowGpuMillis = new ArrayList<>();

        private void add(FrameDriver driver, Render3dDiagnostics diagnostics) {
            cpuMillis.add(driver.statistics().lastCpuSubmitMillis());
            var profile = driver.statistics().lastFrameProfile();
            if (profile.gpuTotalComplete()) gpuMillis.add(profile.totalGpuMillis());
            double shadow = 0.0;
            boolean available = false;
            for (PassProfile pass : profile.passes()) {
                if (!pass.passName().contains("Shadow")) continue;
                if (pass.gpuStatus() == PassProfile.GpuTimingStatus.AVAILABLE) {
                    shadow += pass.gpuMillis();
                    available = true;
                }
            }
            if (available) shadowGpuMillis.add(shadow);
        }

        private double percentile(List<Double> source, double percentile) {
            if (source.isEmpty()) return -1.0;
            double[] values = source.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            int index = (int) Math.ceil(percentile * values.length) - 1;
            return values[Math.max(0, Math.min(values.length - 1, index))];
        }
    }

    private enum Profile { LEGACY, BALANCED }

    private record Options(boolean hidden, int maximumFrames, int warmupFrames,
                           int width, int height, int resizeFrame,
                           int resizeWidth, int resizeHeight,
                           String environmentQuality, boolean verify,
                           boolean benchmark, boolean dynamic, Profile profile) {
        private static Options parse(String[] arguments) {
            boolean hidden = false;
            int frames = -1;
            int warmup = 0;
            int width = 1_280;
            int height = 720;
            int resizeFrame = -1;
            int resizeWidth = 0;
            int resizeHeight = 0;
            String environmentQuality = "default";
            boolean verify = false;
            boolean benchmark = false;
            boolean dynamic = false;
            Profile profile = Profile.BALANCED;
            for (String argument : arguments) {
                if (argument.equals("--hidden") || argument.equals("--deterministic")) {
                    hidden = true;
                } else if (argument.equals("--verify")) {
                    verify = true;
                } else if (argument.equals("--benchmark")) {
                    benchmark = true;
                    hidden = true;
                } else if (argument.equals("--dynamic")) {
                    dynamic = true;
                } else if (argument.startsWith("--frames=")) {
                    frames = positive(argument.substring(9), "--frames");
                } else if (argument.startsWith("--warmup=")) {
                    warmup = nonNegative(argument.substring(9), "--warmup");
                } else if (argument.startsWith("--size=")) {
                    int[] size = size(argument.substring(7));
                    width = size[0];
                    height = size[1];
                } else if (argument.startsWith("--resize=")) {
                    String[] parts = argument.substring(9).split(":", -1);
                    if (parts.length != 2) throw new IllegalArgumentException(
                            "--resize must use frame:WIDTHxHEIGHT");
                    resizeFrame = nonNegative(parts[0], "--resize frame");
                    int[] size = size(parts[1]);
                    resizeWidth = size[0];
                    resizeHeight = size[1];
                } else if (argument.startsWith("--environment-quality=")) {
                    environmentQuality = argument.substring(22);
                } else if (argument.startsWith("--profile=")) {
                    profile = Profile.valueOf(argument.substring(10).toUpperCase(Locale.ROOT));
                } else {
                    throw new IllegalArgumentException("Unknown shadow-budget option: " + argument);
                }
            }
            if (hidden && frames < 0) frames = 18;
            if (verify && (benchmark || profile != Profile.BALANCED || frames < 16)) {
                throw new IllegalArgumentException(
                        "--verify requires balanced non-benchmark mode with at least 16 frames");
            }
            if (warmup >= frames && frames > 0) {
                throw new IllegalArgumentException("--warmup must be smaller than --frames");
            }
            PbrEnvironmentSettings.quality(environmentQuality);
            return new Options(hidden, frames, warmup, width, height, resizeFrame,
                    resizeWidth, resizeHeight, environmentQuality, verify, benchmark, dynamic,
                    profile);
        }

        private static int positive(String value, String name) {
            int result = nonNegative(value, name);
            if (result == 0) throw new IllegalArgumentException(name + " must be positive");
            return result;
        }

        private static int nonNegative(String value, String name) {
            try {
                int result = Integer.parseInt(value);
                if (result < 0) throw new IllegalArgumentException(name + " must be non-negative");
                return result;
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(name + " must be an integer", failure);
            }
        }

        private static int[] size(String value) {
            String[] parts = value.toLowerCase(Locale.ROOT).split("x", -1);
            if (parts.length != 2) throw new IllegalArgumentException(
                    "size must use WIDTHxHEIGHT");
            return new int[]{positive(parts[0], "width"), positive(parts[1], "height")};
        }
    }
}
