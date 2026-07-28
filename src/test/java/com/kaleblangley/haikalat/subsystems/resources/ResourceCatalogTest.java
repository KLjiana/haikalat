package com.kaleblangley.haikalat.subsystems.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceCatalogTest {
    @TempDir
    Path directory;

    @Test
    void mountsNamespacesWithoutFallbackOrDuplicateReplacement() throws IOException {
        Files.write(directory.resolve("scene.json"), "{}".getBytes());
        ResourceCatalog catalog = ResourceCatalog.builder()
                .mount("demo", ResourceSource.directory("demo", directory))
                .build();

        assertArrayEquals("{}".getBytes(),
                catalog.readBytes(AssetId.of("demo", "scene.json"), 2));
        assertFalse(catalog.exists(AssetId.of("other", "scene.json")));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceCatalog.builder()
                        .mount("demo", ResourceSource.directory("demo", directory))
                        .mount("demo", ResourceSource.directory("demo", directory)));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.requireSource("other"));
    }

    @Test
    void directoryWatcherDebouncesChangesToLogicalIds() throws Exception {
        LinkedBlockingQueue<Set<AssetId>> changes = new LinkedBlockingQueue<>();
        Path file = directory.resolve("nested/value.scene.json");
        Files.createDirectories(file.getParent());
        try (DirectoryResourceWatcher watcher = new DirectoryResourceWatcher(
                "demo", directory, Duration.ofMillis(40), changes::offer)) {
            Files.writeString(file, "one");
            Files.writeString(file, "two");
            Set<AssetId> delivered = changes.poll(3, java.util.concurrent.TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertNotNull(delivered);
            org.junit.jupiter.api.Assertions.assertTrue(delivered.contains(
                    AssetId.of("demo", "nested/value.scene.json")));
        }
    }
}
