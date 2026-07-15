package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.core.assets.SceneAssetConfig;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.VertexPacking;
import com.kaleblangley.haikalat.runtime.DebugOverlaySnapshot;
import com.kaleblangley.haikalat.runtime.FrameClock;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.FrameBenchmarkSession;
import com.kaleblangley.haikalat.runtime.PassBenchmarkAccumulator;
import com.kaleblangley.haikalat.runtime.FrameTimingAccumulator;
import com.kaleblangley.haikalat.runtime.PeriodicTimer;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.InstancedRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;

import java.util.Map;
import java.time.Duration;

public final class LearnOpenGlDemo {
    private static volatile BenchmarkResult lastBenchmarkResult;

    private LearnOpenGlDemo() {
    }

    public static void main(String[] args) {
        DemoOptions options = DemoOptions.parse(args);
        demoVertexPacking();

        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(options.antiAliasingMode())
                .toneMappingMode(options.toneMappingMode())
                .exposure(1.0f)
                .bloomSettings(BloomSettings.builder().enabled(options.bloom()).build())
                .vsync(!options.deterministic())
                .build();
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(DemoSupport.DEFAULT_WIDTH, DemoSupport.DEFAULT_HEIGHT)
                .title("LearnOpenGL Demo")
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(settings.vsync());
            if (!options.deterministic()) {
                window.show();
            }
            run(window, settings, options);
        }
    }

    private static void run(GlfwWindow window, RenderSettings settings, DemoOptions options) {
        Camera camera = new Camera(new Vector3f(0, 0, 5));
        ResourceLocator assets = ResourceLocator.classpath(LearnOpenGlDemo.class);
        SceneAssetConfig config = SceneAssetConfig.load(assets, "/demo/learnopengl.properties");
        try (FrameDriver renderLoop = new FrameDriver(settings);
             DemoSceneResources resources = DemoSceneResources.load(assets, config)) {
            Scene scene = buildScene(camera, config, resources);
            Mesh instancedMesh = resources.meshes("builtin:" + BuiltinMeshData.QUAD).getFirst();
            ShaderProgram instancedShader = resources.shader("instanced");
            InstancedMeshBatch batch = InstancedMeshBatch.of(instancedMesh,
                    options.instances(), BuiltinMeshData.INSTANCE_ATTRIBUTE_BASE);
            InstancedRenderer instanced = new InstancedRenderer(
                    batch, instancedShader, options.instanceShadows());
            addInstances(instanced, options.instances());

            RenderPipeline pipeline = new RenderPipeline(window, scene, instanced, settings);
            try {
                pipeline.build();
                renderFrames(window, camera, renderLoop, pipeline, instanced, settings, options);
            } finally {
                pipeline.close();
                instanced.close();
            }
        }
    }

    private static void renderFrames(GlfwWindow window, Camera camera, FrameDriver renderLoop,
                                     RenderPipeline pipeline, InstancedRenderer instanced,
                                     RenderSettings settings, DemoOptions options) {
        int frame = 0;
        FrameClock clock = new FrameClock();
        PeriodicTimer titleUpdate = new PeriodicTimer(Duration.ofMillis(250));
        FrameBenchmarkSession benchmark = new FrameBenchmarkSession(
                options.maxFrames(), options.warmupFrames());
        PassBenchmarkAccumulator passBenchmark = new PassBenchmarkAccumulator(
                Math.max(0, options.maxFrames()));
        while (!window.shouldClose()) {
            if (options.resize() != null && frame == options.resize().frame()) {
                window.resize(options.resize().width(), options.resize().height());
            }

            FrameClock.Tick time = clock.tick();
            DemoSupport.updateFreeCamera(window, camera, time.deltaSeconds());
            if (window.consumeResize()) {
                pipeline.resize(window.width(), window.height());
            }

            renderLoop.beginFrame();
            instanced.beginFrame(frame);
            pipeline.execute(renderLoop.device());
            renderLoop.statistics().recordGraphProfile(pipeline.graph().lastFrameProfile());
            renderLoop.endFrame();
            if (options.resize() != null && frame > options.resize().frame()) {
                options.resize().verify(window, pipeline);
            }

            renderLoop.present(window::swapBuffers);
            window.pollEvents();

            int warmupBefore = benchmark.snapshot().warmupRemaining();
            int measuredBefore = benchmark.snapshot().measuredFrames();
            benchmark.recordFrame(renderLoop, pipeline.graph().lastFrameProfile().totalGpuNanos());
            FrameBenchmarkSession.Snapshot benchmarkSnapshot = benchmark.snapshot();
            if (warmupBefore > 0 && benchmarkSnapshot.warmupRemaining() == 0) {
                instanced.resetBufferStatistics();
                passBenchmark.reset();
            }
            if (benchmarkSnapshot.measuredFrames() > measuredBefore) {
                passBenchmark.add(pipeline.graph().lastFrameProfile());
            }

            if (titleUpdate.poll()) {
                DebugOverlaySnapshot overlay = DebugOverlaySnapshot.from(
                        renderLoop.statistics(), instanced.statistics().drawCalls(),
                        instanced.drawnCount(), settings.antiAliasingMode());
                window.setTitle(String.format("LearnOpenGL | FPS %.1f | CPU %.3f ms | GPU %.2f ms | draw %d | inst %d | AA %s",
                        overlay.fps(), overlay.cpuSubmitMillis(), overlay.gpuMillis(),
                        overlay.drawCalls(), overlay.instanceCount(), overlay.activeAntiAliasingMode()));
            }

            GlDebug.checkError("LearnOpenGlDemo.frame");
            frame++;
            if (benchmark.isComplete()) {
                window.requestClose();
            }
        }
        if (options.maxFrames() > 0) {
            printBenchmark(renderLoop, pipeline, instanced, settings, options,
                    benchmark.snapshot(), passBenchmark);
        }
    }

    private static Scene buildScene(Camera camera, SceneAssetConfig config, DemoSceneResources resources) {
        Scene scene = new Scene(camera);
        for (Map.Entry<String, SceneAssetConfig.ObjectDef> entry : config.objects().entrySet()) {
            SceneAssetConfig.ObjectDef def = entry.getValue();
            Material material = resources.material(def.material());
            addModelMeshes(scene, resources.meshes(def.model()), material,
                    updaterFor(entry.getKey(), def), def.castShadows());
        }
        for (SceneAssetConfig.LightDef light : config.lights().values()) {
            scene.addLight(lightFor(light));
        }
        return scene;
    }

    static int addModelMeshes(Scene scene, java.util.List<Mesh> meshes, Material material,
                              SceneObject.ModelUpdater updater, boolean castShadows) {
        for (Mesh mesh : meshes) {
            scene.add(new SceneObject(mesh, material, updater, castShadows));
        }
        return meshes.size();
    }

    private static void addInstances(InstancedRenderer instanced, int count) {
        int columns = (int) Math.ceil(Math.sqrt(count));
        float center = (columns - 1) * 0.5f;
        for (int index = 0; index < count; index++) {
            final int instanceIndex = index;
            final int row = index / columns;
            final int column = index % columns;
            instanced.addInstance(frame -> new Matrix4f()
                    .translation((column - center) * 0.22f, (row - center) * 0.22f, -4.0f)
                    .rotateZ(frame * 0.01f + (instanceIndex & 15) * 0.1f)
                    .scale(0.08f));
        }
    }

    private static void printBenchmark(FrameDriver driver, RenderPipeline pipeline,
                                       InstancedRenderer instanced, RenderSettings settings,
                                       DemoOptions options, FrameBenchmarkSession.Snapshot benchmark,
                                       PassBenchmarkAccumulator passBenchmark) {
        var timings = benchmark.timings();
        var buffer = instanced.bufferStatistics();
        var state = driver.stateStatistics();
        long stateChecks = state.appliedChanges() + state.avoidedChanges();
        double stateSkip = stateChecks == 0L ? 0.0
                : state.avoidedChanges() * 100.0 / stateChecks;
        double uploadPerFrameMb = benchmark.measuredFrames() == 0 ? 0.0
                : buffer.uploadedBytes() / (double) benchmark.measuredFrames() / (1024.0 * 1024.0);
        Map<String, FrameTimingAccumulator.Summary> passTimings = passBenchmark.snapshot();
        lastBenchmarkResult = new BenchmarkResult(
                settings.toneMappingMode() + "/" + settings.antiAliasingMode()
                        + " bloom=" + settings.bloomSettings().enabled()
                        + " instances=" + options.instances()
                        + " shadows=" + options.instanceShadows(),
                benchmark.presentFps(), timings.averageCpuMillis(), timings.medianCpuMillis(),
                timings.averageGpuMillis(), timings.medianGpuMillis(), uploadPerFrameMb,
                buffer.fenceWaitMillis(), buffer.fenceWaitCount(), benchmark.measuredFrames(),
                stateSkip, passTimings);
        if (options.quiet()) {
            return;
        }
        String scope = options.warmupFrames() >= 100 && benchmark.measuredFrames() >= 1000
                ? "FORMAL" : "INTEGRATION-ONLY";
        System.out.printf("LearnOpenGL [%s] %s/%s bloom=%s | instances %,d | shadows=%s | "
                        + "present FPS %.1f | CPU avg/median %.3f/%.3f ms | "
                        + "GPU avg/median %.3f/%.3f ms | upload %.3f MB/frame | "
                        + "ring wait %.3f ms/%d | state skip %.1f%%%n",
                scope, settings.toneMappingMode(), settings.antiAliasingMode(),
                settings.bloomSettings().enabled(), options.instances(), options.instanceShadows(),
                benchmark.presentFps(), timings.averageCpuMillis(), timings.medianCpuMillis(),
                timings.averageGpuMillis(), timings.medianGpuMillis(), uploadPerFrameMb,
                buffer.fenceWaitMillis(), buffer.fenceWaitCount(), stateSkip);
        passTimings.forEach((name, pass) -> System.out.printf(
                "  pass %-22s CPU avg/median %.4f/%.4f ms | GPU avg/median %.4f/%.4f ms%n",
                name, pass.averageCpuMillis(), pass.medianCpuMillis(),
                pass.averageGpuMillis(), pass.medianGpuMillis()));
        System.out.printf("  shadow ordinary draws %d | shadow instances %,d | geometry instances %,d%n",
                pipeline.lastShadowCasterDrawCount(), pipeline.lastInstancedShadowCasterCount(),
                instanced.drawnCount());
    }

    static BenchmarkResult lastBenchmarkResult() {
        BenchmarkResult result = lastBenchmarkResult;
        if (result == null) {
            throw new IllegalStateException("LearnOpenGlDemo has not completed a finite benchmark run");
        }
        return result;
    }

    private static SceneObject.ModelUpdater updaterFor(String objectName, SceneAssetConfig.ObjectDef def) {
        return switch (objectName) {
            case "triangle" -> (matrix, frame) -> baseTransform(matrix, def).rotateZ(frame * 0.03f);
            case "wall" -> (matrix, frame) -> baseTransform(matrix, def).rotateZ(-frame * 0.02f);
            case "face" -> (matrix, frame) -> baseTransform(matrix, def)
                    .translate(0.0f, (float) Math.sin(frame * 0.04f) * 0.5f, 0.0f);
            default -> (matrix, frame) -> baseTransform(matrix, def);
        };
    }

    private static Matrix4f baseTransform(Matrix4f out, SceneAssetConfig.ObjectDef def) {
        Vector3f position = def.position();
        Vector3f rotation = def.rotationRadians();
        return out.identity()
                .translation(position)
                .rotateXYZ(rotation.x, rotation.y, rotation.z)
                .scale(def.scale());
    }

    private static SceneLight lightFor(SceneAssetConfig.LightDef def) {
        return switch (def.type().toLowerCase()) {
            case "directional" -> def.castShadows()
                    ? SceneLight.shadowedDirectional(def.positionOrDirection(), def.color(), def.intensity())
                    : SceneLight.directional(def.positionOrDirection(), def.color(), def.intensity());
            case "point" -> SceneLight.point(
                    def.positionOrDirection(), def.color(), def.intensity(), def.range());
            default -> throw new IllegalStateException("Unsupported demo light type: " + def.type());
        };
    }

    private static void demoVertexPacking() {
        int packed = VertexPacking.packOctNormal(0.5f, 0.5f, 0.7071f);
        float[] unpacked = VertexPacking.unpackOctNormal(packed);
        System.out.printf("[VertexPacking] packed=0x%08X unpacked=(%.3f,%.3f,%.3f)%n",
                packed, unpacked[0], unpacked[1], unpacked[2]);
    }

    private record DemoOptions(boolean deterministic, int maxFrames,
                               AntiAliasingMode antiAliasingMode, ResizeSpec resize, boolean bloom,
                               ToneMappingMode toneMappingMode, int instances,
                               boolean instanceShadows, int warmupFrames, boolean quiet) {
        static DemoOptions parse(String[] args) {
            boolean deterministic = false;
            int maxFrames = -1;
            AntiAliasingMode mode = AntiAliasingMode.FXAA;
            ResizeSpec resize = null;
            boolean bloom = false;
            ToneMappingMode toneMappingMode = ToneMappingMode.ACES;
            int instances = DemoGrid.COUNT;
            boolean instanceShadows = true;
            int warmupFrames = -1;
            boolean quiet = false;
            for (String arg : args) {
                if ("--deterministic".equals(arg)) {
                    deterministic = true;
                } else if (arg.startsWith("--frames=")) {
                    maxFrames = Integer.parseInt(arg.substring("--frames=".length()));
                    if (maxFrames <= 0) {
                        throw new IllegalArgumentException("--frames must be positive");
                    }
                    deterministic = true;
                } else if (arg.startsWith("--aa=")) {
                    mode = AntiAliasingMode.valueOf(arg.substring("--aa=".length()).toUpperCase());
                } else if (arg.startsWith("--resize=")) {
                    resize = ResizeSpec.parse(arg.substring("--resize=".length()));
                    deterministic = true;
                } else if ("--bloom".equals(arg)) {
                    bloom = true;
                } else if (arg.startsWith("--tone=")) {
                    toneMappingMode = ToneMappingMode.valueOf(
                            arg.substring("--tone=".length()).toUpperCase());
                } else if (arg.startsWith("--instances=")) {
                    instances = Integer.parseInt(arg.substring("--instances=".length()));
                } else if (arg.startsWith("--instance-shadows=")) {
                    instanceShadows = Boolean.parseBoolean(
                            arg.substring("--instance-shadows=".length()));
                } else if (arg.startsWith("--warmup=")) {
                    warmupFrames = Integer.parseInt(arg.substring("--warmup=".length()));
                } else if ("--quiet".equals(arg)) {
                    quiet = true;
                } else {
                    throw new IllegalArgumentException("Unknown demo argument: " + arg);
                }
            }
            if (deterministic && maxFrames < 0) {
                maxFrames = 8;
            }
            if (resize != null && maxFrames <= resize.frame() + 1) {
                throw new IllegalArgumentException("--frames must include one frame after --resize");
            }
            if (instances <= 0 || instances > 100_000) {
                throw new IllegalArgumentException("--instances must be between 1 and 100000");
            }
            if (warmupFrames < -1) {
                throw new IllegalArgumentException("--warmup must be non-negative");
            }
            if (warmupFrames < 0) {
                warmupFrames = maxFrames >= 120 ? 100 : 0;
            }
            if (bloom && toneMappingMode == ToneMappingMode.NONE) {
                throw new IllegalArgumentException("--bloom requires --tone=ACES");
            }
            return new DemoOptions(deterministic, maxFrames, mode, resize, bloom,
                    toneMappingMode, instances, instanceShadows, warmupFrames, quiet);
        }
    }

    record BenchmarkResult(
            String scenario,
            double presentFps,
            double averageCpuMillis,
            double medianCpuMillis,
            double averageGpuMillis,
            double medianGpuMillis,
            double uploadMegabytesPerFrame,
            double ringWaitMillis,
            long ringWaitCount,
            int measuredFrames,
            double stateSkipPercent,
            Map<String, FrameTimingAccumulator.Summary> passTimings
    ) {
    }

    private record ResizeSpec(int frame, int width, int height) {
        static ResizeSpec parse(String value) {
            try {
                String[] frameAndSize = value.split(":", -1);
                String[] dimensions = frameAndSize[1].toLowerCase().split("x", -1);
                if (frameAndSize.length != 2 || dimensions.length != 2) {
                    throw new IllegalArgumentException();
                }
                int frame = Integer.parseInt(frameAndSize[0]);
                int width = Integer.parseInt(dimensions[0]);
                int height = Integer.parseInt(dimensions[1]);
                if (frame < 0 || width <= 0 || height <= 0) {
                    throw new IllegalArgumentException();
                }
                return new ResizeSpec(frame, width, height);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException(
                        "--resize must use <frame>:<positive-width>x<positive-height>", invalid);
            }
        }

        void verify(GlfwWindow window, RenderPipeline pipeline) {
            if (window.width() != width || window.height() != height
                    || pipeline.graph().width() != width || pipeline.graph().height() != height) {
                throw new IllegalStateException("Deterministic resize did not reach " + width + "x" + height);
            }
        }
    }
}
