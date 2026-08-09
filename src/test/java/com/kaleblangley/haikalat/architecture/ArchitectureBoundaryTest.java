package com.kaleblangley.haikalat.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureBoundaryTest {
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final Path HAIKALAT = MAIN_JAVA.resolve(
            Path.of("com", "kaleblangley", "haikalat"));
    private static final Set<String> FORBIDDEN_STABLE_SIGNATURE_PREFIXES = Set.of(
            "org.lwjgl.", "com.fasterxml.jackson.", "com.sun.jna.");

    @Test
    void wholeProjectPublicTypesMatchAllowlist() throws IOException {
        Set<String> discovered = PublicApiCatalog.discover(MAIN_JAVA);
        Map<String, PublicApiCatalog.Entry> catalog = PublicApiCatalog.read();
        Set<String> unclassified = new HashSet<>(discovered);
        unclassified.removeAll(catalog.keySet());
        Set<String> stale = new HashSet<>(catalog.keySet());
        stale.removeAll(discovered);

        assertEquals(Set.of(), unclassified,
                "new public types require classification in docs/architecture/public-api: "
                        + unclassified);
        assertEquals(Set.of(), stale,
                "remove stale public API entries or document a stable migration: " + stale);
        assertCatalogDomains(catalog);
    }

    @Test
    void stableSignaturesDoNotExposeImplementationLibraries() throws Exception {
        for (PublicApiCatalog.Entry entry : PublicApiCatalog.read().values()) {
            if (!entry.category().equals("stable")) continue;
            Class<?> type = Class.forName(entry.type(), false, getClass().getClassLoader());
            for (Field field : type.getDeclaredFields()) {
                if (isPublicOrProtected(field.getModifiers())) {
                    assertAllowedSignature(entry.type(), field.toGenericString(), field.getGenericType());
                }
            }
            for (Executable executable : concat(type.getDeclaredConstructors(), type.getDeclaredMethods())) {
                if (!isPublicOrProtected(executable.getModifiers())) continue;
                for (Type parameter : executable.getGenericParameterTypes()) {
                    assertAllowedSignature(entry.type(), executable.toGenericString(), parameter);
                }
                for (Type exception : executable.getGenericExceptionTypes()) {
                    assertAllowedSignature(entry.type(), executable.toGenericString(), exception);
                }
                for (TypeVariable<?> variable : executable.getTypeParameters()) {
                    for (Type bound : variable.getBounds()) {
                        assertAllowedSignature(entry.type(), executable.toGenericString(), bound);
                    }
                }
                if (executable instanceof java.lang.reflect.Method method) {
                    assertAllowedSignature(entry.type(), executable.toGenericString(),
                            method.getGenericReturnType());
                }
            }
        }
    }

    @Test
    void removedStableTypesRequireMigrationRecord() throws IOException {
        Set<String> currentStable = new HashSet<>();
        PublicApiCatalog.read().values().stream()
                .filter(entry -> entry.category().equals("stable"))
                .map(PublicApiCatalog.Entry::type)
                .forEach(currentStable::add);
        Set<String> baseline = new HashSet<>();
        for (String line : Files.readAllLines(PublicApiCatalog.DIRECTORY.resolve(
                "stable-baseline.allowlist"))) {
            line = line.strip();
            if (!line.isEmpty() && !line.startsWith("#")) baseline.add(line);
        }
        String migrations = Files.readString(PublicApiCatalog.DIRECTORY.resolve("migrations.md"));
        Set<String> undocumented = baseline.stream()
                .filter(type -> !currentStable.contains(type))
                .filter(type -> !migrations.contains("`" + type + "`"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertEquals(Set.of(), undocumented,
                "removed stable types require a migration entry in public-api/migrations.md");
    }

    @Test
    void internalTypesStayInsideTheirSubsystemOrDemoProof() throws IOException {
        Map<String, PublicApiCatalog.Entry> catalog = PublicApiCatalog.read();
        for (PublicApiCatalog.Entry entry : catalog.values()) {
            if (!entry.category().equals("internal")) continue;
            String importLine = "import " + entry.type() + ";";
            for (Path root : Set.of(MAIN_JAVA, Path.of("src", "demo", "java"))) {
                if (!Files.isDirectory(root)) continue;
                try (var files = Files.walk(root)) {
                    for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                        if (!Files.readString(file).contains(importLine)) continue;
                        String normalized = file.toString().replace('\\', '/');
                        boolean demo = normalized.startsWith("src/demo/");
                        assertTrue(demo || normalized.contains(domainPath(entry.file())),
                                entry.type() + " is internal but imported by " + file);
                    }
                }
            }
        }
    }

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
    void embeddedRenderingContractsRemainHostIndependentAndSwapFree() throws IOException {
        Path presentation = HAIKALAT.resolve(Path.of("core", "presentation"));
        Path embeddedRuntime = HAIKALAT.resolve(Path.of("runtime", "HaikalatRuntime.java"));
        Path pipeline = HAIKALAT.resolve(
                Path.of("subsystems", "render3d", "RenderPipeline.java"));
        for (String forbidden : Set.of(
                "net.minecraft.", "net.neoforged.", "ResourceLocation",
                "org.hismeo.haikalathost")) {
            assertTrue(filesContaining(presentation, forbidden).isEmpty(),
                    "presentation contracts must remain host-independent: " + forbidden);
            assertFalse(Files.readString(embeddedRuntime).contains(forbidden),
                    "embedded runtime must remain host-independent: " + forbidden);
        }
        for (Path source : Set.of(embeddedRuntime, pipeline)) {
            String code = Files.readString(source);
            assertFalse(code.contains("glfwSwapBuffers"),
                    source + " must not swap host buffers");
            assertFalse(code.contains(".swapBuffers("),
                    source + " must not invoke a platform swap method");
        }
        String runtimeCode = Files.readString(embeddedRuntime);
        assertFalse(runtimeCode.contains("GlfwWindow"),
                "embedded runtime must not create a GLFW window");
        assertFalse(runtimeCode.contains("GlRenderThread"),
                "embedded runtime must not create a render thread");
    }

    @Test
    void animationSubsystemRemainsGlFree() throws IOException {
        Path animation = HAIKALAT.resolve(Path.of("subsystems", "animation"));
        Set<String> backendImports = importsUnder(animation,
                "com.kaleblangley.haikalat.backend.");
        Set<String> openGlFiles = filesContaining(animation, "org.lwjgl.opengl.");

        assertTrue(backendImports.isEmpty(),
                "animation must not depend on backend resources: " + backendImports);
        assertTrue(openGlFiles.isEmpty(),
                "animation must not issue OpenGL calls: " + openGlFiles);
    }

    @Test
    void resourceSubsystemRemainsHostAndGlFree() throws IOException {
        Path resources = HAIKALAT.resolve(Path.of("subsystems", "resources"));
        Set<String> backendImports = importsUnder(resources,
                "com.kaleblangley.haikalat.backend.");
        Set<String> openGlFiles = filesContaining(resources, "org.lwjgl.opengl.");

        assertTrue(backendImports.isEmpty(),
                "resources must not depend on backend resources: " + backendImports);
        assertTrue(openGlFiles.isEmpty(),
                "resources must not issue OpenGL calls: " + openGlFiles);
    }

    @Test
    void vfxSimulationSubsystemRemainsGlFree() throws IOException {
        Path vfx = HAIKALAT.resolve(Path.of("subsystems", "vfx"));
        Set<String> backendImports = importsUnder(vfx,
                "com.kaleblangley.haikalat.backend.");
        Set<String> openGlFiles = filesContaining(vfx, "org.lwjgl.opengl.");

        assertTrue(backendImports.isEmpty(),
                "VFX simulation must not depend on backend resources: " + backendImports);
        assertTrue(openGlFiles.isEmpty(),
                "VFX simulation must not issue OpenGL calls: " + openGlFiles);
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
    void textRemainsRendererNeutralAndUiIndependent() throws IOException {
        Path text = HAIKALAT.resolve(Path.of("subsystems", "text"));
        Set<String> forbiddenImports = new java.util.HashSet<>();
        forbiddenImports.addAll(importsUnder(text,
                "com.kaleblangley.haikalat.subsystems.ui."));
        forbiddenImports.addAll(importsUnder(text,
                "com.kaleblangley.haikalat.backend."));

        assertTrue(forbiddenImports.isEmpty(),
                "text must remain renderer-neutral and UI-independent: " + forbiddenImports);
        assertTrue(filesContaining(text, "org.lwjgl.opengl.").isEmpty(),
                "text must not issue OpenGL calls");
    }

    @Test
    void markdownDocumentDoesNotExposeCommonMarkImplementationTypes() throws IOException {
        Path document = HAIKALAT.resolve(Path.of("subsystems", "text", "markdown",
                "MarkdownDocument.java"));
        assertFalse(Files.readString(document).contains("org.commonmark"),
                "MarkdownDocument must remain a renderer-neutral value model");
    }

    @Test
    void uiCreatesTheSharedTextSystemOnlyThroughItsTextAdapter() throws IOException {
        Path ui = HAIKALAT.resolve(Path.of("subsystems", "ui"));
        Set<String> creationSites = filesContaining(ui, "TextSystem.createBundled(");
        assertEquals(Set.of("com/kaleblangley/haikalat/subsystems/ui/text/UiTextEngine.java"),
                creationSites, "UI must acquire the shared text system through UiTextEngine");
    }

    @Test
    void nativeUiLibrariesStayInsideTheirAdapters() throws IOException {
        assertFilesUnder(filesContaining(MAIN_JAVA, "org.lwjgl.util.yoga."),
                "com/kaleblangley/haikalat/subsystems/ui/layout/", "Yoga");
        Set<String> fontNativeFiles = new java.util.HashSet<>();
        fontNativeFiles.addAll(filesContaining(MAIN_JAVA, "org.lwjgl.util.freetype."));
        fontNativeFiles.addAll(filesContaining(MAIN_JAVA, "org.lwjgl.util.harfbuzz."));
        assertFilesUnder(fontNativeFiles,
                "com/kaleblangley/haikalat/subsystems/text/", "FreeType/HarfBuzz");
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

    @Test
    void gpuParticleExperimentDoesNotReadBackSimulationData() throws IOException {
        Path source = HAIKALAT.resolve(Path.of("subsystems", "render3d", "vfx",
                "GpuParticleExperiment.java"));
        String code = Files.readString(source);

        for (String forbidden : Set.of("glGetNamedBufferSubData", ".read(", "glMapNamedBuffer")) {
            assertFalse(code.contains(forbidden),
                    "GPU particle experiment must remain GPU-only; found " + forbidden);
        }
    }

    @Test
    void pbrSubsystemKeepsOwnershipAndTypedCommandBoundaries() throws IOException {
        Path pbr = HAIKALAT.resolve(Path.of("subsystems", "render3d", "pbr"));
        if (Files.isDirectory(pbr)) {
            assertTrue(filesContaining(pbr, ".custom(").isEmpty(),
                    "PBR production code must use typed CommandBuffer operations");
        }
        Path renderSettings = HAIKALAT.resolve(Path.of("runtime", "RenderSettings.java"));
        assertTrue(!Files.readString(renderSettings).contains("PbrEnvironment"),
                "RenderSettings must not own PBR GL resources");
    }

    @Test
    void unverifiedNativeModelEntryPointAndDependencyStayRemoved() throws IOException {
        Path experimentalLoader = HAIKALAT.resolve(Path.of("core", "assets", "Ass" + "impModelLoader.java"));
        assertTrue(!Files.exists(experimentalLoader), "The unverified native model entry point must remain removed");

        String build = Files.readString(Path.of("build.gradle"));
        assertTrue(!build.contains("lwjgl-" + "assimp"),
                "The runtime classpath must not reintroduce the removed native dependency");
    }

    private static void assertCatalogDomains(Map<String, PublicApiCatalog.Entry> catalog) {
        Map<String, Set<String>> prefixes = Map.ofEntries(
                Map.entry("animation.allowlist",
                        Set.of("com.kaleblangley.haikalat.subsystems.animation.")),
                Map.entry("backend.allowlist", Set.of("com.kaleblangley.haikalat.backend.")),
                Map.entry("core.allowlist", Set.of("com.kaleblangley.haikalat.core.",
                        "com.kaleblangley.haikalat.util.")),
                Map.entry("runtime.allowlist", Set.of("com.kaleblangley.haikalat.runtime.")),
                Map.entry("render3d.allowlist",
                        Set.of("com.kaleblangley.haikalat.subsystems.render3d.")),
                Map.entry("postprocess.allowlist",
                        Set.of("com.kaleblangley.haikalat.subsystems.postprocess.")),
                Map.entry("resources.allowlist",
                        Set.of("com.kaleblangley.haikalat.subsystems.resources.")),
                Map.entry("scene.allowlist", Set.of("com.kaleblangley.haikalat.subsystems.scene.")),
                Map.entry("text.allowlist", Set.of("com.kaleblangley.haikalat.subsystems.text.")),
                Map.entry("ui.allowlist", Set.of("com.kaleblangley.haikalat.subsystems.ui.")),
                Map.entry("vfx.allowlist", Set.of("com.kaleblangley.haikalat.subsystems.vfx.")),
                Map.entry("windowing.allowlist",
                        Set.of("com.kaleblangley.haikalat.subsystems.windowing.")));
        for (PublicApiCatalog.Entry entry : catalog.values()) {
            assertTrue(prefixes.get(entry.file()).stream().anyMatch(entry.type()::startsWith),
                    entry.type() + " is in the wrong allowlist " + entry.file());
        }
    }

    private static String domainPath(String allowlist) {
        return switch (allowlist) {
            case "animation.allowlist" -> "/subsystems/animation/";
            case "backend.allowlist" -> "/backend/";
            case "core.allowlist" -> "/core/";
            case "runtime.allowlist" -> "/runtime/";
            case "render3d.allowlist" -> "/subsystems/render3d/";
            case "postprocess.allowlist" -> "/subsystems/postprocess/";
            case "resources.allowlist" -> "/subsystems/resources/";
            case "scene.allowlist" -> "/subsystems/scene/";
            case "text.allowlist" -> "/subsystems/text/";
            case "ui.allowlist" -> "/subsystems/ui/";
            case "vfx.allowlist" -> "/subsystems/vfx/";
            case "windowing.allowlist" -> "/subsystems/windowing/";
            default -> throw new AssertionError("unknown allowlist " + allowlist);
        };
    }

    private static boolean isPublicOrProtected(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static void assertAllowedSignature(String owner, String signature, Type type) {
        for (String referenced : referencedTypes(type)) {
            assertFalse(FORBIDDEN_STABLE_SIGNATURE_PREFIXES.stream().anyMatch(referenced::startsWith),
                    owner + " exposes implementation type " + referenced + " in " + signature);
        }
    }

    private static Set<String> referencedTypes(Type type) {
        Set<String> result = new HashSet<>();
        collectTypes(type, result);
        return result;
    }

    private static void collectTypes(Type type, Set<String> output) {
        if (type instanceof Class<?> clazz) {
            if (clazz.isArray()) collectTypes(clazz.getComponentType(), output);
            else output.add(clazz.getName());
        } else if (type instanceof ParameterizedType parameterized) {
            collectTypes(parameterized.getRawType(), output);
            for (Type argument : parameterized.getActualTypeArguments()) collectTypes(argument, output);
        } else if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) collectTypes(bound, output);
            for (Type bound : wildcard.getLowerBounds()) collectTypes(bound, output);
        } else if (type instanceof TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) collectTypes(bound, output);
        } else if (type instanceof GenericArrayType array) {
            collectTypes(array.getGenericComponentType(), output);
        }
    }

    private static Executable[] concat(java.lang.reflect.Constructor<?>[] constructors,
                                       java.lang.reflect.Method[] methods) {
        Executable[] result = new Executable[constructors.length + methods.length];
        System.arraycopy(constructors, 0, result, 0, constructors.length);
        System.arraycopy(methods, 0, result, constructors.length, methods.length);
        return result;
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
