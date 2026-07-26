package com.kaleblangley.haikalat.subsystems.animation;

import com.kaleblangley.haikalat.core.curve.Curves;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.kaleblangley.haikalat.subsystems.animation.AnimationClip.Interpolation.LINEAR;
import static com.kaleblangley.haikalat.subsystems.animation.AnimationPlayer.LoopMode.LOOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationGraphTest {
    private static final float EPSILON = 1.0e-5f;

    @Test
    void graphValidatesDefinitionsAndParameterTypesAtBuildTime() {
        Skeleton skeleton = skeleton();
        ClipMotion clip = motion(skeleton, "idle", 0.0f);

        assertThrows(IllegalArgumentException.class, () -> AnimationGraph.builder("empty", skeleton)
                .build());
        assertThrows(IllegalArgumentException.class, () -> AnimationGraph.builder("missing", skeleton)
                .state("idle", clip, LOOP).entry("unknown").build());
        assertThrows(IllegalArgumentException.class, () -> AnimationGraph.builder("duplicate", skeleton)
                .state("idle", clip, LOOP).state("idle", clip, LOOP));
        assertThrows(IllegalArgumentException.class, () -> AnimationGraph.builder("wrong-type", skeleton)
                .booleanParameter("speed", false)
                .state("move", BlendTree1D.builder("speed")
                        .child(0.0f, clip).child(1.0f, clip).build(), LOOP)
                .entry("move").build());
        assertThrows(IllegalArgumentException.class, () -> AnimationGraph.builder("condition", skeleton)
                .booleanParameter("ready", false)
                .state("idle", clip, LOOP).state("run", clip, LOOP).entry("idle")
                .transition("idle", "run", AnimationGraph.TransitionSpec.builder()
                        .when(AnimationGraph.Condition.floatGreater("ready", 0.5f)).build())
                .build());
    }

    @Test
    void selectedTransitionAloneConsumesTriggerAndDeclarationOrderIsStable() {
        Skeleton skeleton = skeleton();
        AnimationGraph graph = AnimationGraph.builder("combat", skeleton)
                .booleanParameter("blocked", false)
                .triggerParameter("attack")
                .integerParameter("mode", 0)
                .state("idle", motion(skeleton, "idle", 0.0f), LOOP)
                .state("blocked", motion(skeleton, "blocked", 5.0f), LOOP)
                .state("attack", motion(skeleton, "attack", 10.0f), LOOP)
                .entry("idle")
                .transition("idle", "blocked", AnimationGraph.TransitionSpec.builder()
                        .when(AnimationGraph.Condition.bool("blocked", true))
                        .when(AnimationGraph.Condition.trigger("attack")).build())
                .transition("idle", "attack", AnimationGraph.TransitionSpec.builder()
                        .when(AnimationGraph.Condition.trigger("attack")).build())
                .transition("attack", "idle", AnimationGraph.TransitionSpec.builder()
                        .when(AnimationGraph.Condition.integer("mode", 1)).build())
                .build();
        PoseBuffer pose = skeleton.createPoseBuffer();
        AnimationController controller = graph.createController();
        controller.drainSignals();

        controller.fireTrigger("attack").update(0.0f, pose);
        assertEquals("attack", controller.currentStateName());
        assertEquals(10.0f, pose.localTransform(0).translation().x(), EPSILON);

        controller.setInteger("mode", 1).update(0.0f, pose);
        assertEquals("idle", controller.currentStateName());
        controller.setInteger("mode", 0).update(0.0f, pose);
        assertEquals("idle", controller.currentStateName(),
                "selected transition must consume its trigger exactly once");
    }

    @Test
    void oneDimensionalBlendClampsAndUsesOnlyAdjacentChildren() {
        Skeleton skeleton = skeleton();
        BlendTree1D tree = BlendTree1D.builder("speed")
                .child(0.0f, motion(skeleton, "idle", 0.0f))
                .child(1.0f, motion(skeleton, "walk", 10.0f))
                .child(2.0f, motion(skeleton, "run", 30.0f))
                .build();
        AnimationController controller = AnimationGraph.builder("locomotion", skeleton)
                .floatParameter("speed", 0.0f)
                .state("move", tree, LOOP).entry("move").build().createController();
        PoseBuffer pose = skeleton.createPoseBuffer();

        controller.setFloat("speed", 0.5f).update(0.0f, pose);
        assertEquals(5.0f, pose.localTransform(0).translation().x(), EPSILON);
        controller.setFloat("speed", 1.5f).update(0.0f, pose);
        assertEquals(20.0f, pose.localTransform(0).translation().x(), EPSILON);
        controller.setFloat("speed", 99.0f).update(0.0f, pose);
        assertEquals(30.0f, pose.localTransform(0).translation().x(), EPSILON);
    }

    @Test
    void explicitTriangleBlendUsesBarycentricAndHullProjection() {
        Skeleton skeleton = skeleton();
        BlendTree2D tree = BlendTree2D.builder("x", "y")
                .child(0.0f, 0.0f, motion(skeleton, "origin", 0.0f))
                .child(1.0f, 0.0f, motion(skeleton, "right", 10.0f))
                .child(0.0f, 1.0f, motion(skeleton, "up", 20.0f))
                .triangle(0, 1, 2)
                .build();
        AnimationController controller = AnimationGraph.builder("direction", skeleton)
                .floatParameter("x", 0.0f).floatParameter("y", 0.0f)
                .state("move", tree, LOOP).entry("move").build().createController();
        PoseBuffer pose = skeleton.createPoseBuffer();

        controller.setFloat("x", 0.25f).setFloat("y", 0.25f).update(0.0f, pose);
        assertEquals(7.5f, pose.localTransform(0).translation().x(), EPSILON);
        controller.setFloat("x", 2.0f).setFloat("y", 0.0f).update(0.0f, pose);
        assertEquals(10.0f, pose.localTransform(0).translation().x(), EPSILON);
    }

    @Test
    void transitionEasingMarkersSignalsAndCapacityAreDeterministic() {
        Skeleton skeleton = skeleton();
        AnimationClip attack = AnimationClip.builder("attack", skeleton)
                .translation(0, LINEAR, new float[]{0.0f, 1.0f},
                        new Vector3f(10.0f, 0.0f, 0.0f),
                        new Vector3f(10.0f, 0.0f, 0.0f))
                .marker(0.5f, "attack-hit", AnimationMarker.Priority.HIGH)
                .event(0.5f, "impact")
                .build();
        AnimationGraph graph = AnimationGraph.builder("signals", skeleton)
                .triggerParameter("go")
                .state("idle", motion(skeleton, "idle", 0.0f), LOOP)
                .state("attack", new ClipMotion(attack), LOOP,
                        AnimationGraph.StateOptions.defaults().signals("entered", "left"))
                .entry("idle")
                .transition("idle", "attack", AnimationGraph.TransitionSpec.builder()
                        .duration(1.0f).easing(Curves.LINEAR)
                        .when(AnimationGraph.Condition.trigger("go")).build())
                .build();
        AnimationController first = new AnimationController(graph, 4);
        AnimationController second = new AnimationController(graph, 4);
        PoseBuffer firstPose = skeleton.createPoseBuffer();
        PoseBuffer secondPose = skeleton.createPoseBuffer();
        first.drainSignals();
        second.drainSignals();

        first.fireTrigger("go").update(0.5f, firstPose);
        second.fireTrigger("go").update(0.5f, secondPose);

        assertEquals(5.0f, firstPose.localTransform(0).translation().x(), EPSILON);
        assertEquals(first.pendingSignals(), second.pendingSignals());
        assertTrue(first.pendingSignals().stream()
                .anyMatch(signal -> signal.type() == AnimationSignal.Type.MARKER
                        && signal.name().equals("attack-hit")));
        assertFalse(first.diagnostics().updateCpuNanos() < 0L);
    }

    @Test
    void layerStackComposesEightAdditiveLayersInStableSlotOrder() {
        Skeleton skeleton = skeleton();
        AnimationController base = AnimationGraph.builder("base", skeleton)
                .state("base", motion(skeleton, "base", 0.0f), LOOP)
                .entry("base").build().createController();
        AnimationLayerStack stack = new AnimationLayerStack(base, 8);
        for (int index = 0; index < 8; index++) {
            stack.play("layer-" + index, motion(skeleton, "delta-" + index, 1.0f),
                    LOOP, AnimationLayerMode.ADDITIVE, BoneMask.all(skeleton),
                    1.0f, false);
        }
        PoseBuffer pose = skeleton.createPoseBuffer();

        stack.update(0.0f, pose);

        assertEquals(8.0f, pose.localTransform(0).translation().x(), EPSILON);
        assertEquals(8, stack.activeLayerCount());
        assertEquals(8L, stack.diagnostics().additiveLayers());
        assertThrows(IllegalStateException.class, () -> stack.play("overflow",
                motion(skeleton, "overflow", 1.0f), LOOP,
                AnimationLayerMode.ADDITIVE, BoneMask.all(skeleton), 1.0f, false));
    }

    @Test
    void layerHandleFadePauseCancelAndRootPolicyAreExplicit() {
        Skeleton skeleton = skeleton();
        AnimationController base = AnimationGraph.builder("base", skeleton)
                .state("base", motion(skeleton, "base", 0.0f), LOOP)
                .entry("base").build().createController();
        AnimationLayerStack stack = new AnimationLayerStack(base);
        AnimationLayerStack.LayerHandle layer = stack.play("override",
                motion(skeleton, "override", 10.0f), LOOP,
                AnimationLayerMode.OVERRIDE, BoneMask.all(skeleton), 0.0f, false)
                .fadeTo(1.0f, 1.0f, Curves.LINEAR)
                .rootMotion(AnimationLayerStack.LayerRootMotionMode.OVERRIDE);
        PoseBuffer pose = skeleton.createPoseBuffer();

        stack.update(0.5f, pose);
        assertEquals(5.0f, pose.localTransform(0).translation().x(), EPSILON);
        layer.pause();
        stack.update(0.5f, pose);
        assertEquals(10.0f, pose.localTransform(0).translation().x(), EPSILON);
        layer.cancel();
        assertEquals(0, stack.activeLayerCount());
        assertThrows(IllegalStateException.class, layer::weight);
    }

    @Test
    void matchingSyncMarkerIntervalsMapDestinationPhase() {
        Skeleton skeleton = skeleton();
        AnimationClip walk = AnimationClip.builder("walk", skeleton)
                .translation(0, LINEAR, new float[]{0.0f, 2.0f},
                        new Vector3f(), new Vector3f(2.0f, 0.0f, 0.0f))
                .marker(0.0f, "left").marker(1.0f, "right").build();
        AnimationClip run = AnimationClip.builder("run", skeleton)
                .translation(0, LINEAR, new float[]{0.0f, 4.0f},
                        new Vector3f(), new Vector3f(4.0f, 0.0f, 0.0f))
                .marker(0.0f, "left").marker(2.0f, "right").build();
        AnimationGraph.StateOptions synchronizedState =
                AnimationGraph.StateOptions.defaults().syncGroup("gait");
        AnimationController controller = AnimationGraph.builder("sync", skeleton)
                .triggerParameter("run")
                .state("walk", new ClipMotion(walk), LOOP, synchronizedState)
                .state("run", new ClipMotion(run), LOOP, synchronizedState)
                .entry("walk")
                .transition("walk", "run", AnimationGraph.TransitionSpec.builder()
                        .when(AnimationGraph.Condition.trigger("run")).build())
                .build().createController();
        PoseBuffer pose = skeleton.createPoseBuffer();

        controller.update(0.5f, pose);
        controller.fireTrigger("run").update(0.0f, pose);

        assertEquals("run", controller.currentStateName());
        assertEquals(0.25f, controller.currentNormalizedTime(), EPSILON);
        assertEquals(0L, controller.diagnostics().syncFallbacks());
    }

    @Test
    void graphUsesPoseWeightsForBlendTreeAndCrossFadeMorphOutputs() {
        Skeleton skeleton = skeleton();
        ClipMotion zero = morphMotion(skeleton, "zero", 0.0f);
        ClipMotion one = morphMotion(skeleton, "one", 1.0f);
        AnimationGraph blendGraph = AnimationGraph.builder("morph-blend", skeleton)
                .floatParameter("speed", 0.25f)
                .state("move", BlendTree1D.builder("speed")
                        .child(0.0f, zero).child(1.0f, one).build(), LOOP)
                .entry("move").build();
        MorphWeightBuffer weights = new MorphWeightBuffer(2);
        PoseBuffer pose = skeleton.createPoseBuffer();
        try (AnimationController controller = blendGraph.createController()) {
            controller.update(0.0f, pose);
            controller.copyMorphWeights(weights);
            assertEquals(0.25f, weights.weight(0), EPSILON);
            assertEquals(2, controller.diagnostics().morphTargets());
            assertEquals(2, controller.diagnostics().activeMorphWeights());
        }

        AnimationGraph blend2Graph = AnimationGraph.builder("morph-blend-2d", skeleton)
                .floatParameter("x", 0.25f)
                .floatParameter("y", 0.25f)
                .state("move", BlendTree2D.builder("x", "y")
                        .child(0.0f, 0.0f, zero)
                        .child(1.0f, 0.0f, one)
                        .child(0.0f, 1.0f, one)
                        .triangle(0, 1, 2)
                        .build(), LOOP)
                .entry("move").build();
        try (AnimationController controller = blend2Graph.createController()) {
            controller.update(0.0f, pose);
            controller.copyMorphWeights(weights);
            assertEquals(0.5f, weights.weight(0), EPSILON);
        }

        AnimationGraph transitionGraph = AnimationGraph.builder(
                        "morph-transition", skeleton)
                .triggerParameter("go")
                .state("zero", zero, LOOP).state("one", one, LOOP).entry("zero")
                .transition("zero", "one", AnimationGraph.TransitionSpec.builder()
                        .duration(1.0f)
                        .when(AnimationGraph.Condition.trigger("go")).build())
                .build();
        try (AnimationController controller = transitionGraph.createController()) {
            controller.fireTrigger("go").update(0.5f, pose);
            controller.copyMorphWeights(weights);
            assertEquals(0.5f, weights.weight(0), EPSILON);
        }

        AnimationController baseController = AnimationGraph.builder(
                        "morph-layer-base", skeleton)
                .state("zero", zero, LOOP).entry("zero").build().createController();
        try (AnimationLayerStack layers = new AnimationLayerStack(baseController)) {
            layers.play("override", one, LOOP, AnimationLayerMode.OVERRIDE,
                    BoneMask.all(skeleton), 0.5f, false);
            layers.play("additive", one, LOOP, AnimationLayerMode.ADDITIVE,
                    BoneMask.all(skeleton), 0.25f, false);
            layers.update(0.0f, pose, weights);
            assertEquals(0.75f, weights.weight(0), EPSILON);
        }

        assertThrows(IllegalArgumentException.class, () ->
                AnimationGraph.builder("mismatch", skeleton)
                        .floatParameter("speed", 0.0f)
                        .state("bad", BlendTree1D.builder("speed")
                                .child(0.0f, zero)
                                .child(1.0f, new ClipMotion(
                                        clip(skeleton, "three", 0.0f),
                                        new MorphWeightTrack(3,
                                                MorphWeightTrack.Interpolation.LINEAR,
                                                new float[]{0, 1},
                                                new float[]{0, 0, 0, 1, 1, 1})))
                                .build(), LOOP)
                        .entry("bad").build());
    }

    private static ClipMotion motion(Skeleton skeleton, String name, float x) {
        return new ClipMotion(clip(skeleton, name, x));
    }

    private static AnimationClip clip(Skeleton skeleton, String name, float x) {
        return AnimationClip.builder(name, skeleton)
                .translation(0, LINEAR, new float[]{0.0f, 1.0f},
                        new Vector3f(x, 0.0f, 0.0f),
                        new Vector3f(x, 0.0f, 0.0f))
                .build();
    }

    private static ClipMotion morphMotion(Skeleton skeleton, String name, float weight) {
        return new ClipMotion(clip(skeleton, name, 0.0f),
                new MorphWeightTrack(2, MorphWeightTrack.Interpolation.LINEAR,
                        new float[]{0.0f, 1.0f},
                        new float[]{weight, weight, weight, weight}));
    }

    private static Skeleton skeleton() {
        return new Skeleton(List.of(
                new Skeleton.Joint("root", -1, JointTransform.identity())));
    }
}
