package com.kaleblangley.haikalat.demo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 执行 v0.10 自动曝光的 1080p/4K 五轮正式基准。 */
public final class AutoExposureBenchmarkSuite {
    private static final int ROUNDS = 5;
    private static final String[] COMMON_ARGS = {
            "--frames=1000", "--warmup=100", "--quiet", "--tone=ACES", "--aa=FXAA",
            "--instance-shadows=false"
    };

    private AutoExposureBenchmarkSuite() {
    }

    public static void main(String[] args) {
        List<Scenario> scenarios = List.of(
                scenario("1080p-MANUAL", "--size=1920x1080"),
                scenario("1080p-AUTO", "--size=1920x1080", "--auto-exposure"),
                scenario("4K-MANUAL", "--size=3840x2160"),
                scenario("4K-AUTO", "--size=3840x2160", "--auto-exposure"));
        Map<String, List<LearnOpenGlDemo.BenchmarkResult>> results = new LinkedHashMap<>();
        scenarios.forEach(scenario -> results.put(scenario.name(), new ArrayList<>(ROUNDS)));

        for (int round = 0; round < ROUNDS; round++) {
            List<Scenario> order = new ArrayList<>(scenarios);
            if ((round & 1) != 0) {
                Collections.reverse(order);
            }
            for (Scenario scenario : order) {
                System.out.printf("[v0.10 auto-exposure benchmark] round %d/%d: %s%n",
                        round + 1, ROUNDS, scenario.name());
                LearnOpenGlDemo.main(scenario.arguments());
                results.get(scenario.name()).add(LearnOpenGlDemo.lastBenchmarkResult());
            }
        }
        printSummary(results);
    }

    private static Scenario scenario(String name, String... specificArguments) {
        String[] arguments = new String[COMMON_ARGS.length + specificArguments.length];
        System.arraycopy(COMMON_ARGS, 0, arguments, 0, COMMON_ARGS.length);
        System.arraycopy(specificArguments, 0, arguments, COMMON_ARGS.length, specificArguments.length);
        return new Scenario(name, arguments);
    }

    private static void printSummary(Map<String, List<LearnOpenGlDemo.BenchmarkResult>> results) {
        System.out.println("\n## v0.10 automatic-exposure formal benchmark summary");
        System.out.println("| scenario | present FPS | CPU avg/median ms | GPU avg/median ms |");
        System.out.println("|---|---:|---:|---:|");
        results.forEach((name, rounds) -> System.out.printf(
                "| %s | %.1f | %.3f / %.3f | %.3f / %.3f |%n",
                name,
                median(rounds, LearnOpenGlDemo.BenchmarkResult::presentFps),
                median(rounds, LearnOpenGlDemo.BenchmarkResult::averageCpuMillis),
                median(rounds, LearnOpenGlDemo.BenchmarkResult::medianCpuMillis),
                median(rounds, LearnOpenGlDemo.BenchmarkResult::averageGpuMillis),
                median(rounds, LearnOpenGlDemo.BenchmarkResult::medianGpuMillis)));

        for (String scenario : List.of("1080p-AUTO", "4K-AUTO")) {
            List<LearnOpenGlDemo.BenchmarkResult> rounds = results.get(scenario);
            System.out.printf("%n### %s automatic-exposure passes%n", scenario);
            System.out.println("| pass | GPU average ms | GPU median ms |");
            System.out.println("|---|---:|---:|");
            rounds.getFirst().passTimings().keySet().stream()
                    .filter(name -> name.startsWith("AutoExposure"))
                    .forEach(pass -> System.out.printf("| %s | %.4f | %.4f |%n", pass,
                            medianPass(rounds, pass, true), medianPass(rounds, pass, false)));
        }
    }

    private static double medianPass(List<LearnOpenGlDemo.BenchmarkResult> rounds,
                                     String passName, boolean average) {
        return median(rounds.stream()
                .map(LearnOpenGlDemo.BenchmarkResult::passTimings)
                .map(timings -> timings.get(passName))
                .mapToDouble(summary -> average
                        ? summary.averageGpuMillis() : summary.medianGpuMillis())
                .sorted().toArray());
    }

    private static double median(List<LearnOpenGlDemo.BenchmarkResult> values,
                                 DoubleValue extractor) {
        return median(values.stream().mapToDouble(extractor::value).sorted().toArray());
    }

    private static double median(double[] sorted) {
        int middle = sorted.length >>> 1;
        return (sorted.length & 1) == 0
                ? (sorted[middle - 1] + sorted[middle]) * 0.5
                : sorted[middle];
    }

    @FunctionalInterface
    private interface DoubleValue {
        double value(LearnOpenGlDemo.BenchmarkResult result);
    }

    private record Scenario(String name, String[] arguments) {
    }
}
