package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.core.assets.gltf.GltfAssetLoader;
import com.kaleblangley.haikalat.core.assets.gltf.GltfImageData;
import com.kaleblangley.haikalat.core.assets.gltf.GltfImageDecoder;
import com.kaleblangley.haikalat.core.assets.gltf.GltfLoadOptions;
import com.kaleblangley.haikalat.core.assets.gltf.LoadedGltfScene;
import com.kaleblangley.haikalat.core.assets.gltf.SceneSelection;
import com.kaleblangley.haikalat.subsystems.resources.AssetId;
import com.kaleblangley.haikalat.subsystems.resources.DirectoryResourceWatcher;
import com.kaleblangley.haikalat.subsystems.resources.ResourceCatalog;
import com.kaleblangley.haikalat.subsystems.resources.ResourceGeneration;
import com.kaleblangley.haikalat.subsystems.resources.ResourceGenerationTracker;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asynchronously reads and CPU-decodes serialized scenes.
 *
 * <p>This first service boundary intentionally stops before GPU creation. The
 * returned {@link SceneBuildPlan} is immutable and generation-tagged; stale
 * results are rejected before they can enter a future upload stage.</p>
 */
public final class SceneAssetService implements AutoCloseable {
    private static final int MAX_FAILURE_HISTORY = 16;
    private final ResourceCatalog catalog;
    private final ResourceGenerationTracker generations;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final int maxSceneBytes;
    private final boolean strictExtensions;
    private final ConcurrentHashMap<LoadKey, CompletableFuture<SceneBuildPlan>> pending =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SceneHandle, ManagedScene> managedScenes =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AssetId, Set<AssetId>> reverseDependencies =
            new ConcurrentHashMap<>();
    private final Set<DirectoryResourceWatcher> watchers =
            ConcurrentHashMap.newKeySet();
    private final AtomicReference<SceneLoadException> lastFailure = new AtomicReference<>();
    private final ArrayDeque<SceneAssetSnapshot.FailureSnapshot> failureHistory =
            new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SceneAssetService(ResourceCatalog catalog) {
        this(catalog, new ResourceGenerationTracker(),
                Executors.newVirtualThreadPerTaskExecutor(), true,
                SceneJsonParser.MAX_DOCUMENT_BYTES, true);
    }

    public SceneAssetService(ResourceCatalog catalog, ResourceGenerationTracker generations,
                             Executor executor) {
        this(catalog, generations, executor, false);
    }

    private SceneAssetService(ResourceCatalog catalog, ResourceGenerationTracker generations,
                              Executor executor, boolean ownsExecutor) {
        this(catalog, generations, executor, ownsExecutor,
                SceneJsonParser.MAX_DOCUMENT_BYTES, true);
    }

    private SceneAssetService(ResourceCatalog catalog, ResourceGenerationTracker generations,
                              Executor executor, boolean ownsExecutor,
                              int maxSceneBytes, boolean strictExtensions) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.generations = Objects.requireNonNull(generations, "generations");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownedExecutor = ownsExecutor ? (ExecutorService) executor : null;
        this.maxSceneBytes = maxSceneBytes;
        this.strictExtensions = strictExtensions;
    }

    public static SceneAssetService create(ResourceCatalog catalog, SceneAssetOptions options) {
        Objects.requireNonNull(options, "options");
        Executor executor = options.executor();
        boolean owns = executor == null;
        if (owns) executor = Executors.newVirtualThreadPerTaskExecutor();
        return new SceneAssetService(catalog, new ResourceGenerationTracker(), executor, owns,
                options.maxSceneBytes(), options.strictExtensions());
    }

    /**
     * Opens one scene at the current generation. Concurrent opens of the same
     * scene generation share one future and one CPU decode.
     */
    public CompletableFuture<SceneBuildPlan> loadPlan(AssetId sceneId) {
        ensureOpen();
        Objects.requireNonNull(sceneId, "sceneId");
        ResourceGenerationTracker.Ticket ticket = generations.capture(sceneId);
        LoadKey key = new LoadKey(sceneId, ticket.generation());
        CompletableFuture<SceneBuildPlan> existing = pending.get(key);
        if (existing != null) return existing;
        CompletableFuture<SceneBuildPlan> created = CompletableFuture.supplyAsync(() -> {
            try {
                return decode(key, ticket);
            } catch (SceneLoadException failure) {
                recordFailure(failure);
                throw failure;
            }
        }, executor);
        existing = pending.putIfAbsent(key, created);
        if (existing != null) {
            created.cancel(true);
            return existing;
        }
        created.whenComplete((ignored, failure) -> pending.remove(key, created));
        return created;
    }

    /**
     * Opens a stable logical handle and starts the first CPU generation
     * immediately. GPU work remains deferred to {@link #pumpUploads}.
     */
    public SceneHandle open(AssetId sceneId) {
        ensureOpen();
        Objects.requireNonNull(sceneId, "sceneId");
        SceneHandle[] holder = new SceneHandle[1];
        SceneHandle handle = new SceneHandle(sceneId, () -> closeManaged(holder[0]));
        holder[0] = handle;
        ManagedScene managed = new ManagedScene(handle);
        managedScenes.put(handle, managed);
        schedule(managed);
        return handle;
    }

    /** Advances the scene generation and invalidates all in-flight scene tickets. */
    public ResourceGeneration reload(AssetId sceneId) {
        ensureOpen();
        Objects.requireNonNull(sceneId, "sceneId");
        invalidate(sceneId);
        return generations.current(sceneId);
    }

    /**
     * Invalidates a changed dependency and all scenes that successfully
     * referenced it in their last build.
     */
    public Set<AssetId> invalidate(AssetId changedAsset) {
        ensureOpen();
        Objects.requireNonNull(changedAsset, "changedAsset");
        Set<AssetId> affected = new HashSet<>();
        ArrayDeque<AssetId> queue = new ArrayDeque<>();
        queue.add(changedAsset);
        while (!queue.isEmpty()) {
            AssetId current = queue.removeFirst();
            if (!affected.add(current)) continue;
            generations.invalidate(current);
            for (AssetId dependent : reverseDependencies.getOrDefault(current, Set.of())) {
                queue.addLast(dependent);
            }
        }
        managedScenes.values().stream()
                .filter(managed -> affected.contains(managed.handle.id()))
                .forEach(this::schedule);
        return Set.copyOf(affected);
    }

    public ResourceGeneration currentGeneration(AssetId assetId) {
        return generations.current(Objects.requireNonNull(assetId, "assetId"));
    }

    public int pendingLoadCount() {
        return pending.size();
    }

    /** Starts optional directory monitoring for one explicitly mounted namespace. */
    public DirectoryResourceWatcher watchDirectory(String namespace, Path root,
                                                   Duration debounce) throws IOException {
        ensureOpen();
        DirectoryResourceWatcher watcher = new DirectoryResourceWatcher(namespace, root,
                debounce, changed -> {
                    if (closed.get()) return;
                    changed.forEach(this::invalidate);
                });
        watchers.add(watcher);
        return watcher;
    }

    public int watcherCount() {
        return watchers.size();
    }

    /** Pumps the GL-thread cache at frame start without exposing upload internals. */
    public int pumpUploads(GltfGpuAssetCache gpuCache, SceneUploadBudget budget) {
        ensureOpen();
        Objects.requireNonNull(gpuCache, "gpuCache");
        Objects.requireNonNull(budget, "budget");
        for (ManagedScene managed : managedScenes.values()) {
            synchronized (managed) {
                if (managed.plan == null
                        && managed.cpuFuture != null
                        && managed.cpuFuture.isDone()
                        && !managed.cpuFuture.isCompletedExceptionally()
                        && !managed.cpuFuture.isCancelled()) {
                    // The completion callback normally publishes the plan, but
                    // pumpUploads may run on the GL thread immediately after a
                    // caller joins the CPU future. Hydrate it here as a
                    // deterministic hand-off instead of relying on callback
                    // scheduling.
                    managed.plan = managed.cpuFuture.join();
                }
                if (managed.plan == null || managed.uploads != null) continue;
                if (!generations.current(managed.plan.sceneId())
                        .equals(managed.plan.generation())) {
                    managed.plan = null;
                    continue;
                }
                Map<SceneBuildPlan.AssetVariant,
                        CompletableFuture<GltfGpuAssetCache.Lease>> uploads =
                        new LinkedHashMap<>();
                try {
                    managed.plan.gltfAssets().forEach((variant, loaded) ->
                            uploads.put(variant, gpuCache.request(variant.asset(),
                                    variant.generation(),
                                    variant.scene() + ";strict=" + variant.strictExtensions(),
                                    loaded,
                                    managed.plan.decodedImages()
                                            .getOrDefault(variant, Map.of()))));
                    managed.uploads = uploads;
                } catch (RuntimeException failure) {
                    uploads.values().forEach(SceneAssetService::cancelUpload);
                    throw failure;
                }
            }
        }
        int steps = gpuCache.pump(budget);
        for (ManagedScene managed : managedScenes.values()) {
            completeGpuCandidate(managed, gpuCache);
        }
        return steps;
    }

    /** Activates every complete candidate and atomically commits its handle. */
    public int applyReadyScenes(SceneActivator activator) {
        ensureOpen();
        Objects.requireNonNull(activator, "activator");
        int applied = 0;
        for (ManagedScene managed : managedScenes.values()) {
            SceneVersion candidate;
            synchronized (managed) {
                candidate = managed.ready;
                if (candidate == null) continue;
                managed.ready = null;
            }
            if (!generations.current(managed.handle.id()).equals(candidate.generation())) {
                candidate.close();
                continue;
            }
            try {
                if (!activator.activate(candidate)) {
                    candidate.close();
                    continue;
                }
                SceneVersion retired = managed.handle.commit(candidate);
                if (retired != null) retired.close();
                applied++;
            } catch (RuntimeException | Error failure) {
                try {
                    candidate.close();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                managed.handle.markFailure(failure);
                throw failure;
            }
        }
        return applied;
    }

    /**
     * Builds and publishes one ready CPU plan on the GL thread using a
     * prepare/activate/commit protocol.
     *
     * <p>If activation throws or rejects the candidate, the candidate is
     * closed and the handle keeps its old version. The old version is retired
     * only after activation and handle commit both succeed.</p>
     */
    public boolean applyReadyScene(SceneHandle handle, SceneBuildPlan plan,
                                   GltfGpuAssetCache gpuCache,
                                   SceneActivator activator) {
        ensureOpen();
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(gpuCache, "gpuCache");
        Objects.requireNonNull(activator, "activator");
        if (!generations.current(plan.sceneId()).equals(plan.generation())) return false;
        SceneVersion candidate = SceneVersion.build(plan, gpuCache);
        try {
            if (!generations.current(plan.sceneId()).equals(plan.generation())
                    || !activator.activate(candidate)) {
                candidate.close();
                return false;
            }
            SceneVersion retired = handle.commit(candidate);
            if (retired != null) retired.close();
            return true;
        } catch (RuntimeException | Error failure) {
            try {
                candidate.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public Map<AssetId, Set<AssetId>> reverseDependencies() {
        Map<AssetId, Set<AssetId>> snapshot = new HashMap<>();
        reverseDependencies.forEach((asset, dependents) ->
                snapshot.put(asset, Set.copyOf(dependents)));
        return Map.copyOf(snapshot);
    }

    public SceneAssetSnapshot snapshot() {
        int edges = reverseDependencies.values().stream().mapToInt(Set::size).sum();
        int pendingUploads = 0;
        int readyCandidates = 0;
        List<SceneAssetSnapshot.HandleSnapshot> handles = new ArrayList<>();
        for (ManagedScene managed : managedScenes.values()) {
            ResourceGeneration candidateGeneration;
            synchronized (managed) {
                if (managed.uploads != null) {
                    pendingUploads += (int) managed.uploads.values().stream()
                            .filter(upload -> !upload.isDone()).count();
                }
                if (managed.ready != null) readyCandidates++;
                candidateGeneration = managed.ready != null
                        ? managed.ready.generation()
                        : managed.plan != null ? managed.plan.generation() : null;
            }
            ResourceGeneration activeGeneration = managed.handle.currentOptional()
                    .map(SceneVersion::generation)
                    .orElse(null);
            String failureType = managed.handle.failure()
                    .map(failure -> failure.getClass().getName())
                    .orElse(null);
            handles.add(new SceneAssetSnapshot.HandleSnapshot(
                    managed.handle.id(), managed.handle.status(), activeGeneration,
                    candidateGeneration, failureType));
        }
        handles.sort(java.util.Comparator.comparing(
                snapshot -> snapshot.asset().toString()));
        SceneLoadException failure = lastFailure.get();
        List<SceneAssetSnapshot.FailureSnapshot> recentFailures;
        synchronized (failureHistory) {
            recentFailures = List.copyOf(failureHistory);
        }
        long overflows = watchers.stream().mapToLong(
                DirectoryResourceWatcher::overflowCount).sum();
        return new SceneAssetSnapshot(managedScenes.size(), pending.size(), pendingUploads,
                readyCandidates, watchers.size(), overflows, edges, generations.snapshot(),
                handles, recentFailures, failure == null ? null : failure.phase(),
                failure == null ? null : failure.asset());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        watchers.forEach(DirectoryResourceWatcher::close);
        watchers.clear();
        RuntimeException failure = null;
        for (SceneHandle handle : List.copyOf(managedScenes.keySet())) {
            try {
                handle.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        managedScenes.clear();
        if (ownedExecutor != null) ownedExecutor.shutdownNow();
        pending.values().forEach(future -> future.cancel(true));
        pending.clear();
        if (failure != null) throw failure;
    }

    private SceneBuildPlan decode(LoadKey key, ResourceGenerationTracker.Ticket ticket) {
        checkCurrent(ticket);
        byte[] encoded;
        try {
            encoded = catalog.readBytes(key.sceneId(), maxSceneBytes);
        } catch (IOException failure) {
            throw failure("READ_SCENE", key.sceneId(), failure);
        }
        checkCurrent(ticket);
        SceneDefinition definition;
        try {
            definition = SceneJsonParser.parse(key.sceneId(), encoded);
        } catch (RuntimeException failure) {
            throw failure("PARSE_SCENE", key.sceneId(), failure);
        }
        checkCurrent(ticket);

        Map<SceneBuildPlan.AssetVariant, LoadedGltfScene> assets = new HashMap<>();
        Map<SceneBuildPlan.AssetVariant, Map<Integer, GltfImageData>> decodedImages =
                new HashMap<>();
        List<SceneBuildPlan.InstancePlan> instances = new ArrayList<>();
        Set<AssetId> dependencies = new HashSet<>();
        Map<AssetId, Set<AssetId>> assetDependencies = new HashMap<>();
        dependencies.add(key.sceneId());
        for (SceneDefinition.NodeDefinition node : definition.nodes()) {
            SceneDefinition.RenderableDefinition renderable = node.renderable();
            if (renderable == null) continue;
            ResourceGenerationTracker.Ticket assetTicket =
                    generations.capture(renderable.asset());
            SceneBuildPlan.AssetVariant variant = new SceneBuildPlan.AssetVariant(
                    renderable.asset(), assetTicket.generation(), renderable.scene(),
                    strictExtensions);
            LoadedGltfScene decoded = assets.get(variant);
            if (decoded == null) {
                DecodedGltf result = decodeGltf(variant);
                decoded = result.scene();
                if (!generations.isCurrent(assetTicket)) {
                    throw new StaleSceneLoadException(assetTicket.assetId(),
                            assetTicket.generation());
                }
                assets.put(variant, decoded);
                assetDependencies.put(variant.asset(), result.dependencies());
                try {
                    decodedImages.put(variant, decodeImages(decoded));
                } catch (RuntimeException failure) {
                    throw failure("DECODE_DEPENDENCY", variant.asset(), failure);
                }
                dependencies.addAll(result.dependencies());
            }
            if (renderable.animated() && renderable.initialAnimation() != null
                    && decoded.animations().stream().noneMatch(animation ->
                    animation.name().equals(renderable.initialAnimation()))) {
                throw failure("VALIDATE_SCENE", variant.asset(),
                        new IllegalArgumentException("animation not found: "
                                + renderable.initialAnimation()));
            }
            instances.add(new SceneBuildPlan.InstancePlan(node.id(), node.parent(),
                    node.transform(), variant, renderable.animated(),
                    renderable.initialAnimation(), renderable.loop(),
                    renderable.castShadows()));
            checkCurrent(ticket);
        }
        assetDependencies.forEach(this::publishDependencies);
        publishDependencies(key.sceneId(), dependencies);
        return new SceneBuildPlan(key.sceneId(), key.generation(), definition, assets,
                decodedImages, instances);
    }

    private static Map<Integer, GltfImageData> decodeImages(LoadedGltfScene scene) {
        if (scene.images().isEmpty()) return Map.of();
        Set<Integer> usedImages = new HashSet<>();
        for (LoadedGltfScene.MaterialDef material : scene.materials()) {
            material.textureIndices().values().forEach(textureIndex ->
                    usedImages.add(scene.textures().get(textureIndex).imageIndex()));
        }
        Map<Integer, GltfImageData> decoded = new LinkedHashMap<>();
        for (LoadedGltfScene.ImageDef image : scene.images()) {
            if (!usedImages.contains(image.index())) continue;
            try {
                decoded.put(image.index(), GltfImageDecoder.decodeRgba8(image.encoded(), false));
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("failed to decode image " + image.index(),
                        failure);
            }
        }
        return Map.copyOf(decoded);
    }

    private void schedule(ManagedScene managed) {
        CompletableFuture<SceneBuildPlan> future = loadPlan(managed.handle.id());
        synchronized (managed) {
            if (managed.cpuFuture != null && managed.cpuFuture != future) {
                managed.cpuFuture.cancel(true);
            }
            if (managed.uploads != null) {
                managed.uploads.values().forEach(SceneAssetService::cancelUpload);
                managed.uploads = null;
            }
            if (managed.ready != null) {
                managed.ready.close();
                managed.ready = null;
            }
            managed.cpuFuture = future;
            managed.plan = null;
        }
        future.whenComplete((plan, failure) -> {
            synchronized (managed) {
                if (managed.cpuFuture != future || !managedScenes.containsKey(managed.handle)) {
                    return;
                }
                if (failure == null) {
                    managed.plan = plan;
                    return;
                }
                Throwable cause = failure instanceof CompletionException completion
                        ? completion.getCause() : failure;
                managed.handle.markFailure(cause);
            }
        });
    }

    private void completeGpuCandidate(ManagedScene managed, GltfGpuAssetCache cache) {
        SceneBuildPlan plan;
        Map<SceneBuildPlan.AssetVariant,
                CompletableFuture<GltfGpuAssetCache.Lease>> uploads;
        synchronized (managed) {
            plan = managed.plan;
            uploads = managed.uploads;
            if (plan == null || uploads == null
                    || uploads.values().stream().anyMatch(future -> !future.isDone())) return;
            managed.plan = null;
            managed.uploads = null;
        }
        List<GltfGpuAssetCache.Lease> stagingLeases = new ArrayList<>();
        try {
            for (CompletableFuture<GltfGpuAssetCache.Lease> future : uploads.values()) {
                stagingLeases.add(future.join());
            }
            if (!generations.current(plan.sceneId()).equals(plan.generation())) return;
            SceneVersion candidate = SceneVersion.build(plan, cache);
            synchronized (managed) {
                if (managed.ready != null) managed.ready.close();
                managed.ready = candidate;
            }
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            managed.handle.markFailure(uploadFailure(plan, cause));
        } catch (RuntimeException failure) {
            SceneLoadException structured = uploadFailure(plan, failure);
            managed.handle.markFailure(structured);
            throw structured;
        } finally {
            for (int i = stagingLeases.size() - 1; i >= 0; i--) {
                stagingLeases.get(i).close();
            }
        }
    }

    private SceneLoadException uploadFailure(SceneBuildPlan plan, Throwable cause) {
        SceneLoadException failure = cause instanceof SceneLoadException sceneFailure
                ? sceneFailure
                : new SceneLoadException("UPLOAD_GPU", plan.sceneId(), plan.generation(), cause);
        recordFailure(failure);
        return failure;
    }

    private void recordFailure(SceneLoadException failure) {
        lastFailure.set(failure);
        Throwable cause = failure.getCause();
        SceneAssetSnapshot.FailureSnapshot snapshot =
                new SceneAssetSnapshot.FailureSnapshot(
                        failure.phase(), failure.asset(), failure.generation(),
                        cause == null ? failure.getClass().getName()
                                : cause.getClass().getName());
        synchronized (failureHistory) {
            if (failureHistory.size() == MAX_FAILURE_HISTORY) {
                failureHistory.removeFirst();
            }
            failureHistory.addLast(snapshot);
        }
    }

    private void closeManaged(SceneHandle handle) {
        if (handle == null) return;
        ManagedScene managed = managedScenes.remove(handle);
        if (managed == null) return;
        synchronized (managed) {
            if (managed.cpuFuture != null) managed.cpuFuture.cancel(true);
            if (managed.uploads != null) {
                managed.uploads.values().forEach(SceneAssetService::cancelUpload);
            }
            if (managed.ready != null) {
                managed.ready.close();
                managed.ready = null;
            }
        }
    }

    private DecodedGltf decodeGltf(SceneBuildPlan.AssetVariant variant) {
        AssetId asset = variant.asset();
        CatalogAssetByteResolver resolver =
                new CatalogAssetByteResolver(catalog, asset.namespace(), generations);
        GltfAssetLoader loader = new GltfAssetLoader(resolver);
        GltfLoadOptions options = new GltfLoadOptions(parseSceneSelection(variant.scene()),
                strictExtensions, GltfLoadOptions.defaults().limits());
        try {
            com.kaleblangley.haikalat.core.assets.AssetRef ref =
                    com.kaleblangley.haikalat.core.assets.AssetRef.of(asset.path());
            LoadedGltfScene scene = SceneDefinition.isAnimationLibraryAsset(asset)
                    ? loader.loadAnimationLibrary(ref, options)
                    : loader.loadWithSidecar(ref, options);
            for (ResourceGenerationTracker.Ticket ticket : resolver.tickets().values()) {
                if (!generations.isCurrent(ticket)) {
                    throw new StaleSceneLoadException(ticket.assetId(), ticket.generation());
                }
            }
            return new DecodedGltf(scene, resolver.dependencies());
        } catch (RuntimeException failure) {
            throw failure("DECODE_DEPENDENCY", asset, failure);
        }
    }

    private static void cancelUpload(
            CompletableFuture<GltfGpuAssetCache.Lease> upload) {
        if (!upload.isDone()) {
            upload.cancel(false);
            return;
        }
        if (upload.isCancelled() || upload.isCompletedExceptionally()) return;
        upload.join().close();
    }

    private record DecodedGltf(LoadedGltfScene scene, Set<AssetId> dependencies) {}

    private static SceneSelection parseSceneSelection(String value) {
        if (value.equals("default")) return new SceneSelection.Default();
        if (value.startsWith("index:")) {
            try {
                return new SceneSelection.ByIndex(Integer.parseInt(value.substring(6)));
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("invalid scene index: " + value, failure);
            }
        }
        return new SceneSelection.ByName(value.substring("name:".length()));
    }

    private void publishDependencies(AssetId sceneId, Set<AssetId> dependencies) {
        reverseDependencies.forEach((dependency, dependents) -> {
            if (dependents.remove(sceneId) && dependents.isEmpty()) {
                reverseDependencies.remove(dependency, dependents);
            }
        });
        for (AssetId dependency : dependencies) {
            if (dependency.equals(sceneId)) continue;
            reverseDependencies.computeIfAbsent(dependency, ignored ->
                    ConcurrentHashMap.newKeySet()).add(sceneId);
        }
    }

    private void checkCurrent(ResourceGenerationTracker.Ticket ticket) {
        if (!generations.isCurrent(ticket)) {
            throw new StaleSceneLoadException(ticket.assetId(), ticket.generation());
        }
    }

    private RuntimeException failure(String phase, AssetId asset, Throwable cause) {
        return new SceneLoadException(phase, asset, currentGeneration(asset), cause);
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("SceneAssetService is closed");
    }

    private record LoadKey(AssetId sceneId, ResourceGeneration generation) {}

    private static final class ManagedScene {
        private final SceneHandle handle;
        private CompletableFuture<SceneBuildPlan> cpuFuture;
        private SceneBuildPlan plan;
        private Map<SceneBuildPlan.AssetVariant,
                CompletableFuture<GltfGpuAssetCache.Lease>> uploads;
        private SceneVersion ready;

        private ManagedScene(SceneHandle handle) {
            this.handle = handle;
        }
    }

    private static final class StaleSceneLoadException extends RuntimeException {
        StaleSceneLoadException(AssetId asset, ResourceGeneration generation) {
            super("stale scene generation " + asset + "@" + generation.value());
        }
    }

    /** Structured phase and identity information for an asynchronous scene failure. */
    public static final class SceneLoadException extends RuntimeException {
        private final String phase;
        private final AssetId asset;
        private final ResourceGeneration generation;

        SceneLoadException(String phase, AssetId asset, ResourceGeneration generation,
                           Throwable cause) {
            super(phase + " failed for " + asset + "@" + generation.value(), cause);
            this.phase = Objects.requireNonNull(phase, "phase");
            this.asset = Objects.requireNonNull(asset, "asset");
            this.generation = Objects.requireNonNull(generation, "generation");
        }

        public String phase() { return phase; }
        public AssetId asset() { return asset; }
        public ResourceGeneration generation() { return generation; }
    }

    @FunctionalInterface
    public interface SceneActivator {
        boolean activate(SceneVersion candidate);
    }
}
