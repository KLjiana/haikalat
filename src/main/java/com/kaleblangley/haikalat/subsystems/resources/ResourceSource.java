package com.kaleblangley.haikalat.subsystems.resources;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Host-independent byte source used before CPU decoding. */
@FunctionalInterface
public interface ResourceSource {
    long DEFAULT_MAX_BYTES = 256L * 1024L * 1024L;

    byte[] read(AssetId assetId, long maxBytes) throws IOException;

    default byte[] read(AssetId assetId) throws IOException {
        return read(assetId, DEFAULT_MAX_BYTES);
    }

    /** Creates a source confined to one normalized directory and namespace. */
    static ResourceSource directory(String namespace, Path root) {
        String sourceNamespace = AssetId.of(namespace, "probe").namespace();
        Path sourceRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        return (assetId, maxBytes) -> {
            validateRequest(assetId, maxBytes, sourceNamespace);
            Path candidate = sourceRoot.resolve(
                    assetId.path().replace('/', java.io.File.separatorChar)).normalize();
            if (!candidate.startsWith(sourceRoot) || !Files.isRegularFile(candidate)) {
                throw new FileNotFoundException("Resource not found: " + assetId);
            }
            long size = Files.size(candidate);
            if (size > maxBytes) {
                throw new IOException("Resource exceeds byte limit " + maxBytes + ": " + assetId);
            }
            byte[] bytes = Files.readAllBytes(candidate);
            if (bytes.length > maxBytes) {
                throw new IOException("Resource exceeds byte limit " + maxBytes + ": " + assetId);
            }
            return bytes;
        };
    }

    /** Creates a classpath source rooted at the supplied anchor class. */
    static ResourceSource classpath(String namespace, Class<?> anchor) {
        String sourceNamespace = AssetId.of(namespace, "probe").namespace();
        Class<?> sourceAnchor = Objects.requireNonNull(anchor, "anchor");
        return (assetId, maxBytes) -> {
            validateRequest(assetId, maxBytes, sourceNamespace);
            try (InputStream stream = sourceAnchor.getResourceAsStream('/' + assetId.path())) {
                if (stream == null) {
                    throw new FileNotFoundException("Resource not found: " + assetId);
                }
                byte[] bytes = stream.readNBytes(Math.toIntExact(maxBytes + 1L));
                if (bytes.length > maxBytes) {
                    throw new IOException(
                            "Resource exceeds byte limit " + maxBytes + ": " + assetId);
                }
                return bytes;
            }
        };
    }

    private static void validateRequest(AssetId assetId, long maxBytes, String namespace)
            throws FileNotFoundException {
        Objects.requireNonNull(assetId, "assetId");
        if (maxBytes < 0L || maxBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "maxBytes must be in 0.." + (Integer.MAX_VALUE - 1L));
        }
        if (!namespace.equals(assetId.namespace())) {
            throw new FileNotFoundException("Resource namespace is not served: " + assetId);
        }
    }
}
