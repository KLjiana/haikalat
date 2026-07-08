package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class HotReloadableShader implements AutoCloseable {
    private final ResourceLocator locator;
    private final ShaderAsset asset;
    private ShaderProgram program;
    private long vertexStamp = Long.MIN_VALUE;
    private long fragmentStamp = Long.MIN_VALUE;
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
        long newVertexStamp = timestamp(asset.vertexShader());
        long newFragmentStamp = timestamp(asset.fragmentShader());
        if (program == null || newVertexStamp != vertexStamp || newFragmentStamp != fragmentStamp) {
            reload();
            return true;
        }
        return false;
    }

    public ShaderProgram reload() {
        ensureOpen();
        ShaderProgram next = ShaderProgram.fromSources(
                locator.readString(asset.vertexShader()),
                locator.readString(asset.fragmentShader()));
        ShaderProgram old = program;
        program = next;
        vertexStamp = timestamp(asset.vertexShader());
        fragmentStamp = timestamp(asset.fragmentShader());
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

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("HotReloadableShader is closed");
        }
    }
}
