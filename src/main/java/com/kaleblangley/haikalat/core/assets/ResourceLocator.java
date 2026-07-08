package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.backend.GlException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ResourceLocator {
    private final Class<?> anchor;
    private final List<Path> roots = new ArrayList<>();

    private ResourceLocator(Class<?> anchor) {
        this.anchor = Objects.requireNonNull(anchor, "anchor");
    }

    public static ResourceLocator classpath(Class<?> anchor) {
        return new ResourceLocator(anchor);
    }

    public ResourceLocator addRoot(Path root) {
        roots.add(Objects.requireNonNull(root, "root"));
        return this;
    }

    public byte[] readBytes(AssetRef ref) {
        Optional<Path> file = resolveFile(ref);
        if (file.isPresent()) {
            try {
                return Files.readAllBytes(file.get());
            } catch (IOException e) {
                throw new GlException("Failed to read asset: " + ref.path(), e);
            }
        }
        try (InputStream stream = openClasspath(ref)) {
            if (stream == null) {
                throw new GlException("Asset not found: " + ref.path());
            }
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new GlException("Failed to read asset: " + ref.path(), e);
        }
    }

    public String readString(AssetRef ref) {
        return new String(readBytes(ref), StandardCharsets.UTF_8);
    }

    public Optional<Path> resolveFile(AssetRef ref) {
        Path raw = Path.of(ref.path());
        if (raw.isAbsolute() && Files.isRegularFile(raw)) {
            return Optional.of(raw);
        }
        for (Path root : roots) {
            Path candidate = root.resolve(ref.path()).normalize();
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private InputStream openClasspath(AssetRef ref) {
        String path = ref.path().startsWith("/") ? ref.path() : "/" + ref.path();
        return anchor.getResourceAsStream(path);
    }
}
