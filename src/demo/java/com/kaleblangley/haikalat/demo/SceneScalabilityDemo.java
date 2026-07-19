package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.Bounds3f;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import com.kaleblangley.haikalat.runtime.FrameBenchmarkSession;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.diagnostics.DiagnosticsJsonExporter;
import com.kaleblangley.haikalat.runtime.diagnostics.DiagnosticsLevel;
import com.kaleblangley.haikalat.runtime.diagnostics.DiagnosticsSnapshot;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 使用普通 {@link SceneObject} 构建确定性的多 mesh/material 可见性压力场景。
 * 该 Demo 刻意不使用实例化绘制，用来测量 scene traversal、裁剪和队列排序本身。
 */
public final class SceneScalabilityDemo {
    private static final String VERTEX_SHADER = """
            #version 330 core
            layout (location = 0) in vec3 aPosition;
            layout (std140) uniform CameraBlock { mat4 uProjection; mat4 uView; };
            uniform mat4 uModel;
            void main() {
                gl_Position = uProjection * uView * uModel * vec4(aPosition, 1.0);
            }
            """;
    private static final String FRAGMENT_SHADER = """
            #version 330 core
            uniform vec4 uColor;
            out vec4 fragmentColor;
            void main() { fragmentColor = uColor; }
            """;
    private static final int MATERIAL_COUNT = 16;
    private static volatile BenchmarkResult lastBenchmarkResult;

    private SceneScalabilityDemo() {
    }

    public static void main(String[] arguments) {
        lastBenchmarkResult = null;
        Options options = Options.parse(arguments);
        RenderSettings settings = RenderSettings.builder()
                .vsync(false)
                .sceneVisibility(options.visibilityEnabled())
                .build();
        DiagnosticsLevel diagnosticsLevel = options.diagnosticsExport() == null
                ? DiagnosticsLevel.OFF : DiagnosticsLevel.DETAILED;

        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(options.width(), options.height())
                .title("Haikalat Scene Scalability")
                .visible(!options.deterministic())
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(false);

            try (ShaderProgram shader = ShaderProgram.fromSources(VERTEX_SHADER, FRAGMENT_SHADER);
                 Resources resources = Resources.create(shader, options.layout());
                 FrameDriver driver = new FrameDriver(settings, diagnosticsLevel)) {
                long[] updaterCalls = {0L};
                Scene scene = createScene(resources, options, updaterCalls);
                RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings);
                try {
                    pipeline.build();
                    for (int round = 1; round <= options.rounds(); round++) {
                        lastBenchmarkResult = runRound(window, pipeline, driver, options,
                                updaterCalls, round);
                    }
                    if (options.diagnosticsExport() != null) {
                        DiagnosticsJsonExporter.export(driver.diagnostics().freeze(),
                                options.diagnosticsExport());
                    }
                } catch (IOException failure) {
                    throw new IllegalStateException("无法导出场景可见性诊断", failure);
                } finally {
                    pipeline.close();
                }
            }
        }
    }

    static BenchmarkResult lastBenchmarkResult() {
        BenchmarkResult result = lastBenchmarkResult;
        if (result == null) throw new IllegalStateException("SceneScalabilityDemo 尚未完成基准");
        return result;
    }

    private static BenchmarkResult runRound(GlfwWindow window, RenderPipeline pipeline,
                                            FrameDriver driver, Options options,
                                            long[] updaterCalls, int round) {
        FrameBenchmarkSession benchmark = new FrameBenchmarkSession(options.frames(), options.warmup());
        long allocationBytes = 0L;
        long allocationFrames = 0L;
        long queueNanos = 0L;
        long modelNanos = 0L;
        long boundsNanos = 0L;
        long frustumNanos = 0L;
        long sortNanos = 0L;
        long expectedCalls = updaterCalls[0];
        RenderPipeline.VisibilityStatistics last = RenderPipeline.VisibilityStatistics.UNAVAILABLE;

        int totalFrames = Math.addExact(options.warmup(), options.frames());
        for (int frame = 0; frame < totalFrames && !window.shouldClose(); frame++) {
            if (options.resizeFrame() == frame) {
                window.resize(options.resizeWidth(), options.resizeHeight());
                window.pollEvents();
            }
            if (window.consumeResize()) pipeline.resize(window.width(), window.height());

            boolean measured = frame >= options.warmup();
            long allocatedBefore = measured ? DemoAllocationCounter.currentThreadBytes() : -1L;
            driver.beginFrame();
            try {
                pipeline.execute(driver.device(), 1.0f / 60.0f);
                last = pipeline.lastVisibilityStatistics();
                driver.recordGraph(pipeline.graph());
                driver.recordSceneStatistics(last.forwardVisible() + last.shadowVisible(),
                        0L, last.candidateRenderers(), 0L);
                driver.recordSceneVisibility(toDiagnostics(last));
                driver.endFrame();
            } catch (RuntimeException | Error failure) {
                driver.failFrame(pipeline.graph(), failure);
                throw failure;
            }
            driver.present(window::swapBuffers);
            window.pollEvents();
            benchmark.recordFrame(driver, pipeline.graph().lastFrameProfile().totalGpuNanos());

            expectedCalls = Math.addExact(expectedCalls, options.objects());
            if (options.verify() && updaterCalls[0] != expectedCalls) {
                throw new IllegalStateException("updater 调用次数不是每对象每帧一次: expected="
                        + expectedCalls + ", actual=" + updaterCalls[0]);
            }
            if (measured) {
                queueNanos += last.totalQueueBuildNanos();
                modelNanos += last.modelUpdateNanos();
                boundsNanos += last.boundsTransformNanos();
                frustumNanos += last.frustumTestNanos();
                sortNanos += last.queueSortNanos();
                long allocatedAfter = DemoAllocationCounter.currentThreadBytes();
                if (allocatedBefore >= 0L && allocatedAfter >= allocatedBefore) {
                    allocationBytes += allocatedAfter - allocatedBefore;
                    allocationFrames++;
                }
            }
        }

        verifyStatistics(options, last);
        FrameBenchmarkSession.Snapshot snapshot = benchmark.snapshot();
        StateCache.Statistics state = driver.stateStatistics();
        double samples = Math.max(1, snapshot.measuredFrames());
        double skipRatio = (state.appliedChanges() + state.avoidedChanges()) == 0L ? 1.0
                : state.avoidedChanges() / (double) (state.appliedChanges() + state.avoidedChanges());
        double averageQueueMillis = queueNanos / samples / 1_000_000.0;
        double allocationKiB = allocationFrames == 0L ? Double.NaN
                : allocationBytes / (double) allocationFrames / 1024.0;
        BenchmarkResult result = new BenchmarkResult(options.objects(), options.layout().optionName,
                options.visibilityEnabled(), last.forwardVisible(), last.forwardCulled(),
                snapshot.presentFps(), snapshot.timings().averageCpuMillis(),
                snapshot.timings().medianCpuMillis(), snapshot.timings().averageGpuMillis(),
                snapshot.timings().medianGpuMillis(), averageQueueMillis, allocationKiB,
                skipRatio * 100.0);
        System.out.printf(Locale.ROOT,
                "SceneScalability round=%d objects=%d layout=%s visibility=%s visible=%d culled=%d "
                        + "draws=%d FPS=%.1f CPU(avg/median)=%.3f/%.3fms GPU(avg/median)=%.3f/%.3fms "
                        + "queue/model/bounds/frustum/sort=%.3f/%.3f/%.3f/%.3f/%.3fms "
                        + "allocation=%.1fKiB/frame stateSkip=%.2f%%%n",
                round, options.objects(), options.layout().optionName,
                options.visibilityEnabled() ? "enabled" : "disabled",
                last.forwardVisible(), last.forwardCulled(), last.forwardVisible(),
                snapshot.presentFps(), snapshot.timings().averageCpuMillis(),
                snapshot.timings().medianCpuMillis(), snapshot.timings().averageGpuMillis(),
                snapshot.timings().medianGpuMillis(), averageQueueMillis,
                modelNanos / samples / 1_000_000.0, boundsNanos / samples / 1_000_000.0,
                frustumNanos / samples / 1_000_000.0, sortNanos / samples / 1_000_000.0,
                allocationKiB,
                skipRatio * 100.0);
        GlDebug.assertNoError("SceneScalabilityDemo.round");
        return result;
    }

    record BenchmarkResult(int objects, String layout, boolean visibilityEnabled,
                           int visible, int culled, double presentFps,
                           double averageCpuMillis, double medianCpuMillis,
                           double averageGpuMillis, double medianGpuMillis,
                           double averageQueueMillis, double allocationKiBPerFrame,
                           double stateSkipPercent) {
    }

    private static Scene createScene(Resources resources, Options options, long[] updaterCalls) {
        Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
        int visible = options.layout().visibleCount(options.objects());
        int columns = (int) Math.ceil(Math.sqrt(Math.max(1, visible)
                * options.width() / (double) Math.max(1, options.height())));
        int rows = Math.max(1, (visible + columns - 1) / columns);
        float spacingX = Math.min(0.12f, 5.2f / Math.max(1, columns));
        float spacingY = Math.min(0.12f, 3.0f / Math.max(1, rows));
        float baseScale = Math.min(0.08f, Math.min(spacingX, spacingY) * 0.55f);

        for (int index = 0; index < options.objects(); index++) {
            boolean isVisible = index < visible;
            float x;
            float y;
            float z;
            if (isVisible) {
                int column = index % columns;
                int row = index / columns;
                x = (column - (columns - 1) * 0.5f) * spacingX;
                y = (row - (rows - 1) * 0.5f) * spacingY;
                z = -0.001f * (index & 7);
            } else {
                x = 250.0f + index % 97;
                y = ((index / 97) % 31) - 15.0f;
                z = -5.0f;
            }
            boolean mirrored = index % 31 == 0;
            float scaleX = mirrored ? -baseScale : baseScale;
            float scaleY = index % 17 == 0 ? baseScale * 1.6f : baseScale;
            float scaleZ = index % 23 == 0 ? baseScale * 0.7f : baseScale;
            boolean dynamic = index % 113 == 0;
            float phase = (index & 255) * 0.017f;
            Mesh mesh = options.layout() == Layout.ALL_HIDDEN && index == 0
                    ? resources.unboundedProbe() : resources.meshes().get(index & 3);
            Material material = resources.materials().get(index & (MATERIAL_COUNT - 1));
            scene.add(new SceneObject(mesh, material, (model, frame) -> {
                updaterCalls[0]++;
                model.translation(x, y, z);
                if (dynamic) model.rotateY(phase + frame * 0.002f);
                model.scale(scaleX, scaleY, scaleZ);
            }, false));
        }
        return scene;
    }

    private static void verifyStatistics(Options options, RenderPipeline.VisibilityStatistics stats) {
        if (!options.verify()) return;
        int expectedVisible = options.visibilityEnabled()
                ? options.layout().visibleCount(options.objects()) : options.objects();
        if (!stats.available() || stats.candidateRenderers() != options.objects()
                || stats.forwardVisible() != expectedVisible
                || stats.forwardCulled() != options.objects() - expectedVisible
                || stats.forwardVisible() + stats.forwardCulled() != stats.candidateRenderers()) {
            throw new IllegalStateException("场景可见性统计不满足不变量: " + stats
                    + ", expectedVisible=" + expectedVisible);
        }
        if (options.layout() == Layout.ALL_HIDDEN && options.visibilityEnabled()
                && stats.unboundedRenderers() != 1) {
            throw new IllegalStateException("all-hidden 场景必须保留一个 unbounded probe");
        }
    }

    private static DiagnosticsSnapshot.VisibilitySummary toDiagnostics(
            RenderPipeline.VisibilityStatistics value) {
        return new DiagnosticsSnapshot.VisibilitySummary(value.cullingEnabled(), value.sceneRevision(),
                value.candidateRenderers(), value.finiteBoundsRenderers(), value.unboundedRenderers(),
                value.forwardVisible(), value.forwardCulled(), value.shadowCandidates(),
                value.shadowVisible(), value.shadowCulled(), value.modelUpdateNanos(),
                value.boundsTransformNanos(), value.frustumTestNanos(), value.queueSortNanos(),
                value.totalQueueBuildNanos(), value.opaqueDraws(), value.additiveDraws(),
                value.alphaDraws(), value.shaderChanges(), value.materialChanges(),
                value.meshChanges(), value.blendChanges(), value.mirroredChanges());
    }

    private record Resources(List<Mesh> meshes, Mesh unboundedProbe,
                             List<Material> materials) implements AutoCloseable {
        static Resources create(ShaderProgram shader, Layout layout) {
            List<Mesh> meshes = new ArrayList<>(4);
            meshes.add(Mesh.from(BuiltinMeshData.coloredTriangle("scalability-triangle")));
            meshes.add(Mesh.from(BuiltinMeshData.coloredQuad("scalability-quad")));
            meshes.add(Mesh.from(BuiltinMeshData.coloredCube("scalability-cube")));
            meshes.add(Mesh.from(BuiltinMeshData.texturedQuad("scalability-textured-quad")));
            Mesh probe = layout == Layout.ALL_HIDDEN ? createUnboundedProbe() : null;
            List<Material> materials = new ArrayList<>(MATERIAL_COUNT);
            for (int index = 0; index < MATERIAL_COUNT; index++) {
                BlendMode blend = index % 8 == 6 ? BlendMode.ADDITIVE
                        : index % 8 == 7 ? BlendMode.ALPHA : BlendMode.OPAQUE;
                float red = 0.25f + (index & 3) * 0.18f;
                float green = 0.25f + ((index >>> 2) & 3) * 0.18f;
                materials.add(Material.builder(shader)
                        .blendMode(blend)
                        .setVec4("uColor", new Vector4f(red, green, 0.75f,
                                blend == BlendMode.OPAQUE ? 1.0f : 0.65f))
                        .build());
            }
            return new Resources(List.copyOf(meshes), probe, List.copyOf(materials));
        }

        private static Mesh createUnboundedProbe() {
            MeshData data = BuiltinMeshData.coloredTriangle("scalability-unbounded");
            return Mesh.builder()
                    .vertices(data.vertices(), data.layout().strideBytes(),
                            data.layout().attributes().toArray(VertexAttribute[]::new))
                    .bounds(Bounds3f.unbounded())
                    .build();
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            for (Material material : materials) {
                try {
                    material.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            if (unboundedProbe != null) unboundedProbe.close();
            for (Mesh mesh : meshes) mesh.close();
            if (failure != null) throw failure;
        }
    }

    enum Layout {
        SMALL("small", 0.70), MEDIUM("medium", 0.25), LARGE("large", 0.10),
        ALL_VISIBLE("all-visible", 1.0), ALL_HIDDEN("all-hidden", 0.0);

        private final String optionName;
        private final double visibleRatio;

        Layout(String optionName, double visibleRatio) {
            this.optionName = optionName;
            this.visibleRatio = visibleRatio;
        }

        int visibleCount(int objects) {
            if (this == ALL_HIDDEN) return 1;
            return (int) Math.round(objects * visibleRatio);
        }

        static Layout parse(String value) {
            for (Layout layout : values()) if (layout.optionName.equals(value)) return layout;
            throw new IllegalArgumentException("未知 layout: " + value);
        }
    }

    record Options(int objects, boolean visibilityEnabled, Layout layout, int frames, int warmup,
                   int rounds, int width, int height, boolean deterministic, boolean verify,
                   Path diagnosticsExport, int resizeFrame, int resizeWidth, int resizeHeight) {
        static Options parse(String[] arguments) {
            int objects = 10_000;
            boolean visibility = true;
            Layout layout = Layout.LARGE;
            int frames = 1_000;
            int warmup = 100;
            int rounds = 1;
            int width = 1280;
            int height = 720;
            boolean deterministic = false;
            boolean verify = false;
            Path export = null;
            int resizeFrame = -1;
            int resizeWidth = 0;
            int resizeHeight = 0;
            for (String argument : arguments) {
                if (argument.startsWith("--objects=")) objects = positive(argument, "--objects=");
                else if (argument.equals("--visibility=enabled")) visibility = true;
                else if (argument.equals("--visibility=disabled")) visibility = false;
                else if (argument.startsWith("--layout=")) layout = Layout.parse(value(argument));
                else if (argument.startsWith("--frames=")) frames = positive(argument, "--frames=");
                else if (argument.startsWith("--warmup=")) warmup = nonNegative(argument, "--warmup=");
                else if (argument.startsWith("--rounds=")) rounds = positive(argument, "--rounds=");
                else if (argument.startsWith("--size=")) {
                    int[] size = size(value(argument));
                    width = size[0];
                    height = size[1];
                } else if (argument.startsWith("--resize=")) {
                    String[] parts = value(argument).split(":", -1);
                    if (parts.length != 2) throw new IllegalArgumentException("--resize 需要 frame:WxH");
                    resizeFrame = Integer.parseInt(parts[0]);
                    if (resizeFrame < 0) throw new IllegalArgumentException("resize frame 不能为负数");
                    int[] size = size(parts[1]);
                    resizeWidth = size[0];
                    resizeHeight = size[1];
                } else if (argument.equals("--deterministic")) deterministic = true;
                else if (argument.equals("--verify")) verify = true;
                else if (argument.startsWith("--diagnostics-export=")) export = safeExport(value(argument));
                else throw new IllegalArgumentException("未知 SceneScalabilityDemo 参数: " + argument);
            }
            if (objects != 100 && objects != 1_000 && objects != 10_000) {
                throw new IllegalArgumentException("--objects 只接受 100、1000 或 10000");
            }
            return new Options(objects, visibility, layout, frames, warmup, rounds, width, height,
                    deterministic, verify, export, resizeFrame, resizeWidth, resizeHeight);
        }

        private static String value(String argument) {
            return argument.substring(argument.indexOf('=') + 1);
        }

        private static int positive(String argument, String prefix) {
            int value = Integer.parseInt(argument.substring(prefix.length()));
            if (value <= 0) throw new IllegalArgumentException(prefix + " 必须为正数");
            return value;
        }

        private static int nonNegative(String argument, String prefix) {
            int value = Integer.parseInt(argument.substring(prefix.length()));
            if (value < 0) throw new IllegalArgumentException(prefix + " 不能为负数");
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
