package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAssetLoader;
import com.kaleblangley.haikalat.core.assets.gltf.LoadedGltfScene;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.FrameBenchmarkSession;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.runtime.diagnostics.DiagnosticsJsonExporter;
import com.kaleblangley.haikalat.runtime.diagnostics.DiagnosticsLevel;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfRuntimeLibrary;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfSceneAsset;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import org.lwjgl.BufferUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 复用单份 GPU glTF 资产构造 100、1,000 或 10,000 个普通 renderer 的 CPU 提交基准。
 *
 * <p>该入口只负责场景配置、运行和统计。资源解析、GPU 上传及固定 node transform
 * 实例化继续由正式 glTF API 完成，扩容不会复制 mesh、texture、sampler 或 material。</p>
 */
public final class GltfSceneScalabilityDemo {
    private static final String DEFAULT_SCENE = "/gltf/scalability.gltf";
    private static volatile BenchmarkResult lastBenchmarkResult;

    private GltfSceneScalabilityDemo() {
    }

    public static void main(String[] arguments) {
        lastBenchmarkResult = null;
        Options options = Options.parse(arguments);
        System.setProperty("haikalat.scene.staticCache", Boolean.toString(options.staticCache()));
        System.setProperty("haikalat.scene.queueCache", Boolean.toString(options.queueCache()));
        System.setProperty("haikalat.command.matrixArena", Boolean.toString(options.matrixArena()));

        RenderSettings settings = RenderSettings.builder()
                .vsync(false)
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .bloomSettings(BloomSettings.builder().enabled(false).build())
                .sceneVisibility(options.visibility())
                .build();
        DiagnosticsLevel level = options.export() == null
                ? DiagnosticsLevel.OFF : DiagnosticsLevel.DETAILED;

        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(options.width(), options.height())
                .title("Haikalat glTF Scene Scalability")
                .visible(!options.deterministic())
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(false);

            ResourceLocator resources = ResourceLocator.classpath(GltfSceneScalabilityDemo.class);
            LoadedGltfScene loaded = new GltfAssetLoader(resources)
                    .load(AssetRef.of(options.scene()));
            try (FrameDriver driver = new FrameDriver(settings, level);
                 PbrEnvironment environment = PbrEnvironmentLoader.load(driver.device(),
                         GltfSceneScalabilityDemo.class, "/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality());
                 GltfRuntimeLibrary library = GltfRuntimeLibrary.create();
                 GltfSceneAsset asset = GltfSceneAsset.upload(loaded, library)) {
                Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 6.0f));
                Scene scene = buildScene(asset, camera, options);
                RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings, environment);
                try {
                    pipeline.build();
                    for (int round = 1; round <= options.rounds(); round++) {
                        lastBenchmarkResult = runRound(window, driver, pipeline, camera, asset,
                                options, round);
                    }
                    if (options.export() != null) {
                        DiagnosticsJsonExporter.export(driver.diagnostics().freeze(), options.export());
                    }
                } catch (IOException failure) {
                    throw new IllegalStateException("无法导出 glTF scalability diagnostics", failure);
                } finally {
                    pipeline.close();
                }
            }
        }
    }

    static BenchmarkResult lastBenchmarkResult() {
        BenchmarkResult result = lastBenchmarkResult;
        if (result == null) throw new IllegalStateException("glTF scalability 基准尚未完成");
        return result;
    }

    private static Scene buildScene(GltfSceneAsset asset, Camera camera, Options options) {
        Scene scene = new Scene(camera);
        scene.addLight(SceneLight.directional(new Vector3f(-0.35f, -1.0f, -0.55f),
                new Vector3f(1.0f, 0.94f, 0.84f), 3.0f));
        int renderersPerRoot = asset.instantiate(new Matrix4f(), false).size();
        int visibleRenderers = options.layout().visibleRenderers(options.renderers());
        int visibleRoots = (visibleRenderers + renderersPerRoot - 1) / renderersPerRoot;
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(visibleRoots
                * options.width() / (double) Math.max(1, options.height()))));
        int rows = Math.max(1, (visibleRoots + columns - 1) / columns);
        float spacingX = Math.min(0.75f, 5.2f / Math.max(1, columns));
        float spacingY = Math.min(0.75f, 3.0f / Math.max(1, rows));
        float scale = Math.min(0.28f, Math.min(spacingX, spacingY) * 0.32f);
        int added = 0;
        for (int root = 0; added < visibleRenderers; root++) {
            float x = (root % columns - (columns - 1) * 0.5f) * spacingX;
            float y = (root / columns - (rows - 1) * 0.5f) * spacingY;
            Matrix4f rootTransform = new Matrix4f().translation(x, y, 0.0f).scale(scale);
            List<SceneObject> objects = asset.instantiate(rootTransform, false);
            for (SceneObject object : objects) {
                if (added >= visibleRenderers) break;
                scene.add(object);
                added++;
            }
        }
        int hiddenRoot = 0;
        while (added < options.renderers()) {
            float x = 200.0f + hiddenRoot % 31;
            float y = (hiddenRoot / 31) % 17 - 8.0f;
            List<SceneObject> objects = asset.instantiate(
                    new Matrix4f().translation(x, y, 0.0f).scale(scale), false);
            for (SceneObject object : objects) {
                if (added >= options.renderers()) break;
                scene.add(object);
                added++;
            }
            hiddenRoot++;
        }
        if (added != options.renderers()) {
            throw new IllegalStateException("glTF renderer 扩容数量错误: " + added);
        }
        return scene;
    }

    private static BenchmarkResult runRound(GlfwWindow window, FrameDriver driver,
                                            RenderPipeline pipeline, Camera camera,
                                            GltfSceneAsset asset, Options options, int round) {
        FrameBenchmarkSession benchmark = new FrameBenchmarkSession(options.frames(), options.warmup());
        int totalFrames = Math.addExact(options.warmup(), options.frames());
        long allocationBytes = 0L;
        long allocationFrames = 0L;
        long queueNanos = 0L;
        long commandNanos = 0L;
        RenderPipeline.VisibilityStatistics last = RenderPipeline.VisibilityStatistics.UNAVAILABLE;
        boolean observedQueueReuse = false;
        boolean observedQueueRebuild = false;
        for (int frame = 0; frame < totalFrames && !window.shouldClose(); frame++) {
            if (options.resizeFrame() == frame) {
                window.resize(options.resizeWidth(), options.resizeHeight());
                window.pollEvents();
            }
            if (window.consumeResize()) pipeline.resize(window.width(), window.height());
            if (options.layout() == Layout.CAMERA_MOTION) {
                camera.setPosition(new Vector3f((float) Math.sin(frame * 0.017f) * 0.35f,
                        0.0f, 6.0f));
            }

            boolean measured = frame >= options.warmup();
            long allocatedBefore = measured ? DemoAllocationCounter.currentThreadBytes() : -1L;
            driver.beginFrame();
            try {
                pipeline.execute(driver.device(), 1.0f / 60.0f);
                last = pipeline.lastVisibilityStatistics();
                driver.recordGraph(pipeline.graph());
                driver.recordSceneStatistics(last.forwardVisible(), 0L,
                        last.candidateRenderers(), 0L);
                driver.recordSceneVisibility(SceneScalabilityDemo.toDiagnostics(last));
                driver.endFrame();
            } catch (RuntimeException | Error failure) {
                driver.failFrame(pipeline.graph(), failure);
                throw failure;
            }
            if (options.verify() && frame == 0) verifyNonEmptyFramebuffer(window, last);
            driver.present(window::swapBuffers);
            window.pollEvents();
            benchmark.recordFrame(driver, pipeline.graph().lastFrameProfile().totalGpuNanos());
            observedQueueReuse |= last.forwardQueueReused();
            observedQueueRebuild |= last.forwardQueueRebuilt();
            if (measured) {
                queueNanos = Math.addExact(queueNanos, last.totalQueueBuildNanos());
                commandNanos = Math.addExact(commandNanos, last.commandRecordNanos());
                long allocatedAfter = DemoAllocationCounter.currentThreadBytes();
                if (allocatedBefore >= 0L && allocatedAfter >= allocatedBefore) {
                    allocationBytes = Math.addExact(allocationBytes, allocatedAfter - allocatedBefore);
                    allocationFrames++;
                }
            }
        }

        double allocationKiB = allocationFrames == 0 ? Double.NaN
                : allocationBytes / (double) allocationFrames / 1024.0;
        verify(options, last, allocationKiB, observedQueueReuse, observedQueueRebuild);
        FrameBenchmarkSession.Snapshot snapshot = benchmark.snapshot();
        StateCache.Statistics state = driver.stateStatistics();
        long stateTotal = state.appliedChanges() + state.avoidedChanges();
        double skipPercent = stateTotal == 0 ? 100.0 : state.avoidedChanges() * 100.0 / stateTotal;
        double samples = Math.max(1, snapshot.measuredFrames());
        BenchmarkResult result = new BenchmarkResult(options.renderers(), options.layout().option,
                last.forwardVisible(), last.forwardCulled(), snapshot.presentFps(),
                snapshot.timings().averageCpuMillis(), snapshot.timings().medianCpuMillis(),
                snapshot.timings().averageGpuMillis(), snapshot.timings().medianGpuMillis(),
                queueNanos / samples / 1_000_000.0,
                commandNanos / samples / 1_000_000.0, allocationKiB, skipPercent);
        System.out.printf(Locale.ROOT,
                "GltfSceneScalability round=%d renderers=%d layout=%s visibility=%s "
                        + "visible/culled=%d/%d unique(mesh/material/texture/sampler)=%d/%d/%d/%d "
                        + "estimatedGPU=%.3fMiB draws=%d FPS=%.1f "
                        + "CPU(avg/median)=%.3f/%.3fms GPU(avg/median)=%.3f/%.3fms "
                        + "queue/command=%.3f/%.3fms cache(model/bounds)=%d/%d "
                        + "queueReuse=%s commands/matrices/objects=%d/%d/%d "
                        + "allocation=%.1fKiB/frame stateSkip=%.2f%%%n",
                round, options.renderers(), options.layout().option,
                options.visibility() ? "enabled" : "disabled", last.forwardVisible(),
                last.forwardCulled(), asset.uniqueMeshCount(), asset.uniqueMaterialCount(),
                asset.uniqueTextureCount(), asset.uniqueSamplerCount(),
                asset.estimatedGpuBytes() / 1048576.0, last.forwardVisible(), result.presentFps(),
                result.averageCpuMillis(), result.medianCpuMillis(), result.averageGpuMillis(),
                result.medianGpuMillis(), result.averageQueueMillis(),
                result.averageCommandMillis(), last.modelCacheHits(), last.boundsCacheHits(),
                last.forwardQueueReused(), last.recordedCommands(),
                last.recordedMatrixSnapshots(), last.recordedObjectPayloads(),
                allocationKiB, skipPercent);
        GlDebug.assertNoError("GltfSceneScalabilityDemo.round");
        return result;
    }

    private static void verifyNonEmptyFramebuffer(GlfwWindow window,
                                                   RenderPipeline.VisibilityStatistics stats) {
        if (stats.forwardVisible() <= 0) {
            throw new IllegalStateException("像素验证要求至少一个可见 glTF renderer");
        }
        int byteCount = Math.multiplyExact(Math.multiplyExact(window.width(), window.height()), 4);
        ByteBuffer pixels = BufferUtils.createByteBuffer(byteCount);
        org.lwjgl.opengl.GL11.glReadPixels(0, 0, window.width(), window.height(),
                org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, pixels);
        int minimum = 255;
        int maximum = 0;
        for (int offset = 0; offset < byteCount; offset += 4) {
            int red = Byte.toUnsignedInt(pixels.get(offset));
            int green = Byte.toUnsignedInt(pixels.get(offset + 1));
            int blue = Byte.toUnsignedInt(pixels.get(offset + 2));
            minimum = Math.min(minimum, Math.min(red, Math.min(green, blue)));
            maximum = Math.max(maximum, Math.max(red, Math.max(green, blue)));
        }
        if (maximum - minimum < 2) {
            throw new IllegalStateException("真实 glTF 路径没有产生可辨识的非空像素");
        }
    }

    private static void verify(Options options, RenderPipeline.VisibilityStatistics stats,
                               double allocationKiB, boolean observedReuse,
                               boolean observedRebuild) {
        if (!options.verify()) return;
        int expectedVisible = options.visibility()
                ? options.layout().visibleRenderers(options.renderers()) : options.renderers();
        if (!stats.available() || stats.candidateRenderers() != options.renderers()
                || stats.forwardVisible() != expectedVisible
                || stats.forwardCulled() != options.renderers() - expectedVisible) {
            throw new IllegalStateException("glTF visibility 不变量失败: " + stats
                    + ", expectedVisible=" + expectedVisible);
        }
        if (options.staticCache() && stats.modelCacheHits() != options.renderers()) {
            throw new IllegalStateException("稳定帧未全部命中 static model cache: " + stats);
        }
        if (options.queueCache() && options.layout() != Layout.CAMERA_MOTION && !observedReuse) {
            throw new IllegalStateException("稳定场景未观察到 forward queue reuse");
        }
        if (options.layout() == Layout.CAMERA_MOTION && !observedRebuild) {
            throw new IllegalStateException("camera-motion 未观察到 forward queue rebuild");
        }
        if (options.renderers() == 10_000 && Double.isFinite(allocationKiB)) {
            double limit = options.layout() == Layout.MOSTLY_HIDDEN ? 128.0 : 512.0;
            if (allocationKiB > limit) {
                throw new IllegalStateException("allocation gate 超限: " + allocationKiB
                        + " KiB/frame > " + limit);
            }
        }
    }

    record BenchmarkResult(int renderers, String layout, int visible, int culled,
                           double presentFps, double averageCpuMillis, double medianCpuMillis,
                           double averageGpuMillis, double medianGpuMillis,
                           double averageQueueMillis, double averageCommandMillis,
                           double allocationKiBPerFrame, double stateSkipPercent) {
    }

    enum Layout {
        ALL_VISIBLE("all-visible", 1.0), MIXED("mixed", 0.5),
        MOSTLY_HIDDEN("mostly-hidden", 0.1), CAMERA_MOTION("camera-motion", 0.5);

        private final String option;
        private final double ratio;

        Layout(String option, double ratio) {
            this.option = option;
            this.ratio = ratio;
        }

        int visibleRenderers(int total) {
            return (int) Math.round(total * ratio);
        }

        static Layout parse(String value) {
            for (Layout layout : values()) if (layout.option.equals(value)) return layout;
            throw new IllegalArgumentException("未知 glTF scalability layout: " + value);
        }
    }

    record Options(String scene, int renderers, Layout layout, boolean visibility,
                   boolean staticCache, boolean queueCache, boolean matrixArena,
                   int frames, int warmup, int rounds, int width, int height,
                   int resizeFrame, int resizeWidth, int resizeHeight,
                   boolean deterministic, boolean verify, Path export) {
        static Options parse(String[] arguments) {
            String scene = DEFAULT_SCENE;
            int renderers = 1_000;
            Layout layout = Layout.MIXED;
            boolean visibility = true;
            boolean staticCache = true;
            boolean queueCache = true;
            boolean matrixArena = true;
            int frames = 1_000;
            int warmup = 100;
            int rounds = 1;
            int width = 1280;
            int height = 720;
            int resizeFrame = -1;
            int resizeWidth = 0;
            int resizeHeight = 0;
            boolean deterministic = false;
            boolean verify = false;
            Path export = null;
            for (String argument : arguments) {
                if (argument.startsWith("--scene=")) scene = value(argument);
                else if (argument.startsWith("--renderers=")) renderers = positive(argument);
                else if (argument.startsWith("--layout=")) layout = Layout.parse(value(argument));
                else if (argument.equals("--visibility=enabled")) visibility = true;
                else if (argument.equals("--visibility=disabled")) visibility = false;
                else if (argument.equals("--static-cache=enabled")) staticCache = true;
                else if (argument.equals("--static-cache=disabled")) staticCache = false;
                else if (argument.equals("--queue-cache=enabled")) queueCache = true;
                else if (argument.equals("--queue-cache=disabled")) queueCache = false;
                else if (argument.equals("--command-matrix-arena=enabled")) matrixArena = true;
                else if (argument.equals("--command-matrix-arena=disabled")) matrixArena = false;
                else if (argument.startsWith("--frames=")) frames = positive(argument);
                else if (argument.startsWith("--warmup=")) warmup = nonNegative(argument);
                else if (argument.startsWith("--rounds=")) rounds = positive(argument);
                else if (argument.startsWith("--size=")) {
                    int[] size = size(value(argument)); width = size[0]; height = size[1];
                } else if (argument.startsWith("--resize=")) {
                    String[] parts = value(argument).split(":", -1);
                    if (parts.length != 2) throw new IllegalArgumentException("--resize 需要 frame:WxH");
                    resizeFrame = Integer.parseInt(parts[0]);
                    if (resizeFrame < 0) throw new IllegalArgumentException("resize frame 不能为负数");
                    int[] size = size(parts[1]); resizeWidth = size[0]; resizeHeight = size[1];
                } else if (argument.equals("--deterministic")) deterministic = true;
                else if (argument.equals("--verify")) verify = true;
                else if (argument.startsWith("--diagnostics-export=")) export = safeExport(value(argument));
                else throw new IllegalArgumentException("未知 GltfSceneScalabilityDemo 参数: " + argument);
            }
            if (renderers != 100 && renderers != 1_000 && renderers != 10_000) {
                throw new IllegalArgumentException("--renderers 只接受 100、1000 或 10000");
            }
            return new Options(scene, renderers, layout, visibility, staticCache, queueCache,
                    matrixArena, frames, warmup, rounds, width, height, resizeFrame, resizeWidth,
                    resizeHeight, deterministic, verify, export);
        }

        private static String value(String argument) {
            return argument.substring(argument.indexOf('=') + 1);
        }

        private static int positive(String argument) {
            int value = Integer.parseInt(value(argument));
            if (value <= 0) throw new IllegalArgumentException(argument + " 必须为正数");
            return value;
        }

        private static int nonNegative(String argument) {
            int value = Integer.parseInt(value(argument));
            if (value < 0) throw new IllegalArgumentException(argument + " 不能为负数");
            return value;
        }

        private static int[] size(String value) {
            String[] parts = value.toLowerCase(Locale.ROOT).split("x", -1);
            if (parts.length != 2) throw new IllegalArgumentException("窗口尺寸必须为 WxH");
            int width = Integer.parseInt(parts[0]);
            int height = Integer.parseInt(parts[1]);
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("窗口尺寸必须为正数");
            return new int[]{width, height};
        }

        private static Path safeExport(String value) {
            Path root = Path.of("build", "diagnostics").toAbsolutePath().normalize();
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (!path.startsWith(root)) {
                throw new IllegalArgumentException("诊断导出路径必须位于 build/diagnostics 内");
            }
            return path;
        }
    }
}
