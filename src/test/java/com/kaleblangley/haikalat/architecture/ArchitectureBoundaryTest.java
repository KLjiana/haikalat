package com.kaleblangley.haikalat.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureBoundaryTest {
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final Set<String> APPROVED_BACKEND_TO_CORE = Set.of(
            "com.kaleblangley.haikalat.core.device.RenderFormat",
            "com.kaleblangley.haikalat.core.upload.BufferUploadTarget",
            "com.kaleblangley.haikalat.core.mesh.VertexAttribute",
            "com.kaleblangley.haikalat.core.mesh.VertexLayout"
    );

    @Test
    void backendDoesNotExpandItsCoreDependencySurface() throws IOException {
        Set<String> imports = importsUnder(MAIN_JAVA.resolve(
                Path.of("com", "kaleblangley", "haikalat", "backend")),
                "com.kaleblangley.haikalat.core.");

        assertEquals(APPROVED_BACKEND_TO_CORE, imports,
                "The OpenGL-specialized backend/core seam must be reviewed before adding another dependency");
    }

    @Test
    void coreAndRuntimeDoNotDependOnSubsystems() throws IOException {
        Set<String> coreImports = importsUnder(MAIN_JAVA.resolve(
                Path.of("com", "kaleblangley", "haikalat", "core")),
                "com.kaleblangley.haikalat.subsystems.");
        Set<String> runtimeImports = importsUnder(MAIN_JAVA.resolve(
                Path.of("com", "kaleblangley", "haikalat", "runtime")),
                "com.kaleblangley.haikalat.subsystems.");

        assertTrue(coreImports.isEmpty(), "core must not depend on subsystem orchestration: " + coreImports);
        assertTrue(runtimeImports.isEmpty(), "runtime must not depend on subsystem orchestration: " + runtimeImports);
    }

    private static Set<String> importsUnder(Path root, String prefix) throws IOException {
        try (var files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .flatMap(ArchitectureBoundaryTest::lines)
                    .map(String::trim)
                    .filter(line -> line.startsWith("import " + prefix))
                    .map(line -> line.substring("import ".length(), line.length() - 1))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private static java.util.stream.Stream<String> lines(Path path) {
        try {
            return Files.readAllLines(path).stream();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect " + path, e);
        }
    }
}
