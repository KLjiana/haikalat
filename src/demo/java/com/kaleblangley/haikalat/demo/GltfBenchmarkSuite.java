package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAssetLoader;
import com.kaleblangley.haikalat.core.assets.gltf.LoadedGltfScene;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfRuntimeLibrary;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfSceneAsset;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.lwjgl.opengl.GL;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.glFinish;

/** 执行 v0.13 glTF 五轮 CPU decode、GPU upload 与 1080p/4K 稳态基准。 */
public final class GltfBenchmarkSuite {
    private static final int ROUNDS = 5;
    private static final int WARMUP_FRAMES = 100;
    private static final int MEASURED_FRAMES = 1_000;

    private GltfBenchmarkSuite() {}

    public static void main(String[] arguments) throws Exception {
        ResourceLocator classpath = ResourceLocator.classpath(GltfBenchmarkSuite.class);
        GltfAssetLoader loader = new GltfAssetLoader(classpath);
        AssetRef source = AssetRef.of("/radio.gltf");

        for (int index = 0; index < 3; index++) loader.load(source);
        double[] decodeMillis = new double[ROUNDS];
        LoadedGltfScene loaded = null;
        for (int round = 0; round < ROUNDS; round++) {
            long start = System.nanoTime();
            loaded = loader.load(source);
            decodeMillis[round] = nanosToMillis(System.nanoTime() - start);
        }
        System.out.printf("[v0.13 glTF decode] median %.3f ms | P95 %.3f ms | "
                        + "nodes %d | primitives %d | decoded %.3f MiB | canonical %.3f MiB%n",
                median(decodeMillis), percentile95(decodeMillis), loaded.statistics().nodeCount(),
                loaded.statistics().primitiveCount(),
                loaded.statistics().decodedBufferBytes() / 1048576.0,
                (loaded.statistics().vertexBytes() + loaded.statistics().indexBytes()) / 1048576.0);

        double[] uploadMillis = benchmarkUpload(loaded);
        System.out.printf("[v0.13 glTF upload] median %.3f ms | P95 %.3f ms | "
                        + "unique meshes %d | encoded image %.3f MiB%n",
                median(uploadMillis), percentile95(uploadMillis), loaded.primitives().size(),
                loaded.statistics().encodedImageBytes() / 1048576.0);

        benchmarkSteadyState();
    }

    private static double[] benchmarkUpload(LoadedGltfScene loaded) {
        double[] uploadMillis = new double[ROUNDS];
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(64, 64)
                .title("glTF upload benchmark").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            try (GltfRuntimeLibrary library = GltfRuntimeLibrary.create()) {
                for (int index = 0; index < 2; index++) {
                    GltfSceneAsset warmup = GltfSceneAsset.upload(loaded, library);
                    glFinish();
                    warmup.close();
                }
                for (int round = 0; round < ROUNDS; round++) {
                    glFinish();
                    long start = System.nanoTime();
                    GltfSceneAsset asset = GltfSceneAsset.upload(loaded, library);
                    glFinish();
                    uploadMillis[round] = nanosToMillis(System.nanoTime() - start);
                    asset.close();
                }
            }
        }
        return uploadMillis;
    }

    private static void benchmarkSteadyState() {
        List<Scenario> scenarios = List.of(
                new Scenario("1080p", "--size=1920x1080"),
                new Scenario("4K", "--size=3840x2160"));
        Map<String, List<LearnOpenGlDemo.BenchmarkResult>> results = new LinkedHashMap<>();
        scenarios.forEach(scenario -> results.put(scenario.name(), new ArrayList<>(ROUNDS)));
        for (int round = 0; round < ROUNDS; round++) {
            List<Scenario> order = new ArrayList<>(scenarios);
            if ((round & 1) != 0) Collections.reverse(order);
            for (Scenario scenario : order) {
                System.out.printf("[v0.13 glTF steady] round %d/%d: %s%n",
                        round + 1, ROUNDS, scenario.name());
                LearnOpenGlDemo.main(new String[]{"--deterministic", "--quiet",
                        "--warmup=" + WARMUP_FRAMES, "--frames=" + MEASURED_FRAMES,
                        scenario.sizeArgument(), "--aa=FXAA", "--bloom", "--auto-exposure"});
                results.get(scenario.name()).add(LearnOpenGlDemo.lastBenchmarkResult());
            }
        }
        System.out.println("| size | present FPS | CPU avg/median ms | GPU avg/median ms | state skip | ordinary draws/pass |");
        System.out.println("|---|---:|---:|---:|---:|---:|");
        results.forEach((name, rounds) -> System.out.printf(
                "| %s | %.1f | %.3f / %.3f | %.3f / %.3f | %.1f%% | %d |%n", name,
                median(rounds.stream().mapToDouble(LearnOpenGlDemo.BenchmarkResult::presentFps).toArray()),
                median(rounds.stream().mapToDouble(LearnOpenGlDemo.BenchmarkResult::averageCpuMillis).toArray()),
                median(rounds.stream().mapToDouble(LearnOpenGlDemo.BenchmarkResult::medianCpuMillis).toArray()),
                median(rounds.stream().mapToDouble(LearnOpenGlDemo.BenchmarkResult::averageGpuMillis).toArray()),
                median(rounds.stream().mapToDouble(LearnOpenGlDemo.BenchmarkResult::medianGpuMillis).toArray()),
                median(rounds.stream().mapToDouble(LearnOpenGlDemo.BenchmarkResult::stateSkipPercent).toArray()),
                rounds.getFirst().ordinarySceneDraws()));
    }

    private static double nanosToMillis(long nanos) { return nanos / 1_000_000.0; }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length >>> 1];
    }

    private static double percentile95(double[] values) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.95) - 1)];
    }

    private record Scenario(String name, String sizeArgument) {}
}
