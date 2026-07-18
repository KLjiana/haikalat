package com.kaleblangley.haikalat.core.assets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceLocatorRelativeTest {
    @TempDir Path temporaryDirectory;
    private final ResourceLocator locator = ResourceLocator.classpath(ResourceLocatorRelativeTest.class);

    @Test
    void resolvesClasspathSiblingAndNormalizesDotSegments() {
        assertEquals("/models/textures/base color.png",
                locator.resolveRelative(AssetRef.of("/models/scene.gltf"),
                        "./textures/base%20color.png").path());
        assertEquals("/textures/base.png",
                locator.resolveRelative(AssetRef.of("/models/scene.gltf"),
                        "../textures/base.png").path());
    }

    @Test
    void rejectsRootEscapeRemoteAndQueryUris() {
        assertThrows(IllegalArgumentException.class,
                () -> locator.resolveRelative(AssetRef.of("/models/scene.gltf"), "../../secret.bin"));
        assertThrows(IllegalArgumentException.class,
                () -> locator.resolveRelative(AssetRef.of("/models/scene.gltf"), "https://example.com/a.bin"));
        assertThrows(IllegalArgumentException.class,
                () -> locator.resolveRelative(AssetRef.of("/models/scene.gltf"), "a.bin?v=1"));
        assertThrows(IllegalArgumentException.class,
                () -> locator.resolveRelative(AssetRef.of("/models/scene.gltf"),
                        "%2e%2e/%2e%2e/secret.bin"));
        assertThrows(IllegalArgumentException.class,
                () -> locator.resolveRelative(AssetRef.of("/models/scene.gltf"),
                        "C%3A/secret.bin"));
        assertThrows(IllegalArgumentException.class,
                () -> locator.resolveRelative(AssetRef.of("/models/scene.gltf"),
                        "..%5c..%5csecret.bin"));
    }

    @Test
    void limitedReadRejectsOversizedFileBeforeReturningPayload() throws Exception {
        Files.write(temporaryDirectory.resolve("large.bin"), new byte[17]);
        ResourceLocator rooted = ResourceLocator.classpath(getClass()).addRoot(temporaryDirectory);

        assertThrows(RuntimeException.class,
                () -> rooted.readBytes(AssetRef.of("large.bin"), 16));
        assertEquals(17, rooted.readBytes(AssetRef.of("large.bin"), 17).length);
    }
}
