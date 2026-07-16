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
    private static final Path HAIKALAT = MAIN_JAVA.resolve(
            Path.of("com", "kaleblangley", "haikalat"));
    @Test
    void backendDoesNotDependOnHigherLayers() throws IOException {
        Path backend = MAIN_JAVA.resolve(Path.of("com", "kaleblangley", "haikalat", "backend"));
        Set<String> imports = importsUnder(MAIN_JAVA.resolve(
                Path.of("com", "kaleblangley", "haikalat", "backend")),
                "com.kaleblangley.haikalat.core.");
        Set<String> runtimeImports = importsUnder(backend, "com.kaleblangley.haikalat.runtime.");
        Set<String> subsystemImports = importsUnder(backend, "com.kaleblangley.haikalat.subsystems.");

        assertTrue(imports.isEmpty(), "backend must not depend on core: " + imports);
        assertTrue(runtimeImports.isEmpty(), "backend must not depend on runtime: " + runtimeImports);
        assertTrue(subsystemImports.isEmpty(), "backend must not depend on subsystems: " + subsystemImports);
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

    @Test
    void coreDoesNotDependOnRuntime() throws IOException {
        Path core = MAIN_JAVA.resolve(Path.of("com", "kaleblangley", "haikalat", "core"));
        Set<String> imports = importsUnder(core, "com.kaleblangley.haikalat.runtime.");

        assertTrue(imports.isEmpty(), "core must not depend on runtime: " + imports);
    }

    @Test
    void uiRemainsASiblingSubsystemWithoutReverseDependencies() throws IOException {
        Path ui = HAIKALAT.resolve(Path.of("subsystems", "ui"));
        Set<String> higherLayerImports = new java.util.HashSet<>();
        higherLayerImports.addAll(importsUnder(ui,
                "com.kaleblangley.haikalat.subsystems.render3d."));
        higherLayerImports.addAll(importsUnder(ui,
                "com.kaleblangley.haikalat.subsystems.postprocess."));
        assertTrue(higherLayerImports.isEmpty(),
                "UI must not depend on render3d/postprocess orchestration: " + higherLayerImports);

        for (Path root : Set.of(
                HAIKALAT.resolve("backend"),
                HAIKALAT.resolve("core"),
                HAIKALAT.resolve("runtime"),
                HAIKALAT.resolve(Path.of("subsystems", "render3d")),
                HAIKALAT.resolve(Path.of("subsystems", "postprocess")),
                HAIKALAT.resolve(Path.of("subsystems", "windowing")))) {
            Set<String> imports = importsUnder(root,
                    "com.kaleblangley.haikalat.subsystems.ui.");
            assertTrue(imports.isEmpty(), root + " must not depend on UI: " + imports);
        }
    }

    @Test
    void nativeUiLibrariesStayInsideTheirAdapters() throws IOException {
        assertFilesUnder(filesContaining(MAIN_JAVA, "org.lwjgl.util.yoga."),
                "com/kaleblangley/haikalat/subsystems/ui/layout/", "Yoga");
        Set<String> fontNativeFiles = new java.util.HashSet<>();
        fontNativeFiles.addAll(filesContaining(MAIN_JAVA, "org.lwjgl.util.freetype."));
        fontNativeFiles.addAll(filesContaining(MAIN_JAVA, "org.lwjgl.util.harfbuzz."));
        assertFilesUnder(fontNativeFiles,
                "com/kaleblangley/haikalat/subsystems/ui/text/", "FreeType/HarfBuzz");
        assertFilesUnder(filesContaining(MAIN_JAVA, "com.sun.jna."),
                "com/kaleblangley/haikalat/subsystems/windowing/input/win32/", "JNA/Win32");
    }

    @Test
    void uiLogicPackagesDoNotIssueOpenGlCalls() throws IOException {
        for (String packageName : Set.of("event", "layout", "style", "widget")) {
            Path root = HAIKALAT.resolve(Path.of("subsystems", "ui", packageName));
            Set<String> files = filesContaining(root, "org.lwjgl.opengl.");
            assertTrue(files.isEmpty(), packageName + " UI logic must remain GL-free: " + files);
        }
    }

    @Test
    void productionCodeDoesNotUseCustomCommandEscapeHatch() throws IOException {
        Set<String> callers;
        try (var files = Files.walk(MAIN_JAVA)) {
            callers = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, ".custom("))
                    .map(path -> MAIN_JAVA.relativize(path).toString().replace('\\', '/'))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        assertEquals(Set.of(), callers,
                "Production GL work must use typed CommandBuffer operations");
    }

    @Test
    void automaticExposureProductionPathDoesNotReadBackGpuData() throws IOException {
        Path source = MAIN_JAVA.resolve(Path.of("com", "kaleblangley", "haikalat", "subsystems",
                "postprocess", "AutoExposurePass.java"));
        String code = Files.readString(source);

        for (String forbidden : Set.of("glReadPixels", "glGetTexImage", "glMapBuffer", ".custom(")) {
            assertTrue(!code.contains(forbidden),
                    "Automatic exposure must remain GPU-only; found " + forbidden);
        }
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

    private static Set<String> filesContaining(Path root, String fragment) throws IOException {
        try (var files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, fragment))
                    .map(path -> MAIN_JAVA.relativize(path).toString().replace('\\', '/'))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private static void assertFilesUnder(Set<String> files, String allowedPrefix, String library) {
        Set<String> violations = files.stream()
                .filter(path -> !path.startsWith(allowedPrefix))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertTrue(violations.isEmpty(), library + " symbols escaped adapter package: " + violations);
    }

    private static java.util.stream.Stream<String> lines(Path path) {
        try {
            return Files.readAllLines(path).stream();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect " + path, e);
        }
    }

    private static boolean contains(Path path, String fragment) {
        try {
            return Files.readString(path).contains(fragment);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect " + path, e);
        }
    }
}
