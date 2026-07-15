package com.kaleblangley.haikalat.demo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 执行 post-v0.8 路线图规定的五轮、100 帧预热、1000 帧正式基准。 */
public final class PostV08BenchmarkSuite {
    private static final int ROUNDS = 5;
    private static final String[] COMMON_ARGS = {"--frames=1000", "--warmup=100", "--quiet"};

    private PostV08BenchmarkSuite() {
    }

    public static void main(String[] args) {
        boolean shadowOnly = List.of(args).contains("--shadow-only");
        List<Scenario> scenarios = scenarios(shadowOnly);
        Map<String, List<LearnOpenGlDemo.BenchmarkResult>> results = new LinkedHashMap<>();
        scenarios.forEach(scenario -> results.put(scenario.name(), new ArrayList<>(ROUNDS)));

        for (int round = 0; round < ROUNDS; round++) {
            List<Scenario> order = new ArrayList<>(scenarios);
            if ((round & 1) != 0) {
                Collections.reverse(order);
            }
            for (Scenario scenario : order) {
                System.out.printf("[post-v0.8 benchmark] round %d/%d: %s%n",
                        round + 1, ROUNDS, scenario.name());
                LearnOpenGlDemo.main(scenario.arguments());
                results.get(scenario.name()).add(LearnOpenGlDemo.lastBenchmarkResult());
            }
        }

        printSummary(results);
    }

    private static List<Scenario> scenarios(boolean shadowOnly) {
        List<Scenario> scenarios = new ArrayList<>();
        if (!shadowOnly) {
            for (String aa : List.of("NONE", "MSAA", "FXAA", "TAA")) {
                scenarios.add(scenario("LDR-" + aa,
                        "--tone=NONE", "--aa=" + aa, "--instance-shadows=false"));
                scenarios.add(scenario("ACES-" + aa,
                        "--tone=ACES", "--aa=" + aa, "--instance-shadows=false"));
            }
            scenarios.add(scenario("ACES-FXAA-BLOOM",
                    "--tone=ACES", "--aa=FXAA", "--bloom", "--instance-shadows=false"));
        }
        scenarios.add(scenario("100K-SHADOW-OFF",
                "--tone=ACES", "--aa=NONE", "--instances=100000", "--instance-shadows=false"));
        scenarios.add(scenario("100K-SHADOW-ON",
                "--tone=ACES", "--aa=NONE", "--instances=100000", "--instance-shadows=true"));
        return List.copyOf(scenarios);
    }

    private static Scenario scenario(String name, String... specificArguments) {
        String[] arguments = new String[COMMON_ARGS.length + specificArguments.length];
        System.arraycopy(COMMON_ARGS, 0, arguments, 0, COMMON_ARGS.length);
        System.arraycopy(specificArguments, 0, arguments, COMMON_ARGS.length, specificArguments.length);
        return new Scenario(name, arguments);
    }

    private static void printSummary(Map<String, List<LearnOpenGlDemo.BenchmarkResult>> results) {
        System.out.println("\n## post-v0.8 formal benchmark summary");
        System.out.println("| scenario | present FPS | CPU avg/median ms | GPU avg/median ms | upload MB/frame | ring wait ms/frame | state skip | ");
        System.out.println("|---|---:|---:|---:|---:|---:|---:|");
        results.forEach((name, rounds) -> System.out.printf(
                "| %s | %.1f | %.3f / %.3f | %.3f / %.3f | %.3f | %.3f | %.1f%% |%n",
                name,
                median(rounds, LearnOpenGlDemo.BenchmarkResult::presentFps),
                median(rounds, LearnOpenGlDemo.BenchmarkResult::averageCpuMillis),
                median(rounds, LearnOpenGlDemo.BenchmarkResult::medianCpuMillis),
                median(rounds, LearnOpenGlDemo.BenchmarkResult::averageGpuMillis),
                median(rounds, LearnOpenGlDemo.BenchmarkResult::medianGpuMillis),
                median(rounds, LearnOpenGlDemo.BenchmarkResult::uploadMegabytesPerFrame),
                median(rounds, result -> result.ringWaitMillis() / result.measuredFrames()),
                median(rounds, LearnOpenGlDemo.BenchmarkResult::stateSkipPercent)));

        List<LearnOpenGlDemo.BenchmarkResult> bloom = results.get("ACES-FXAA-BLOOM");
        if (bloom != null) {
            System.out.println("\n### Bloom pass GPU cost");
            System.out.println("| pass | GPU average ms | GPU median ms |");
            System.out.println("|---|---:|---:|");
            bloom.getFirst().passTimings().keySet().stream()
                    .filter(name -> name.startsWith("Bloom"))
                    .forEach(pass -> System.out.printf("| %s | %.4f | %.4f |%n", pass,
                            medianPass(bloom, pass, true), medianPass(bloom, pass, false)));
        }

        List<LearnOpenGlDemo.BenchmarkResult> shadowOff = results.get("100K-SHADOW-OFF");
        List<LearnOpenGlDemo.BenchmarkResult> shadowOn = results.get("100K-SHADOW-ON");
        int cpuTriggerRounds = 0;
        for (int round = 0; round < ROUNDS; round++) {
            if (shadowOn.get(round).medianCpuMillis() - shadowOff.get(round).medianCpuMillis() >= 0.5) {
                cpuTriggerRounds++;
            }
        }
        boolean ringWaitMeasurable = median(shadowOn,
                result -> result.ringWaitMillis() / result.measuredFrames()) >= 0.05;
        System.out.printf("%nSingle-upload trigger: CPU +0.5 ms in %d/%d rounds; ring wait measurable=%s; decision=%s.%n",
                cpuTriggerRounds, ROUNDS, ringWaitMeasurable,
                cpuTriggerRounds >= 3 || ringWaitMeasurable ? "TRIGGERED" : "KEEP CURRENT TWO-UPLOAD PATH");
    }

    private static double medianPass(List<LearnOpenGlDemo.BenchmarkResult> rounds,
                                     String passName, boolean average) {
        double[] values = rounds.stream().map(LearnOpenGlDemo.BenchmarkResult::passTimings)
                .map(timings -> timings.get(passName))
                .mapToDouble(summary -> average
                        ? summary.averageGpuMillis() : summary.medianGpuMillis())
                .sorted().toArray();
        return median(values);
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
