package com.kaleblangley.haikalat.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 读取并校验 v0.14 分领域公共 API 分类清单。 */
final class PublicApiCatalog {
    static final Path DIRECTORY = Path.of("docs", "architecture", "public-api");
    static final Set<String> FILES = Set.of(
            "animation.allowlist", "backend.allowlist", "core.allowlist", "runtime.allowlist",
            "render3d.allowlist", "postprocess.allowlist", "resources.allowlist", "ui.allowlist",
            "vfx.allowlist", "windowing.allowlist");
    static final Set<String> CATEGORIES = Set.of("stable", "advanced", "internal");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([A-Za-z0-9_.]+);");
    private static final Pattern PUBLIC_TOP_LEVEL = Pattern.compile(
            "(?m)^public\\s+(?:(?:final|abstract|sealed|non-sealed)\\s+)?"
                    + "(?:class|interface|record|enum|@interface)\\s+([A-Za-z0-9_]+)");

    private PublicApiCatalog() {
    }

    static Map<String, Entry> read() throws IOException {
        Map<String, Entry> result = new HashMap<>();
        for (String fileName : FILES) {
            Path file = DIRECTORY.resolve(fileName);
            if (!Files.isRegularFile(file)) {
                throw new AssertionError("missing public API allowlist: " + file);
            }
            int lineNumber = 0;
            for (String sourceLine : Files.readAllLines(file)) {
                lineNumber++;
                String line = sourceLine.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] fields = line.split("\\s+");
                if (fields.length != 2 || !CATEGORIES.contains(fields[0])) {
                    throw new AssertionError("invalid public API entry " + file + ":" + lineNumber
                            + ": " + sourceLine);
                }
                Entry entry = new Entry(fields[0], fields[1], fileName, lineNumber);
                Entry previous = result.putIfAbsent(entry.type(), entry);
                if (previous != null) {
                    throw new AssertionError("public API type appears more than once: " + entry.type()
                            + " in " + previous.file() + " and " + fileName);
                }
            }
        }
        return Map.copyOf(result);
    }

    static Set<String> discover(Path sourceRoot) throws IOException {
        Set<String> result = new HashSet<>();
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher type = PUBLIC_TOP_LEVEL.matcher(source);
                if (!type.find()) continue;
                Matcher packageName = PACKAGE.matcher(source);
                if (!packageName.find()) {
                    throw new AssertionError("missing package declaration: " + file);
                }
                String qualifiedName = packageName.group(1) + '.' + type.group(1);
                if (!result.add(qualifiedName)) {
                    throw new AssertionError("duplicate public top-level type: " + qualifiedName);
                }
            }
        }
        return Set.copyOf(result);
    }

    static Set<String> classifiedUnder(Map<String, Entry> catalog, String packagePrefix) {
        Set<String> result = new HashSet<>();
        catalog.keySet().stream().filter(type -> type.startsWith(packagePrefix)).forEach(result::add);
        return Set.copyOf(result);
    }

    record Entry(String category, String type, String file, int line) {
    }
}
