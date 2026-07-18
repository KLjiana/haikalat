package com.kaleblangley.haikalat.demo.gltf;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.demo.DemoSupport;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.DebugOverlaySnapshot;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.FrameClock;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.PeriodicTimer;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;

import java.time.Duration;
import java.util.Locale;

/**
 * 只加载并绘制 {@code gltf模型} 的独立静态资产 Demo。
 *
 * <p>该入口自行拥有窗口、runtime、PBR environment、场景、pipeline 和 retained inspector，
 * 不依赖主综合 Demo 的 manifest、对象或启动循环。</p>
 */
public final class GltfDemo {
    private GltfDemo() {
    }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(options.width(), options.height())
                .title("Haikalat glTF Demo")
                .visible(!options.hidden())
                .cursorMode(GlfwWindow.CursorMode.DISABLED)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(!options.hidden());
            run(window, options);
        }
    }

    private static void run(GlfwWindow window, Options options) {
        RenderSettings settings = RenderSettings.builder()
                .vsync(!options.hidden())
                .antiAliasingMode(options.antiAliasingMode())
                .toneMappingMode(ToneMappingMode.ACES)
                .exposureMode(options.autoExposure() ? ExposureMode.AUTO : ExposureMode.MANUAL)
                .bloomSettings(BloomSettings.builder().enabled(options.bloom()).build())
                .build();
        try (FrameDriver driver = new FrameDriver(settings);
             PbrEnvironment environment = PbrEnvironmentLoader.load(driver.device(), GltfDemo.class,
                     "/pbr/studio-small.hdr", PbrEnvironmentSettings.quality(options.environmentQuality()));
             GltfDemoAssets assets = GltfDemoAssets.load()) {
            Camera camera = new Camera(new Vector3f(0.0f, 0.8f, 8.0f));
            Scene scene = new Scene(camera);
            assets.objects().forEach(scene::add);
            scene.addLight(SceneLight.directional(new Vector3f(-0.35f, -1.0f, -0.55f),
                    new Vector3f(1.0f, 0.94f, 0.84f), 3.0f));
            scene.addLight(SceneLight.point(new Vector3f(2.8f, 2.8f, 4.0f),
                    new Vector3f(0.35f, 0.55f, 1.0f), 18.0f, 12.0f));

            RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings, environment);
            try {
                pipeline.build();
                try (GltfDemoOverlay overlay = GltfDemoOverlay.attach(
                        window, pipeline, assets.inspectionLines())) {
                    renderLoop(window, driver, pipeline, overlay, camera, scene, settings, options);
                }
            } finally {
                pipeline.close();
            }
        }
    }

    private static void renderLoop(GlfwWindow window, FrameDriver driver, RenderPipeline pipeline,
                                   GltfDemoOverlay overlay, Camera camera, Scene scene,
                                   RenderSettings settings, Options options) {
        FrameClock clock = new FrameClock();
        PeriodicTimer titleUpdate = new PeriodicTimer(Duration.ofMillis(250));
        int frame = 0;
        while (!window.shouldClose()) {
            if (options.resize() != null && frame == options.resize().frame()) {
                window.resize(options.resize().width(), options.resize().height());
            }
            float deltaSeconds = clock.tick().deltaSeconds();
            WindowInputSnapshot input = window.inputSnapshot();
            DebugOverlaySnapshot previous = DebugOverlaySnapshot.from(driver.statistics(),
                    scene.renderers().size(), scene.renderers().size(), settings.antiAliasingMode());
            overlay.update(input, deltaSeconds, previous);
            if (overlay.consumeCameraInputPermission()) {
                DemoSupport.updateFreeCamera(window, camera, deltaSeconds);
            }
            if (window.consumeResize()) pipeline.resize(window.width(), window.height());

            driver.beginFrame();
            pipeline.execute(driver.device(), deltaSeconds);
            driver.statistics().recordGraphProfile(pipeline.graph().lastFrameProfile());
            driver.endFrame();
            driver.present(window::swapBuffers);
            window.pollEvents();

            if (titleUpdate.poll()) {
                DebugOverlaySnapshot stats = DebugOverlaySnapshot.from(driver.statistics(),
                        scene.renderers().size(), scene.renderers().size(), settings.antiAliasingMode());
                window.setTitle(String.format(Locale.ROOT,
                        "Haikalat glTF | showcase + radio + creeper | FPS %.1f | CPU %.3f ms | GPU %.3f ms | draw %d",
                        stats.fps(), stats.cpuSubmitMillis(), stats.gpuMillis(), stats.drawCalls()));
            }
            GlDebug.checkError("GltfDemo.frame");
            frame++;
            if (options.frames() > 0 && frame >= options.frames()) window.requestClose();
        }
        System.out.printf(Locale.ROOT,
                "glTF demo: frames=%d objects=%d fps=%.1f CPU=%.3fms GPU=%.3fms size=%dx%d%n",
                frame, scene.renderers().size(), driver.statistics().averageFps(),
                driver.statistics().lastCpuSubmitMillis(),
                driver.statistics().lastFrameProfile().totalGpuMillis(),
                window.width(), window.height());
    }

    private record Options(boolean hidden, int frames, int width, int height,
                           AntiAliasingMode antiAliasingMode, boolean bloom,
                           boolean autoExposure, String environmentQuality, Resize resize) {
        static Options parse(String[] args) {
            boolean hidden = false;
            int frames = -1;
            int width = 1280;
            int height = 720;
            AntiAliasingMode aa = AntiAliasingMode.FXAA;
            boolean bloom = true;
            boolean autoExposure = true;
            String quality = "default";
            Resize resize = null;
            for (String arg : args) {
                if (arg.equals("--hidden") || arg.equals("--deterministic")) hidden = true;
                else if (arg.startsWith("--frames=")) frames = positive(arg, "--frames=");
                else if (arg.startsWith("--aa=")) {
                    aa = AntiAliasingMode.valueOf(arg.substring(5).toUpperCase(Locale.ROOT));
                } else if (arg.equals("--bloom") || arg.equals("--bloom=on")) bloom = true;
                else if (arg.equals("--bloom=off")) bloom = false;
                else if (arg.equals("--auto-exposure") || arg.equals("--auto-exposure=on")) {
                    autoExposure = true;
                } else if (arg.equals("--auto-exposure=off")) autoExposure = false;
                else if (arg.startsWith("--environment-quality=")) quality = arg.substring(22);
                else if (arg.startsWith("--size=")) {
                    int[] size = size(arg.substring(7), "--size");
                    width = size[0];
                    height = size[1];
                } else if (arg.startsWith("--resize=")) {
                    resize = Resize.parse(arg.substring(9));
                    hidden = true;
                } else {
                    throw new IllegalArgumentException("Unknown GltfDemo option: " + arg);
                }
            }
            if (hidden && frames < 0) frames = 8;
            PbrEnvironmentSettings.quality(quality);
            if (resize != null && frames <= resize.frame() + 1) {
                throw new IllegalArgumentException("--frames must include one frame after --resize");
            }
            return new Options(hidden, frames, width, height, aa, bloom, autoExposure, quality, resize);
        }

        private static int positive(String argument, String prefix) {
            int value = Integer.parseInt(argument.substring(prefix.length()));
            if (value <= 0) throw new IllegalArgumentException(prefix + " must be positive");
            return value;
        }

        private static int[] size(String value, String option) {
            String[] parts = value.toLowerCase(Locale.ROOT).split("x", -1);
            if (parts.length != 2) throw new IllegalArgumentException(option + " must be WIDTHxHEIGHT");
            int width = Integer.parseInt(parts[0]);
            int height = Integer.parseInt(parts[1]);
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException(option + " dimensions must be positive");
            }
            return new int[]{width, height};
        }
    }

    private record Resize(int frame, int width, int height) {
        static Resize parse(String value) {
            String[] parts = value.split(":", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("--resize must be FRAME:WIDTHxHEIGHT");
            }
            int frame = Integer.parseInt(parts[0]);
            int[] dimensions = Options.size(parts[1], "--resize");
            if (frame < 0) throw new IllegalArgumentException("--resize frame must be non-negative");
            return new Resize(frame, dimensions[0], dimensions[1]);
        }
    }
}
