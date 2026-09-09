package com.kaleblangley.haikalat.demo.pbr;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Automated TAA quality A/B harness.
 *
 * <p>Renders the same deterministic camera path twice (no AA vs native TAA)
 * with a hard camera cut, then asserts three measurable properties:
 * anti-aliasing energy must drop, edge sharpness must be retained, and the
 * post-cut ghost must decay within a bounded number of frames.</p>
 */
public final class Render3dTemporalQualitySuite {
    private Render3dTemporalQualitySuite() {
    }

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        String candidate = candidate();
        Path reportDirectory = Path.of("build", "reports", "temporal", candidate, "quality");
        Path noneDirectory = reportDirectory.resolve("none");
        Path taaDirectory = reportDirectory.resolve("taa");
        try {
            Files.createDirectories(noneDirectory);
            Files.createDirectories(taaDirectory);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot create quality report directories", failure);
        }

        render(options, "NONE", noneDirectory);
        render(options, "TAA", taaDirectory);
        List<float[]> noneFrames = loadFrames(noneDirectory, options.frames);
        List<float[]> taaFrames = loadFrames(taaDirectory, options.frames);
        if (noneFrames.size() != options.frames || taaFrames.size() != options.frames) {
            throw new IllegalStateException("quality sequences are incomplete: none="
                    + noneFrames.size() + " taa=" + taaFrames.size());
        }

        int converged = options.cutFrame > 0 ? options.cutFrame - 1 : options.frames - 1;
        double noneAliasing = aliasingEnergy(noneFrames.get(converged));
        double taaAliasing = aliasingEnergy(taaFrames.get(converged));
        double noneSharpness = sharpness(noneFrames.get(converged));
        double taaSharpness = sharpness(taaFrames.get(converged));
        int ghostFrames = options.cutFrame >= 0
                ? ghostDecayFrames(taaFrames, options.cutFrame, options.ghostThreshold)
                : -1;
        double finalDifference = meanAbsDifference(taaFrames.get(options.frames - 1),
                noneFrames.get(options.frames - 1));

        String metrics = "{\n"
                + "  \"candidate\": \"" + candidate + "\",\n"
                + "  \"frames\": " + options.frames + ",\n"
                + "  \"cutFrame\": " + options.cutFrame + ",\n"
                + "  \"noneAliasingEnergy\": " + round(noneAliasing) + ",\n"
                + "  \"taaAliasingEnergy\": " + round(taaAliasing) + ",\n"
                + "  \"aliasingRatio\": " + round(taaAliasing / Math.max(noneAliasing, 1.0e-9)) + ",\n"
                + "  \"noneEdgeSharpness\": " + round(noneSharpness) + ",\n"
                + "  \"taaEdgeSharpness\": " + round(taaSharpness) + ",\n"
                + "  \"sharpnessRatio\": " + round(taaSharpness / Math.max(noneSharpness, 1.0e-9)) + ",\n"
                + "  \"ghostDecayFrames\": " + ghostFrames + ",\n"
                + "  \"finalDifference\": " + round(finalDifference) + "\n"
                + "}\n";
        try {
            Files.writeString(reportDirectory.resolve("metrics.json"), metrics,
                    StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot write quality metrics", failure);
        }
        System.out.printf(Locale.ROOT,
                "temporal quality: aliasing %.5f -> %.5f (ratio %.3f), sharpness ratio %.3f, "
                        + "ghostDecay=%d frames, finalDiff=%.5f%n",
                noneAliasing, taaAliasing, taaAliasing / Math.max(noneAliasing, 1.0e-9),
                taaSharpness / Math.max(noneSharpness, 1.0e-9), ghostFrames, finalDifference);

        List<String> failures = new ArrayList<>();
        if (taaAliasing > noneAliasing * options.maxAliasingRatio) {
            failures.add("aliasing energy ratio " + round(taaAliasing / noneAliasing)
                    + " > " + options.maxAliasingRatio);
        }
        if (taaSharpness < noneSharpness * options.minSharpnessRatio) {
            failures.add("edge sharpness ratio " + round(taaSharpness / noneSharpness)
                    + " < " + options.minSharpnessRatio);
        }
        if (options.cutFrame >= 0 && (ghostFrames < 0 || ghostFrames > options.maxGhostFrames)) {
            failures.add("ghost decay " + ghostFrames + " frames > " + options.maxGhostFrames);
        }
        if (finalDifference > options.maxFinalDifference) {
            failures.add("converged difference " + round(finalDifference)
                    + " > " + options.maxFinalDifference);
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("temporal quality failed:\n- "
                    + String.join("\n- ", failures));
        }
        System.out.println("temporal quality report: " + reportDirectory.toAbsolutePath());
    }

    private static void render(Options options, String mode, Path directory) {
        Render3dTemporalDemo.main(new String[]{
                "--hidden", "--pause-animation", "--frames=" + options.frames,
                "--cut=" + options.cutFrame, "--size=" + options.width + "x" + options.height,
                "--aa=" + mode, "--capture-sequence=" + directory});
    }

    private static List<float[]> loadFrames(Path directory, int expected) {
        List<float[]> frames = new ArrayList<>(expected);
        for (int index = 0; index < expected; index++) {
            Path file = directory.resolve("frame_%04d.png".formatted(index));
            try {
                BufferedImage image = ImageIO.read(file.toFile());
                if (image == null) throw new IOException("unreadable image");
                frames.add(luminance(image));
            } catch (IOException failure) {
                throw new IllegalStateException("cannot read " + file, failure);
            }
        }
        return frames;
    }

    private static float[] luminance(BufferedImage image) {
        float[] result = new float[image.getWidth() * image.getHeight()];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                float r = ((rgb >> 16) & 0xFF) / 255.0f;
                float g = ((rgb >> 8) & 0xFF) / 255.0f;
                float b = (rgb & 0xFF) / 255.0f;
                result[y * image.getWidth() + x] = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            }
        }
        return result;
    }

    private static double aliasingEnergy(float[] frame) {
        int width = (int) Math.sqrt(frame.length);
        double sum = 0.0;
        int count = 0;
        for (int y = 1; y < width - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int index = y * width + x;
                float average = (frame[index - 1] + frame[index + 1]
                        + frame[index - width] + frame[index + width]) * 0.25f;
                sum += Math.abs(frame[index] - average);
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static double sharpness(float[] frame) {
        int width = (int) Math.sqrt(frame.length);
        List<Float> gradients = new ArrayList<>();
        for (int y = 1; y < width - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int index = y * width + x;
                float dx = frame[index + 1] - frame[index - 1];
                float dy = frame[index + width] - frame[index - width];
                gradients.add((float) Math.sqrt(dx * dx + dy * dy));
            }
        }
        gradients.sort(java.util.Comparator.reverseOrder());
        // The strongest 1% of gradients are dominated by the geometry
        // silhouette rather than the intentionally-smoothed checkerboard, so
        // this measures edge retention instead of texture sharpening.
        int top = Math.max(1, gradients.size() / 100);
        double sum = 0.0;
        for (int index = 0; index < top; index++) {
            sum += gradients.get(index);
        }
        return sum / top;
    }

    private static int ghostDecayFrames(List<float[]> frames, int cutFrame, double threshold) {
        float[] settled = frames.get(frames.size() - 1);
        for (int index = cutFrame; index < frames.size(); index++) {
            if (meanAbsDifference(frames.get(index), settled) < threshold) {
                return index - cutFrame;
            }
        }
        return -1;
    }

    private static double meanAbsDifference(float[] left, float[] right) {
        double sum = 0.0;
        for (int index = 0; index < left.length; index++) {
            sum += Math.abs(left[index] - right[index]);
        }
        return sum / left.length;
    }

    private static double round(double value) {
        return Math.round(value * 100000.0) / 100000.0;
    }

    private static String candidate() {
        String sha = System.getenv("GITHUB_SHA");
        return sha == null || sha.isBlank() ? "local" : sha.substring(0, Math.min(12, sha.length()));
    }

    private static final class Options {
        private int width = 480;
        private int height = 480;
        private int frames = 40;
        private int cutFrame = 24;
        private double maxAliasingRatio = 0.85;
        private double minSharpnessRatio = 0.65;
        private int maxGhostFrames = 8;
        private double ghostThreshold = 0.01;
        private double maxFinalDifference = 0.02;

        private static Options parse(String[] arguments) {
            Options options = new Options();
            for (String argument : arguments) {
                if (argument.startsWith("--size=")) {
                    String[] size = argument.substring("--size=".length()).split("x");
                    options.width = Integer.parseInt(size[0]);
                    options.height = Integer.parseInt(size[1]);
                } else if (argument.startsWith("--frames=")) {
                    options.frames = Integer.parseInt(argument.substring("--frames=".length()));
                } else if (argument.startsWith("--cut=")) {
                    options.cutFrame = Integer.parseInt(argument.substring("--cut=".length()));
                } else if (argument.startsWith("--max-aliasing-ratio=")) {
                    options.maxAliasingRatio = Double.parseDouble(
                            argument.substring("--max-aliasing-ratio=".length()));
                } else if (argument.startsWith("--min-sharpness-ratio=")) {
                    options.minSharpnessRatio = Double.parseDouble(
                            argument.substring("--min-sharpness-ratio=".length()));
                } else if (argument.startsWith("--max-ghost-frames=")) {
                    options.maxGhostFrames = Integer.parseInt(
                            argument.substring("--max-ghost-frames=".length()));
                } else {
                    throw new IllegalArgumentException("Unknown temporal quality argument: "
                            + argument);
                }
            }
            if (options.width != options.height) {
                throw new IllegalArgumentException(
                        "quality harness requires a square extent for its metric indexing");
            }
            if (options.frames < 4 || options.cutFrame <= 0 || options.cutFrame >= options.frames) {
                throw new IllegalArgumentException("invalid quality frame/cut counts");
            }
            return options;
        }
    }
}
