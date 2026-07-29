package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.subsystems.animation.gltf.GltfAnimationRig;
import com.kaleblangley.haikalat.testing.GltfAnimationLibraryFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfAnimationLibraryTest {
    @TempDir Path temporaryDirectory;

    @Test
    void unpackedLibraryAppendsAndRemapsExternalAnimations() throws Exception {
        Path library = GltfAnimationLibraryFixture.write(
                temporaryDirectory.resolve("library"),
                "wave", "arm");
        LoadedGltfScene scene = new GltfAssetLoader(
                ResourceLocator.classpath(getClass()).addRoot(temporaryDirectory))
                .loadAnimationLibrary(AssetRef.of(
                        temporaryDirectory.relativize(library.resolve(
                                "animation-library.json")).toString()));

        assertIterableEquals(List.of("idle", "wave"),
                scene.animations().stream()
                        .map(LoadedGltfScene.AnimationDef::name).toList());
        assertEquals(0, scene.animations().getFirst().index());
        assertEquals(1, scene.animations().get(1).index());
        assertEquals(1, scene.animations().get(1).channels().getFirst().nodeIndex());
        assertEquals("hit_start",
                scene.animations().get(1).markers().getFirst().name());
        assertEquals(2, scene.statistics().animationCount());
        assertEquals(2, scene.statistics().animationChannelCount());
        assertEquals(80L, scene.statistics().decodedBufferBytes());

        GltfAnimationRig rig = GltfAnimationRig.from(scene);
        assertIterableEquals(List.of("idle", "wave"),
                rig.clips().stream().map(clip -> clip.name()).toList());
    }

    @Test
    void zipLibraryLoadsWithoutExtraction() throws Exception {
        Path library = GltfAnimationLibraryFixture.write(
                temporaryDirectory.resolve("source"),
                "wave", "arm");
        Path archive = temporaryDirectory.resolve("actor.gltf-animations.zip");
        GltfAnimationLibraryFixture.zip(library, archive, "bundle/");

        LoadedGltfScene scene = GltfAssetLoader.loadAnimationLibrary(archive);

        assertIterableEquals(List.of("idle", "wave"),
                scene.animations().stream()
                        .map(LoadedGltfScene.AnimationDef::name).toList());
        assertEquals("arm", scene.nodes().get(
                scene.animations().get(1).channels().getFirst().nodeIndex()).name());
    }

    @Test
    void staleNodeBindingFailsInsteadOfAnimatingTheWrongNode() throws Exception {
        Path library = GltfAnimationLibraryFixture.write(
                temporaryDirectory.resolve("stale"),
                "wave", "wrong_arm");
        GltfAssetLoader loader = new GltfAssetLoader(
                ResourceLocator.classpath(getClass()).addRoot(temporaryDirectory));

        GltfAssetException failure = assertThrows(GltfAssetException.class,
                () -> loader.loadAnimationLibrary(AssetRef.of(
                        temporaryDirectory.relativize(library.resolve(
                                "animation-library.json")).toString())));

        assertTrue(failure.location().endsWith(".name"));
        assertTrue(failure.source().path().endsWith("animation-library.json"));
        assertTrue(failure.getMessage().contains("differs from sidecar node name"));
    }

    @Test
    void externalAnimationCannotShadowAModelAnimation() throws Exception {
        Path library = GltfAnimationLibraryFixture.write(
                temporaryDirectory.resolve("duplicate"),
                "idle", "arm");
        GltfAssetLoader loader = new GltfAssetLoader(
                ResourceLocator.classpath(getClass()).addRoot(temporaryDirectory));

        GltfAssetException failure = assertThrows(GltfAssetException.class,
                () -> loader.loadAnimationLibrary(AssetRef.of(
                        temporaryDirectory.relativize(library.resolve(
                                "animation-library.json")).toString())));

        assertTrue(failure.getMessage().contains("duplicate animation name idle"));
    }

    @Test
    void archiveRejectsTraversalEntriesBeforeLoading() throws Exception {
        Path archive = temporaryDirectory.resolve("unsafe.zip");
        try (ZipOutputStream output = new ZipOutputStream(
                Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            put(output, "animation-library.json", "{}");
            put(output, "../outside.gltf", "{}");
        }

        GltfAssetException failure = assertThrows(GltfAssetException.class,
                () -> GltfAssetLoader.loadAnimationLibrary(archive));

        assertEquals(GltfAssetException.Phase.READ, failure.phase());
        assertTrue(failure.getMessage().contains("unsafe animation library archive entry"));
    }

    private static void put(ZipOutputStream output, String name,
                            String contents) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(contents.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }
}
