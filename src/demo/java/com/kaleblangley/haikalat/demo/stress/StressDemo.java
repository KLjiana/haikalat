package com.kaleblangley.haikalat.demo.stress;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.PipelineStatisticsQuery;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.buffer.PackedInstanceBuffer;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.demo.DemoSupport;
import com.kaleblangley.haikalat.runtime.FrameBenchmarkSession;
import com.kaleblangley.haikalat.runtime.FrameClock;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.PeriodicTimer;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glFinish;

/** 用于同机比较 procedural 与实例布局的大规模单 draw 压力测试。 */
public final class StressDemo {
    private static final String PASS_NAME = "StressInstances";
    private static final String FRAGMENT_SHADER = "/demo/vertex_color_unlit.frag";
    private static final int DEFAULT_INSTANCES = 1_000_000;
    private static final int PACKED_STORAGE_BINDING = 0;

    private StressDemo() {
    }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        RenderSettings settings = RenderSettings.builder().vsync(options.vsync()).build();
        StressGrid grid = StressGrid.forInstances(options.instances());
        Camera camera = new Camera(new Vector3f(
                0.0f, 0.0f, Math.max(5.0f, grid.columns() * 0.19f)));

        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(1280, 720)
                .title("Haikalat Stress")
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(settings.vsync());
            if (!options.hidden()) window.show();
            switch (options.mode()) {
                case GPU -> runGpu(window, camera, settings, options, grid);
                case INDEXED -> runIndexed(window, camera, settings, options, grid);
                case INDEXED_SSBO -> runIndexedSsbo(window, camera, settings, options, grid);
                case DYNAMIC -> runDynamic(window, camera, settings, options, grid);
            }
        }
    }

    private static void runGpu(GlfwWindow window, Camera camera, RenderSettings settings,
                               Options options, StressGrid grid) {
        try (FrameDriver driver = new FrameDriver(settings);
             ShaderProgram shader = ShaderProgram.fromResource(StressDemo.class,
                     options.primitive().gpuShaderResource(), FRAGMENT_SHADER);
             VertexArray emptyVao = new VertexArray();
             RenderGraph graph = createProceduralGraph(window, camera, shader, emptyVao.id(),
                     options, grid, options.primitive().gpuVertexCount())) {
            runLoop(window, camera, driver, graph, options, () -> 1,
                    "StressDemo.gpuFrame", null, null);
        }
    }

    private static void runIndexed(GlfwWindow window, Camera camera, RenderSettings settings,
                                   Options options, StressGrid grid) {
        try (FrameDriver driver = new FrameDriver(settings);
             ShaderProgram shader = ShaderProgram.fromResource(StressDemo.class,
                     options.primitive().indexedShaderResource(), FRAGMENT_SHADER);
             StressIndexedGeometry geometry = new StressIndexedGeometry(options.primitive());
             RenderGraph graph = createIndexedGraph(window, camera, shader, geometry,
                     options, grid, null)) {
            runLoop(window, camera, driver, graph, options, () -> 1,
                    "StressDemo.indexedFrame", null, null);
        }
    }

    private static void runIndexedSsbo(GlfwWindow window, Camera camera, RenderSettings settings,
                                       Options options, StressGrid grid) {
        ByteBuffer instanceData = StressPackedInstances.create(
                options.primitive(), options.instances(), grid);
        try (FrameDriver driver = new FrameDriver(settings);
             ShaderProgram shader = ShaderProgram.fromResource(StressDemo.class,
                     options.primitive().indexedSsboShaderResource(), FRAGMENT_SHADER);
             StressIndexedGeometry geometry = new StressIndexedGeometry(options.primitive());
             PackedInstanceBuffer instances = PackedInstanceBuffer.immutable(
                     instanceData, options.instances());
             RenderGraph graph = createIndexedGraph(window, camera, shader, geometry,
                     options, grid, instances)) {
            shader.bindStorageBlock("PackedInstances", PACKED_STORAGE_BINDING);
            runLoop(window, camera, driver, graph, options, () -> 1,
                    "StressDemo.indexedSsboFrame", instances, null);
        }
    }

    private static void runDynamic(GlfwWindow window, Camera camera, RenderSettings settings,
                                   Options options, StressGrid grid) {
        List<Matrix4f> transforms = createTransforms(
                options.primitive(), options.instances(), grid);
        try (FrameDriver driver = new FrameDriver(settings);
             ShaderProgram shader = DemoSupport.loadProjectionViewInstancedShader(StressDemo.class);
             Mesh mesh = Mesh.from(BuiltinMeshData.named(options.primitive().builtinName()));
             InstancedMeshBatch batch = InstancedMeshBatch.of(mesh, options.instances(),
                     BuiltinMeshData.INSTANCE_ATTRIBUTE_BASE);
             RenderGraph graph = createDynamicGraph(window, camera, shader, batch, transforms)) {
            runLoop(window, camera, driver, graph, options,
                    () -> batch.statistics().drawCalls(), "StressDemo.dynamicFrame", null, null);
        }
    }

    private static void runLoop(GlfwWindow window, Camera camera, FrameDriver driver,
                                RenderGraph graph, Options options, IntSupplier drawCalls,
                                String debugLabel, PackedInstanceBuffer packedInstances,
                                Runnable beforeFrame) {
        FrameClock clock = new FrameClock();
        PeriodicTimer titleUpdate = new PeriodicTimer(Duration.ofMillis(250));
        FrameBenchmarkSession benchmark = new FrameBenchmarkSession(
                options.maxFrames(), options.warmupFrames());
        long vertexInvocations;

        try (PipelineStatisticsQuery pipelineQuery = PipelineStatisticsQuery.vertexShaderInvocations()) {
            while (!window.shouldClose()) {
                FrameClock.Tick time = clock.tick();
                DemoSupport.updateFreeCamera(window, camera, time.deltaSeconds());
                if (window.consumeResize()) graph.resize(window.width(), window.height());
                if (beforeFrame != null) beforeFrame.run();
                if (packedInstances != null && packedInstances.dynamic()) packedInstances.beginFrame();

                pipelineQuery.begin();
                driver.frame(graph);
                pipelineQuery.end();
                if (packedInstances != null && packedInstances.dynamic()) packedInstances.finishFrame();
                driver.present(window::swapBuffers);
                window.pollEvents();

                benchmark.recordFrame(driver, graph.lastFrameProfile().totalGpuNanos());
                if (titleUpdate.poll()) {
                    window.setTitle(formatStatistics(driver, benchmark.snapshot(),
                            pipelineQuery.latestValue(), drawCalls.getAsInt(), options));
                }
                if (benchmark.isComplete()) window.requestClose();
            }
            // 在正式测量区间之外解析最后一个异步管线统计结果。
            glFinish();
            vertexInvocations = pipelineQuery.latestValue();
        }

        GlDebug.checkError(debugLabel);
        if (options.maxFrames() > 0) {
            System.out.println(formatStatistics(driver, benchmark.snapshot(),
                    vertexInvocations, drawCalls.getAsInt(), options));
        }
    }

    private static RenderGraph createDynamicGraph(GlfwWindow window, Camera camera,
                                                   ShaderProgram shader, InstancedMeshBatch batch,
                                                   List<Matrix4f> transforms) {
        Matrix4f projectionView = new Matrix4f();
        Matrix4f view = new Matrix4f();
        RenderGraph graph = new RenderGraph(window.width(), window.height());
        graph.addPass(PASS_NAME)
                .writeToBackbuffer()
                .noClear()
                .execute((resources, commands) -> {
                    updateProjectionView(window, camera, projectionView, view);
                    recordCommonDrawState(commands, shader, projectionView)
                            .drawInstancedBatch(batch, transforms);
                });
        graph.compile();
        return graph;
    }

    private static RenderGraph createProceduralGraph(GlfwWindow window, Camera camera,
                                                      ShaderProgram shader, int vao,
                                                      Options options, StressGrid grid,
                                                      int vertexCount) {
        configureGrid(shader, options.primitive(), grid);
        long startNanos = System.nanoTime();
        Matrix4f projectionView = new Matrix4f();
        Matrix4f view = new Matrix4f();
        RenderGraph graph = new RenderGraph(window.width(), window.height());
        graph.addPass(PASS_NAME)
                .writeToBackbuffer()
                .noClear()
                .execute((resources, commands) -> {
                    updateProjectionView(window, camera, projectionView, view);
                    recordCommonDrawState(commands, shader, projectionView);
                    recordTimeRotation(commands, shader, options.primitive(), startNanos);
                    commands.bindVertexArray(vao)
                            .drawArraysInstanced(GL_TRIANGLES, 0, vertexCount, options.instances());
                });
        graph.compile();
        return graph;
    }

    private static RenderGraph createIndexedGraph(GlfwWindow window, Camera camera,
                                                   ShaderProgram shader,
                                                   StressIndexedGeometry geometry,
                                                   Options options, StressGrid grid,
                                                   PackedInstanceBuffer instances) {
        if (instances == null) configureGrid(shader, options.primitive(), grid);
        long startNanos = System.nanoTime();
        Matrix4f projectionView = new Matrix4f();
        Matrix4f view = new Matrix4f();
        RenderGraph graph = new RenderGraph(window.width(), window.height());
        graph.addPass(PASS_NAME)
                .writeToBackbuffer()
                .noClear()
                .execute((resources, commands) -> {
                    updateProjectionView(window, camera, projectionView, view);
                    recordCommonDrawState(commands, shader, projectionView);
                    recordTimeRotation(commands, shader, options.primitive(), startNanos);
                    if (instances != null) {
                        commands.bindStorageBuffer(PACKED_STORAGE_BINDING, instances.buffer(),
                                instances.bindingOffsetBytes(), instances.bindingSizeBytes());
                    }
                    geometry.recordDraw(commands, options.primitive(), options.instances());
                });
        graph.compile();
        return graph;
    }

    private static com.kaleblangley.haikalat.core.command.CommandBuffer recordCommonDrawState(
            com.kaleblangley.haikalat.core.command.CommandBuffer commands,
            ShaderProgram shader, Matrix4f projectionView) {
        return commands.clearColor(0.025f, 0.03f, 0.045f, 1.0f)
                .clear(true, true)
                .enableDepthTest(true)
                .depthMask(true)
                .enableCullFace(true)
                .bindShader(shader)
                .setUniformMat4(shader, DemoSupport.U_PROJECTION_VIEW, projectionView);
    }

    private static void updateProjectionView(GlfwWindow window, Camera camera,
                                             Matrix4f projectionView, Matrix4f view) {
        DemoSupport.perspective(projectionView, window.width(), window.height())
                .mul(camera.getViewMatrix(view));
    }

    private static void configureGrid(ShaderProgram shader,
                                      GeneratedStressPrimitive primitive, StressGrid grid) {
        float scale = primitive == GeneratedStressPrimitive.CUBE ? 0.105f : 0.115f;
        shader.setInt("uColumnShift", grid.columnShift())
                .setInt("uColumnMask", grid.columnMask())
                .setVec4("uGrid", (grid.columns() - 1) * 0.5f,
                        (grid.rows() - 1) * 0.5f, 0.14f, scale);
    }

    private static void recordTimeRotation(
            com.kaleblangley.haikalat.core.command.CommandBuffer commands,
            ShaderProgram shader, GeneratedStressPrimitive primitive, long startNanos) {
        if (primitive != GeneratedStressPrimitive.CUBE) return;
        double angle = (System.nanoTime() - startNanos) * 0.000_000_001 * 0.15;
        commands.setUniformVec2(shader, "uTimeRotation",
                (float) Math.cos(angle), (float) Math.sin(angle));
    }

    private static List<Matrix4f> createTransforms(GeneratedStressPrimitive primitive,
                                                   int count, StressGrid grid) {
        float spacing = 0.14f;
        float scale = primitive == GeneratedStressPrimitive.CUBE ? 0.105f : 0.115f;
        float startX = -(grid.columns() - 1) * spacing * 0.5f;
        float startY = -(grid.rows() - 1) * spacing * 0.5f;
        List<Matrix4f> transforms = new ArrayList<>(count);
        for (int instance = 0; instance < count; instance++) {
            int row = instance >> grid.columnShift();
            int column = instance & grid.columnMask();
            float z = primitive.depthLayers() ? ((instance & 15) - 8) * 0.008f : 0.0f;
            transforms.add(new Matrix4f()
                    .translation(startX + column * spacing, startY + row * spacing, z)
                    .rotateZ((instance & 15) * ((float) Math.PI / 8.0f))
                    .scale(scale));
        }
        return List.copyOf(transforms);
    }

    private static String formatStatistics(FrameDriver driver,
                                           FrameBenchmarkSession.Snapshot benchmark,
                                           long vertexInvocations,
                                           int drawCalls, Options options) {
        StateCache.Statistics state = driver.stateStatistics();
        long stateChecks = state.appliedChanges() + state.avoidedChanges();
        double stateSkipPercent = stateChecks == 0L
                ? 0.0 : state.avoidedChanges() * 100.0 / stateChecks;
        long triangles = (long) options.instances() * options.primitive().trianglesPerInstance();
        String vs = vertexInvocations < 0L ? "N/A" : String.format("%,d", vertexInvocations);
        var timings = benchmark.timings();
        return String.format(
                "Stress %s/%s | instances %,d | triangles %,d | present FPS %.1f | "
                        + "CPU avg/median %.3f/%.3f ms | GPU avg/median %.3f/%.3f ms | "
                        + "draws %d | state skip %.1f%% | VS invocations %s",
                options.mode().displayName(), options.primitive(), options.instances(), triangles,
                benchmark.presentFps(), timings.averageCpuMillis(), timings.medianCpuMillis(),
                timings.averageGpuMillis(), timings.medianGpuMillis(), drawCalls,
                stateSkipPercent, vs);
    }

    private enum Mode {
        GPU("gpu"), INDEXED("indexed"), INDEXED_SSBO("indexed-ssbo"), DYNAMIC("dynamic");

        private final String displayName;

        Mode(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }

    private record Options(Mode mode, GeneratedStressPrimitive primitive, int instances,
                           boolean hidden, boolean vsync, int maxFrames, int warmupFrames) {
        static Options parse(String[] args) {
            Mode mode = Mode.GPU;
            GeneratedStressPrimitive primitive = GeneratedStressPrimitive.TRIANGLE;
            int instances = DEFAULT_INSTANCES;
            boolean hidden = false;
            boolean vsync = false;
            int maxFrames = -1;
            int warmupFrames = -1;
            for (String arg : args) {
                if (arg.startsWith("--shape=")) {
                    primitive = GeneratedStressPrimitive.valueOf(
                            arg.substring("--shape=".length()).toUpperCase());
                } else if (arg.startsWith("--mode=")) {
                    mode = Mode.valueOf(arg.substring("--mode=".length())
                            .toUpperCase().replace('-', '_'));
                } else if (arg.startsWith("--instances=")) {
                    instances = Integer.parseInt(arg.substring("--instances=".length()));
                } else if (arg.startsWith("--frames=")) {
                    maxFrames = Integer.parseInt(arg.substring("--frames=".length()));
                } else if (arg.startsWith("--warmup=")) {
                    warmupFrames = Integer.parseInt(arg.substring("--warmup=".length()));
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
            if (warmupFrames < -1) throw new IllegalArgumentException("--warmup must be non-negative");
            if (warmupFrames < 0) warmupFrames = maxFrames >= 120 ? 100 : 0;
            return new Options(mode, primitive, instances, hidden, vsync, maxFrames, warmupFrames);
        }
    }
}
