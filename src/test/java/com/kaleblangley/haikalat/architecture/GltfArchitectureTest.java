package com.kaleblangley.haikalat.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定 glTF CPU/runtime 边界和首个公开 API 面。 */
class GltfArchitectureTest {
    private static final Path CORE = Path.of("src/main/java/com/kaleblangley/haikalat/core/assets/gltf");
    private static final Path RUNTIME = Path.of("src/main/java/com/kaleblangley/haikalat/subsystems/render3d/gltf");
    private static final Pattern PUBLIC = Pattern.compile("(?m)^public\\s+(?:(?:final|sealed)\\s+)?(?:class|record|interface|enum)\\s+(\\w+)");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+);");

    @Test
    void publicTypesMatchAllowlist() throws IOException {
        Set<String> discovered = new HashSet<>();
        discover(CORE, discovered);
        discover(RUNTIME, discovered);
        Set<String> classified = PublicApiCatalog.classifiedUnder(PublicApiCatalog.read(),
                "com.kaleblangley.haikalat.core.assets.gltf.");
        Set<String> runtimeClassified = PublicApiCatalog.classifiedUnder(PublicApiCatalog.read(),
                "com.kaleblangley.haikalat.subsystems.render3d.gltf.");
        classified = new HashSet<>(classified);
        classified.addAll(runtimeClassified);
        assertEquals(discovered, classified);
    }

    @Test
    void cpuLoaderDoesNotDependOnSubsystemOrInvokeOpenGl() throws IOException {
        for (Path file : javaFiles(CORE)) {
            String source = Files.readString(file);
            assertFalse(source.contains("com.kaleblangley.haikalat.subsystems."), file.toString());
            assertFalse(Pattern.compile("\\bgl[A-Z]\\w*\\s*\\(").matcher(source).find(), file.toString());
        }
    }

    @Test
    void decodeStagesRemainPackagePrivateBehindTheFacade() throws IOException {
        for (String stage : Set.of("GltfDocumentReader", "GltfUriResolver", "GltfBufferTable",
                "GltfAccessorDecoder", "GltfMaterialDecoder", "GltfNodeDecoder",
                "GltfMeshCanonicalizer")) {
            Path sourceFile = CORE.resolve(stage + ".java");
            assertTrue(Files.isRegularFile(sourceFile), "missing glTF decode stage: " + stage);
            String source = Files.readString(sourceFile);
            assertFalse(Pattern.compile("(?m)^public\\s+(?:final\\s+)?class\\s+" + stage + "\\b")
                    .matcher(source).find(), stage + " must remain package-private");
        }
        assertTrue(Files.readString(CORE.resolve("GltfAssetLoader.java"))
                .contains("public final class GltfAssetLoader"));
    }

    @Test
    void backendDoesNotDependOnGltfPackages() throws IOException {
        Path backend = Path.of("src/main/java/com/kaleblangley/haikalat/backend");
        for (Path file : javaFiles(backend)) {
            assertFalse(Files.readString(file).contains("assets.gltf"), file.toString());
        }
    }

    @Test
    void dedicatedDemoDoesNotDelegateToLearnOpenGlDemo() throws IOException {
        String source = Files.readString(Path.of(
                "src/demo/java/com/kaleblangley/haikalat/demo/gltf/GltfDemo.java"));
        assertFalse(source.contains("import com.kaleblangley.haikalat.demo.LearnOpenGlDemo"));
        assertFalse(source.contains("LearnOpenGlDemo."));
        assertTrue(source.contains("GltfDemoAssets.load()"));
        String assets = Files.readString(Path.of(
                "src/demo/java/com/kaleblangley/haikalat/demo/gltf/GltfDemoAssets.java"));
        assertTrue(assets.contains("/scenes/gltf/showcase.gltf"));
        assertTrue(assets.contains("/scenes/gltf/radio.gltf"));
    }

    private static void discover(Path root, Set<String> output) throws IOException {
        for (Path file : javaFiles(root)) {
            String source = Files.readString(file);
            Matcher type = PUBLIC.matcher(source);
            if (!type.find()) continue;
            Matcher packageName = PACKAGE.matcher(source);
            assertTrue(packageName.find());
            output.add(packageName.group(1) + "." + type.group(1));
        }
    }

    private static java.util.List<Path> javaFiles(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
