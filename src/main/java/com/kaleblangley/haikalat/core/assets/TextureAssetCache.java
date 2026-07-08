package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.backend.texture.Texture2D;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class TextureAssetCache implements AutoCloseable {
    private final Function<AssetRef, Texture2D> loader;
    private final Map<AssetRef, Texture2D> textures = new LinkedHashMap<>();
    private boolean closed;

    public TextureAssetCache(Function<AssetRef, Texture2D> loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public static TextureAssetCache classpath(Class<?> anchor, boolean flipVertically) {
        Objects.requireNonNull(anchor, "anchor");
        return new TextureAssetCache(ref -> Texture2D.fromResource(anchor, ref.path(), flipVertically));
    }

    public Texture2D get(String path) {
        return get(AssetRef.of(path));
    }

    public Texture2D get(AssetRef ref) {
        ensureOpen();
        return textures.computeIfAbsent(Objects.requireNonNull(ref, "ref"), loader);
    }

    public int size() {
        return textures.size();
    }

    public void clear() {
        ensureOpen();
        for (Texture2D texture : textures.values()) {
            texture.close();
        }
        textures.clear();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        for (Texture2D texture : textures.values()) {
            texture.close();
        }
        textures.clear();
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("TextureAssetCache is closed");
        }
    }
}
