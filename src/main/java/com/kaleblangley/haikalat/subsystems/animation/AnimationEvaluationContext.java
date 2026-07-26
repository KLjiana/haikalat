package com.kaleblangley.haikalat.subsystems.animation;

import java.util.Arrays;

/** Controller-owned reusable scratch arena. */
final class AnimationEvaluationContext {
    private final Skeleton skeleton;
    private PoseBuffer[] poses = new PoseBuffer[8];
    private ClipMotion[] activeClips = new ClipMotion[8];
    private float[] activeWeights = new float[8];
    private int poseCursor;
    private MorphWeightBuffer[] morphBuffers;
    private int morphCursor;
    private int activeClipCount;

    AnimationEvaluationContext(Skeleton skeleton) {
        this(skeleton, 0);
    }

    AnimationEvaluationContext(Skeleton skeleton, int morphTargetCount) {
        this.skeleton = skeleton;
        for (int index = 0; index < poses.length; index++) {
            poses[index] = skeleton.createPoseBuffer();
        }
        if (morphTargetCount > 0) {
            morphBuffers = new MorphWeightBuffer[8];
            for (int index = 0; index < morphBuffers.length; index++) {
                morphBuffers[index] = new MorphWeightBuffer(morphTargetCount);
            }
        }
    }

    void reset() {
        poseCursor = 0;
        morphCursor = 0;
        activeClipCount = 0;
    }

    MorphWeightBuffer morph() {
        if (morphBuffers == null) {
            throw new IllegalStateException("graph has no morph output");
        }
        if (morphCursor == morphBuffers.length) {
            int previous = morphBuffers.length;
            morphBuffers = Arrays.copyOf(morphBuffers, previous * 2);
            int targets = morphBuffers[0].targetCount();
            for (int index = previous; index < morphBuffers.length; index++) {
                morphBuffers[index] = new MorphWeightBuffer(targets);
            }
        }
        return morphBuffers[morphCursor++];
    }

    void resetPoses() {
        poseCursor = 0;
    }

    void resetMorphs() {
        morphCursor = 0;
    }

    PoseBuffer pose() {
        if (poseCursor == poses.length) {
            int previous = poses.length;
            poses = Arrays.copyOf(poses, previous * 2);
            for (int index = previous; index < poses.length; index++) {
                poses[index] = skeleton.createPoseBuffer();
            }
        }
        return poses[poseCursor++];
    }

    void active(ClipMotion clip, float weight) {
        if (weight <= 1.0e-6f) return;
        for (int index = 0; index < activeClipCount; index++) {
            if (activeClips[index] == clip) {
                activeWeights[index] += weight;
                return;
            }
        }
        if (activeClipCount == activeClips.length) {
            activeClips = Arrays.copyOf(activeClips, activeClips.length * 2);
            activeWeights = Arrays.copyOf(activeWeights, activeWeights.length * 2);
        }
        activeClips[activeClipCount] = clip;
        activeWeights[activeClipCount] = weight;
        activeClipCount++;
    }

    int activeClipCount() {
        return activeClipCount;
    }

    ClipMotion activeClip(int index) {
        return activeClips[index];
    }

    float activeWeight(int index) {
        return activeWeights[index];
    }

    long estimatedBytes() {
        long joints = skeleton.jointCount();
        return poses.length * joints * (10L * Float.BYTES + 16L * Float.BYTES)
                + activeClips.length * 8L + activeWeights.length * Float.BYTES
                + (morphBuffers == null ? 0L
                : (long) morphBuffers.length * morphBuffers[0].targetCount() * Float.BYTES);
    }
}
