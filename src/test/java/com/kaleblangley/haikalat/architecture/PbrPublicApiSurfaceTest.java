package com.kaleblangley.haikalat.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 保证 v0.12 PBR 顶层 public 类型具有唯一且显式的兼容性分类。 */
class PbrPublicApiSurfaceTest {
    private static final Path PBR_SOURCE = Path.of("src", "main", "java", "com",
            "kaleblangley", "haikalat", "subsystems", "render3d", "pbr");
    private static final Path ALLOWLIST = Path.of("docs", "architecture",
            "public-api", "render3d.allowlist");
    private static final Pattern PACKAGE = Pattern.compile(
            "(?m)^package\\s+([A-Za-z0-9_.]+);");
    private static final Pattern PUBLIC_TOP_LEVEL = Pattern.compile(
            "(?m)^public\\s+(?:(?:final|abstract|sealed|non-sealed)\\s+)?"
                    + "(?:class|interface|record|enum|@interface)\\s+([A-Za-z0-9_]+)");
    private static final Set<String> CATEGORIES = Set.of("stable", "advanced", "internal");

    @Test
    void everyPublicPbrTypeHasExactlyOneCurrentClassification() throws IOException {
        Set<String> discovered = discoverPublicTypes();
        Map<String, String> classified = new HashMap<>(readAllowlist());
        classified.keySet().removeIf(type -> !type.startsWith(
                "com.kaleblangley.haikalat.subsystems.render3d.pbr."));
        Set<String> unclassified = new HashSet<>(discovered);
        unclassified.removeAll(classified.keySet());
        Set<String> stale = new HashSet<>(classified.keySet());
        stale.removeAll(discovered);

        assertEquals(Set.of(), unclassified,
                "new public PBR types require stable/advanced/internal classification");
        assertEquals(Set.of(), stale, "remove stale PBR API classifications");
        assertEquals("stable", classified.get(
                "com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment"));
        assertEquals("internal", classified.get(
                "com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterialBinder"));
    }

    private static Map<String, String> readAllowlist() throws IOException {
        Map<String, String> result = new HashMap<>();
        int lineNumber = 0;
        for (String sourceLine : Files.readAllLines(ALLOWLIST)) {
            lineNumber++;
            String line = sourceLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split("\\s+");
            assertEquals(2, fields.length, "invalid PBR API allowlist line " + lineNumber);
            assertTrue(CATEGORIES.contains(fields[0]),
                    "unknown PBR API category at line " + lineNumber + ": " + fields[0]);
            String previous = result.putIfAbsent(fields[1], fields[0]);
            assertEquals(null, previous,
                    "duplicate PBR API type at line " + lineNumber + ": " + fields[1]);
        }
        return Map.copyOf(result);
    }

    private static Set<String> discoverPublicTypes() throws IOException {
        Set<String> result = new HashSet<>();
        try (var files = Files.walk(PBR_SOURCE)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher type = PUBLIC_TOP_LEVEL.matcher(source);
                if (!type.find()) continue;
                Matcher packageName = PACKAGE.matcher(source);
                assertTrue(packageName.find(), "missing package declaration: " + file);
                assertTrue(result.add(packageName.group(1) + '.' + type.group(1)),
                        "duplicate public PBR type discovered in " + file);
            }
        }
        return Set.copyOf(result);
    }
}
