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
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Locale;

import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL43.GL_DEBUG_SOURCE_API;
import static org.lwjgl.opengl.GL43.GL_DEBUG_TYPE_PERFORMANCE;
import static org.lwjgl.opengl.GL43.GL_DONT_CARE;
import static org.lwjgl.opengl.GL43.glDebugMessageControl;

/**
 * 加载并绘制静态与骨骼动画 glTF 的独立资产 Demo。
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
                .title(options.asset().windowTitle())
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
                     "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.quality(options.environmentQuality()));
            GltfDemoAssets assets = GltfDemoAssets.load(options.asset())) {
            Camera camera = new Camera(new Vector3f(0.0f,
                    options.asset() == Asset.CROUCH_WALK ? 0.2f : 0.8f,
                    options.asset() == Asset.DEFAULT ? 8.0f : 7.0f));
            Scene scene = new Scene(camera);
            assets.objects().forEach(scene::add);
            scene.addLight(SceneLight.shadowedDirectional(new Vector3f(-0.35f, -1.0f, -0.55f),
                    new Vector3f(1.0f, 0.94f, 0.84f), 3.0f));
            scene.addLight(SceneLight.point(new Vector3f(2.8f, 2.8f, 4.0f),
                    new Vector3f(0.35f, 0.55f, 1.0f), 18.0f, 12.0f));

            RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings, environment);
            try {
                pipeline.build();
                try (GltfDemoOverlay overlay = GltfDemoOverlay.attach(
                         window, pipeline, assets.inspectionLines(),
                         !options.hidden() && options.asset() == Asset.DEFAULT,
                         assets::animationDebugText)) {
                    renderLoop(window, driver, pipeline, overlay, camera, scene, settings, options,
                            assets);
                }
            } finally {
                pipeline.close();
            }
        }
    }

    private static void renderLoop(GlfwWindow window, FrameDriver driver, RenderPipeline pipeline,
                                   GltfDemoOverlay overlay, Camera camera, Scene scene,
                                   RenderSettings settings, Options options,
                                   GltfDemoAssets assets) {
        FrameClock clock = new FrameClock();
        PeriodicTimer titleUpdate = new PeriodicTimer(Duration.ofMillis(250));
        int frame = 0;
        int pixelDelta = -1;
        while (!window.shouldClose()) {
            if (options.resize() != null && frame == options.resize().frame()) {
                window.resize(options.resize().width(), options.resize().height());
            }
            float deltaSeconds = clock.tick().deltaSeconds();
            assets.update(options.hidden() ? 1.0f / 60.0f : deltaSeconds);
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
            if (options.hidden() && options.asset() == Asset.CROUCH_WALK
                    && options.frames() > 0 && frame + 1 >= options.frames()) {
                pixelDelta = verifyCrouchWalkPixels(window, pipeline, scene.renderers().size());
            }
            driver.recordGraph(pipeline.graph());
            driver.endFrame();
            driver.present(window::swapBuffers);
            window.pollEvents();

            if (titleUpdate.poll()) {
                DebugOverlaySnapshot stats = DebugOverlaySnapshot.from(driver.statistics(),
                        scene.renderers().size(), scene.renderers().size(), settings.antiAliasingMode());
                window.setTitle(String.format(Locale.ROOT,
                        "Haikalat glTF | %s | FPS %.1f | CPU %.3f ms | GPU %.3f ms | draw %d",
                        options.asset().cliName(), stats.fps(), stats.cpuSubmitMillis(),
                        stats.gpuMillis(), stats.drawCalls()));
            }
            GlDebug.checkError("GltfDemo.frame");
            frame++;
            if (options.frames() > 0 && frame >= options.frames()) window.requestClose();
        }
        if (options.hidden() && assets.animationMotion() <= 0.01f) {
            throw new IllegalStateException(
                    "glTF animation integration did not move the probed node");
        }
        RenderPipeline.VisibilityStatistics visibility = pipeline.lastVisibilityStatistics();
        System.out.printf(Locale.ROOT,
                "glTF demo: asset=%s frames=%d objects=%d animationMotion=%.3f "
                        + "visible=%d pixelDelta=%d fps=%.1f CPU=%.3fms GPU=%.3fms size=%dx%d%n",
                options.asset().cliName(),
                frame, scene.renderers().size(), assets.animationMotion(),
                visibility.available() ? visibility.forwardVisible() : -1, pixelDelta,
                driver.statistics().averageFps(),
                driver.statistics().lastCpuSubmitMillis(),
                driver.statistics().lastFrameProfile().totalGpuMillis(),
                window.width(), window.height());
    }

    private static int verifyCrouchWalkPixels(GlfwWindow window, RenderPipeline pipeline,
                                               int expectedObjects) {
        RenderPipeline.VisibilityStatistics visibility = pipeline.lastVisibilityStatistics();
        if (!visibility.available() || visibility.forwardVisible() != expectedObjects) {
            throw new IllegalStateException("crouch_walk visibility mismatch: expected "
                    + expectedObjects + " renderers, got "
                    + (visibility.available() ? visibility.forwardVisible() : "unavailable"));
        }
        int width = window.width();
        int height = window.height();
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        glReadBuffer(GL_BACK);
        glDebugMessageControl(GL_DEBUG_SOURCE_API, GL_DEBUG_TYPE_PERFORMANCE,
                GL_DONT_CARE, 131154, false);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        int[] background = averagePatch(pixels, width, 4, 4,
                Math.max(8, Math.min(width, height) / 64));
        int distinguishable = 0;
        int maximumDelta = 0;
        int minimumX = width;
        int minimumY = height;
        int maximumX = -1;
        int maximumY = -1;
        for (int y = height / 10; y < height * 9 / 10; y++) {
            for (int x = width / 4; x < width * 3 / 4; x++) {
                int offset = (y * width + x) * 4;
                int delta = Math.abs(Byte.toUnsignedInt(pixels.get(offset)) - background[0])
                        + Math.abs(Byte.toUnsignedInt(pixels.get(offset + 1)) - background[1])
                        + Math.abs(Byte.toUnsignedInt(pixels.get(offset + 2)) - background[2]);
                maximumDelta = Math.max(maximumDelta, delta);
                if (delta <= 24) continue;
                distinguishable++;
                minimumX = Math.min(minimumX, x);
                minimumY = Math.min(minimumY, y);
                maximumX = Math.max(maximumX, x);
                maximumY = Math.max(maximumY, y);
            }
        }
        System.out.printf(Locale.ROOT,
                "crouch_walk pixel probe: pixels=%d maxDelta=%d bounds=%d,%d..%d,%d%n",
                distinguishable, maximumDelta, minimumX, minimumY, maximumX, maximumY);
        if (distinguishable <= 100) {
            throw new IllegalStateException("crouch_walk did not produce enough pixels distinct "
                    + "from the environment background: pixels=" + distinguishable
                    + " maxDelta=" + maximumDelta);
        }
        return distinguishable;
    }

    private static int[] averagePatch(ByteBuffer pixels, int frameWidth,
                                      int x, int y, int size) {
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        for (int patchY = y; patchY < y + size; patchY++) {
            for (int patchX = x; patchX < x + size; patchX++) {
                int offset = (patchY * frameWidth + patchX) * 4;
                red += Byte.toUnsignedInt(pixels.get(offset));
                green += Byte.toUnsignedInt(pixels.get(offset + 1));
                blue += Byte.toUnsignedInt(pixels.get(offset + 2));
            }
        }
        int count = size * size;
        return new int[]{(int) (red / count), (int) (green / count), (int) (blue / count)};
    }

    private record Options(boolean hidden, int frames, int width, int height,
                           AntiAliasingMode antiAliasingMode, boolean bloom,
                           boolean autoExposure, String environmentQuality, Resize resize,
                           Asset asset) {
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
            Asset asset = Asset.DEFAULT;
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
                } else if (arg.equals("--zombie")) {
                    asset = Asset.ZOMBIE;
                } else if (arg.equals("--crouch-walk") || arg.equals("--crouch_walk")) {
                    asset = Asset.CROUCH_WALK;
                } else if (arg.equals("--player-slie") || arg.equals("--player_slie")) {
                    asset = Asset.PLAYER_SLIE;
                } else if (arg.startsWith("--asset=")) {
                    asset = Asset.parse(arg.substring(8));
                } else {
                    throw new IllegalArgumentException("Unknown GltfDemo option: " + arg);
                }
            }
            if (hidden && frames < 0) frames = 8;
            PbrEnvironmentSettings.quality(quality);
            if (resize != null && frames <= resize.frame() + 1) {
                throw new IllegalArgumentException("--frames must include one frame after --resize");
            }
            return new Options(hidden, frames, width, height, aa, bloom, autoExposure,
                    quality, resize, asset);
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

    enum Asset {
        DEFAULT("default", "Haikalat glTF Demo"),
        ZOMBIE("zombie", "Haikalat GeckoLib glTF Zombie"),
        CROUCH_WALK("crouch_walk", "Haikalat glTF Crouch Walk"),
        PLAYER_SLIE("player_slie", "Haikalat Blockbench Player Slie");

        private final String cliName;
        private final String windowTitle;

        Asset(String cliName, String windowTitle) {
            this.cliName = cliName;
            this.windowTitle = windowTitle;
        }

        String cliName() {
            return cliName;
        }

        String windowTitle() {
            return windowTitle;
        }

        static Asset parse(String value) {
            for (Asset asset : values()) {
                if (asset.cliName.equals(value)) return asset;
            }
            throw new IllegalArgumentException(
                    "--asset must be default, zombie, crouch_walk, or player_slie");
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
