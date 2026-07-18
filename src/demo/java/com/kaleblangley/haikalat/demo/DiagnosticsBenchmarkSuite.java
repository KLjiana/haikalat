package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.runtime.diagnostics.DiagnosticsLevel;

import java.util.Locale;
import java.util.ArrayList;

/** 在同一 Demo 配置下对比 OFF/BASIC/DETAILED 的诊断成本。 */
public final class DiagnosticsBenchmarkSuite {
    private DiagnosticsBenchmarkSuite() {
    }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        for (int round = 1; round <= options.rounds; round++) {
            for (DiagnosticsLevel level : DiagnosticsLevel.values()) {
                ArrayList<String> arguments = new ArrayList<>(java.util.List.of(
                        "--deterministic", "--quiet", "--frames=" + options.frames,
                        "--warmup=" + options.warmup, "--size=" + options.size,
                        "--instances=100", "--diagnostics=" + level.name().toLowerCase(Locale.ROOT)));
                arguments.add("--measure-allocation");
                if (level == DiagnosticsLevel.DETAILED) arguments.add("--diagnostics-panel");
                LearnOpenGlDemo.main(arguments.toArray(String[]::new));
                LearnOpenGlDemo.BenchmarkResult result = LearnOpenGlDemo.lastBenchmarkResult();
                System.out.printf(Locale.ROOT,
                        "Diagnostics round %d | %-8s | FPS %.1f | CPU avg/median %.3f/%.3f ms | GPU avg/median %.3f/%.3f ms | allocation %.1f KiB/frame%n",
                        round, level, result.presentFps(), result.averageCpuMillis(),
                        result.medianCpuMillis(), result.averageGpuMillis(), result.medianGpuMillis(),
                        result.allocatedBytesPerFrame() / 1024.0);
            }
        }
    }

    private record Options(int rounds, int warmup, int frames, String size) {
        static Options parse(String[] args) {
            int rounds = 3;
            int warmup = 100;
            int frames = 1000;
            String size = "1920x1080";
            for (String arg : args) {
                if (arg.startsWith("--rounds=")) rounds = positive(arg, "--rounds=");
                else if (arg.startsWith("--warmup=")) warmup = nonNegative(arg, "--warmup=");
                else if (arg.startsWith("--frames=")) frames = positive(arg, "--frames=");
                else if (arg.startsWith("--size=")) size = arg.substring("--size=".length());
                else throw new IllegalArgumentException("Unknown diagnostics benchmark argument: " + arg);
            }
            return new Options(rounds, warmup, frames, size);
        }

        private static int positive(String arg, String prefix) {
            int value = Integer.parseInt(arg.substring(prefix.length()));
            if (value <= 0) throw new IllegalArgumentException(prefix + " must be positive");
            return value;
        }

        private static int nonNegative(String arg, String prefix) {
            int value = Integer.parseInt(arg.substring(prefix.length()));
            if (value < 0) throw new IllegalArgumentException(prefix + " must be non-negative");
            return value;
        }
    }
}
