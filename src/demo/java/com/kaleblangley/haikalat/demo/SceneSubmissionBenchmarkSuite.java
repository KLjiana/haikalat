package com.kaleblangley.haikalat.demo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 执行 v0.17 程序化场景与真实 glTF 场景的固定/优化五轮 A/B 矩阵。 */
public final class SceneSubmissionBenchmarkSuite {
    private SceneSubmissionBenchmarkSuite() {
    }

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        List<Case> cases = List.of(
                new Case("programmatic", 100, "all-visible"),
                new Case("programmatic", 1_000, "medium"),
                new Case("programmatic", 10_000, "large"),
                new Case("gltf", 100, "all-visible"),
                new Case("gltf", 1_000, "mixed"),
                new Case("gltf", 10_000, "mostly-hidden"));
        List<Mode> modes = List.of(
                new Mode("compat", false, false, false),
                new Mode("optimized", true, true, true));
        Map<String, List<Result>> results = new LinkedHashMap<>();
        for (Case benchmarkCase : cases) {
            for (Mode mode : modes) results.put(benchmarkCase.key(mode), new ArrayList<>());
        }

        for (int round = 0; round < options.rounds(); round++) {
            List<Case> order = new ArrayList<>(cases);
            if ((round & 1) != 0) java.util.Collections.reverse(order);
            for (Case benchmarkCase : order) {
                for (Mode mode : modes) {
                    System.out.printf(Locale.ROOT, "[v0.17 submission] round %d/%d %s %d %s %s%n",
                            round + 1, options.rounds(), benchmarkCase.asset,
                            benchmarkCase.renderers, benchmarkCase.layout, mode.name);
                    Result result = benchmarkCase.asset.equals("gltf")
                            ? runGltf(benchmarkCase, mode, options)
                            : runProgrammatic(benchmarkCase, mode, options);
                    results.get(benchmarkCase.key(mode)).add(result);
                }
            }
        }

        System.out.println("| asset | renderers | layout | mode | CPU median ms | GPU median ms | queue ms | command ms | allocation KiB/frame |");
        System.out.println("|---|---:|---|---|---:|---:|---:|---:|---:|");
        for (Case benchmarkCase : cases) {
            for (Mode mode : modes) {
                List<Result> samples = results.get(benchmarkCase.key(mode));
                System.out.printf(Locale.ROOT, "| %s | %,d | %s | %s | %.3f | %.3f | %.3f | %.3f | %.1f |%n",
                        benchmarkCase.asset, benchmarkCase.renderers, benchmarkCase.layout,
                        mode.name, median(samples, Result::cpu), median(samples, Result::gpu),
                        median(samples, Result::queue), median(samples, Result::command),
                        median(samples, Result::allocation));
            }
        }
    }

    private static Result runGltf(Case benchmarkCase, Mode mode, Options options) {
        GltfSceneScalabilityDemo.main(new String[]{
                "--renderers=" + benchmarkCase.renderers,
                "--layout=" + benchmarkCase.layout,
                "--visibility=enabled",
                "--static-cache=" + mode.option(mode.staticCache),
                "--queue-cache=" + mode.option(mode.queueCache),
                "--command-matrix-arena=" + mode.option(mode.matrixArena),
                "--frames=" + options.frames, "--warmup=" + options.warmup,
                "--rounds=1", "--size=" + options.size, "--deterministic", "--verify"
        });
        GltfSceneScalabilityDemo.BenchmarkResult value =
                GltfSceneScalabilityDemo.lastBenchmarkResult();
        return new Result(value.medianCpuMillis(), value.medianGpuMillis(),
                value.averageQueueMillis(), value.averageCommandMillis(),
                value.allocationKiBPerFrame());
    }

    private static Result runProgrammatic(Case benchmarkCase, Mode mode, Options options) {
        SceneScalabilityDemo.main(new String[]{
                "--objects=" + benchmarkCase.renderers,
                "--layout=" + benchmarkCase.layout,
                "--model-source=static", "--visibility=enabled",
                "--static-cache=" + mode.option(mode.staticCache),
                "--queue-cache=" + mode.option(mode.queueCache),
                "--command-matrix-arena=" + mode.option(mode.matrixArena),
                "--frames=" + options.frames, "--warmup=" + options.warmup,
                "--rounds=1", "--size=" + options.size, "--deterministic", "--verify"
        });
        SceneScalabilityDemo.BenchmarkResult value = SceneScalabilityDemo.lastBenchmarkResult();
        return new Result(value.medianCpuMillis(), value.medianGpuMillis(),
                value.averageQueueMillis(), Double.NaN, value.allocationKiBPerFrame());
    }

    private static double median(List<Result> values, Metric metric) {
        double[] numbers = values.stream().mapToDouble(metric::get)
                .filter(Double::isFinite).sorted().toArray();
        if (numbers.length == 0) return Double.NaN;
        int middle = numbers.length >>> 1;
        return (numbers.length & 1) == 0
                ? (numbers[middle - 1] + numbers[middle]) * 0.5 : numbers[middle];
    }

    private interface Metric { double get(Result value); }
    private record Result(double cpu, double gpu, double queue, double command, double allocation) {}
    private record Case(String asset, int renderers, String layout) {
        String key(Mode mode) { return asset + ':' + renderers + ':' + layout + ':' + mode.name; }
    }
    private record Mode(String name, boolean staticCache, boolean queueCache, boolean matrixArena) {
        String option(boolean enabled) { return enabled ? "enabled" : "disabled"; }
    }

    private record Options(int rounds, int warmup, int frames, String size) {
        static Options parse(String[] arguments) {
            int rounds = 5;
            int warmup = 100;
            int frames = 1_000;
            String size = "1280x720";
            for (String argument : arguments) {
                if (argument.startsWith("--rounds=")) rounds = positive(argument);
                else if (argument.startsWith("--warmup=")) warmup = nonNegative(argument);
                else if (argument.startsWith("--frames=")) frames = positive(argument);
                else if (argument.startsWith("--size=")) size = value(argument);
                else throw new IllegalArgumentException("未知 submission benchmark 参数: " + argument);
            }
            return new Options(rounds, warmup, frames, size);
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
    }
}
