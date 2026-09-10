package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.demo.pbr.ClusteredDemoSceneFactory.Bundle;
import com.kaleblangley.haikalat.demo.pbr.ClusteredDemoSceneFactory.Kind;
import com.kaleblangley.haikalat.demo.pbr.ClusteredDemoSceneFactory.Request;
import com.kaleblangley.haikalat.demo.pbr.ClusteredDemoSceneFactory.StressMode;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.ClusteredLightingDiagnostics;
import com.kaleblangley.haikalat.subsystems.render3d.ClusteredLightingSettings;
import com.kaleblangley.haikalat.subsystems.render3d.ClusterDebugMode;
import com.kaleblangley.haikalat.subsystems.render3d.LocalShadowPipelineSettings;
import com.kaleblangley.haikalat.subsystems.render3d.OutdoorEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Render3dDiagnostics;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.StylizedSkySettings;
import com.kaleblangley.haikalat.subsystems.render3d.VolumetricSunSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.postprocess.FogSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_2;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_3;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_4;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_6;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_C;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_H;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_L;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_O;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;

/**
 * v0.24.2 clustered-forward demo: lighting lab, night town and stress matrix.
 *
 * <p>Every scene is produced by {@link ClusteredDemoSceneFactory} so the
 * benchmark and quality runners measure the same production pipeline.</p>
 */
public final class Render3dClusteredDemo {
    private static final int DEFAULT_FRAMES = 120;
    private static final long MOVING_SEED = 0x5eedL;

    private Render3dClusteredDemo() {
    }

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(options.width(), options.height())
                .title("Haikalat Clustered Forward v0.24.2")
                .visible(!options.hidden())
                .decorated(!options.hidden())
                .cursorMode(options.hidden()
                        ? GlfwWindow.CursorMode.NORMAL : GlfwWindow.CursorMode.DISABLED)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(!options.hidden());
            run(window, options);
        }
    }

    private static void run(GlfwWindow window, Options options) {
        boolean town = options.kind() == Kind.TOWN;
        RenderSettings settings = RenderSettings.builder()
                .vsync(!options.hidden())
                .antiAliasingMode(options.antiAliasing())
                .msaaSamples(4)
                .toneMappingMode(ToneMappingMode.ACES)
                .exposureMode(ExposureMode.MANUAL)
                .exposure(town ? 1.3f : 1.15f)
                .bloomSettings(town && options.bloom()
                        ? BloomSettings.builder().enabled(true).threshold(1.4f)
                                .softKnee(0.45f).intensity(0.15f).maxLevels(4).build()
                        : BloomSettings.builder().enabled(options.kind() == Kind.LAB
                                && options.bloom())
                                .threshold(0.85f).softKnee(0.6f).intensity(0.35f).build())
                .sceneVisibility(true)
                .build();
        ClusteredLightingSettings clustered = ClusteredLightingSettings.builder()
                .tileSize(options.tileSize())
                .zSlices(options.zSlices())
                .inlineIndicesPerCluster(options.clusterCapacity())
                .maxLocalLights(Math.max(64, options.lights()))
                .build();
        try (FrameDriver driver = new FrameDriver(settings);
             PbrEnvironment environment = PbrEnvironmentLoader.load(driver.device(),
                     Render3dClusteredDemo.class, "/environments/pbr/studio-small.hdr",
                     PbrEnvironmentSettings.testQuality());
             PbrFallbackTextures fallbacks = new PbrFallbackTextures();
             ShaderProgram shader = ShaderProgram.fromResource(Render3dClusteredDemo.class,
                     "/shaders/render3d/pbr/pbr-forward.vert",
                     "/shaders/render3d/pbr/pbr-forward.frag")) {
            if (town) {
                // Night town: dim the studio IBL so local lamps and lit windows
                // carry the image; the environment still supplies reflections.
                environment.intensity(0.25f);
            }
            Request request = options.kind() == Kind.TOWN
                    ? Request.town()
                    : options.kind() == Kind.LAB
                    ? Request.lab(options.lights(), options.spots(), options.seed())
                    : Request.stress(options.lights(), options.stressMode(), options.seed());
            Bundle bundle = ClusteredDemoSceneFactory.create(request, shader, fallbacks);
            try {
                RenderPipeline pipeline = new RenderPipeline(window, bundle.scene, null,
                        settings, environment)
                        .clusteredLighting(clustered)
                        .clusteredDebug(options.debugMode())
                        .localShadows(LocalShadowPipelineSettings.balanced());
                if (town) {
                    if (options.fog()) {
                        pipeline.postProcessSettings(PostProcessSettings.builder()
                                .fog(FogSettings.builder()
                                        .color(0.03f, 0.045f, 0.10f)
                                        .distanceDensity(0.004f)
                                        .heightDensity(0.004f)
                                        .heightFalloff(0.12f)
                                        .baseHeight(-1.5f)
                                        .maximumOpacity(0.5f)
                                        .build())
                                .build());
                    }
                }
                try {
                    pipeline.build();
                    runFrames(window, driver, pipeline, bundle, options);
                } finally {
                    pipeline.close();
                }
            } finally {
                bundle.close();
            }
        }
    }

    private static void runFrames(GlfwWindow window, FrameDriver driver,
                                  RenderPipeline pipeline, Bundle bundle, Options options) {
        int frames = options.frames() > 0 ? options.frames() : Integer.MAX_VALUE;
        int resizeFrame = options.resizeFrame();
        List<Float> samples = new ArrayList<>();
        List<byte[]> captured = new ArrayList<>();
        long churnSequence = 0L;
        Random churnRandom = new Random(options.seed());
        Vector3f[] movingColors = new Vector3f[bundle.localLightCount];
        Vector3f[] movingOrigins = new Vector3f[bundle.localLightCount];
        for (int index = 0; index < bundle.localLightCount; index++) {
            SceneLight light = bundle.scene.lights().get(
                    index + directionalOffset(bundle));
            movingColors[index] = light.color();
            movingOrigins[index] = light.position();
        }
        Interaction interaction = new Interaction(options);
        float[] animatedBaseIntensity = new float[bundle.torchLightCount
                + bundle.skillLightCount];
        for (int index = 0; index < animatedBaseIntensity.length; index++) {
            int sceneIndex = index < bundle.torchLightCount
                    ? bundle.torchLightStart + index
                    : bundle.skillLightStart + (index - bundle.torchLightCount);
            animatedBaseIntensity[index] = bundle.scene.lights().get(sceneIndex).intensity();
        }
        for (int frame = 0; frame < frames; frame++) {
            if (frame == 1) {
                System.out.println("PASSES=" + pipeline.graph().description().executionOrder());
            }
            if (resizeFrame >= 0 && frame == resizeFrame) {
                window.resize(options.resizeWidth(), options.resizeHeight());
            }
            if (window.consumeResize() || pipeline.graph() != null
                    && (pipeline.graph().width() != window.width()
                    || pipeline.graph().height() != window.height())) {
                pipeline.resize(window.width(), window.height());
            }
            if (!options.hidden()) {
                interaction.handleKeys(window, pipeline, bundle, frame);
            }
            boolean lightsPaused = options.pauseLights() || interaction.pauseLights;
            if (!interaction.pauseCamera) {
                if (interaction.cameraPath == CameraPath.FREE) {
                    updateFreeCamera(window, bundle.scene.camera(), 1.0f / 60.0f);
                } else {
                    updateCamera(bundle.scene.camera(), interaction.cameraPath, frame);
                }
            }
            if (!lightsPaused) {
                if (options.kind() == Kind.TOWN) {
                    animateTownLights(bundle, animatedBaseIntensity, frame);
                }
                switch (options.stressMode()) {
                    case MOVING -> updateMovingLights(bundle, movingColors, movingOrigins, frame);
                    case CHURN -> churnLights(bundle, churnRandom, churnSequence++);
                    default -> { }
                }
            }
            driver.beginFrame();
            long start = System.nanoTime();
            pipeline.execute(driver.device(), 1.0f / 60.0f);
            long elapsed = System.nanoTime() - start;
            driver.recordGraph(pipeline.graph());
            driver.endFrame();
            if (!options.hidden()) {
                window.pollEvents();
                window.swapBuffers();
                interaction.updateTitle(window, bundle, options, elapsed);
            }
            if (frame >= options.warmup()) {
                samples.add(elapsed / 1_000_000.0f);
            }
            if (options.capture() != null && frame == options.captureFrame()) {
                captured.add(capture(window, options.capture()));
            }
            for (CaptureAt capture : options.captureAt()) {
                if (capture.frame() == frame) {
                    captured.add(capture(window, capture.path()));
                }
            }
            if (window.shouldClose()) {
                break;
            }
        }
        glFinish();
        Render3dDiagnostics diagnostics = pipeline.lastRender3dDiagnostics();
        if (options.benchmark()) {
            reportBenchmark(options, samples, diagnostics);
        }
        if (options.verify()) {
            verify(options, diagnostics);
            verifyCaptures(captured);
        }
        if (options.summary() != null) {
            writeSummary(options, diagnostics, options.summary());
        }
        System.out.println(summary(options, diagnostics));
    }

    private static void writeSummary(Options options, Render3dDiagnostics diagnostics,
                                     String path) {
        ClusteredLightingDiagnostics clustered = diagnostics.clustered();
        List<String> lines = List.of(
                "scene=" + options.kind().name().toLowerCase(Locale.ROOT),
                "width=" + options.width(),
                "height=" + options.height(),
                "localLights=" + clustered.localLights(),
                "clusterCount=" + clustered.clusterCount(),
                "overflowClusters=" + clustered.overflowClusters(),
                "maxInlineCount=" + clustered.maxInlineCount(),
                "gpuCountersAvailable=" + clustered.gpuCountersAvailable(),
                "gpuSampleFrameSequence=" + clustered.gpuSampleFrameSequence(),
                "shadowSelected=" + diagnostics.shadows().selectedLights().size());
        try {
            File file = new File(path);
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null) parent.mkdirs();
            java.nio.file.Files.write(file.toPath(), lines, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to write clustered summary to " + path,
                    failure);
        }
    }

    private static void verifyCaptures(List<byte[]> captures) {
        if (captures.size() < 2) return;
        for (int index = 1; index < captures.size(); index++) {
            int difference = pixelDifference(captures.get(index - 1), captures.get(index));
            require(difference > 500,
                    "declared quality captures " + (index - 1) + "/" + index
                            + " are nearly identical (" + difference + " changed bytes)");
        }
    }

    private static int pixelDifference(byte[] first, byte[] second) {
        require(first.length == second.length, "quality captures have different extents");
        int changed = 0;
        for (int index = 0; index < first.length; index++) {
            if (Math.abs((first[index] & 0xFF) - (second[index] & 0xFF)) > 4) changed++;
        }
        return changed;
    }

    private static int directionalOffset(Bundle bundle) {
        return bundle.scene.lights().size() - bundle.localLightCount;
    }

    private static void updateCamera(Camera camera, CameraPath path, int frame) {
        if (path == CameraPath.STATIC || path == CameraPath.FREE) return;
        // Approach the plaza through the south arch, ease in/out, then pull
        // back; x=3.5 clears the arch pillars and the tree ring.
        float loop = (frame % 480) / 480.0f;
        float phase = loop < 0.5f ? loop * 2.0f : (1.0f - loop) * 2.0f;
        float eased = phase * phase * (3.0f - 2.0f * phase);
        float z = 40.0f - 28.0f * eased;
        camera.setPosition(new Vector3f(3.5f, 4.6f, z));
        camera.setYaw(-90.0f + 5.0f * (float) Math.sin(frame * 0.01f));
        camera.setPitch(-4.0f);
    }

    private static void updateMovingLights(Bundle bundle, Vector3f[] colors,
                                           Vector3f[] origins, int frame) {
        Scene scene = bundle.scene;
        int offset = directionalOffset(bundle);
        for (int index = 0; index < bundle.localLightCount; index++) {
            float angle = frame * 0.03f + index * 0.37f;
            Vector3f origin = origins[index];
            Vector3f position = new Vector3f(
                    origin.x + (float) Math.cos(angle) * 6.0f,
                    origin.y,
                    origin.z + (float) Math.sin(angle * 0.5f) * 6.0f);
            scene.setLight(offset + index, SceneLight.point(position, colors[index],
                    5.0f, 8.0f));
        }
    }

    private static void churnLights(Bundle bundle, Random random, long sequence) {
        Scene scene = bundle.scene;
        int offset = directionalOffset(bundle);
        int churn = Math.min(16, bundle.localLightCount);
        if (churn == 0) return;
        for (int index = 0; index < churn; index++) {
            scene.removeLight(scene.lights().size() - 1);
        }
        for (int index = 0; index < churn; index++) {
            float hue = ((sequence * churn + index) * 0.618034f) % 1.0f;
            scene.addLight(SceneLight.point(
                    new Vector3f((random.nextFloat() - 0.5f) * 50.0f, 2.0f,
                            (random.nextFloat() - 0.5f) * 50.0f),
                    new Vector3f(0.4f + 0.6f * hue, 0.4f, 1.0f - 0.6f * hue),
                    5.0f, 7.0f));
        }
    }

    private static void verify(Options options, Render3dDiagnostics diagnostics) {
        require(diagnostics.available(), "diagnostics unavailable");
        ClusteredLightingDiagnostics clustered = diagnostics.clustered();
        require(clustered.available(), "clustered diagnostics unavailable");
        require(clustered.gpuCountersAvailable(),
                "GPU counters unavailable after drain; cannot verify overflow");
        require(clustered.localLights() == options.lights()
                        || options.kind() == Kind.TOWN && clustered.localLights() == 128,
                "unexpected local light count " + clustered.localLights());
        require(clustered.clusterCount() > 0, "empty cluster grid");
        if (options.kind() == Kind.STRESS && options.stressMode() == StressMode.OVERLAP) {
            require(clustered.overflowClusters() > 0,
                    "overlap scene must report overflow clusters");
        } else if (options.kind() == Kind.STRESS) {
            require(clustered.overflowClusters() == 0,
                    "sparse stress scene must not overflow: " + clustered.overflowClusters());
        }
        require(clustered.maxInlineCount() >= 0, "invalid max inline count");
        require(diagnostics.shadows().selectedLights().stream()
                        .allMatch(light -> light.frameLightIndex() >= 0),
                "selected shadow lights need frame light indices");
        if (options.kind() == Kind.TOWN) {
            require(diagnostics.shadows().selectedLights().stream()
                            .anyMatch(light -> !light.type().equals("DIRECTIONAL")),
                    "night town must select local shadow lights within budget");
        }
        require(glGetError() == GL_NO_ERROR, "OpenGL error after clustered frame");
    }

    private static void reportBenchmark(Options options, List<Float> samples,
                                        Render3dDiagnostics diagnostics) {
        float[] measured = new float[samples.size()];
        for (int index = 0; index < samples.size(); index++) {
            measured[index] = samples.get(index);
        }
        Arrays.sort(measured);
        System.out.printf(Locale.ROOT,
                "CLUSTERED BENCHMARK scene=%s lights=%d size=%dx%d frames=%d "
                        + "p50=%.3fms p95=%.3fms max=%.3fms overflow=%d%n",
                options.kind().name().toLowerCase(Locale.ROOT), options.lights(),
                options.width(), options.height(), measured.length,
                percentile(measured, 0.50), percentile(measured, 0.95),
                measured.length == 0 ? 0.0f : measured[measured.length - 1],
                diagnostics.clustered().overflowClusters());
    }

    private static float percentile(float[] sorted, double fraction) {
        if (sorted.length == 0) return 0.0f;
        int index = (int) Math.min(sorted.length - 1, Math.round(fraction * (sorted.length - 1)));
        return sorted[index];
    }

    private static String summary(Options options, Render3dDiagnostics diagnostics) {
        ClusteredLightingDiagnostics clustered = diagnostics.clustered();
        return String.format(Locale.ROOT,
                "CLUSTERED scene=%s lights=%d grid=%dx%dx%d clusters=%d K=%d "
                        + "overflowClusters=%d maxInline=%d shadowSelected=%d",
                options.kind().name().toLowerCase(Locale.ROOT), clustered.localLights(),
                options.tileSize(), options.tileSize(), options.zSlices(),
                clustered.clusterCount(),
                options.clusterCapacity(), clustered.overflowClusters(),
                clustered.maxInlineCount(),
                diagnostics.shadows().selectedLights().size());
    }

    private static byte[] capture(GlfwWindow window, String path) {
        int width = window.width();
        int height = window.height();
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        glViewport(0, 0, width, height);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        byte[] raw = new byte[pixels.remaining()];
        pixels.get(raw);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = ((height - 1 - y) * width + x) * 4;
                int r = raw[offset] & 0xFF;
                int g = raw[offset + 1] & 0xFF;
                int b = raw[offset + 2] & 0xFF;
                int a = raw[offset + 3] & 0xFF;
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        try {
            File file = new File(path);
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null) parent.mkdirs();
            ImageIO.write(image, "png", file);
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to capture frame to " + path, failure);
        }
        return raw;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("clustered verify failed: " + message);
    }

    private enum CameraPath {
        STATIC,
        STREET,
        FREE
    }

    private static void updateFreeCamera(GlfwWindow window, Camera camera, float deltaSeconds) {
        camera.processMouseMovement((float) window.mouseDeltaX(), (float) window.mouseDeltaY());
        if (window.isKeyDown(GLFW_KEY_W)) {
            camera.processKeyboard(Camera.Movement.FORWARD, deltaSeconds);
        }
        if (window.isKeyDown(GLFW_KEY_S)) {
            camera.processKeyboard(Camera.Movement.BACKWARD, deltaSeconds);
        }
        if (window.isKeyDown(GLFW_KEY_A)) {
            camera.processKeyboard(Camera.Movement.LEFT, deltaSeconds);
        }
        if (window.isKeyDown(GLFW_KEY_D)) {
            camera.processKeyboard(Camera.Movement.RIGHT, deltaSeconds);
        }
    }

    /**
     * Deterministic torch flicker and skill-light pulse for the night town.
     * Values are pure functions of the frame index so two runs stay identical.
     */
    private static void animateTownLights(Bundle bundle, float[] baseIntensity, int frame) {
        Scene scene = bundle.scene;
        for (int index = 0; index < baseIntensity.length; index++) {
            int sceneIndex = index < bundle.torchLightCount
                    ? bundle.torchLightStart + index
                    : bundle.skillLightStart + (index - bundle.torchLightCount);
            SceneLight light = scene.lights().get(sceneIndex);
            Vector3f color = new Vector3f(light.color());
            float intensity;
            if (index < bundle.torchLightCount) {
                float flicker = 0.82f + 0.18f * (float) Math.sin(
                        frame * 0.21f + index * 1.7f);
                intensity = baseIntensity[index] * flicker;
            } else {
                float pulse = 0.75f + 0.25f * (float) Math.sin(
                        frame * 0.045f + index * 0.9f);
                intensity = baseIntensity[index] * pulse;
                color.mul(0.75f + 0.25f * (float) Math.sin(frame * 0.03f + index));
            }
            scene.setLight(sceneIndex, withIntensity(light, color, intensity));
        }
    }

    private static SceneLight withIntensity(SceneLight light, Vector3f color, float intensity) {
        return switch (light.type()) {
            case DIRECTIONAL -> light.castShadows()
                    ? SceneLight.shadowedDirectional(light.direction(), color, intensity)
                    : SceneLight.directional(light.direction(), color, intensity);
            case POINT -> light.castShadows()
                    ? SceneLight.shadowedPoint(light.position(), color, intensity, light.range())
                    : SceneLight.point(light.position(), color, intensity, light.range());
            case SPOT -> light.castShadows()
                    ? SceneLight.shadowedSpot(light.position(), light.direction(), color,
                            intensity, light.range(), light.innerConeRadians(),
                            light.outerConeRadians())
                    : SceneLight.spot(light.position(), light.direction(), color,
                            intensity, light.range(), light.innerConeRadians(),
                            light.outerConeRadians());
        };
    }

    /** Visible-window key bindings, FPS and title HUD. */
    private static final class Interaction {
        private static final ClusterDebugMode[] DEBUG_MODES = {
                ClusterDebugMode.OFF, ClusterDebugMode.CLUSTER_ID, ClusterDebugMode.SLICE,
                ClusterDebugMode.LIGHT_COUNT, ClusterDebugMode.OVERFLOW,
                ClusterDebugMode.SHADOW_SLOT
        };

        private final boolean[] keyDown = new boolean[512];
        private final boolean[] keyDownPrevious = new boolean[512];
        private CameraPath cameraPath;
        private ClusterDebugMode debugMode;
        private boolean pauseLights;
        private boolean pauseCamera;
        private float framesPerSecond;
        private int titleFrame = -100;

        private Interaction(Options options) {
            this.cameraPath = options.cameraPath();
            this.debugMode = options.debugMode();
            this.pauseLights = options.pauseLights();
            this.pauseCamera = options.pauseCamera();
        }

        private void handleKeys(GlfwWindow window, RenderPipeline pipeline, Bundle bundle,
                                int frame) {
            System.arraycopy(keyDown, 0, keyDownPrevious, 0, keyDown.length);
            keyDown[GLFW_KEY_1] = window.isKeyDown(GLFW_KEY_1);
            keyDown[GLFW_KEY_2] = window.isKeyDown(GLFW_KEY_2);
            keyDown[GLFW_KEY_3] = window.isKeyDown(GLFW_KEY_3);
            keyDown[GLFW_KEY_4] = window.isKeyDown(GLFW_KEY_4);
            keyDown[GLFW_KEY_5] = window.isKeyDown(GLFW_KEY_5);
            keyDown[GLFW_KEY_6] = window.isKeyDown(GLFW_KEY_6);
            keyDown[GLFW_KEY_L] = window.isKeyDown(GLFW_KEY_L);
            keyDown[GLFW_KEY_O] = window.isKeyDown(GLFW_KEY_O);
            keyDown[GLFW_KEY_F] = window.isKeyDown(GLFW_KEY_F);
            keyDown[GLFW_KEY_C] = window.isKeyDown(GLFW_KEY_C);
            keyDown[GLFW_KEY_H] = window.isKeyDown(GLFW_KEY_H);
            keyDown[GLFW_KEY_ESCAPE] = window.isKeyDown(GLFW_KEY_ESCAPE);
            for (int mode = 0; mode < DEBUG_MODES.length; mode++) {
                int key = GLFW_KEY_1 + mode;
                if (pressed(key)) {
                    debugMode = DEBUG_MODES[mode];
                    pipeline.clusteredDebug(debugMode);
                }
            }
            if (pressed(GLFW_KEY_L)) pauseLights = !pauseLights;
            if (pressed(GLFW_KEY_O)) pauseCamera = !pauseCamera;
            if (pressed(GLFW_KEY_F)) {
                cameraPath = switch (cameraPath) {
                    case STATIC -> CameraPath.STREET;
                    case STREET -> CameraPath.FREE;
                    case FREE -> CameraPath.STATIC;
                };
            }
            if (pressed(GLFW_KEY_C)) {
                capture(window, "build/reports/clustered-capture/manual-" + frame + ".png");
            }
            if (pressed(GLFW_KEY_H)) {
                System.out.println("Controls: 1-6 debug mode | L lights | O camera | F path | "
                        + "C capture | H help | ESC quit | WASD+mouse in FREE path");
            }
            if (pressed(GLFW_KEY_ESCAPE)) {
                window.requestClose();
            }
        }

        private boolean pressed(int key) {
            return keyDown[key] && !keyDownPrevious[key];
        }

        private void updateTitle(GlfwWindow window, Bundle bundle, Options options,
                                 long elapsedNanos) {
            float instant = 1_000_000_000.0f / Math.max(1L, elapsedNanos);
            framesPerSecond = framesPerSecond == 0.0f
                    ? instant : framesPerSecond * 0.92f + instant * 0.08f;
            titleFrame++;
            if (titleFrame % 10 != 0) {
                return;
            }
            window.setTitle(String.format(Locale.ROOT,
                    "Haikalat Clustered v0.24.2 | %s | %d lights | %.0f fps | %s%s%s | %s",
                    options.kind().name().toLowerCase(Locale.ROOT), bundle.localLightCount,
                    framesPerSecond, debugMode.name().toLowerCase(Locale.ROOT),
                    pauseLights ? " | lights-paused" : "",
                    pauseCamera ? " | cam-paused" : " | " + cameraPath.name().toLowerCase(Locale.ROOT),
                    options.antiAliasing().name()));
        }
    }

    private record CaptureAt(int frame, String path) {
    }

    private record Options(Kind kind, int lights, int spots, long seed, int width, int height,
                           AntiAliasingMode antiAliasing, int tileSize, int zSlices,
                           int clusterCapacity, CameraPath cameraPath, int frames, int warmup,
                           ClusterDebugMode debugMode, boolean pauseLights, boolean pauseCamera,
                           boolean hidden, boolean verify, boolean benchmark, String capture,
                           int captureFrame, int resizeFrame, int resizeWidth, int resizeHeight,
                           StressMode stressMode, List<CaptureAt> captureAt, String summary,
                           boolean bloom, boolean fog) {

        static Options parse(String[] arguments) {
            Kind kind = Kind.TOWN;
            int lights = 128;
            int spots = 24;
            long seed = 242L;
            int width = 1280;
            int height = 720;
            AntiAliasingMode antiAliasing = AntiAliasingMode.NONE;
            int tileSize = ClusteredLightingSettings.DEFAULT_TILE_SIZE;
            int zSlices = ClusteredLightingSettings.DEFAULT_Z_SLICES;
            int clusterCapacity = ClusteredLightingSettings.DEFAULT_INLINE_INDICES;
            CameraPath cameraPath = CameraPath.STATIC;
            int frames = -1;
            int warmup = 0;
            ClusterDebugMode debugMode = ClusterDebugMode.OFF;
            boolean pauseLights = false;
            boolean pauseCamera = false;
            boolean hidden = false;
            boolean verify = false;
            boolean benchmark = false;
            String capture = null;
            int captureFrame = -1;
            List<CaptureAt> captureAt = new ArrayList<>();
            String summary = null;
            boolean bloom = true;
            boolean fog = true;
            int resizeFrame = -1;
            int resizeWidth = 0;
            int resizeHeight = 0;
            StressMode stressMode = StressMode.SPARSE;
            for (String argument : arguments) {
                String value = argument;
                int equals = argument.indexOf('=');
                String key = equals < 0 ? argument : argument.substring(0, equals);
                if (equals >= 0) value = argument.substring(equals + 1);
                switch (key) {
                    case "--scene" -> kind = switch (value.toLowerCase(Locale.ROOT)) {
                        case "lab" -> Kind.LAB;
                        case "town" -> Kind.TOWN;
                        case "stress" -> Kind.STRESS;
                        default -> throw new IllegalArgumentException("Unknown scene: " + value);
                    };
                    case "--lights" -> lights = Integer.parseInt(value);
                    case "--spots" -> spots = Integer.parseInt(value);
                    case "--seed" -> seed = Long.parseLong(value);
                    case "--size" -> {
                        String[] parts = value.split("x");
                        width = Integer.parseInt(parts[0]);
                        height = Integer.parseInt(parts[1]);
                    }
                    case "--aa" -> antiAliasing = AntiAliasingMode.valueOf(
                            value.toUpperCase(Locale.ROOT));
                    case "--tile-size" -> tileSize = Integer.parseInt(value);
                    case "--z-slices" -> zSlices = Integer.parseInt(value);
                    case "--cluster-capacity" -> clusterCapacity = Integer.parseInt(value);
                    case "--camera-path" -> cameraPath = CameraPath.valueOf(
                            value.toUpperCase(Locale.ROOT));
                    case "--frames" -> frames = Integer.parseInt(value);
                    case "--warmup" -> warmup = Integer.parseInt(value);
                    case "--debug" -> debugMode = switch (value.toLowerCase(Locale.ROOT)) {
                        case "off" -> ClusterDebugMode.OFF;
                        case "cluster-id" -> ClusterDebugMode.CLUSTER_ID;
                        case "slice" -> ClusterDebugMode.SLICE;
                        case "light-count" -> ClusterDebugMode.LIGHT_COUNT;
                        case "overflow" -> ClusterDebugMode.OVERFLOW;
                        case "shadow-slot" -> ClusterDebugMode.SHADOW_SLOT;
                        default -> throw new IllegalArgumentException("Unknown debug mode: " + value);
                    };
                    case "--mode" -> stressMode = StressMode.valueOf(value.toUpperCase(Locale.ROOT));
                    case "--pause-lights" -> pauseLights = true;
                    case "--pause-camera" -> pauseCamera = true;
                    case "--hidden" -> hidden = true;
                    case "--verify" -> verify = true;
                    case "--benchmark" -> benchmark = true;
                    case "--capture" -> capture = value;
                    case "--capture-frame" -> captureFrame = Integer.parseInt(value);
                    case "--capture-at" -> {
                        String[] parts = value.split(":", 2);
                        captureAt.add(new CaptureAt(Integer.parseInt(parts[0]), parts[1]));
                    }
                    case "--summary" -> summary = value;
                    case "--bloom" -> bloom = Boolean.parseBoolean(value);
                    case "--fog" -> fog = Boolean.parseBoolean(value);
                    case "--resize" -> {
                        String[] parts = value.split(":");
                        resizeFrame = Integer.parseInt(parts[0]);
                        String[] extent = parts[1].split("x");
                        resizeWidth = Integer.parseInt(extent[0]);
                        resizeHeight = Integer.parseInt(extent[1]);
                    }
                    default -> throw new IllegalArgumentException("Unknown option: " + argument);
                }
            }
            if (hideable(kind) && lights <= 0) {
                throw new IllegalArgumentException("--lights must be positive");
            }
            if (hidden && frames < 0) {
                frames = DEFAULT_FRAMES;
            }
            return new Options(kind, kind == Kind.TOWN ? 128 : lights, spots, seed, width, height,
                    antiAliasing, tileSize, zSlices, clusterCapacity, cameraPath, frames, warmup,
                    debugMode, pauseLights, pauseCamera, hidden, verify, benchmark, capture,
                    captureFrame, resizeFrame, resizeWidth, resizeHeight, stressMode,
                    List.copyOf(captureAt), summary, bloom, fog);
        }

        private static boolean hideable(Kind kind) {
            return kind != Kind.TOWN;
        }
    }
}
