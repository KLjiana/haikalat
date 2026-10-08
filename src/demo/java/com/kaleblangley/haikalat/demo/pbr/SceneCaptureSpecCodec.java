package com.kaleblangley.haikalat.demo.pbr;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Properties;

/** Small atomic codec for deterministic scene/camera evidence inputs. */
final class SceneCaptureSpecCodec {
    private SceneCaptureSpecCodec() {
    }

    static void save(Path path, SceneCaptureSpec spec) {
        Path output = path.toAbsolutePath().normalize();
        Path temporary = null;
        try {
            Path parent = output.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path directory = parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
            temporary = Files.createTempFile(directory, output.getFileName().toString(), ".tmp");
            Properties values = new Properties();
            values.setProperty("schemaVersion", "1");
            values.setProperty("sceneRevision", spec.sceneRevision());
            values.setProperty("cameraPath", spec.cameraPath());
            values.setProperty("seed", Integer.toString(spec.seed()));
            values.setProperty("captureFrames", spec.captureFrames().stream()
                    .map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
            try (var writer = Files.newBufferedWriter(temporary)) {
                values.store(writer, "Haikalat scene capture spec");
            }
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException failure) {
            throw new IllegalStateException("failed to save capture spec " + output, failure);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    static SceneCaptureSpec load(Path path) {
        Path input = path.toAbsolutePath().normalize();
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(input)) {
            values.load(reader);
        } catch (IOException failure) {
            throw new IllegalStateException("failed to load capture spec " + input, failure);
        }
        if (!"1".equals(values.getProperty("schemaVersion"))) {
            throw new IllegalArgumentException(input + ": unsupported capture schema");
        }
        try {
            return new SceneCaptureSpec(values.getProperty("sceneRevision"),
                    values.getProperty("cameraPath"), Integer.parseInt(values.getProperty("seed")),
                    Arrays.stream(values.getProperty("captureFrames").split(","))
                            .map(String::trim).map(Integer::parseInt).toList());
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(input + ": invalid capture spec: "
                    + failure.getMessage(), failure);
        }
    }
}
