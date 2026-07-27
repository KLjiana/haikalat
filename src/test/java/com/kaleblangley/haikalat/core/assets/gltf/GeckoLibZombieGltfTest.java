package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.subsystems.animation.AnimationClip;
import com.kaleblangley.haikalat.subsystems.animation.Pose;
import com.kaleblangley.haikalat.subsystems.animation.gltf.GltfAnimationRig;
import org.joml.Quaternionfc;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeckoLibZombieGltfTest {
    @Test
    void blockbenchGeckoLibExportDecodesAsRigidHierarchyAnimation() {
        LoadedGltfScene scene = new GltfAssetLoader(
                ResourceLocator.classpath(getClass()).addRoot(Path.of("src/demo/resources")))
                .load(AssetRef.of("scenes/gltf/zombie.gltf"));

        assertEquals(13, scene.nodes().size());
        assertEquals(7, scene.primitives().size());
        assertEquals(0, scene.skins().size());
        assertEquals(1, scene.animations().size());
        assertEquals(4, scene.statistics().animationChannelCount());
        assertEquals(5, scene.nodeRigs().get(4).parentIndex());
        assertEquals(7, scene.nodeRigs().get(6).parentIndex());
        assertEquals(9, scene.nodeRigs().get(8).parentIndex());
        assertEquals(11, scene.nodeRigs().get(10).parentIndex());

        GltfAnimationRig rig = GltfAnimationRig.from(scene);
        assertEquals(13, rig.skeleton().jointCount());
        AnimationClip run = rig.clips().getFirst();
        assertEquals("run", run.name());
        assertEquals(3.75f, run.durationSeconds(), 1.0e-6f);

        Pose start = run.sample(0.0f);
        Quaternionfc rightArm = start.localTransform(5).rotation();
        // GeckoLib negates source X/Y Euler angles before the glTF exporter bakes them.
        assertEquals(0.8433914f, rightArm.x(), 1.0e-5f);
        assertEquals(0.5372996f, rightArm.w(), 1.0e-5f);

        Quaternionfc legStart = start.localTransform(9).rotation();
        Quaternionfc legStride = run.sample(1.25f).localTransform(9).rotation();
        float dot = Math.abs(legStart.x() * legStride.x()
                + legStart.y() * legStride.y()
                + legStart.z() * legStride.z()
                + legStart.w() * legStride.w());
        assertTrue(dot < 0.999f, "the rigid leg pivot must animate");
    }
}
