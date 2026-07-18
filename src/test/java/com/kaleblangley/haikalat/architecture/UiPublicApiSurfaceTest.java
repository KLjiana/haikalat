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

/** 保证 v0.11 UI 顶层 public 类型均具有唯一、显式的兼容性分类。 */
class UiPublicApiSurfaceTest {
    private static final Path UI_SOURCE = Path.of("src", "main", "java", "com",
            "kaleblangley", "haikalat", "subsystems", "ui");
    private static final Path ALLOWLIST = Path.of("docs", "architecture",
            "public-api", "ui.allowlist");
    private static final Pattern PACKAGE = Pattern.compile(
            "(?m)^package\\s+([A-Za-z0-9_.]+);");
    private static final Pattern PUBLIC_TOP_LEVEL = Pattern.compile(
            "(?m)^public\\s+(?:(?:final|abstract|sealed|non-sealed)\\s+)?"
                    + "(?:class|interface|record|enum|@interface)\\s+([A-Za-z0-9_]+)");
    private static final Set<String> CATEGORIES = Set.of("stable", "advanced", "internal");

    @Test
    void everyPublicUiTypeHasExactlyOneCurrentClassification() throws IOException {
        Set<String> discovered = discoverPublicTypes();
        Map<String, String> classified = new HashMap<>(readAllowlist());
        Set<String> unclassified = new HashSet<>(discovered);
        unclassified.removeAll(classified.keySet());
        Set<String> stale = new HashSet<>(classified.keySet());
        stale.removeAll(discovered);

        assertEquals(Set.of(), unclassified,
                "new public UI types require stable/advanced/internal classification");
        assertEquals(Set.of(), stale, "remove stale UI API classifications");
        assertEquals("stable", classified.get(
                "com.kaleblangley.haikalat.subsystems.ui.UiSystem"));
        assertEquals("stable", classified.get(
                "com.kaleblangley.haikalat.subsystems.ui.UiNode"));
        classified.forEach((type, category) -> {
            if (type.contains(".render.")) {
                assertEquals("internal", category,
                        "UI renderer protocol must not become public API implicitly: " + type);
            }
        });
    }

    private static Map<String, String> readAllowlist() throws IOException {
        Map<String, String> result = new HashMap<>();
        int lineNumber = 0;
        for (String sourceLine : Files.readAllLines(ALLOWLIST)) {
            lineNumber++;
            String line = sourceLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split("\\s+");
            assertEquals(2, fields.length, "invalid UI API allowlist line " + lineNumber);
            assertTrue(CATEGORIES.contains(fields[0]),
                    "unknown UI API category at line " + lineNumber + ": " + fields[0]);
            String previous = result.putIfAbsent(fields[1], fields[0]);
            assertEquals(null, previous,
                    "duplicate UI API type at line " + lineNumber + ": " + fields[1]);
        }
        return Map.copyOf(result);
    }

    private static Set<String> discoverPublicTypes() throws IOException {
        Set<String> result = new HashSet<>();
        try (var files = Files.walk(UI_SOURCE)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher type = PUBLIC_TOP_LEVEL.matcher(source);
                if (!type.find()) continue;
                Matcher packageName = PACKAGE.matcher(source);
                assertTrue(packageName.find(), "missing package declaration: " + file);
                assertTrue(result.add(packageName.group(1) + '.' + type.group(1)),
                        "duplicate public UI type discovered in " + file);
            }
        }
        return Set.copyOf(result);
    }
}
