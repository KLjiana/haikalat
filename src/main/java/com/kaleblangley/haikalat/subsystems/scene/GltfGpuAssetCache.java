package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.core.assets.gltf.LoadedGltfScene;
import com.kaleblangley.haikalat.core.assets.gltf.GltfImageData;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfRuntimeLibrary;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfSceneAsset;
import com.kaleblangley.haikalat.subsystems.resources.AssetId;
import com.kaleblangley.haikalat.subsystems.resources.ResourceGeneration;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * GL-thread cache keyed by exact logical asset identity, generation and load
 * variant. A lease pins an uploaded asset until its owning SceneVersion retires.
 */
public final class GltfGpuAssetCache implements AutoCloseable {
    private final GltfRuntimeLibrary library;
    private final Map<Key, Entry> entries = new LinkedHashMap<>();
    private final ArrayDeque<Key> uploadQueue = new ArrayDeque<>();
    private boolean closed;

    public GltfGpuAssetCache(GltfRuntimeLibrary library) {
        this.library = Objects.requireNonNull(library, "library");
    }

    public synchronized Lease acquire(AssetId asset, ResourceGeneration generation,
                                       String variant, LoadedGltfScene source) {
        return acquire(asset, generation, variant, source, Map.of());
    }

    public synchronized Lease acquire(AssetId asset, ResourceGeneration generation,
                                       String variant, LoadedGltfScene source,
                                       Map<Integer, GltfImageData> decodedImages) {
        ensureOpen();
        Key key = new Key(asset, generation, variant);
        Entry entry = entries.get(key);
        if (entry == null) {
            entry = new Entry(GltfSceneAsset.beginUpload(source, library, decodedImages));
            entries.put(key, entry);
            uploadQueue.addLast(key);
        }
        if (entry.asset == null) {
            entry.references++;
            try {
                while (!entry.session.isComplete()) entry.session.advance(Integer.MAX_VALUE);
                finishEntry(key, entry);
                uploadQueue.remove(key);
                return new Lease(this, key, entry.asset);
            } catch (RuntimeException failure) {
                failEntry(key, entry, failure);
                throw failure;
            }
        }
        entry.references++;
        return new Lease(this, key, entry.asset);
    }

    /**
     * Coalesces an exact-generation staged upload request. The future completes
     * only after {@link #pump(SceneUploadBudget)} finishes the GPU asset.
     */
    public synchronized CompletableFuture<Lease> request(
            AssetId asset, ResourceGeneration generation, String variant,
            LoadedGltfScene source) {
        return request(asset, generation, variant, source, Map.of());
    }

    public synchronized CompletableFuture<Lease> request(
            AssetId asset, ResourceGeneration generation, String variant,
            LoadedGltfScene source, Map<Integer, GltfImageData> decodedImages) {
        ensureOpen();
        Key key = new Key(asset, generation, variant);
        Entry entry = entries.get(key);
        if (entry != null && entry.asset != null) {
            entry.references++;
            return CompletableFuture.completedFuture(new Lease(this, key, entry.asset));
        }
        if (entry == null) {
            entry = new Entry(GltfSceneAsset.beginUpload(source, library, decodedImages));
            entries.put(key, entry);
            uploadQueue.addLast(key);
        }
        CompletableFuture<Lease> future = new CompletableFuture<>();
        entry.waiters.add(future);
        return future;
    }

    /**
     * Advances pending uploads in round-robin order within a soft step/time budget.
     *
     * @return resource creation steps completed
     */
    public synchronized int pump(SceneUploadBudget budget) {
        ensureOpen();
        Objects.requireNonNull(budget, "budget");
        long started = System.nanoTime();
        int steps = 0;
        long bytes = 0L;
        while (!uploadQueue.isEmpty() && steps < budget.maxSteps()
                && bytes < budget.maxBytes()
                && System.nanoTime() - started < budget.maxNanos()) {
            Key key = uploadQueue.removeFirst();
            Entry entry = entries.get(key);
            if (entry == null || entry.asset != null) continue;
            if (entry.waiters.stream().allMatch(CompletableFuture::isCancelled)) {
                entries.remove(key);
                entry.session.close();
                continue;
            }
            try {
                long estimate = entry.session.estimatedNextBytes();
                if (steps > 0 && estimate > 0L
                        && estimate > budget.maxBytes() - bytes) {
                    uploadQueue.addLast(key);
                    break;
                }
                steps += entry.session.advance(1);
                bytes = Math.addExact(bytes, estimate);
                if (entry.session.isComplete()) {
                    finishEntry(key, entry);
                } else {
                    uploadQueue.addLast(key);
                }
            } catch (RuntimeException failure) {
                failEntry(key, entry, failure);
                throw failure;
            }
        }
        return steps;
    }

    public synchronized int entryCount() {
        return entries.size();
    }

    public synchronized int activeLeaseCount() {
        return entries.values().stream().mapToInt(entry -> entry.references).sum();
    }

    public synchronized int pendingUploadCount() {
        return uploadQueue.size();
    }

    public synchronized List<Key> keys() {
        return List.copyOf(entries.keySet());
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        if (activeLeaseCount() != 0) {
            throw new IllegalStateException("cannot close GPU asset cache while leases are active");
        }
        closed = true;
        RuntimeException failure = null;
        List<Entry> values = new ArrayList<>(entries.values());
        entries.clear();
        uploadQueue.clear();
        for (int i = values.size() - 1; i >= 0; i--) {
            try {
                Entry entry = values.get(i);
                if (entry.asset != null) entry.asset.close();
                if (entry.session != null) entry.session.close();
                entry.waiters.forEach(future -> future.completeExceptionally(
                        new IllegalStateException("GPU asset cache closed before upload completed")));
            } catch (RuntimeException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private synchronized void release(Key key, GltfSceneAsset expected) {
        Entry entry = entries.get(key);
        if (entry == null || entry.asset != expected || entry.references <= 0) {
            throw new IllegalStateException("GPU asset lease is not active: " + key);
        }
        entry.references--;
        if (entry.references != 0) return;
        entries.remove(key);
        entry.asset.close();
    }

    private void finishEntry(Key key, Entry entry) {
        entry.asset = entry.session.finish();
        entry.session = null;
        for (CompletableFuture<Lease> waiter : entry.waiters) {
            if (waiter.isCancelled()) continue;
            entry.references++;
            waiter.complete(new Lease(this, key, entry.asset));
        }
        entry.waiters.clear();
        if (entry.references == 0) {
            entries.remove(key);
            entry.asset.close();
        }
    }

    private void failEntry(Key key, Entry entry, RuntimeException failure) {
        entries.remove(key);
        uploadQueue.remove(key);
        if (entry.session != null) {
            try {
                entry.session.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        entry.waiters.forEach(waiter -> waiter.completeExceptionally(failure));
        entry.waiters.clear();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("GPU asset cache is closed");
    }

    public record Key(AssetId asset, ResourceGeneration generation, String variant) {
        public Key {
            Objects.requireNonNull(asset, "asset");
            Objects.requireNonNull(generation, "generation");
            variant = Objects.requireNonNull(variant, "variant");
        }
    }

    public static final class Lease implements AutoCloseable {
        private final GltfGpuAssetCache owner;
        private final Key key;
        private final GltfSceneAsset asset;
        private boolean closed;

        private Lease(GltfGpuAssetCache owner, Key key, GltfSceneAsset asset) {
            this.owner = owner;
            this.key = key;
            this.asset = asset;
        }

        public GltfSceneAsset asset() {
            if (closed) throw new IllegalStateException("GPU asset lease is closed");
            return asset;
        }

        public Key key() {
            return key;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            owner.release(key, asset);
        }
    }

    private static final class Entry {
        private GltfSceneAsset asset;
        private GltfSceneAsset.UploadSession session;
        private final List<CompletableFuture<Lease>> waiters = new ArrayList<>();
        private int references;

        private Entry(GltfSceneAsset asset) {
            this.asset = asset;
        }

        private Entry(GltfSceneAsset.UploadSession session) {
            this.session = session;
        }
    }
}
