package com.kaleblangley.haikalat.demo.stress;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.demo.DemoSupport;
import com.kaleblangley.haikalat.runtime.FrameClock;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.PeriodicTimer;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.RenderStatistics;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Large instanced-draw workload for CPU submission, upload, GPU and state-cache profiling. */
public final class StressDemo {
    private static final String PASS_NAME = "StressInstances";
    private static final int DEFAULT_INSTANCES = 100_000;

    private StressDemo() {
    }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        RenderSettings settings = RenderSettings.builder().vsync(options.vsync()).build();
        int gridSide = (int) Math.ceil(Math.sqrt(options.instances()));
        Camera camera = new Camera(new Vector3f(0.0f, 0.0f, Math.max(5.0f, gridSide * 0.19f)));

        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(1280, 720)
                .title("Haikalat Stress")
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(settings.vsync());
            if (!options.hidden()) window.show();
            run(window, camera, settings, options);
        }
    }

    private static void run(GlfwWindow window, Camera camera, RenderSettings settings, Options options) {
        List<Matrix4f> transforms = createTransforms(options.shape(), options.instances());
        try (FrameDriver driver = new FrameDriver(settings);
             ShaderProgram shader = DemoSupport.loadProjectionViewInstancedShader(StressDemo.class);
             Mesh mesh = Mesh.from(BuiltinMeshData.named(options.shape().builtinName));
             InstancedMeshBatch batch = InstancedMeshBatch.of(mesh, options.instances(),
                     BuiltinMeshData.INSTANCE_ATTRIBUTE_BASE);
             RenderGraph graph = createGraph(window, camera, shader, batch, transforms)) {
            FrameClock clock = new FrameClock();
            PeriodicTimer titleUpdate = new PeriodicTimer(Duration.ofMillis(250));
            int frame = 0;
            while (!window.shouldClose()) {
                FrameClock.Tick time = clock.tick();
                DemoSupport.updateFreeCamera(window, camera, time.deltaSeconds());
                if (window.consumeResize()) graph.resize(window.width(), window.height());

                driver.frame(graph);
                driver.present(window::swapBuffers);
                window.pollEvents();

                if (titleUpdate.poll()) {
                    updateTitle(window, driver, batch, options);
                }
                GlDebug.checkError("StressDemo.frame");
                frame++;
                if (options.maxFrames() > 0 && frame >= options.maxFrames()) window.requestClose();
            }
        }
    }

    private static RenderGraph createGraph(GlfwWindow window, Camera camera, ShaderProgram shader,
                                           InstancedMeshBatch batch, List<Matrix4f> transforms) {
        RenderGraph graph = new RenderGraph(window.width(), window.height());
        graph.addPass(PASS_NAME)
                .writeToBackbuffer()
                .noClear()
                .execute((resources, commands) -> {
                    Matrix4f projectionView = DemoSupport.perspective(
                            new Matrix4f(), window.width(), window.height())
                            .mul(camera.getViewMatrix());
                    commands.clearColor(0.025f, 0.03f, 0.045f, 1.0f)
                            .clear(true, true)
                            .enableDepthTest(true)
                            .depthMask(true)
                            .enableCullFace(false)
                            .bindShader(shader)
                            .setUniformMat4(shader, DemoSupport.U_PROJECTION_VIEW, projectionView)
                            .drawInstancedBatch(batch, transforms);
                });
        graph.compile();
        return graph;
    }

    private static List<Matrix4f> createTransforms(Shape shape, int count) {
        int columns = (int) Math.ceil(Math.sqrt(count));
        int rows = (count + columns - 1) / columns;
        float spacing = 0.14f;
        float scale = shape == Shape.CUBE ? 0.105f : 0.115f;
        float startX = -(columns - 1) * spacing * 0.5f;
        float startY = -(rows - 1) * spacing * 0.5f;
        List<Matrix4f> transforms = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int row = i / columns;
            int column = i % columns;
            float z = shape == Shape.CUBE ? ((i % 17) - 8) * 0.008f : 0.0f;
            transforms.add(new Matrix4f()
                    .translation(startX + column * spacing, startY + row * spacing, z)
                    .rotateZ((i % 31) * 0.017f)
                    .scale(scale));
        }
        return List.copyOf(transforms);
    }

    private static void updateTitle(GlfwWindow window, FrameDriver driver,
                                    InstancedMeshBatch batch, Options options) {
        RenderStatistics.Snapshot timing = driver.statistics().snapshot();
        StateCache.Statistics state = driver.stateStatistics();
        long stateChecks = state.appliedChanges() + state.avoidedChanges();
        double stateSkipPercent = stateChecks == 0L ? 0.0 : state.avoidedChanges() * 100.0 / stateChecks;
        long triangles = (long) options.instances() * options.shape().trianglesPerInstance;
        window.setTitle(String.format(
                "Stress %s | instances %,d | triangles %,d | FPS %.1f | CPU %.2f ms | GPU %.2f ms | draws %d | state skip %.1f%%",
                options.shape(), options.instances(), triangles, timing.presentFps(),
                timing.cpuSubmitMillis(), timing.gpuMillis(), batch.statistics().drawCalls(),
                stateSkipPercent));
    }

    private enum Shape {
        TRIANGLE(BuiltinMeshData.TRIANGLE, 1),
        QUAD(BuiltinMeshData.QUAD, 2),
        CUBE(BuiltinMeshData.CUBE, 12);

        private final String builtinName;
        private final int trianglesPerInstance;

        Shape(String builtinName, int trianglesPerInstance) {
            this.builtinName = builtinName;
            this.trianglesPerInstance = trianglesPerInstance;
        }
    }

    private record Options(Shape shape, int instances, boolean hidden, boolean vsync, int maxFrames) {
        static Options parse(String[] args) {
            Shape shape = Shape.TRIANGLE;
            int instances = DEFAULT_INSTANCES;
            boolean hidden = false;
            boolean vsync = false;
            int maxFrames = -1;
            for (String arg : args) {
                if (arg.startsWith("--shape=")) {
                    shape = Shape.valueOf(arg.substring("--shape=".length()).toUpperCase());
                } else if (arg.startsWith("--instances=")) {
                    instances = Integer.parseInt(arg.substring("--instances=".length()));
                } else if (arg.startsWith("--frames=")) {
                    maxFrames = Integer.parseInt(arg.substring("--frames=".length()));
                } else if ("--hidden".equals(arg)) {
                    hidden = true;
                } else if ("--vsync".equals(arg)) {
                    vsync = true;
                } else if ("--deterministic".equals(arg)) {
                    hidden = true;
                    vsync = false;
                    if (maxFrames < 0) maxFrames = 3;
                } else {
                    throw new IllegalArgumentException("Unknown stress argument: " + arg);
                }
            }
            if (instances <= 0 || instances > 1_000_000) {
                throw new IllegalArgumentException("--instances must be between 1 and 1000000");
            }
            if (maxFrames == 0 || maxFrames < -1) {
                throw new IllegalArgumentException("--frames must be positive");
            }
            return new Options(shape, instances, hidden, vsync, maxFrames);
        }
    }
}
