package com.kaleblangley.haikalat.demo.pbr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 执行 1080p/4K、四种 AA 与 Bloom/曝光代表组合的五轮 v0.12 基准。 */
public final class PbrBenchmarkSuite {
    private static final int ROUNDS = 5;
    private static final String[] COMMON = {
            "--hidden", "--environment-quality=default", "--warmup=100", "--frames=1000"
    };

    private PbrBenchmarkSuite() {
    }

    public static void main(String[] args) {
        List<Scenario> scenarios = List.of(
                scenario("1080p-NONE-manual", "--size=1920x1080", "--aa=none", "--auto-exposure=off", "--bloom=off"),
                scenario("1080p-FXAA-auto-bloom", "--size=1920x1080", "--aa=fxaa", "--auto-exposure=on", "--bloom=on"),
                scenario("1080p-MSAA-manual", "--size=1920x1080", "--aa=msaa", "--auto-exposure=off", "--bloom=off"),
                scenario("1080p-TAA-auto-bloom", "--size=1920x1080", "--aa=taa", "--auto-exposure=on", "--bloom=on"),
                scenario("4K-NONE-manual", "--size=3840x2160", "--aa=none", "--auto-exposure=off", "--bloom=off"),
                scenario("4K-FXAA-auto-bloom", "--size=3840x2160", "--aa=fxaa", "--auto-exposure=on", "--bloom=on"),
                scenario("4K-MSAA-manual", "--size=3840x2160", "--aa=msaa", "--auto-exposure=off", "--bloom=off"),
                scenario("4K-TAA-auto-bloom", "--size=3840x2160", "--aa=taa", "--auto-exposure=on", "--bloom=on"));
        Map<String, List<PbrDemo.BenchmarkResult>> results = new LinkedHashMap<>();
        scenarios.forEach(scenario -> results.put(scenario.name(), new ArrayList<>(ROUNDS)));
        for (int round = 0; round < ROUNDS; round++) {
            List<Scenario> order = new ArrayList<>(scenarios);
            if ((round & 1) != 0) Collections.reverse(order);
            for (Scenario scenario : order) {
                System.out.printf("[v0.12 PBR benchmark] round %d/%d: %s%n",
                        round + 1, ROUNDS, scenario.name());
                PbrDemo.main(scenario.arguments());
                results.get(scenario.name()).add(PbrDemo.lastBenchmarkResult());
            }
        }
        System.out.println("\n| scenario | present FPS | CPU avg/median ms | GPU avg/median ms |");
        System.out.println("|---|---:|---:|---:|");
        results.forEach((name, rounds) -> System.out.printf(
                "| %s | %.1f | %.3f / %.3f | %.3f / %.3f |%n", name,
                median(rounds.stream().mapToDouble(PbrDemo.BenchmarkResult::presentFps).toArray()),
                median(rounds.stream().mapToDouble(r -> r.timings().averageCpuMillis()).toArray()),
                median(rounds.stream().mapToDouble(r -> r.timings().medianCpuMillis()).toArray()),
                median(rounds.stream().mapToDouble(r -> r.timings().averageGpuMillis()).toArray()),
                median(rounds.stream().mapToDouble(r -> r.timings().medianGpuMillis()).toArray())));
    }

    private static Scenario scenario(String name, String... values) {
        String[] arguments = new String[COMMON.length + values.length];
        System.arraycopy(COMMON, 0, arguments, 0, COMMON.length);
        System.arraycopy(values, 0, arguments, COMMON.length, values.length);
        return new Scenario(name, arguments);
    }

    private static double median(double[] values) {
        java.util.Arrays.sort(values);
        int middle = values.length >>> 1;
        return (values.length & 1) == 0 ? (values[middle - 1] + values[middle]) * 0.5 : values[middle];
    }

    private record Scenario(String name, String[] arguments) {
    }
}
