package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.testing.SkinnedGltfFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GltfAnimationSetTest {
    @TempDir
    Path directory;

    @Test
    void glbAnimationSetBindsToTwoModelsWithoutCopyingClips() throws Exception {
        String source = SkinnedGltfFixture.document();
        Files.write(directory.resolve("player.glb"), glb(source, payload(source)));
        Files.writeString(directory.resolve("wild.gltf"),
                withoutAnimations(SkinnedGltfFixture.document(0, 6)), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("sile.gltf"),
                withoutAnimations(SkinnedGltfFixture.document(1, 6)), StandardCharsets.UTF_8);
        GltfAssetLoader loader = loader();

        GltfAnimationSet set = loader.loadAnimationSet(AssetRef.of("player.glb"));
        LoadedGltfScene wild = set.bind(loader.load(AssetRef.of("wild.gltf")));
        LoadedGltfScene sile = set.bind(loader.load(AssetRef.of("sile.gltf")));

        assertEquals(java.util.List.of("lift"), set.animationNames());
        assertEquals(java.util.List.of("lift"), wild.animations().stream()
                .map(LoadedGltfScene.AnimationDef::name).toList());
        assertEquals(java.util.List.of("lift"), sile.animations().stream()
                .map(LoadedGltfScene.AnimationDef::name).toList());
        assertSame(set.animations().getFirst(), wild.animations().getFirst());
        assertSame(set.animations().getFirst(), sile.animations().getFirst());
    }

    @Test
    void rejectsBindSkeletonDrift() throws Exception {
        String source = SkinnedGltfFixture.document();
        Files.writeString(directory.resolve("player.gltf"), source, StandardCharsets.UTF_8);
        String drifted = withoutAnimations(SkinnedGltfFixture.document(0, 6))
                .replace("\"tipJoint\",\"translation\":[0,1,0]",
                        "\"tipJoint\",\"translation\":[0,2,0]");
        Files.writeString(directory.resolve("drifted.gltf"), drifted, StandardCharsets.UTF_8);
        GltfAssetLoader loader = loader();
        GltfAnimationSet set = loader.loadAnimationSet(AssetRef.of("player.gltf"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> set.bind(loader.load(AssetRef.of("drifted.gltf"))));
        org.junit.jupiter.api.Assertions.assertTrue(
                failure.getMessage().contains("incompatible glTF animation rig"));
    }

    private GltfAssetLoader loader() {
        return new GltfAssetLoader(ResourceLocator.classpath(getClass()).addRoot(directory));
    }

    private static String withoutAnimations(String json) {
        int marker = json.lastIndexOf("\"animations\"");
        int start = json.lastIndexOf(',', marker);
        if (start < 0) throw new AssertionError("fixture has no animations");
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
