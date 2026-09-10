package com.kaleblangley.haikalat.demo.pbr;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic image gate for the clustered night-town path.
 *
 * <p>Two identical runs of the production demo must produce pixel-identical
 * captures at every declared frame.  The declared frames must also differ from
 * each other (the camera path and lighting are live), and the diagnostic
 * summary (light count, grid, overflow, shadow selection) must match.  This is
 * a determinism/stability gate, not a substitute for the full-scan numeric
 * reference in {@code ClusterLightingGlTest}.</p>
 */
public final class Render3dClusteredQualitySuite {
    private static final List<Integer> DECLARED_FRAMES = List.of(60, 240, 420);
    private static final int MIN_CHANGED_BYTES = 500;

    private Render3dClusteredQualitySuite() {
    }

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        Path directory = Path.of("build/reports/clustered-quality", UUID.randomUUID().toString());
        List<String> failures = new ArrayList<>();
        Map<String, String> summaryA = Map.of();
        Map<String, String> summaryB = Map.of();
        try {
            Path passA = directory.resolve("pass-a");
            Path passB = directory.resolve("pass-b");
            runPass(options, passA);
            runPass(options, passB);
            summaryA = readSummary(passA.resolve("summary.properties"));
            summaryB = readSummary(passB.resolve("summary.properties"));
            compareSummaries(summaryA, summaryB, failures);
            for (int frame : DECLARED_FRAMES) {
                BufferedImage imageA = readImage(passA.resolve(captureName(frame)));
                BufferedImage imageB = readImage(passB.resolve(captureName(frame)));
                int difference = pixelDifference(imageA, imageB);
                if (difference != 0) {
                    failures.add("frame " + frame + ": repeat run differs by " + difference
                            + " channels");
                }
            }
            for (int index = 1; index < DECLARED_FRAMES.size(); index++) {
                BufferedImage previous = readImage(passA.resolve(
                        captureName(DECLARED_FRAMES.get(index - 1))));
                BufferedImage current = readImage(passA.resolve(
                        captureName(DECLARED_FRAMES.get(index))));
                int difference = pixelDifference(previous, current);
                if (difference < MIN_CHANGED_BYTES) {
                    failures.add("frames " + DECLARED_FRAMES.get(index - 1) + "/"
                            + DECLARED_FRAMES.get(index)
                            + " are nearly identical (" + difference + " changed channels)");
                }
            }
        } finally {
            writeReport(directory, options, summaryA, summaryB, failures);
            System.out.println("clustered quality report: " + directory.toAbsolutePath());
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("clustered quality failed: " + failures);
        }
    }

    private static void runPass(Options options, Path passDirectory) {
        List<String> args = new ArrayList<>(List.of(
                "--hidden", "--scene=town", "--aa=TAA", "--camera-path=street",
                "--frames=" + options.frames, "--size=" + options.size, "--verify",
                "--summary=" + passDirectory.resolve("summary.properties").toString().replace('\\', '/')));
        for (int frame : DECLARED_FRAMES) {
            args.add("--capture-at=" + frame + ":"
                    + passDirectory.resolve(captureName(frame)).toString().replace('\\', '/'));
        }
        Render3dClusteredDemo.main(args.toArray(String[]::new));
    }

    private static String captureName(int frame) {
        return String.format("town-%04d.png", frame);
    }

    private static Map<String, String> readSummary(Path path) {
        try {
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : Files.readAllLines(path)) {
                int equals = line.indexOf('=');
                if (equals > 0) {
                    values.put(line.substring(0, equals), line.substring(equals + 1));
                }
            }
            return values;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read clustered summary " + path, failure);
        }
    }

    private static void compareSummaries(Map<String, String> first, Map<String, String> second,
                                         List<String> failures) {
        for (String key : List.of("scene", "width", "height", "localLights", "clusterCount",
                "overflowClusters", "gpuCountersAvailable", "shadowSelected")) {
            String a = first.get(key);
            String b = second.get(key);
            if (a == null || !a.equals(b)) {
                failures.add("summary mismatch " + key + ": " + a + " vs " + b);
            }
        }
        if (!"true".equals(first.get("gpuCountersAvailable"))) {
            failures.add("pass A has no GPU counter snapshot");
        }
    }

    private static BufferedImage readImage(Path path) {
        try {
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) {
                throw new IllegalStateException("Missing capture " + path);
            }
            return image;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read clustered capture " + path, failure);
        }
    }

    private static int pixelDifference(BufferedImage first, BufferedImage second) {
        if (first.getWidth() != second.getWidth() || first.getHeight() != second.getHeight()) {
            throw new IllegalArgumentException("capture extents differ");
        }
        int changed = 0;
        for (int y = 0; y < first.getHeight(); y++) {
            for (int x = 0; x < first.getWidth(); x++) {
                if (first.getRGB(x, y) != second.getRGB(x, y)) changed++;
            }
        }
        return changed;
    }

    private static void writeReport(Path directory, Options options,
                                    Map<String, String> summaryA, Map<String, String> summaryB,
                                    List<String> failures) {
        try {
            Files.createDirectories(directory);
            try (JsonGenerator json = new JsonFactory().createGenerator(
                    directory.resolve("metrics.json").toFile(), JsonEncoding.UTF8)) {
                json.useDefaultPrettyPrinter();
                json.writeStartObject();
                json.writeStringField("generatedAt", Instant.now().toString());
                json.writeStringField("status", failures.isEmpty() ? "passed" : "failed");
                json.writeNumberField("frames", options.frames);
                json.writeStringField("size", options.size);
                json.writeStringField("aa", "TAA");
                json.writeArrayFieldStart("declaredFrames");
                for (int frame : DECLARED_FRAMES) json.writeNumber(frame);
                json.writeEndArray();
                json.writeObjectFieldStart("summaryA");
                for (Map.Entry<String, String> entry : summaryA.entrySet()) {
                    json.writeStringField(entry.getKey(), entry.getValue());
                }
                json.writeEndObject();
                json.writeObjectFieldStart("summaryB");
                for (Map.Entry<String, String> entry : summaryB.entrySet()) {
                    json.writeStringField(entry.getKey(), entry.getValue());
                }
                json.writeEndObject();
                json.writeArrayFieldStart("failures");
                for (String failure : failures) json.writeString(failure);
                json.writeEndArray();
                json.writeEndObject();
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot preserve clustered quality report", failure);
        }
    }

    private record Options(int frames, String size) {
        private static Options parse(String[] arguments) {
            int frames = 480;
            String size = "1280x720";
            for (String argument : arguments) {
                if (argument.startsWith("--frames=")) {
                    frames = Integer.parseInt(argument.substring("--frames=".length()));
                } else if (argument.startsWith("--size=")) {
                    size = argument.substring("--size=".length());
                } else {
                    throw new IllegalArgumentException(
                            "Unknown clustered quality argument: " + argument);
                }
            }
            if (frames < DECLARED_FRAMES.getLast()) {
                throw new IllegalArgumentException("frames must cover every declared capture");
            }
            if (!size.matches("[1-9][0-9]*x[1-9][0-9]*")) {
                throw new IllegalArgumentException("invalid size: " + size);
            }
            return new Options(frames, size);
        }
    }
}
