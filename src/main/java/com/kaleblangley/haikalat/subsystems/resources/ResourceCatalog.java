package com.kaleblangley.haikalat.subsystems.resources;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable namespace-to-source mapping for application assets.
 *
 * <p>A catalog never guesses a fallback source. Every logical identity must
 * resolve through the namespace explicitly mounted by the caller.</p>
 */
public final class ResourceCatalog {
    private final Map<String, ResourceSource> mounts;

    private ResourceCatalog(Map<String, ResourceSource> mounts) {
        this.mounts = Map.copyOf(mounts);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<ResourceSource> source(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        String normalized = AssetId.of(namespace, "probe").namespace();
        return Optional.ofNullable(mounts.get(normalized));
    }

    public ResourceSource requireSource(String namespace) {
        return source(namespace).orElseThrow(() ->
                new IllegalArgumentException("unknown resource namespace: " + namespace));
    }

    public byte[] readBytes(AssetId assetId, long maxBytes) throws IOException {
        return requireSource(assetId.namespace()).read(assetId, maxBytes);
    }

    public byte[] readBytes(AssetId assetId) throws IOException {
        return requireSource(assetId.namespace()).read(assetId);
    }

    public boolean exists(AssetId assetId) {
        Objects.requireNonNull(assetId, "assetId");
        ResourceSource source = mounts.get(assetId.namespace());
        if (source == null) return false;
        try {
            source.read(assetId, 0L);
            return true;
        } catch (java.io.FileNotFoundException missing) {
            return false;
        } catch (IOException failure) {
            return true;
        }
    }

    public Map<String, ResourceSource> mounts() {
        return mounts;
    }

    public static final class Builder {
        private final Map<String, ResourceSource> mounts = new LinkedHashMap<>();

        public Builder mount(String namespace, ResourceSource source) {
            Objects.requireNonNull(source, "source");
            String normalized = AssetId.of(namespace, "probe").namespace();
            if (mounts.putIfAbsent(normalized, source) != null) {
                throw new IllegalArgumentException("resource namespace already mounted: " + normalized);
            }
            return this;
        }

        public ResourceCatalog build() {
            if (mounts.isEmpty()) {
                throw new IllegalStateException("resource catalog must contain at least one mount");
            }
            return new ResourceCatalog(mounts);
        }
    }
}
