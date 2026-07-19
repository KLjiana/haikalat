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
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.runtime.diagnostics.DiagnosticsJsonExporter;
import com.kaleblangley.haikalat.runtime.diagnostics.DiagnosticsLevel;
import com.kaleblangley.haikalat.runtime.diagnostics.DiagnosticsSnapshot;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.InstancedRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.ui.UiFrameStats;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.Key;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;

import java.util.Map;
import java.nio.file.Path;
import java.time.Duration;

public final class LearnOpenGlDemo {
    private static volatile BenchmarkResult lastBenchmarkResult;
    private static volatile LearnOpenGlOverlay.Result lastOverlayResult;

    private LearnOpenGlDemo() {
    }

    public static void main(String[] args) {
        lastBenchmarkResult = null;
        lastOverlayResult = null;
        DemoOptions options = DemoOptions.parse(args);
        demoVertexPacking();

        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(options.antiAliasingMode())
                .toneMappingMode(options.toneMappingMode())
                .exposure(1.0f)
                .exposureMode(options.autoExposure() ? ExposureMode.AUTO : ExposureMode.MANUAL)
                .bloomSettings(BloomSettings.builder().enabled(options.bloom()).build())
                .vsync(!options.deterministic())
                .build();
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(options.size().width(), options.size().height())
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
            if (options.diagnosticsPanel() && !lastOverlayResult().diagnosticsVisible()) {
                throw new IllegalStateException("Diagnostics panel was not visible during integration");
            }
            if (options.verifyDiagnosticsCleanup()
                    && !GlDebug.resources().liveResources().isEmpty()) {
                throw new IllegalStateException("Tracked GL resources leaked after Demo close: "
                        + GlDebug.resources().liveResources());
            }
        }
    }

    private static void run(GlfwWindow window, RenderSettings settings, DemoOptions options) {
        Camera camera = new Camera(new Vector3f(0, 0, 5));
        ResourceLocator assets = ResourceLocator.classpath(LearnOpenGlDemo.class);
        SceneAssetConfig config = SceneAssetConfig.load(assets, "/demo/learnopengl.properties");
        try (FrameDriver renderLoop = new FrameDriver(settings, options.diagnosticsLevel());
             DemoSceneResources resources = DemoSceneResources.load(assets, config);
             DemoGltfResources gltfResources = DemoGltfResources.load(assets, config);
             PbrEnvironment environment = PbrEnvironmentLoader.load(renderLoop.device(), LearnOpenGlDemo.class,
                     "/pbr/studio-small.hdr", options.deterministic()
                             ? PbrEnvironmentSettings.testQuality()
                             : PbrEnvironmentSettings.defaultQuality())) {
            Scene scene = buildScene(camera, config, resources, gltfResources);
            Mesh instancedMesh = resources.meshes("builtin:" + BuiltinMeshData.QUAD,
                    com.kaleblangley.haikalat.core.assets.MaterialModel.LEGACY).getFirst();
            ShaderProgram instancedShader = resources.shader("instanced");
            InstancedMeshBatch batch = InstancedMeshBatch.of(instancedMesh,
                    options.instances(), BuiltinMeshData.INSTANCE_ATTRIBUTE_BASE);
            InstancedRenderer instanced = new InstancedRenderer(
                    batch, instancedShader, options.instanceShadows());
            addInstances(instanced, options.instances());

            RenderPipeline pipeline = new RenderPipeline(window, scene, instanced, settings, environment);
            try {
                pipeline.build();
                try (LearnOpenGlOverlay overlay = LearnOpenGlOverlay.attach(
                        window, pipeline, settings, renderLoop.diagnostics(),
                        options.diagnosticsExport() == null
                                ? Path.of("build", "diagnostics", "learnopengl.json")
                                : options.diagnosticsExport(), options.diagnosticsPanel())) {
                    renderFrames(window, camera, renderLoop, pipeline, instanced,
                            settings, options, overlay);
                }
            } finally {
                pipeline.close();
                instanced.close();
            }
        }
    }

    private static void renderFrames(GlfwWindow window, Camera camera, FrameDriver renderLoop,
                                     RenderPipeline pipeline, InstancedRenderer instanced,
                                     RenderSettings settings, DemoOptions options,
                                     LearnOpenGlOverlay uiOverlay) {
        int frame = 0;
        FrameClock clock = new FrameClock();
        PeriodicTimer titleUpdate = new PeriodicTimer(Duration.ofMillis(250));
        FrameBenchmarkSession benchmark = new FrameBenchmarkSession(
                options.maxFrames(), options.warmupFrames());
        PassBenchmarkAccumulator passBenchmark = new PassBenchmarkAccumulator(
                Math.max(0, options.maxFrames()));
        long measuredAllocationBytes = 0L;
        long measuredAllocationFrames = 0L;
        while (!window.shouldClose()) {
            long allocatedBefore = options.measureAllocation()
                    ? DemoAllocationCounter.currentThreadBytes() : -1L;
            if (options.resize() != null && frame == options.resize().frame()) {
                window.resize(options.resize().width(), options.resize().height());
            }

            FrameClock.Tick time = clock.tick();
            WindowInputSnapshot input = window.inputSnapshot();
            DebugOverlaySnapshot previousFrame = DebugOverlaySnapshot.from(
                    renderLoop.statistics(), instanced.statistics().drawCalls(),
                    instanced.drawnCount(), settings.antiAliasingMode());
            uiOverlay.update(input, time.deltaSeconds(), previousFrame, frame,
                    options.deterministic());
            if (input.keyPressed(Key.ESCAPE)) window.requestClose();
            if (uiOverlay.consumeCameraInputPermission()) {
                DemoSupport.updateFreeCamera(window, camera, time.deltaSeconds());
            }
            if (window.consumeResize()) {
                pipeline.resize(window.width(), window.height());
            }

            renderLoop.beginFrame();
            try {
                updateAutoExposureIntegrationScene(pipeline.scene(), options, frame);
                instanced.beginFrame(frame);
                pipeline.execute(renderLoop.device(), time.deltaSeconds());
                var instanceStats = instanced.statistics();
                renderLoop.recordSceneStatistics(
                        pipeline.lastVisibilityStatistics().forwardVisible() + instanceStats.drawCalls()
                                + pipeline.lastShadowCasterDrawCount(),
                    instanced.drawnCount(), pipeline.scene().renderers().size(), 1L);
                var visibility = pipeline.lastVisibilityStatistics();
                if (visibility.available()) {
                    renderLoop.recordSceneVisibility(new DiagnosticsSnapshot.VisibilitySummary(
                            visibility.cullingEnabled(), visibility.sceneRevision(),
                            visibility.candidateRenderers(), visibility.finiteBoundsRenderers(),
                            visibility.unboundedRenderers(), visibility.forwardVisible(),
                            visibility.forwardCulled(), visibility.shadowCandidates(),
                            visibility.shadowVisible(), visibility.shadowCulled(),
                            visibility.staticRenderers(), visibility.dynamicRenderers(),
                            visibility.modelCacheHits(), visibility.modelCacheMisses(),
                            visibility.boundsCacheHits(), visibility.boundsCacheMisses(),
                            visibility.forwardQueueReused(), visibility.forwardQueueRebuilt(),
                            visibility.shadowQueueReused(), visibility.shadowQueueRebuilt(),
                            visibility.modelUpdateNanos(), visibility.boundsTransformNanos(),
                            visibility.frustumTestNanos(), visibility.queueSortNanos(),
                            visibility.totalQueueBuildNanos(), visibility.opaqueDraws(),
                            visibility.additiveDraws(), visibility.alphaDraws(),
                            visibility.shaderChanges(), visibility.materialChanges(),
                            visibility.meshChanges(), visibility.blendChanges(),
                            visibility.mirroredChanges(), visibility.commandRecordNanos(),
                            visibility.recordedCommands(), visibility.recordedMatrixSnapshots(),
                            visibility.recordedObjectPayloads()));
                }
                UiFrameStats uiStats = uiOverlay.statistics();
                renderLoop.recordUiStatistics(uiStats.visibleNodes(), uiStats.quads(), uiStats.glyphs(),
                        uiStats.drawCalls(), uiStats.uiUpdateNanos(), uiStats.vertexBytes(),
                        uiStats.indexBytes(), uiStats.atlasUploadBytes());
                renderLoop.recordGraph(pipeline.graph());
                renderLoop.endFrame();
            } catch (RuntimeException | Error failure) {
                try {
                    renderLoop.failFrame(pipeline.graph(), failure);
                } catch (RuntimeException | Error diagnosticsFailure) {
                    failure.addSuppressed(diagnosticsFailure);
                }
                throw failure;
            }
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
                long allocatedAfter = DemoAllocationCounter.currentThreadBytes();
                if (allocatedBefore >= 0L && allocatedAfter >= allocatedBefore) {
                    measuredAllocationBytes += allocatedAfter - allocatedBefore;
                    measuredAllocationFrames++;
                }
            }

            if (titleUpdate.poll()) {
                DebugOverlaySnapshot overlay = DebugOverlaySnapshot.from(
                        renderLoop.statistics(), instanced.statistics().drawCalls(),
                        instanced.drawnCount(), settings.antiAliasingMode());
                window.setTitle(String.format("LearnOpenGL | FPS %.1f | CPU %.3f ms | GPU %.2f ms | draw %d | inst %d | AA %s | exposure %s | input %s",
                        overlay.fps(), overlay.cpuSubmitMillis(), overlay.gpuMillis(),
                        overlay.drawCalls(), overlay.instanceCount(), overlay.activeAntiAliasingMode(),
                        settings.exposureMode(), uiOverlay.inputModeName()));
            }

            GlDebug.checkError("LearnOpenGlDemo.frame");
            frame++;
            if (benchmark.isComplete()) {
                window.requestClose();
            }
        }
        lastOverlayResult = uiOverlay.result(pipeline.graph().lastFrameProfile());
        if (options.diagnosticsExport() != null) {
            try {
                DiagnosticsJsonExporter.export(renderLoop.diagnostics().freeze(),
                        options.diagnosticsExport());
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Failed to export diagnostics", failure);
            }
        }
        if (options.maxFrames() > 0) {
            printBenchmark(renderLoop, pipeline, instanced, settings, options,
                    benchmark.snapshot(), passBenchmark,
                    measuredAllocationFrames == 0L ? -1.0
                            : measuredAllocationBytes / (double) measuredAllocationFrames);
        }
    }

    private static Scene buildScene(Camera camera, SceneAssetConfig config, DemoSceneResources resources,
                                    DemoGltfResources gltfResources) {
        Scene scene = new Scene(camera);
        for (Map.Entry<String, SceneAssetConfig.ObjectDef> entry : config.objects().entrySet()) {
            SceneAssetConfig.ObjectDef def = entry.getValue();
            Material material = resources.material(def.material());
            addModelMeshes(scene, resources.meshes(def, config), material,
                    updaterFor(entry.getKey(), def), def.castShadows());
        }
        for (SceneObject object : gltfResources.objects()) scene.add(object);
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
                                       PassBenchmarkAccumulator passBenchmark,
                                       double allocatedBytesPerFrame) {
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
                        + " exposure=" + settings.exposureMode()
                        + " bloom=" + settings.bloomSettings().enabled()
                        + " instances=" + options.instances()
                        + " shadows=" + options.instanceShadows(),
                benchmark.presentFps(), timings.averageCpuMillis(), timings.medianCpuMillis(),
                timings.averageGpuMillis(), timings.medianGpuMillis(), uploadPerFrameMb,
                buffer.fenceWaitMillis(), buffer.fenceWaitCount(), benchmark.measuredFrames(),
                stateSkip, pipeline.scene().renderers().size(), allocatedBytesPerFrame, passTimings);
        if (options.quiet()) {
            return;
        }
        String scope = options.warmupFrames() >= 100 && benchmark.measuredFrames() >= 1000
                ? "FORMAL" : "INTEGRATION-ONLY";
        System.out.printf("LearnOpenGL [%s] %s/%s exposure=%s bloom=%s | instances %,d | shadows=%s | "
                        + "present FPS %.1f | CPU avg/median %.3f/%.3f ms | "
                        + "GPU avg/median %.3f/%.3f ms | upload %.3f MB/frame | "
                        + "ring wait %.3f ms/%d | state skip %.1f%%%n",
                scope, settings.toneMappingMode(), settings.antiAliasingMode(), settings.exposureMode(),
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

    static LearnOpenGlOverlay.Result lastOverlayResult() {
        LearnOpenGlOverlay.Result result = lastOverlayResult;
        if (result == null) {
            throw new IllegalStateException("LearnOpenGlDemo UI overlay has not completed a frame");
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

    private static void updateAutoExposureIntegrationScene(Scene scene, DemoOptions options, int frame) {
        if (!options.autoExposureCycle() || (frame != 3 && frame != 7)) {
            return;
        }
        float scale = frame == 3 ? 8.0f : 0.01f;
        java.util.List<SceneLight> lights = scene.lights();
        for (int index = 0; index < lights.size(); index++) {
            SceneLight light = lights.get(index);
            scene.setLight(index, new SceneLight(light.type(), light.color(), light.intensity() * scale,
                    light.direction(), light.position(), light.range(), light.innerConeRadians(),
                    light.outerConeRadians(), light.castShadows()));
        }
    }

    record DemoOptions(boolean deterministic, int maxFrames,
                               AntiAliasingMode antiAliasingMode, ResizeSpec resize, boolean bloom,
                               ToneMappingMode toneMappingMode, int instances,
                               boolean instanceShadows, int warmupFrames, boolean quiet,
                               boolean autoExposure, boolean autoExposureCycle, SizeSpec size,
                               DiagnosticsLevel diagnosticsLevel, Path diagnosticsExport,
                               boolean diagnosticsPanel, boolean verifyDiagnosticsCleanup,
                               boolean measureAllocation) {
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
            boolean autoExposure = false;
            boolean autoExposureCycle = false;
            SizeSpec size = new SizeSpec(DemoSupport.DEFAULT_WIDTH, DemoSupport.DEFAULT_HEIGHT);
            DiagnosticsLevel diagnosticsLevel = DiagnosticsLevel.BASIC;
            Path diagnosticsExport = null;
            boolean diagnosticsPanel = false;
            boolean verifyDiagnosticsCleanup = false;
            boolean measureAllocation = false;
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
                } else if ("--auto-exposure".equals(arg)) {
                    autoExposure = true;
                } else if ("--auto-exposure-cycle".equals(arg)) {
                    autoExposure = true;
                    autoExposureCycle = true;
                } else if (arg.startsWith("--size=")) {
                    size = SizeSpec.parse(arg.substring("--size=".length()));
                } else if (arg.startsWith("--diagnostics=")) {
                    diagnosticsLevel = DiagnosticsLevel.valueOf(
                            arg.substring("--diagnostics=".length()).toUpperCase());
                } else if (arg.startsWith("--diagnostics-export=")) {
                    diagnosticsExport = diagnosticsExportPath(
                            arg.substring("--diagnostics-export=".length()));
                } else if ("--diagnostics-panel".equals(arg)) {
                    diagnosticsPanel = true;
                } else if ("--diagnostics-verify-cleanup".equals(arg)) {
                    verifyDiagnosticsCleanup = true;
                } else if ("--measure-allocation".equals(arg)) {
                    measureAllocation = true;
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
            if (autoExposure && toneMappingMode == ToneMappingMode.NONE) {
                throw new IllegalArgumentException("--auto-exposure requires --tone=ACES");
            }
            return new DemoOptions(deterministic, maxFrames, mode, resize, bloom,
                    toneMappingMode, instances, instanceShadows, warmupFrames, quiet,
                    autoExposure, autoExposureCycle, size, diagnosticsLevel, diagnosticsExport,
                    diagnosticsPanel, verifyDiagnosticsCleanup, measureAllocation);
        }

        private static Path diagnosticsExportPath(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("--diagnostics-export path must not be blank");
            }
            Path root = Path.of("build", "diagnostics").toAbsolutePath().normalize();
            Path candidate = Path.of(value).toAbsolutePath().normalize();
            if (!candidate.startsWith(root) || candidate.equals(root)) {
                throw new IllegalArgumentException(
                        "--diagnostics-export must be a file inside " + root);
            }
            Path cursor = root;
            if (java.nio.file.Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException("diagnostics export root must not be a symbolic link");
            }
            Path parent = candidate.getParent();
            if (parent != null) {
                for (Path segment : root.relativize(parent)) {
                    cursor = cursor.resolve(segment);
                    if (java.nio.file.Files.isSymbolicLink(cursor)) {
                        throw new IllegalArgumentException(
                                "diagnostics export path must not traverse a symbolic link: " + cursor);
                    }
                }
            }
            return candidate;
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
            int ordinarySceneDraws,
            double allocatedBytesPerFrame,
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

    private record SizeSpec(int width, int height) {
        static SizeSpec parse(String value) {
            try {
                String[] dimensions = value.toLowerCase().split("x", -1);
                if (dimensions.length != 2) {
                    throw new IllegalArgumentException();
                }
                int width = Integer.parseInt(dimensions[0]);
                int height = Integer.parseInt(dimensions[1]);
                if (width <= 0 || height <= 0 || width > 7680 || height > 4320) {
                    throw new IllegalArgumentException();
                }
                return new SizeSpec(width, height);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("--size must use positive WIDTHxHEIGHT up to 7680x4320", invalid);
            }
        }
    }
}
