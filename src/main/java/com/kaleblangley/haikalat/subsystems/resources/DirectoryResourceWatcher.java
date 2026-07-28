package com.kaleblangley.haikalat.subsystems.resources;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Optional development-time watcher for one directory-backed namespace.
 * File events are debounced and delivered as a coalesced logical AssetId set.
 */
public final class DirectoryResourceWatcher implements AutoCloseable {
    private final Path root;
    private final String namespace;
    private final Consumer<Set<AssetId>> listener;
    private final long debounceNanos;
    private final WatchService watchService;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong overflowCount = new AtomicLong();
    private final LinkedHashSet<AssetId> knownAssets = new LinkedHashSet<>();
    private final Thread worker;
    private volatile RuntimeException lastFailure;

    public DirectoryResourceWatcher(String namespace, Path root,
                                    Duration debounce, Consumer<Set<AssetId>> listener)
            throws IOException {
        this.namespace = AssetId.of(namespace, "probe").namespace();
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.listener = Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(debounce, "debounce");
        if (debounce.isNegative() || debounce.isZero()) {
            throw new IllegalArgumentException("debounce must be positive");
        }
        this.debounceNanos = debounce.toNanos();
        if (!Files.isDirectory(this.root)) {
            throw new IllegalArgumentException("watch root must be a directory: " + this.root);
        }
        this.watchService = FileSystems.getDefault().newWatchService();
        registerTree(this.root);
        knownAssets.addAll(scanAll());
        this.worker = Thread.ofVirtual().name("haikalat-resource-watcher-" + namespace).start(this::run);
    }

    public long overflowCount() {
        return overflowCount.get();
    }

    public RuntimeException lastFailure() {
        return lastFailure;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            watchService.close();
        } catch (IOException failure) {
            lastFailure = new IllegalStateException("failed to close resource watcher", failure);
        }
        worker.interrupt();
        if (Thread.currentThread() == worker) return;
        try {
            worker.join(1000L);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            lastFailure = new IllegalStateException("resource watcher did not stop cleanly", failure);
        }
    }

    private void run() {
        LinkedHashSet<AssetId> pending = new LinkedHashSet<>();
        long deadline = Long.MAX_VALUE;
        try {
            while (!closed.get()) {
                long waitNanos = pending.isEmpty() ? 0L : Math.max(1L, deadline - System.nanoTime());
                WatchKey key = waitNanos == 0L
                        ? watchService.take()
                        : watchService.poll(waitNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
                if (key != null) {
                    collect(key, pending);
                    if (!pending.isEmpty()) deadline = System.nanoTime() + debounceNanos;
                    if (!key.reset()) break;
                }
                if (!pending.isEmpty() && System.nanoTime() >= deadline) {
                    emit(pending);
                    pending.clear();
                    deadline = Long.MAX_VALUE;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException ignored) {
            // Normal close path.
        }
        if (!pending.isEmpty() && !closed.get()) emit(pending);
    }

    private void collect(WatchKey key, Set<AssetId> pending) {
        Path directory = (Path) key.watchable();
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                overflowCount.incrementAndGet();
                Set<AssetId> current = scanAll();
                // Include the previous snapshot so a file deleted while the
                // native queue was overflowing still invalidates dependents.
                pending.addAll(knownAssets);
                pending.addAll(current);
                knownAssets.clear();
                knownAssets.addAll(current);
                continue;
            }
            if (event.kind() != StandardWatchEventKinds.ENTRY_CREATE
                    && event.kind() != StandardWatchEventKinds.ENTRY_MODIFY
                    && event.kind() != StandardWatchEventKinds.ENTRY_DELETE) continue;
            Path changed = directory.resolve((Path) event.context()).normalize();
            if (!changed.startsWith(root)) continue;
            Path relative = root.relativize(changed);
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                    && Files.isDirectory(changed)) {
                try {
                    registerTree(changed);
                } catch (IOException failure) {
                    lastFailure = new IllegalStateException("failed to register " + changed, failure);
                }
                continue;
            }
            if (relative.getNameCount() > 0) {
                AssetId asset = AssetId.of(namespace,
                        relative.toString().replace('\\', '/'));
                pending.add(asset);
                if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                    knownAssets.remove(asset);
                } else {
                    knownAssets.add(asset);
                }
            }
        }
    }

    private Set<AssetId> scanAll() {
        List<AssetId> result = new ArrayList<>();
        try (var files = Files.walk(root)) {
            files.filter(Files::isRegularFile).forEach(path ->
                    result.add(AssetId.of(namespace,
                            root.relativize(path).toString().replace('\\', '/'))));
        } catch (IOException failure) {
            lastFailure = new IllegalStateException("resource watcher rescan failed", failure);
        }
        return Set.copyOf(result);
    }

    private void emit(Set<AssetId> changes) {
        try {
            listener.accept(Set.copyOf(changes));
        } catch (RuntimeException failure) {
            lastFailure = failure;
        }
    }

    private void registerTree(Path directory) throws IOException {
        try (var directories = Files.walk(directory)) {
            for (Path path : directories.filter(Files::isDirectory).toList()) {
                path.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
            }
        }
    }

}
