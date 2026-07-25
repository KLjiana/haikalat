package com.kaleblangley.haikalat.subsystems.animation.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAssetLoader;
import com.kaleblangley.haikalat.subsystems.animation.JointPalette;
import com.kaleblangley.haikalat.subsystems.animation.PoseBuffer;
import com.kaleblangley.haikalat.testing.SkinnedGltfFixture;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
