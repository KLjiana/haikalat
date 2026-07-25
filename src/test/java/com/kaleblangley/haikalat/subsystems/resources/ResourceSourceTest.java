package com.kaleblangley.haikalat.subsystems.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceSourceTest {
    @TempDir
    Path directory;

    @Test
    void directorySourceReadsOnlyItsNamespaceAndHonorsByteLimit() throws IOException {
        Path file = directory.resolve("models/hero.bin");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "payload", StandardCharsets.UTF_8);
        ResourceSource source = ResourceSource.directory("game", directory);

        assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8),
                source.read(AssetId.of("game", "models/hero.bin"), 7));
        assertThrows(IOException.class,
                () -> source.read(AssetId.of("game", "models/hero.bin"), 6));
        assertThrows(FileNotFoundException.class,
                () -> source.read(AssetId.of("other", "models/hero.bin"), 7));
    }
}
