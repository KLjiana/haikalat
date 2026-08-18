package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.subsystems.animation.gltf.GltfAnimationRig;
import com.kaleblangley.haikalat.testing.GltfAnimationLibraryFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
    void compactAnimationClipUsesExactModelNodeNamesWithoutRepeatedBindings()
            throws Exception {
        Path library = GltfAnimationLibraryFixture.write(
                temporaryDirectory.resolve("compact"));
        Files.writeString(library.resolve("animations/wave.animation.json"), """
                {
                  "schema":"haikalat.animation-clip/1",
                  "name":"wave",
                  "duration":1.0,
                  "loop":"loop",
                  "fps":24,
                  "tracks":[{
                    "node":"arm",
                    "path":"rotation",
                    "interpolation":"linear",
                    "keyframes":[
                      {"time":0.0,"value":[0,0,0,1]},
                      {"time":1.0,"value":[0,0,0.70710677,0.70710677]}
                    ]
                  }],
                  "events":[{"time":0.5,"name":"hit_start","priority":"high"}]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(library.resolve("animation-library.json"), """
                {
                  "schema":"haikalat.gltf-animation-library/1",
                  "generatedBy":"test",
                  "pluginVersion":"1.4.0",
                  "gltfVersion":"2.0",
                  "model":"model/actor.gltf",
                  "nodeBinding":"name",
                  "animations":[{
                    "name":"wave",
                    "file":"animations/wave.animation.json",
                    "selfContained":true
                  }]
                }
                """, StandardCharsets.UTF_8);

        LoadedGltfScene scene = new GltfAssetLoader(
                ResourceLocator.classpath(getClass()).addRoot(temporaryDirectory))
                .loadAnimationLibrary(AssetRef.of(temporaryDirectory.relativize(
                        library.resolve("animation-library.json")).toString()));

        LoadedGltfScene.AnimationDef clip = scene.animations().get(1);
        assertEquals("wave", clip.name());
        assertEquals(1, clip.channels().getFirst().nodeIndex());
        assertEquals(1.0f, clip.durationSeconds());
        assertEquals("hit_start", clip.markers().getFirst().name());
        assertEquals("high", clip.markers().getFirst().priority());
        assertEquals(40L, scene.statistics().decodedBufferBytes());
    }

    @Test
    void compactAnimationClipRejectsUnknownModelNode() throws Exception {
        Path library = GltfAnimationLibraryFixture.write(
                temporaryDirectory.resolve("unknown-node"));
        Files.writeString(library.resolve("animations/wave.animation.json"), """
                {
                  "schema":"haikalat.animation-clip/1",
                  "name":"wave",
                  "duration":0,
                  "loop":"once",
                  "fps":24,
                  "tracks":[{
                    "node":"missing_arm",
                    "path":"rotation",
                    "interpolation":"linear",
                    "keyframes":[{"time":0,"value":[0,0,0,1]}]
                  }],
                  "events":[]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(library.resolve("animation-library.json"), """
                {
                  "schema":"haikalat.gltf-animation-library/1",
                  "gltfVersion":"2.0",
                  "model":"model/actor.gltf",
                  "nodeBinding":"name",
                  "animations":[{
                    "name":"wave",
                    "file":"animations/wave.animation.json",
                    "selfContained":true
                  }]
                }
                """, StandardCharsets.UTF_8);
        GltfAssetLoader loader = new GltfAssetLoader(
                ResourceLocator.classpath(getClass()).addRoot(temporaryDirectory));

        GltfAssetException failure = assertThrows(GltfAssetException.class,
                () -> loader.loadAnimationLibrary(AssetRef.of(
                        temporaryDirectory.relativize(library.resolve(
                                "animation-library.json")).toString())));

        assertEquals("tracks[0].node", failure.location());
        assertTrue(failure.getMessage().contains("missing_arm"));
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

    @Test
    void librarySelectsAClipFromAnExternalGlbSource() throws Exception {
        Path root = temporaryDirectory.resolve("glb-library");
        Files.createDirectories(root.resolve("animations"));
        String model = com.kaleblangley.haikalat.testing.SkinnedGltfFixture.document();
        Files.writeString(root.resolve("model.gltf"), withoutAnimations(model),
                StandardCharsets.UTF_8);
        Files.write(root.resolve("animations/player.glb"), glb(model, payload(model)));
        Files.writeString(root.resolve("animation-library.json"), """
                {"schema":"haikalat.gltf-animation-library/1","gltfVersion":"2.0",
                 "nodeBinding":"name","model":"model.gltf","animations":[
                 {"name":"stand","file":"animations/player.glb","clip":"lift"}]}
                """, StandardCharsets.UTF_8);

        LoadedGltfScene scene = new GltfAssetLoader(
                ResourceLocator.classpath(getClass()).addRoot(temporaryDirectory))
                .loadAnimationLibrary(AssetRef.of(temporaryDirectory.relativize(
                        root.resolve("animation-library.json")).toString()));

        assertEquals(List.of("stand"), scene.animations().stream()
                .map(LoadedGltfScene.AnimationDef::name).toList());
        assertEquals(1, scene.animations().getFirst().channels().size());
    }

    private static void put(ZipOutputStream output, String name,
                            String contents) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(contents.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static String withoutAnimations(String json) {
        int marker = json.lastIndexOf("\"animations\"");
        int start = json.lastIndexOf(',', marker);
        return json.substring(0, start) + "}";
    }

    private static byte[] payload(String json) {
        int start = json.indexOf("\"buffers\":[");
        int uri = json.indexOf("base64,", start) + "base64,".length();
        int end = json.indexOf('"', uri);
        return java.util.Base64.getDecoder().decode(json.substring(uri, end));
    }

    private static byte[] glb(String json, byte[] bin) {
        byte[] rawJson = json.getBytes(StandardCharsets.UTF_8);
        int jsonLength = (rawJson.length + 3) & ~3;
        int binLength = (bin.length + 3) & ~3;
        ByteBuffer output = ByteBuffer.allocate(12 + 8 + jsonLength + 8 + binLength)
                .order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0x46546C67).putInt(2).putInt(output.capacity());
        output.putInt(jsonLength).putInt(0x4E4F534A).put(rawJson);
        while (output.position() < 20 + jsonLength) output.put((byte) 0x20);
        output.putInt(binLength).putInt(0x004E4942).put(bin);
        while (output.hasRemaining()) output.put((byte) 0);
        return output.array();
    }
}
