package com.kaleblangley.haikalat.demo;

import java.util.Locale;

/** v0.17.2 三种 10k immutable scene 布局的固定、同机可比较基准入口。 */
public final class SceneHardeningBenchmarkSuite {
    private SceneHardeningBenchmarkSuite() {
    }

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        System.out.printf(Locale.ROOT,
                "[SceneHardeningBenchmark] config objects=10000 size=%s warmup=%d samples=%d rounds=%d%n",
                options.size, options.warmup, options.frames, options.rounds);
        run("all-hidden", options);
        run("large", options);
        run("all-visible", options);
    }

    private static void run(String layout, Options options) {
        SceneScalabilityDemo.main(new String[]{
                "--objects=10000", "--layout=" + layout, "--model-source=static",
                "--visibility=enabled", "--static-cache=enabled", "--queue-cache=enabled",
                "--command-matrix-arena=enabled", "--frames=" + options.frames,
                "--warmup=" + options.warmup, "--rounds=" + options.rounds,
                "--size=" + options.size, "--deterministic", "--verify"
        });
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
                else throw new IllegalArgumentException("未知 hardening benchmark 参数: " + argument);
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
