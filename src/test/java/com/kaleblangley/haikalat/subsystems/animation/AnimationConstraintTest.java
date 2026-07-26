package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationConstraintTest {
    private static final float EPSILON = 3.0e-2f;

    @Test
    void lookAtHonorsWeightLimitsAndDegenerateTarget() {
        Skeleton skeleton = oneJoint();
        PoseBuffer pose = skeleton.createPoseBuffer();
        LookAtConstraint lookAt = new LookAtConstraint(skeleton, 0,
                new Vector3f(0.0f, 0.0f, -1.0f),
                new Vector3f(0.0f, 1.0f, 0.0f),
                (float) Math.toRadians(45.0),
                (float) Math.toRadians(30.0));

        AnimationConstraint.Result result = lookAt.apply(pose,
                AnimationConstraint.Context.target(
                        new Vector3f(10.0f, 0.0f, 0.0f),
                        new Vector3f(0.0f, 1.0f, 0.0f), 1.0f, 1.0f / 60.0f));

        assertTrue(result.clamped());
        assertTrue(result.residual() > 0.0f);
        AnimationConstraint.Result degenerate = lookAt.apply(pose,
                AnimationConstraint.Context.target(new Vector3f(),
                        new Vector3f(0.0f, 1.0f, 0.0f), 1.0f, 0.0f));
        assertTrue(degenerate.clamped());
    }

    @Test
    void jointLimitClampsLocalTwist() {
        Skeleton skeleton = oneJoint();
        PoseBuffer pose = skeleton.createPoseBuffer()
                .setRotation(0, new Quaternionf().rotateX((float) Math.toRadians(90.0)));
        JointLimit limit = new JointLimit(skeleton, 0,
                new Vector3f(1.0f, 0.0f, 0.0f),
                (float) Math.PI, (float) Math.toRadians(-30.0),
                (float) Math.toRadians(30.0), 1.0f);

        assertTrue(limit.apply(pose));
        assertEquals(30.0f, (float) Math.toDegrees(
                new Quaternionf(pose.localTransform(0).rotation()).angle()), 0.5f);
    }

    @Test
    void fabrikSolvesReachableAndClampsUnreachableContinuousChains() {
        Skeleton skeleton = chainSkeleton();
        FabrikSolver solver = new FabrikSolver(skeleton, 0, 1, 2, 3);
        PoseBuffer reachablePose = skeleton.createPoseBuffer();

        AnimationConstraint.Result reachable = solver.apply(reachablePose,
                AnimationConstraint.Context.target(
                        new Vector3f(2.0f, 1.0f, 0.0f),
                        new Vector3f(0.0f, 0.0f, 1.0f), 1.0f, 1.0f / 60.0f));

        assertFalse(reachable.clamped());
        assertEquals(0.0f, reachable.residual(), EPSILON);

        AnimationConstraint.Result unreachable = solver.apply(
                skeleton.createPoseBuffer(),
                AnimationConstraint.Context.target(
                        new Vector3f(10.0f, 0.0f, 0.0f),
                        new Vector3f(0.0f, 1.0f, 0.0f), 1.0f, 0.0f));
        assertTrue(unreachable.clamped());
        assertEquals(7.0f, unreachable.residual(), EPSILON);

        assertThrows(IllegalArgumentException.class,
                () -> new FabrikSolver(skeleton, 0, 2, 3));
    }

    @Test
    void constraintStackUsesStableDeclarationOrderAndExportsDiagnostics() {
        Skeleton skeleton = threeJoint();
        AnimationConstraint.Context first = AnimationConstraint.Context.target(
                new Vector3f(1.0f, 1.0f, 0.0f),
                new Vector3f(0.0f, 0.0f, 1.0f), 1.0f, 0.0f);
        AnimationConstraintStack stack = AnimationConstraintStack.builder(skeleton)
                .twoBone("left-arm", 0, 1, 2, first)
                .build();
        PoseBuffer pose = skeleton.createPoseBuffer();

        AnimationConstraintStack.ConstraintDiagnostics diagnostics = stack.apply(pose);

        assertEquals(1, diagnostics.constraintCount());
        assertEquals(1, diagnostics.iterations());
        assertEquals(0.0f, diagnostics.maximumResidual(), EPSILON);
        assertThrows(IllegalArgumentException.class,
                () -> stack.context("missing", first));
    }

    @Test
    void twoHandConstraintConsumesOnlyCallerProvidedSecondaryTarget() {
        Skeleton skeleton = threeJoint();
        PoseBuffer pose = skeleton.createPoseBuffer();
        TwoHandIkConstraint constraint = new TwoHandIkConstraint(skeleton, 0, 1, 2);

        AnimationConstraint.Result result = constraint.apply(pose,
                AnimationConstraint.Context.target(
                        new Vector3f(1.0f, 1.0f, 0.0f),
                        new Vector3f(0.0f, 0.0f, 1.0f), 1.0f, 0.0f));

        assertEquals(0.0f, result.residual(), EPSILON);
        assertEquals(1.0f, ConstraintMath.position(pose, 2).x(), EPSILON);
        assertEquals(1.0f, ConstraintMath.position(pose, 2).y(), EPSILON);
    }

    @Test
    void footIkSolvesLeftThenRightAndAppliesPureInputPelvisOffset() {
        Skeleton skeleton = bipedSkeleton();
        PoseBuffer pose = skeleton.createPoseBuffer();
        FootIkSolver solver = new FootIkSolver(skeleton, 0,
                new FootIkSolver.Leg(1, 2, 3, new Vector3f(0, 1, 0)),
                new FootIkSolver.Leg(4, 5, 6, new Vector3f(0, 1, 0)));
        FootIkSolver.FootTarget left = new FootIkSolver.FootTarget(
                new Vector3f(-0.5f, -1.5f, 0.5f),
                new Vector3f(0, 1, 0), new Vector3f(-1, -1, 1), 1.0f);
        FootIkSolver.FootTarget right = new FootIkSolver.FootTarget(
                new Vector3f(0.5f, -1.5f, 0.5f),
                new Vector3f(0, 1, 0), new Vector3f(1, -1, 1), 1.0f);

        FootIkSolver.Result result = solver.solve(pose,
                new FootIkSolver.Input(left, right, -0.2f), 0.0f);

        assertEquals(-0.2f, pose.localTransform(0).translation().y(), 1.0e-6f);
        assertEquals(1.0f, result.leftWeight(), 1.0e-6f);
        assertEquals(1.0f, result.rightWeight(), 1.0e-6f);
        assertTrue(result.left().tipError() < 0.25f);
        assertTrue(result.right().tipError() < 0.25f);
    }

    private static Skeleton oneJoint() {
        return new Skeleton(List.of(
                new Skeleton.Joint("root", -1, JointTransform.identity())));
    }

    private static Skeleton threeJoint() {
        return new Skeleton(List.of(
                new Skeleton.Joint("root", -1, JointTransform.identity()),
                new Skeleton.Joint("middle", 0, translation(1.0f)),
                new Skeleton.Joint("tip", 1, translation(1.0f))));
    }

    private static Skeleton chainSkeleton() {
        return new Skeleton(List.of(
                new Skeleton.Joint("root", -1, JointTransform.identity()),
                new Skeleton.Joint("one", 0, translation(1.0f)),
                new Skeleton.Joint("two", 1, translation(1.0f)),
                new Skeleton.Joint("tip", 2, translation(1.0f))));
    }

    private static Skeleton bipedSkeleton() {
        return new Skeleton(List.of(
                new Skeleton.Joint("pelvis", -1, JointTransform.identity()),
                new Skeleton.Joint("leftHip", 0, translation(-0.5f, 0, 0)),
                new Skeleton.Joint("leftKnee", 1, translation(0, -1, 0)),
                new Skeleton.Joint("leftAnkle", 2, translation(0, -1, 0)),
                new Skeleton.Joint("rightHip", 0, translation(0.5f, 0, 0)),
                new Skeleton.Joint("rightKnee", 4, translation(0, -1, 0)),
                new Skeleton.Joint("rightAnkle", 5, translation(0, -1, 0))));
    }

    private static JointTransform translation(float x) {
        return translation(x, 0.0f, 0.0f);
    }

    private static JointTransform translation(float x, float y, float z) {
        return new JointTransform(new Vector3f(x, y, z),
                new Quaternionf(), new Vector3f(1.0f));
    }
}
