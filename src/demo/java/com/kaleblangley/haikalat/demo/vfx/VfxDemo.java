package com.kaleblangley.haikalat.demo.vfx;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.demo.DemoSupport;
import com.kaleblangley.haikalat.runtime.FrameClock;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.VfxRenderer;
import com.kaleblangley.haikalat.subsystems.vfx.Decal;
import com.kaleblangley.haikalat.subsystems.vfx.EffectAsset;
import com.kaleblangley.haikalat.subsystems.vfx.EffectInstance;
import com.kaleblangley.haikalat.subsystems.vfx.EffectSnapshot;
import com.kaleblangley.haikalat.subsystems.vfx.ParticleEmitter;
import com.kaleblangley.haikalat.subsystems.vfx.RibbonEmitter;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL;

import java.util.Locale;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

/** CPU VFX 模拟、透明顺序、GPU 适配和性能计时的独立证明。 */
public final class VfxDemo {
    private static final float FIXED_DELTA_SECONDS = 1.0f / 60.0f;
    private static final String PASS_NAME = "VfxPass";

    private VfxDemo() {
    }

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        RenderSettings settings = RenderSettings.builder().vsync(!options.hidden()).build();
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(DemoSupport.DEFAULT_WIDTH, DemoSupport.DEFAULT_HEIGHT)
                .title("Haikalat VFX")
                .visible(!options.hidden())
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(settings.vsync());
            run(window, settings, options);
        }
    }

    private static void run(GlfwWindow window, RenderSettings settings, Options options) {
        Vector3f camera = new Vector3f(0.0f, 0.2f, 5.0f);
        Matrix4f projection = new Matrix4f();
        Matrix4f view = new Matrix4f().lookAt(camera, new Vector3f(),
                new Vector3f(0.0f, 1.0f, 0.0f));
        EffectSnapshot[] current = new EffectSnapshot[1];
        long updateNanos = 0L;
        long gpuNanos = 0L;
        int gpuSamples = 0;
        FrameClock clock = new FrameClock();

        try (FrameDriver driver = new FrameDriver(settings);
             VfxRenderer renderer = new VfxRenderer();
             EffectAsset asset = asset(options.maxParticles());
             EffectInstance instance = asset.instantiate(0x5eedL);
             RenderGraph graph = graph(window, renderer, current, projection, view)) {
            int frame = 0;
            while (!window.shouldClose()) {
                if (window.isKeyDown(GLFW_KEY_ESCAPE)) window.requestClose();
                if (options.resizeFrame() == frame) {
                    window.resize(options.resizeWidth(), options.resizeHeight());
                    window.pollEvents();
                }
                if (window.consumeResize()) graph.resize(window.width(), window.height());

                float delta = options.hidden() ? FIXED_DELTA_SECONDS : clock.tick().deltaSeconds();
                Vector3f origin = new Vector3f(
                        (float) Math.sin(frame * 0.07f) * 1.4f,
                        (float) Math.sin(frame * 0.13f) * 0.45f,
                        (float) Math.cos(frame * 0.05f) * 0.25f);
                long updateStart = System.nanoTime();
                instance.update(delta, origin);
                if (frame == 0 || frame == options.maxFrames() / 2) {
                    instance.spawnDecal(new Vector3f(origin.x, -1.0f, origin.z - 0.1f),
                            new Vector3f(0.0f, 0.0f, 1.0f), new Vector2f(0.9f, 0.35f),
                            frame * 0.08f);
                }
                current[0] = instance.snapshot(camera);
                updateNanos += System.nanoTime() - updateStart;

                driver.frame(graph);
                long frameGpuNanos = graph.lastFrameProfile().totalGpuNanos();
                if (frame >= options.warmupFrames() && frameGpuNanos > 0L) {
                    gpuNanos += frameGpuNanos;
                    gpuSamples++;
                }
                driver.present(window::swapBuffers);
                window.pollEvents();
                frame++;
                if (options.maxFrames() > 0 && frame >= options.maxFrames()) window.requestClose();
            }

            EffectSnapshot snapshot = current[0];
            VfxRenderer.Statistics renderStats = renderer.statistics();
            if (options.hidden() && (snapshot == null || snapshot.particles().isEmpty()
                    || snapshot.ribbonSegments().isEmpty() || snapshot.decals().isEmpty()
                    || renderStats.drawCalls() != snapshot.primitiveCount())) {
                throw new IllegalStateException("VFX integration did not render every effect primitive");
            }
            int measuredFrames = Math.max(1, options.maxFrames());
            System.out.printf(Locale.ROOT,
                    "VFX frames=%d particles=%d ribbons=%d decals=%d draws=%d "
                            + "cpuUpdateMs=%.4f gpuMs=%.4f uniformBytes=%d resized=%s%n",
                    options.maxFrames(), snapshot == null ? 0 : snapshot.particles().size(),
                    snapshot == null ? 0 : snapshot.ribbonSegments().size(),
                    snapshot == null ? 0 : snapshot.decals().size(), renderStats.drawCalls(),
                    updateNanos / 1_000_000.0 / measuredFrames,
                    gpuSamples == 0 ? 0.0 : gpuNanos / 1_000_000.0 / gpuSamples,
                    renderStats.uniformPayloadBytes(), options.resizeFrame() >= 0);
        }
    }

    private static RenderGraph graph(GlfwWindow window, VfxRenderer renderer,
                                     EffectSnapshot[] current, Matrix4f projection,
                                     Matrix4f view) {
        RenderGraph graph = new RenderGraph(window.width(), window.height());
        graph.addPass(PASS_NAME).writeToBackbuffer().noClear().execute((resources, commands) -> {
            DemoSupport.perspective(projection, window.width(), window.height());
            commands.bindDefaultFramebuffer().viewport(0, 0, window.width(), window.height())
                    .enableFramebufferSrgb(false).enableDepthTest(true).depthMask(true)
                    .clearColor(0.012f, 0.018f, 0.028f, 1.0f).clear(true, true);
            if (current[0] != null) renderer.record(commands, current[0], projection, view);
        });
        graph.compile();
        return graph;
    }

    private static EffectAsset asset(int maxParticles) {
        return EffectAsset.builder("demo-vfx")
                .particles(new ParticleEmitter(maxParticles, Math.min(600.0f, maxParticles * 1.5f),
                        2.0f, new Vector3f(0.0f, 1.0f, 0.0f), 0.55f,
                        0.5f, 2.0f, new Vector3f(0.0f, -0.7f, 0.0f), 0.15f,
                        0.16f, 0.02f, new Vector4f(1.0f, 0.62f, 0.12f, 0.9f),
                        new Vector4f(0.9f, 0.08f, 0.03f, 0.0f)))
                .ribbon(new RibbonEmitter(48, 1.5f, 0.025f, 0.16f, 0.015f,
                        new Vector4f(0.08f, 0.75f, 1.0f, 0.78f),
                        new Vector4f(0.1f, 0.15f, 0.8f, 0.0f)))
                .decals(new Decal(8, 4.0f,
                        new Vector4f(0.62f, 0.18f, 1.0f, 0.65f),
                        new Vector4f(0.25f, 0.04f, 0.5f, 0.0f)))
                .build();
    }

    private record Options(boolean hidden, int maxFrames, int warmupFrames,
                           int maxParticles, int resizeFrame,
                           int resizeWidth, int resizeHeight) {
        private static Options parse(String[] arguments) {
            boolean hidden = false;
            int frames = -1;
            int warmup = 0;
            int particles = 256;
            int resizeFrame = -1;
            int resizeWidth = -1;
            int resizeHeight = -1;
            for (String argument : arguments) {
                if ("--hidden".equals(argument) || "--deterministic".equals(argument)) {
                    hidden = true;
                } else if (argument.startsWith("--frames=")) {
                    frames = Integer.parseInt(argument.substring("--frames=".length()));
                } else if (argument.startsWith("--warmup=")) {
                    warmup = Integer.parseInt(argument.substring("--warmup=".length()));
                } else if (argument.startsWith("--particles=")) {
                    particles = Integer.parseInt(argument.substring("--particles=".length()));
                } else if (argument.startsWith("--resize=")) {
                    String[] frameAndSize = argument.substring("--resize=".length()).split(":", 2);
                    String[] size = frameAndSize[1].toLowerCase(Locale.ROOT).split("x", 2);
                    resizeFrame = Integer.parseInt(frameAndSize[0]);
                    resizeWidth = Integer.parseInt(size[0]);
                    resizeHeight = Integer.parseInt(size[1]);
                } else {
                    throw new IllegalArgumentException("Unknown VfxDemo argument: " + argument);
                }
            }
            if (hidden && frames < 0) frames = 16;
            if (frames == 0 || frames < -1) throw new IllegalArgumentException("--frames must be positive");
            if (warmup < 0 || frames > 0 && warmup >= frames) {
                throw new IllegalArgumentException("--warmup must be non-negative and below --frames");
            }
            if (particles <= 0) throw new IllegalArgumentException("--particles must be positive");
            if (resizeFrame >= 0 && (resizeWidth <= 0 || resizeHeight <= 0
                    || frames > 0 && resizeFrame >= frames)) {
                throw new IllegalArgumentException("--resize must fit FRAME:WIDTHxHEIGHT");
            }
            return new Options(hidden, frames, warmup, particles,
                    resizeFrame, resizeWidth, resizeHeight);
        }
    }
}
