package com.kaleblangley.haikalat.subsystems.resources;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Objects;

/** Stable, host-independent identity for an encoded engine resource. */
public record AssetId(String namespace, String path) {
    public static final String DEFAULT_NAMESPACE = "haikalat";

    public AssetId {
        namespace = normalizeNamespace(namespace);
        path = normalizePath(path);
    }

    public static AssetId of(String path) {
        return new AssetId(DEFAULT_NAMESPACE, path);
    }

    public static AssetId of(String namespace, String path) {
        return new AssetId(namespace, path);
    }

    /** Parses either {@code namespace:path} or a path in the default namespace. */
    public static AssetId parse(String value) {
        Objects.requireNonNull(value, "value");
        int separator = value.indexOf(':');
        if (separator < 0) {
            return of(value);
        }
        return of(value.substring(0, separator), value.substring(separator + 1));
    }

    /** Resolves a local resource reference relative to this asset's directory. */
    public AssetId resolve(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        if (relativePath.isBlank() || isAbsolute(relativePath)) {
            throw new IllegalArgumentException("relativePath must be a non-absolute resource path");
        }
        int slash = path.lastIndexOf('/');
        String directory = slash < 0 ? "" : path.substring(0, slash + 1);
        return new AssetId(namespace, directory + relativePath);
    }

    public String extension() {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot <= slash || dot == path.length() - 1
                ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return namespace + ':' + path;
    }

    private static String normalizeNamespace(String value) {
        String normalized = Objects.requireNonNull(value, "namespace").strip()
                .toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException(
                    "namespace must match [a-z0-9][a-z0-9._-]*");
        }
        return normalized;
    }

    private static String normalizePath(String value) {
        String original = Objects.requireNonNull(value, "path");
        if (original.indexOf('\0') >= 0 || isAbsolute(original)
                || original.indexOf('?') >= 0 || original.indexOf('#') >= 0
                || original.indexOf(':') >= 0) {
            throw new IllegalArgumentException("path must be a local logical resource path: "
                    + original);
        }
        ArrayDeque<String> segments = new ArrayDeque<>();
        for (String segment : original.replace('\\', '/').split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    throw new IllegalArgumentException("path escapes its resource root: " + original);
                }
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        return String.join("/", segments);
    }

    private static boolean isAbsolute(String path) {
        String normalized = path.replace('\\', '/');
        return normalized.startsWith("/") || normalized.startsWith("//")
                || normalized.matches("^[A-Za-z]:.*");
    }
}
