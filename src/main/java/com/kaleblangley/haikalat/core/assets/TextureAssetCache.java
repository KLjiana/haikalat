package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class TextureAssetCache implements AutoCloseable {
    private final TextureLoader loader;
    private final boolean defaultFlipVertically;
    private final Map<TextureKey, Texture2D> textures = new LinkedHashMap<>();
    private boolean closed;

    public TextureAssetCache(Function<AssetRef, Texture2D> loader) {
        this((path, flipVertically, colorSpace) -> loader.apply(path), true);
    }

    public TextureAssetCache(TextureLoader loader) {
        this(loader, true);
    }

    private TextureAssetCache(TextureLoader loader, boolean defaultFlipVertically) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.defaultFlipVertically = defaultFlipVertically;
    }

    public static TextureAssetCache classpath(Class<?> anchor, boolean flipVertically) {
        Objects.requireNonNull(anchor, "anchor");
        return new TextureAssetCache((path, flip, colorSpace) ->
                Texture2D.fromResource(anchor, path.path(), flip, colorSpace), flipVertically);
    }

    public Texture2D get(String path) {
        return get(AssetRef.of(path));
    }

    public Texture2D get(AssetRef ref) {
        return get(ref, defaultFlipVertically, TextureColorSpace.LINEAR);
    }

    /**
     * 按资源路径、翻转方式和颜色空间获取纹理；三个字段共同构成缓存键。
     *
     * @param path           资源路径
     * @param flipVertically 是否垂直翻转
     * @param colorSpace     采样颜色空间
     * @return 已缓存或新加载的纹理
     */
    public Texture2D get(String path, boolean flipVertically, TextureColorSpace colorSpace) {
        return get(AssetRef.of(path), flipVertically, colorSpace);
    }

    public Texture2D get(AssetRef ref, boolean flipVertically, TextureColorSpace colorSpace) {
        ensureOpen();
        TextureKey key = new TextureKey(Objects.requireNonNull(ref, "ref"), flipVertically,
                Objects.requireNonNull(colorSpace, "colorSpace"));
        return textures.computeIfAbsent(key,
                ignored -> loader.load(key.path(), key.flipVertically(), key.colorSpace()));
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

    /** 负责把纯数据缓存键转换为 OpenGL 纹理。 */
    @FunctionalInterface
    public interface TextureLoader {
        Texture2D load(AssetRef path, boolean flipVertically, TextureColorSpace colorSpace);
    }

    /**
     * 纹理缓存的完整身份，避免同一路径的线性与 sRGB 资源错误复用。
     *
     * @param path 纹理资产路径
     * @param flipVertically 是否垂直翻转
     * @param colorSpace 纹理颜色空间
     */
    public record TextureKey(AssetRef path, boolean flipVertically, TextureColorSpace colorSpace) {
        public TextureKey {
            path = Objects.requireNonNull(path, "path");
            colorSpace = Objects.requireNonNull(colorSpace, "colorSpace");
        }
    }
}
