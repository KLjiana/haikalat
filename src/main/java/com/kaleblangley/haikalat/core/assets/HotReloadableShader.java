package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.EnumMap;
import java.util.Map;

public final class HotReloadableShader implements AutoCloseable {
    private final ResourceLocator locator;
    private final ShaderAsset asset;
    private ShaderProgram program;
    private final EnumMap<ShaderStage, Long> stamps = new EnumMap<>(ShaderStage.class);
    private boolean closed;

    public HotReloadableShader(ResourceLocator locator, ShaderAsset asset) {
        this.locator = Objects.requireNonNull(locator, "locator");
        this.asset = Objects.requireNonNull(asset, "asset");
    }

    public ShaderProgram program() {
        ensureOpen();
        if (program == null) {
            reload();
        }
        return program;
    }

    public boolean reloadIfChanged() {
        ensureOpen();
        if (program == null || changed()) {
            reload();
            return true;
        }
        return false;
    }

    public ShaderProgram reload() {
        ensureOpen();
        ShaderProgram.Builder builder = ShaderProgram.builder();
        for (Map.Entry<ShaderStage, AssetRef> entry : asset.stages().entrySet()) {
            builder.stage(entry.getKey(), locator.readString(entry.getValue()), entry.getValue().path());
        }
        ShaderProgram next = builder.link();
        ShaderProgram old = program;
        program = next;
        asset.stages().forEach((stage, ref) -> stamps.put(stage, timestamp(ref)));
        if (old != null) {
            old.close();
        }
        return program;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (program != null) {
            program.close();
        }
        closed = true;
    }

    private long timestamp(AssetRef ref) {
        Optional<Path> file = locator.resolveFile(ref);
        if (file.isEmpty()) {
            return 0L;
        }
        try {
            return Files.getLastModifiedTime(file.get()).toMillis();
        } catch (java.io.IOException e) {
            return 0L;
        }
    }

    private boolean changed() {
        for (Map.Entry<ShaderStage, AssetRef> entry : asset.stages().entrySet()) {
            if (timestamp(entry.getValue()) != stamps.getOrDefault(entry.getKey(), Long.MIN_VALUE)) {
                return true;
            }
        }
        return false;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("HotReloadableShader is closed");
        }
    }
}
