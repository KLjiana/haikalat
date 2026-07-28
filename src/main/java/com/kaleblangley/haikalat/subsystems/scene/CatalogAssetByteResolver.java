package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.core.assets.AssetByteResolver;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.subsystems.resources.AssetId;
import com.kaleblangley.haikalat.subsystems.resources.ResourceCatalog;
import com.kaleblangley.haikalat.subsystems.resources.ResourceGenerationTracker;

import java.io.IOException;
import java.util.Set;
import java.util.Objects;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binds core's local AssetRef view to one explicitly mounted catalog namespace.
 * This adapter is intentionally package-private; scene orchestration owns it.
 */
final class CatalogAssetByteResolver implements AssetByteResolver {
    private final ResourceCatalog catalog;
    private final String namespace;
    private final ResourceGenerationTracker generations;
    private final Set<AssetId> dependencies = ConcurrentHashMap.newKeySet();
    private final Map<AssetId, ResourceGenerationTracker.Ticket> tickets =
            new ConcurrentHashMap<>();

    CatalogAssetByteResolver(ResourceCatalog catalog, String namespace) {
        this(catalog, namespace, null);
    }

    CatalogAssetByteResolver(ResourceCatalog catalog, String namespace,
                             ResourceGenerationTracker generations) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.namespace = AssetId.of(namespace, "probe").namespace();
        this.generations = generations;
        catalog.requireSource(this.namespace);
    }

    @Override
    public byte[] readBytes(AssetRef ref, long maxBytes) {
        AssetId asset = toAssetId(ref);
        track(asset);
        try {
            return catalog.readBytes(asset, maxBytes);
        } catch (IOException failure) {
            throw new IllegalArgumentException("failed to read " + asset, failure);
        }
    }

    @Override
    public boolean exists(AssetRef ref) {
        AssetId asset = toAssetId(ref);
        track(asset);
        return catalog.exists(asset);
    }

    @Override
    public AssetRef resolveRelative(AssetRef owner, String uri) {
        String ownerPath = owner.path();
        if (ownerPath.startsWith("/")) ownerPath = ownerPath.substring(1);
        AssetId resolved = AssetId.of(namespace, ownerPath).resolve(uri);
        track(resolved);
        return AssetRef.of(resolved.path());
    }

    Set<AssetId> dependencies() {
        return Set.copyOf(dependencies);
    }

    Map<AssetId, ResourceGenerationTracker.Ticket> tickets() {
        return Map.copyOf(new LinkedHashMap<>(tickets));
    }

    private void track(AssetId asset) {
        dependencies.add(asset);
        if (generations != null) {
            tickets.computeIfAbsent(asset, generations::capture);
        }
    }

    private AssetId toAssetId(AssetRef ref) {
        String path = ref.path().startsWith("/") ? ref.path().substring(1) : ref.path();
        return AssetId.of(namespace, path);
    }
}
