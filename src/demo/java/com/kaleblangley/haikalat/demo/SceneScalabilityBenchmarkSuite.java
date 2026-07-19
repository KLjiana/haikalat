package com.kaleblangley.haikalat.demo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 对 100、1,000、10,000 普通 renderer 执行 enabled/disabled 配对基准。 */
public final class SceneScalabilityBenchmarkSuite {
    private SceneScalabilityBenchmarkSuite() {
    }

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        for (String size : options.sizes()) {
            List<Case> cases = cases(options.cases());
            for (Case benchmarkCase : cases) {
                PairAccumulator accumulator = new PairAccumulator(options.rounds());
                for (int round = 1; round <= options.rounds(); round++) {
                    String[] order = (round & 1) == 1
                            ? new String[]{"disabled", "enabled"}
                            : new String[]{"enabled", "disabled"};
                    for (String visibility : order) {
                        System.out.printf("SceneScalability pair=%d/%d size=%s case=%s visibility=%s%n",
                                round, options.rounds(), size, benchmarkCase.layout(), visibility);
                        SceneScalabilityDemo.main(new String[]{
                                "--objects=" + benchmarkCase.objects(),
                                "--layout=" + benchmarkCase.layout(),
                                "--visibility=" + visibility,
                                "--size=" + size,
                                "--rounds=1",
                                "--warmup=" + options.warmup(),
                                "--frames=" + options.frames(),
                                "--deterministic",
                                "--verify"
                        });
                        accumulator.add(round - 1, visibility,
                                SceneScalabilityDemo.lastBenchmarkResult());
                    }
                }
                accumulator.print(size, benchmarkCase);
            }
        }
    }

    private static List<Case> cases(List<String> names) {
        Map<String, Case> catalog = new LinkedHashMap<>();
        catalog.put("small", new Case(100, "small"));
        catalog.put("tiny-all-visible", new Case(100, "all-visible"));
        catalog.put("medium", new Case(1_000, "medium"));
        catalog.put("large", new Case(10_000, "large"));
        catalog.put("all-visible", new Case(10_000, "all-visible"));
        catalog.put("all-hidden", new Case(10_000, "all-hidden"));
        ArrayList<Case> selected = new ArrayList<>();
        for (String name : names) {
            Case benchmarkCase = catalog.get(name);
            if (benchmarkCase == null) throw new IllegalArgumentException("未知 benchmark case: " + name);
            selected.add(benchmarkCase);
        }
        return List.copyOf(selected);
    }

    private static final class PairAccumulator {
        private final SceneScalabilityDemo.BenchmarkResult[] disabled;
        private final SceneScalabilityDemo.BenchmarkResult[] enabled;

        private PairAccumulator(int rounds) {
            disabled = new SceneScalabilityDemo.BenchmarkResult[rounds];
            enabled = new SceneScalabilityDemo.BenchmarkResult[rounds];
        }

        private void add(int round, String visibility,
                         SceneScalabilityDemo.BenchmarkResult result) {
            (visibility.equals("enabled") ? enabled : disabled)[round] = result;
        }

        private void print(String size, Case benchmarkCase) {
            double disabledCpu = median(disabled, SceneScalabilityDemo.BenchmarkResult::medianCpuMillis);
            double enabledCpu = median(enabled, SceneScalabilityDemo.BenchmarkResult::medianCpuMillis);
            double disabledGpu = median(disabled, SceneScalabilityDemo.BenchmarkResult::medianGpuMillis);
            double enabledGpu = median(enabled, SceneScalabilityDemo.BenchmarkResult::medianGpuMillis);
            double cpuSaved = medianDifference(disabled, enabled,
                    SceneScalabilityDemo.BenchmarkResult::medianCpuMillis);
            double gpuSaved = medianDifference(disabled, enabled,
                    SceneScalabilityDemo.BenchmarkResult::medianGpuMillis);
            System.out.printf(Locale.ROOT,
                    "SceneScalability SUMMARY size=%s case=%s objects=%d draw=%d->%d "
                            + "CPU=%.3f->%.3fms pairedSaved=%.3fms "
                            + "GPU=%.3f->%.3fms pairedSaved=%.3fms "
                            + "queue=%.3f->%.3fms allocation=%.1f->%.1fKiB/frame%n",
                    size, benchmarkCase.layout(), benchmarkCase.objects(),
                    disabled[0].visible(), enabled[0].visible(), disabledCpu, enabledCpu,
                    cpuSaved, disabledGpu, enabledGpu, gpuSaved,
                    median(disabled, SceneScalabilityDemo.BenchmarkResult::averageQueueMillis),
                    median(enabled, SceneScalabilityDemo.BenchmarkResult::averageQueueMillis),
                    median(disabled, SceneScalabilityDemo.BenchmarkResult::allocationKiBPerFrame),
                    median(enabled, SceneScalabilityDemo.BenchmarkResult::allocationKiBPerFrame));
        }

        private static double median(SceneScalabilityDemo.BenchmarkResult[] values,
                                     Metric metric) {
            double[] samples = new double[values.length];
            for (int index = 0; index < values.length; index++) samples[index] = metric.get(values[index]);
            return median(samples);
        }

        private static double medianDifference(SceneScalabilityDemo.BenchmarkResult[] left,
                                               SceneScalabilityDemo.BenchmarkResult[] right,
                                               Metric metric) {
            double[] differences = new double[left.length];
            for (int index = 0; index < left.length; index++) {
                differences[index] = metric.get(left[index]) - metric.get(right[index]);
            }
            return median(differences);
        }

        private static double median(double[] values) {
            Arrays.sort(values);
            int middle = values.length >>> 1;
            return (values.length & 1) == 0
                    ? (values[middle - 1] + values[middle]) * 0.5 : values[middle];
        }

        @FunctionalInterface
        private interface Metric {
            double get(SceneScalabilityDemo.BenchmarkResult result);
        }
    }

    private record Case(int objects, String layout) {
    }

    private record Options(int rounds, int warmup, int frames, List<String> sizes,
                           List<String> cases) {
        static Options parse(String[] arguments) {
            int rounds = 5;
            int warmup = 100;
            int frames = 1_000;
            List<String> sizes = List.of("1920x1080", "3840x2160");
            List<String> cases = List.of("small", "tiny-all-visible", "medium", "large",
                    "all-visible", "all-hidden");
            for (String argument : arguments) {
                if (argument.startsWith("--rounds=")) rounds = positive(argument);
                else if (argument.startsWith("--warmup=")) warmup = nonNegative(argument);
                else if (argument.startsWith("--frames=")) frames = positive(argument);
                else if (argument.startsWith("--sizes=")) {
                    ArrayList<String> parsed = new ArrayList<>();
                    for (String size : value(argument).split(",")) {
                        if (!size.matches("[1-9][0-9]*x[1-9][0-9]*")) {
                            throw new IllegalArgumentException("基准尺寸必须为逗号分隔的 WxH");
                        }
                        parsed.add(size);
                    }
                    if (parsed.isEmpty()) throw new IllegalArgumentException("--sizes 不能为空");
                    sizes = List.copyOf(parsed);
                } else if (argument.startsWith("--cases=")) {
                    cases = List.of(value(argument).split(","));
                    if (cases.isEmpty() || cases.stream().anyMatch(String::isBlank)) {
                        throw new IllegalArgumentException("--cases 不能为空");
                    }
                } else throw new IllegalArgumentException("未知基准参数: " + argument);
            }
            return new Options(rounds, warmup, frames, sizes, cases);
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
