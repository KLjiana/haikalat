package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.ObjModelLoader;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.FrameClock;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.FrameBenchmarkSession;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/** v0.12 metallic-roughness、GPU IBL、legacy 共存与后处理的专用验收场景。 */
public final class PbrDemo {
    private static volatile BenchmarkResult lastBenchmarkResult;
    private PbrDemo() {
    }

    public static void main(String[] args) {
        lastBenchmarkResult = null;
        Options options = Options.parse(args);
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(options.width(), options.height())
                .title("Haikalat PBR Demo").visible(!options.hidden())
                .cursorMode(GlfwWindow.CursorMode.NORMAL).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(!options.hidden());
            if (options.verifyFailure()) {
                verifyFailureCleanup(options);
                return;
            }
            run(window, options);
        }
    }

    private static void run(GlfwWindow window, Options options) {
        RenderSettings settings = RenderSettings.builder()
                .vsync(!options.hidden())
                .antiAliasingMode(options.aa())
                .toneMappingMode(ToneMappingMode.ACES)
                .exposureMode(options.autoExposure() ? ExposureMode.AUTO : ExposureMode.MANUAL)
                .bloomSettings(BloomSettings.builder().enabled(options.bloom()).build())
                .build();
        List<Material> materials = new ArrayList<>();
        List<Mesh> meshes = new ArrayList<>();
        List<Texture2D> textures = new ArrayList<>();
        Throwable primaryFailure = null;
        try (FrameDriver driver = new FrameDriver(settings);
             PbrEnvironment environment = PbrEnvironmentLoader.load(driver.device(), PbrDemo.class,
                     "/pbr/studio-small.hdr", options.environmentSettings());
             PbrFallbackTextures fallbacks = new PbrFallbackTextures();
             ShaderProgram pbrShader = ShaderProgram.fromResource(PbrDemo.class,
                     "/render3d/pbr/pbr_forward.vert",
                     "/render3d/pbr/pbr_forward.frag");
             ShaderProgram legacyShader = ShaderProgram.fromResource(PbrDemo.class,
                     "/demo/color_scene.vert", "/demo/lit_scene.frag")) {
            Mesh sphere = Mesh.from(PbrSphereMesh.create(32, 20));
            meshes.add(sphere);
            Mesh pyramid = Mesh.from(new ObjModelLoader(ResourceLocator.classpath(PbrDemo.class))
                    .load(AssetRef.of("/demo/models/baseline_pyramid.obj"), ObjModelLoader.Options.PBR)
                    .firstMesh());
            meshes.add(pyramid);
            Mesh legacyTriangle = Mesh.from(BuiltinMeshData.coloredTriangle("pbr-legacy-proof"));
            meshes.add(legacyTriangle);

            Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 15.0f)));
            for (int row = 0; row < 5; row++) {
                for (int column = 0; column < 5; column++) {
                    float metallic = column / 4.0f;
                    float roughness = 0.05f + row / 4.0f * 0.95f;
                    Material material = PbrMaterials.create(pbrShader,
                            properties(new Vector4f(0.74f, 0.28f, 0.08f, 1.0f), metallic, roughness),
                            java.util.Map.of(), fallbacks);
                    materials.add(material);
                    float x = (column - 2) * 2.0f;
                    float y = (2 - row) * 2.0f;
                    scene.add(new SceneObject(sphere, material,
                            (out, frame) -> out.identity().translation(x, y, 0.0f).scale(0.82f)));
                }
            }

            EnumMap<PbrTextureRole, Texture2D> supplied = loadTextureSet(textures);
            Material textured = PbrMaterials.create(pbrShader,
                    properties(new Vector4f(1.0f), 0.65f, 0.35f), supplied, fallbacks);
            materials.add(textured);
            scene.add(new SceneObject(pyramid, textured,
                    (out, frame) -> out.identity().translation(6.1f, 0.0f, 0.0f)
                            .rotateY(frame * 0.006f).scale(1.25f)));

            Material legacy = Material.builder(legacyShader).setInt("uUseTexture", 0).build();
            materials.add(legacy);
            scene.add(new SceneObject(legacyTriangle, legacy,
                    (out, frame) -> out.identity().translation(-6.1f, 0.0f, 0.0f).scale(1.4f), false));
            scene.addLight(SceneLight.shadowedDirectional(new Vector3f(-0.4f, -1.0f, -0.5f),
                    new Vector3f(1.0f, 0.92f, 0.82f), 2.5f));
            scene.addLight(SceneLight.point(new Vector3f(4.0f, 4.0f, 5.0f),
                    new Vector3f(1.0f, 0.15f, 0.08f), 15.0f, 13.0f));
            scene.addLight(SceneLight.point(new Vector3f(-4.0f, -2.0f, 4.0f),
                    new Vector3f(0.08f, 0.3f, 1.0f), 12.0f, 12.0f));

            RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings, environment);
            try {
                pipeline.build();
                try (PbrDemoOverlay overlay = PbrDemoOverlay.attach(window, pipeline, settings,
                        scene.renderers().get(25).material(), environment)) {
                    renderLoop(window, driver, pipeline, overlay, options);
                }
            } finally {
                pipeline.close();
            }
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            RuntimeException cleanupFailure = null;
            cleanupFailure = closeReverse(materials, cleanupFailure);
            cleanupFailure = closeReverse(meshes, cleanupFailure);
            cleanupFailure = closeReverse(textures, cleanupFailure);
            if (cleanupFailure != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    private static void renderLoop(GlfwWindow window, FrameDriver driver,
                                   RenderPipeline pipeline, PbrDemoOverlay overlay, Options options) {
        FrameClock clock = new FrameClock();
        FrameBenchmarkSession benchmark = new FrameBenchmarkSession(options.frames(), options.warmup());
        int frame = 0;
        while (!window.shouldClose()) {
            if (options.resizeFrame() >= 0 && frame == options.resizeFrame()) {
                window.resize(960, 540);
            }
            float delta = clock.tick().deltaSeconds();
            overlay.update(window.inputSnapshot(), delta);
            if (window.consumeResize()) pipeline.resize(window.width(), window.height());
            driver.beginFrame();
            pipeline.execute(driver.device(), delta);
            driver.recordGraph(pipeline.graph());
            driver.endFrame();
            driver.present(window::swapBuffers);
            window.pollEvents();
            benchmark.recordFrame(driver, pipeline.graph().lastFrameProfile().totalGpuNanos());
            GlDebug.checkError("PbrDemo.frame");
            frame++;
            if (benchmark.isComplete()) window.requestClose();
        }
        var result = benchmark.snapshot();
        lastBenchmarkResult = new BenchmarkResult(result.presentFps(), result.timings());
        System.out.printf("PBR demo: measured=%d warmup=%d fps=%.1f cpu avg/median=%.3f/%.3fms "
                        + "gpu avg/median=%.3f/%.3fms environment=%s size=%dx%d%n",
                result.measuredFrames(), options.warmup(), result.presentFps(),
                result.timings().averageCpuMillis(), result.timings().medianCpuMillis(),
                result.timings().averageGpuMillis(), result.timings().medianGpuMillis(),
                options.quality(), options.width(), options.height());
    }

    public static BenchmarkResult lastBenchmarkResult() {
        return lastBenchmarkResult;
    }

    public record BenchmarkResult(double presentFps,
                                  com.kaleblangley.haikalat.runtime.FrameTimingAccumulator.Summary timings) {
    }

    private static PbrMaterialProperties properties(Vector4f color, float metallic, float roughness) {
        return new PbrMaterialProperties(color, metallic, roughness, 1.0f, 1.0f,
                new Vector3f(0.02f), java.util.Map.of());
    }

    private static EnumMap<PbrTextureRole, Texture2D> loadTextureSet(List<Texture2D> owner) {
        EnumMap<PbrTextureRole, Texture2D> result = new EnumMap<>(PbrTextureRole.class);
        result.put(PbrTextureRole.BASE_COLOR, load(owner, "/wall.png", TextureColorSpace.SRGB));
        result.put(PbrTextureRole.NORMAL, load(owner, "/wall.png", TextureColorSpace.LINEAR));
        result.put(PbrTextureRole.METALLIC_ROUGHNESS,
                load(owner, "/awesomeface.png", TextureColorSpace.LINEAR));
        result.put(PbrTextureRole.OCCLUSION, load(owner, "/wall.png", TextureColorSpace.LINEAR));
        result.put(PbrTextureRole.EMISSIVE, load(owner, "/awesomeface.png", TextureColorSpace.SRGB));
        return result;
    }

    private static Texture2D load(List<Texture2D> owner, String path, TextureColorSpace colorSpace) {
        Texture2D texture = Texture2D.fromResource(PbrDemo.class, path, true, colorSpace);
        owner.add(texture);
        return texture;
    }

    private static void verifyFailureCleanup(Options options) {
        GlRenderDevice device = new GlRenderDevice();
        String property = "haikalat.pbr.testFailurePoint";
        for (String point : List.of("AFTER_ENVIRONMENT_CUBE", "BEFORE_PREFILTER_MIP",
                "AFTER_BRDF_LUT")) {
            System.setProperty(property, point);
            try {
                try (PbrEnvironment ignored = PbrEnvironmentLoader.load(device, PbrDemo.class,
                        "/pbr/studio-small.hdr", options.environmentSettings())) {
                    throw new AssertionError("expected environment preprocessing failure at " + point);
                }
            } catch (RuntimeException expected) {
                if (!expected.getMessage().contains(point)) throw expected;
                System.out.println("PBR failure cleanup verified: " + point);
            } finally {
                System.clearProperty(property);
            }
        }
        GlDebug.checkError("PBR preprocessing failure cleanup");
    }

    private static RuntimeException closeReverse(List<? extends AutoCloseable> resources,
                                                 RuntimeException failure) {
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (Exception closeFailure) {
                RuntimeException wrapped = closeFailure instanceof RuntimeException runtime ? runtime
                        : new IllegalStateException("Failed to close PBR Demo resource", closeFailure);
                if (failure == null) failure = wrapped;
                else failure.addSuppressed(wrapped);
            }
        }
        return failure;
    }

    private record Options(boolean hidden, int frames, String quality,
                           boolean autoExposure, boolean bloom, AntiAliasingMode aa,
                           int resizeFrame, boolean verifyFailure, int warmup,
                           int width, int height) {
        static Options parse(String[] args) {
            boolean hidden = false;
            int frames = -1;
            String quality = "default";
            boolean autoExposure = true;
            boolean bloom = true;
            AntiAliasingMode aa = AntiAliasingMode.FXAA;
            int resizeFrame = -1;
            boolean verifyFailure = false;
            int warmup = 0;
            int width = 1280;
            int height = 720;
            for (String arg : args) {
                if (arg.equals("--hidden") || arg.equals("--deterministic")) hidden = true;
                else if (arg.startsWith("--frames=")) frames = positive(arg, "--frames=");
                else if (arg.startsWith("--environment-quality=")) quality = arg.substring(22);
                else if (arg.equals("--auto-exposure=on")) autoExposure = true;
                else if (arg.equals("--auto-exposure=off")) autoExposure = false;
                else if (arg.equals("--bloom=on")) bloom = true;
                else if (arg.equals("--bloom=off")) bloom = false;
                else if (arg.startsWith("--aa=")) aa = parseAa(arg.substring(5));
                else if (arg.startsWith("--resize-frame=")) resizeFrame = positive(arg, "--resize-frame=");
                else if (arg.equals("--verify-failure-cleanup")) verifyFailure = true;
                else if (arg.startsWith("--warmup=")) warmup = nonNegative(arg, "--warmup=");
                else if (arg.startsWith("--size=")) {
                    String[] parts = arg.substring(7).toLowerCase(java.util.Locale.ROOT).split("x");
                    if (parts.length != 2) throw new IllegalArgumentException("--size must be WIDTHxHEIGHT");
                    width = Integer.parseInt(parts[0]);
                    height = Integer.parseInt(parts[1]);
                    if (width <= 0 || height <= 0) throw new IllegalArgumentException("--size must be positive");
                }
                else throw new IllegalArgumentException("Unknown PbrDemo option: " + arg);
            }
            if (hidden && frames < 0 && !verifyFailure) frames = 8;
            PbrEnvironmentSettings.quality(quality);
            return new Options(hidden, frames, quality, autoExposure, bloom, aa, resizeFrame,
                    verifyFailure, warmup, width, height);
        }

        PbrEnvironmentSettings environmentSettings() { return PbrEnvironmentSettings.quality(quality); }

        private static int positive(String arg, String prefix) {
            int value = Integer.parseInt(arg.substring(prefix.length()));
            if (value <= 0) throw new IllegalArgumentException(prefix + " requires a positive integer");
            return value;
        }

        private static int nonNegative(String arg, String prefix) {
            int value = Integer.parseInt(arg.substring(prefix.length()));
            if (value < 0) throw new IllegalArgumentException(prefix + " requires a non-negative integer");
            return value;
        }

        private static AntiAliasingMode parseAa(String value) {
            return switch (value.toLowerCase(java.util.Locale.ROOT)) {
                case "none" -> AntiAliasingMode.NONE;
                case "fxaa" -> AntiAliasingMode.FXAA;
                case "msaa" -> AntiAliasingMode.MSAA;
                // 当前管线以 MSAA resolve 作为组合路径；保留路线图 CLI 别名。
                case "msaa-fxaa" -> AntiAliasingMode.MSAA;
                case "taa" -> AntiAliasingMode.TAA;
                default -> throw new IllegalArgumentException("unsupported PBR AA mode: " + value);
            };
        }
    }
}
