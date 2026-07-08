package com.kaleblangley.haikalat.core.assets;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ModelAssetManager {
    private final Map<String, ModelAssetLoader> loaders = new LinkedHashMap<>();

    public ModelAssetManager register(String extension, ModelAssetLoader loader) {
        loaders.put(normalize(extension), Objects.requireNonNull(loader, "loader"));
        return this;
    }

    public LoadedModel load(String path) {
        return load(AssetRef.of(path));
    }

    public LoadedModel load(AssetRef ref) {
        ModelAssetLoader loader = loaders.get(ref.extension());
        if (loader == null) {
            throw new IllegalArgumentException("No model loader registered for extension: " + ref.extension());
        }
        return loader.load(ref);
    }

    public boolean supports(String extension) {
        return loaders.containsKey(normalize(extension));
    }

    private static String normalize(String extension) {
        String value = Objects.requireNonNull(extension, "extension").toLowerCase(java.util.Locale.ROOT);
        return value.startsWith(".") ? value.substring(1) : value;
    }
}
