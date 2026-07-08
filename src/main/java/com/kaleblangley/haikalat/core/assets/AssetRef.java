package com.kaleblangley.haikalat.core.assets;

import java.util.Objects;

public record AssetRef(String path) {
    public AssetRef {
        path = normalize(Objects.requireNonNull(path, "path"));
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
    }

    public static AssetRef of(String path) {
        return new AssetRef(path);
    }

    public String extension() {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) {
            return "";
        }
        return path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalize(String value) {
        return value.replace('\\', '/');
    }
}
