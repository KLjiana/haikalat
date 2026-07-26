package com.kaleblangley.haikalat.demo.animation;

import com.kaleblangley.haikalat.subsystems.animation.AnimationClip;
import com.kaleblangley.haikalat.subsystems.animation.AnimationGraph;
import com.kaleblangley.haikalat.subsystems.animation.AnimationPlayer;
import com.kaleblangley.haikalat.subsystems.animation.ClipMotion;
import com.kaleblangley.haikalat.subsystems.animation.JointTransform;
import com.kaleblangley.haikalat.subsystems.animation.PoseBuffer;
import com.kaleblangley.haikalat.subsystems.animation.Skeleton;
import com.kaleblangley.haikalat.subsystems.vfx.Decal;
import com.kaleblangley.haikalat.subsystems.vfx.EffectAsset;
import com.kaleblangley.haikalat.subsystems.vfx.EffectInstance;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CharacterEffectBridgeTest {
    @Test
    void markerDrivesDeterministicVfxOnceWithoutSubsystemReverseDependency() {
        Skeleton skeleton = new Skeleton(List.of(
                new Skeleton.Joint("weapon", -1, JointTransform.identity())));
        AnimationClip attack = AnimationClip.builder("attack", skeleton)
                .translation(0, AnimationClip.Interpolation.LINEAR,
                        new float[]{0.0f, 1.0f}, new Vector3f(), new Vector3f(1, 0, 0))
                .marker(0.25f, "attack-hit")
                .build();
        AnimationGraph graph = AnimationGraph.builder("fighter", skeleton)
                .state("attack", new ClipMotion(attack), AnimationPlayer.LoopMode.LOOP)
                .entry("attack").build();
        PoseBuffer pose = skeleton.createPoseBuffer();

        try (EffectAsset asset = EffectAsset.builder("impact")
                .decals(new Decal(4, 2.0f, new Vector4f(1, 1, 1, 1),
                        new Vector4f(1, 1, 1, 0))).build();
             EffectInstance effect = asset.instantiate(73L);
             var controller = graph.createController()) {
            CharacterEffectBridge bridge = new CharacterEffectBridge(73L);
            controller.drainSignals();
            controller.update(0.3f, pose);

            int first = bridge.consume(controller.drainSignals(), effect,
                    new Vector3f(1, 2, 3), new Vector3f(0, 1, 0));
            int duplicate = bridge.consume(controller.pendingSignals(), effect,
                    new Vector3f(1, 2, 3), new Vector3f(0, 1, 0));

            assertEquals(1, first);
            assertEquals(0, duplicate);
            assertEquals(1, bridge.consumedSignalCount());
            assertEquals(1, effect.statistics().activeDecals());
        }
    }
}
