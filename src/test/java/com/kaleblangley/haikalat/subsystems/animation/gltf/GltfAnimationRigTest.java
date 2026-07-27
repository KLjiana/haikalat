package com.kaleblangley.haikalat.subsystems.animation.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAssetLoader;
import com.kaleblangley.haikalat.subsystems.animation.JointPalette;
import com.kaleblangley.haikalat.subsystems.animation.PoseBuffer;
import com.kaleblangley.haikalat.testing.SkinnedGltfFixture;
import com.kaleblangley.haikalat.testing.MorphGltfFixture;
import com.kaleblangley.haikalat.subsystems.animation.MorphWeightBuffer;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfAnimationRigTest {
    @TempDir
    Path directory;

    @Test
    void adaptsCubicChannelsAndSkinPaletteToAnimationSubsystem() throws Exception {
        Files.writeString(directory.resolve("skinned.gltf"), SkinnedGltfFixture.document());
        var scene = new GltfAssetLoader(ResourceLocator.classpath(getClass()).addRoot(directory))
                .load(AssetRef.of("skinned.gltf"));

        GltfAnimationRig rig = GltfAnimationRig.from(scene);

        assertEquals(3, rig.skeleton().jointCount());
        assertEquals(1, rig.skins().size());
        assertEquals(1, rig.clips().size());
        PoseBuffer pose = rig.skeleton().createPoseBuffer();
        rig.clips().getFirst().sample(0.5f, pose);
        assertEquals(1.5f, pose.localTransform(2).translation().y(), 1.0e-6f);

        JointPalette palette = rig.skins().getFirst().createPalette()
                .update(pose, pose.globalMatrix(0));
        assertEquals(0.0f, palette.matrix(0).m31(), 1.0e-6f);
        assertEquals(0.5f, palette.matrix(1).m31(), 1.0e-6f);
        float[] packed = new float[palette.floatCount()];
        palette.copyTo(packed, 0);
        assertEquals(0.5f, packed[16 + 13], 1.0e-6f);
    }

    @Test
    void adaptsDynamicMorphWeightChannelsWithoutAddingPoseChannels() throws Exception {
        Files.writeString(directory.resolve("morph.gltf"), MorphGltfFixture.document());
        var scene = new GltfAssetLoader(ResourceLocator.classpath(getClass()).addRoot(directory))
                .load(AssetRef.of("morph.gltf"));

        GltfAnimationRig rig = GltfAnimationRig.from(scene);
        MorphWeightBuffer weights = new MorphWeightBuffer(4);
        rig.morphWeightTrack(0, 0).orElseThrow().sample(0.5f, weights);

        assertEquals(0.5f, weights.weight(0), 1.0e-6f);
        assertEquals(0.25f, weights.weight(1), 1.0e-6f);
        assertEquals(1.0f, rig.clips().getFirst().durationSeconds(), 1.0e-6f);
        assertEquals(1, rig.clips().getFirst().channelCount());
        assertEquals(1, rig.skins().size());
    }

    @Test
    void importsAnimationMarkersFromGltfExtras() throws Exception {
        String document = SkinnedGltfFixture.document()
                .replace("\"samplers\":[{\"input\":4",
                        "\"extras\":{\"markers\":[{\"name\":\"hit_start\","
                                + "\"normalizedTime\":0.5},{\"name\":\"hit_end\","
                                + "\"normalizedTime\":1.0}]},\"samplers\":[{\"input\":4");
        Files.writeString(directory.resolve("marked.gltf"), document);
        var scene = new GltfAssetLoader(ResourceLocator.classpath(getClass()).addRoot(directory))
                .load(AssetRef.of("marked.gltf"));

        GltfAnimationRig rig = GltfAnimationRig.from(scene);
        assertEquals(2, rig.clips().getFirst().markers().size());
        assertEquals("hit_start", rig.clips().getFirst().markers().getFirst().name());
        assertEquals(0.5f, rig.clips().getFirst().markers().getFirst().timeSeconds(),
                1.0e-6f);
        assertTrue(rig.clips().getFirst().markers().stream()
                .anyMatch(marker -> marker.name().equals("hit_end")));
    }

    @Test
    void importsAnimationMarkersFromAnimationSidecar() throws Exception {
        Files.writeString(directory.resolve("sidecar.gltf"), SkinnedGltfFixture.document());
        Files.writeString(directory.resolve("sidecar.animation.json"),
                "{\"animations\":{\"lift\":{\"markers\":["
                        + "{\"name\":\"hit_start\",\"timeSeconds\":0.5},"
                        + "{\"name\":\"hit_end\",\"timeSeconds\":0.6},"
                        + "{\"name\":\"cancel_open\",\"timeSeconds\":0.7},"
                        + "{\"name\":\"cancel_close\",\"timeSeconds\":0.8},"
                        + "{\"name\":\"combo_open\",\"timeSeconds\":0.9},"
                        + "{\"name\":\"combo_close\",\"timeSeconds\":1.0}]}}}");
        var scene = new GltfAssetLoader(ResourceLocator.classpath(getClass()).addRoot(directory))
                .loadWithSidecar(AssetRef.of("sidecar.gltf"));

        GltfAnimationRig rig = GltfAnimationRig.from(scene);
        assertEquals(6, rig.clips().getFirst().markers().size());
        assertEquals("hit_start", rig.clips().getFirst().markers().getFirst().name());
        assertEquals(1.0f, rig.clips().getFirst().markers().getLast().timeSeconds(),
                1.0e-6f);
    }
}
