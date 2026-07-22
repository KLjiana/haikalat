package com.kaleblangley.haikalat.demo;

import java.util.Locale;

/** 在相同窗口、场景和统计区间下运行 preview closed/direct/MSAA/cube 对照。 */
public final class PreviewBenchmarkSuite {
    private PreviewBenchmarkSuite() { }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        System.out.printf(Locale.ROOT,
                "[PreviewBenchmark] config objects=100 size=%s warmup=%d samples=%d rounds=%d%n",
                options.size, options.warmup, options.frames, options.rounds);
        for (int round = 1; round <= options.rounds; round++) {
            run(round, "closed", options, "--diagnostics=off", "--aa=MSAA");
            run(round, "direct-hdr", options, "--aa=MSAA", "--preview=hdrResolvedColor");
            run(round, "msaa-depth", options, "--aa=MSAA", "--preview=GeometryPass/depth");
            run(round, "cubemap", options, "--aa=MSAA", "--preview=prefiltered");
        }
    }

    private static void run(int round, String name, Options options, String... variant) {
        System.out.printf(Locale.ROOT, "[PreviewBenchmark] round=%d mode=%s%n", round, name);
        String[] arguments = new String[variant.length + 4];
        arguments[0] = "--deterministic";
        arguments[1] = "--frames=" + options.frames;
        arguments[2] = "--warmup=" + options.warmup;
        arguments[3] = "--size=" + options.size;
        System.arraycopy(variant, 0, arguments, 4, variant.length);
        LearnOpenGlDemo.main(arguments);
    }

    private record Options(int rounds, int warmup, int frames, String size) {
        static Options parse(String[] args) {
            int rounds = 5;
            int warmup = 100;
            int frames = 1000;
            String size = "1920x1080";
            for (String argument : args) {
                if (argument.startsWith("--rounds=")) rounds = integer(argument, "--rounds=");
                else if (argument.startsWith("--warmup=")) warmup = integer(argument, "--warmup=");
                else if (argument.startsWith("--frames=")) frames = integer(argument, "--frames=");
                else if (argument.startsWith("--size=")) size = argument.substring("--size=".length());
                else throw new IllegalArgumentException("Unknown preview benchmark argument: " + argument);
            }
            if (rounds <= 0 || warmup < 0 || frames <= warmup) {
                throw new IllegalArgumentException("preview benchmark requires rounds > 0 and frames > warmup >= 0");
            }
            return new Options(rounds, warmup, frames, size);
        }

        private static int integer(String argument, String prefix) {
            return Integer.parseInt(argument.substring(prefix.length()));
        }
    }
}
