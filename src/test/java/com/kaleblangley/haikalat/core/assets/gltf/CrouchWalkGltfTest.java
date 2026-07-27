package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.subsystems.animation.AnimationClip;
import com.kaleblangley.haikalat.subsystems.animation.Pose;
import com.kaleblangley.haikalat.subsystems.animation.gltf.GltfAnimationRig;
import org.joml.Quaternionfc;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrouchWalkGltfTest {
    @Test
    void blenderObjectActionsComposeIntoOneRigidCrouchWalk() {
        LoadedGltfScene scene = new GltfAssetLoader(
                ResourceLocator.classpath(getClass()).addRoot(Path.of("src/demo/resources")))
                .load(AssetRef.of("scenes/gltf/crouch_walk.glb"),
                        new GltfLoadOptions(new SceneSelection.Default(), false,
                                GltfAssetLimits.defaults()));

        assertEquals(11, scene.nodes().size());
        assertEquals(11, scene.primitives().size());
        assertEquals(0, scene.skins().size());
        assertEquals(10, scene.animations().size());
        assertEquals(11, scene.statistics().animationChannelCount());
        assertEquals("leftArm动作", scene.animations().get(6).name());
        assertEquals(10, scene.rootNodeIndices().getFirst());
        assertTrue(scene.warnings().stream()
                .anyMatch(warning -> warning.contains("KHR_materials_specular")));

        GltfAnimationRig rig = GltfAnimationRig.from(scene);
        AnimationClip crouchWalk = rig.composePoseClips("crouch_walk",
                IntStream.range(0, rig.clips().size()).boxed().toList());
        assertEquals(11, crouchWalk.channelCount());
        assertEquals(1.0f / 6.0f, crouchWalk.durationSeconds(), 1.0e-6f);

        Pose start = crouchWalk.sample(0.0f);
        Pose stride = crouchWalk.sample(1.0f / 12.0f);
        Quaternionfc leftLegStart = start.localTransform(1).rotation();
        Quaternionfc leftLegStride = stride.localTransform(1).rotation();
        float dot = Math.abs(leftLegStart.x() * leftLegStride.x()
                + leftLegStart.y() * leftLegStride.y()
                + leftLegStart.z() * leftLegStride.z()
                + leftLegStart.w() * leftLegStride.w());
        assertTrue(dot < 0.999f, "the composed left-leg action must animate");
    }
}
