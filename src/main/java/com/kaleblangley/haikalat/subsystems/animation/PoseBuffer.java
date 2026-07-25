package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 可复用的局部姿态与模型空间矩阵缓冲。 */
public final class PoseBuffer {
    private final Skeleton skeleton;
    private final Vector3f[] translations;
    private final Quaternionf[] rotations;
    private final Vector3f[] scales;
    private final Matrix4f[] globalMatrices;
    private final Matrix4f localScratch = new Matrix4f();
    private boolean globalsDirty = true;
    private long revision;

    public PoseBuffer(Skeleton skeleton) {
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        int count = skeleton.jointCount();
        translations = new Vector3f[count];
        rotations = new Quaternionf[count];
        scales = new Vector3f[count];
        globalMatrices = new Matrix4f[count];
        for (int joint = 0; joint < count; joint++) {
            translations[joint] = new Vector3f();
            rotations[joint] = new Quaternionf();
            scales[joint] = new Vector3f(1.0f);
            globalMatrices[joint] = new Matrix4f();
            copyBindTransform(joint);
        }
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    public int jointCount() {
        return translations.length;
    }

    public long revision() {
        return revision;
    }

    public PoseBuffer resetToBindPose() {
        for (int joint = 0; joint < translations.length; joint++) {
            copyBindTransform(joint);
        }
        changed();
        return this;
    }

    public PoseBuffer setLocalTransform(int jointIndex, JointTransform transform) {
        checkJoint(jointIndex);
        JointTransform value = Objects.requireNonNull(transform, "transform");
        value.copyTranslation(translations[jointIndex]);
        value.copyRotation(rotations[jointIndex]);
        value.copyScale(scales[jointIndex]);
        changed();
        return this;
    }

    public PoseBuffer setTranslation(int jointIndex, Vector3fc value) {
        checkJoint(jointIndex);
        JointTransform.requireFinite(value, "translation");
        translations[jointIndex].set(value);
        changed();
        return this;
    }

    public PoseBuffer setRotation(int jointIndex, Quaternionfc value) {
        checkJoint(jointIndex);
        JointTransform.setNormalized(rotations[jointIndex], value, "rotation");
        changed();
        return this;
    }

    public PoseBuffer setScale(int jointIndex, Vector3fc value) {
        checkJoint(jointIndex);
        JointTransform.requireFinite(value, "scale");
        scales[jointIndex].set(value);
        changed();
        return this;
    }

    public JointTransform localTransform(int jointIndex) {
        checkJoint(jointIndex);
        return new JointTransform(translations[jointIndex], rotations[jointIndex], scales[jointIndex]);
    }

    public Matrix4fc globalMatrix(int jointIndex) {
        checkJoint(jointIndex);
        updateGlobalMatrices();
        return new Matrix4f(globalMatrices[jointIndex]);
    }

    public Matrix4f globalMatrix(int jointIndex, Matrix4f destination) {
        checkJoint(jointIndex);
        updateGlobalMatrices();
        return Objects.requireNonNull(destination, "destination").set(globalMatrices[jointIndex]);
    }

    public Pose snapshot() {
        List<JointTransform> transforms = new ArrayList<>(translations.length);
        for (int joint = 0; joint < translations.length; joint++) {
            transforms.add(new JointTransform(translations[joint], rotations[joint], scales[joint]));
        }
        return new Pose(skeleton, transforms);
    }

    public PoseBuffer load(Pose pose) {
        Pose source = Objects.requireNonNull(pose, "pose");
        requireSkeleton(source.skeleton());
        for (int joint = 0; joint < translations.length; joint++) {
            JointTransform transform = source.localTransform(joint);
            transform.copyTranslation(translations[joint]);
            transform.copyRotation(rotations[joint]);
            transform.copyScale(scales[joint]);
        }
        changed();
        return this;
    }

    void setTranslationSample(int jointIndex, Vector3fc first, Vector3fc second, float alpha) {
        translations[jointIndex].set(first).lerp(second, alpha);
        changed();
    }

    void setScaleSample(int jointIndex, Vector3fc first, Vector3fc second, float alpha) {
        scales[jointIndex].set(first).lerp(second, alpha);
        changed();
    }

    void setRotationSample(int jointIndex, Quaternionfc first, Quaternionfc second, float alpha) {
        rotations[jointIndex].set(first).slerp(second, alpha).normalize();
        changed();
    }

    void setTranslationCubicSample(int jointIndex, Vector3fc first, Vector3fc firstOutTangent,
                                   Vector3fc second, Vector3fc secondInTangent,
                                   float alpha, float durationSeconds) {
        hermite(translations[jointIndex], first, firstOutTangent, second, secondInTangent,
                alpha, durationSeconds);
        changed();
    }

    void setScaleCubicSample(int jointIndex, Vector3fc first, Vector3fc firstOutTangent,
                             Vector3fc second, Vector3fc secondInTangent,
                             float alpha, float durationSeconds) {
        hermite(scales[jointIndex], first, firstOutTangent, second, secondInTangent,
                alpha, durationSeconds);
        changed();
    }

    void setRotationCubicSample(int jointIndex, Quaternionfc first,
                                Quaternionfc firstOutTangent, Quaternionfc second,
                                Quaternionfc secondInTangent, float alpha,
                                float durationSeconds) {
        float alpha2 = alpha * alpha;
        float alpha3 = alpha2 * alpha;
        float h00 = 2.0f * alpha3 - 3.0f * alpha2 + 1.0f;
        float h10 = alpha3 - 2.0f * alpha2 + alpha;
        float h01 = -2.0f * alpha3 + 3.0f * alpha2;
        float h11 = alpha3 - alpha2;
        Quaternionf result = rotations[jointIndex];
        result.set(
                h00 * first.x() + h10 * durationSeconds * firstOutTangent.x()
                        + h01 * second.x() + h11 * durationSeconds * secondInTangent.x(),
                h00 * first.y() + h10 * durationSeconds * firstOutTangent.y()
                        + h01 * second.y() + h11 * durationSeconds * secondInTangent.y(),
                h00 * first.z() + h10 * durationSeconds * firstOutTangent.z()
                        + h01 * second.z() + h11 * durationSeconds * secondInTangent.z(),
                h00 * first.w() + h10 * durationSeconds * firstOutTangent.w()
                        + h01 * second.w() + h11 * durationSeconds * secondInTangent.w());
        if (!Float.isFinite(result.lengthSquared()) || result.lengthSquared() <= 1.0e-12f) {
            throw new IllegalStateException("cubic quaternion sample is zero or non-finite");
        }
        result.normalize();
        changed();
    }

    private static void hermite(Vector3f destination, Vector3fc first,
                                Vector3fc firstOutTangent, Vector3fc second,
                                Vector3fc secondInTangent, float alpha,
                                float durationSeconds) {
        float alpha2 = alpha * alpha;
        float alpha3 = alpha2 * alpha;
        float h00 = 2.0f * alpha3 - 3.0f * alpha2 + 1.0f;
        float h10 = alpha3 - 2.0f * alpha2 + alpha;
        float h01 = -2.0f * alpha3 + 3.0f * alpha2;
        float h11 = alpha3 - alpha2;
        destination.set(first).mul(h00)
                .fma(h10 * durationSeconds, firstOutTangent)
                .fma(h01, second)
                .fma(h11 * durationSeconds, secondInTangent);
    }

    private void updateGlobalMatrices() {
        if (!globalsDirty) return;
        for (int order = 0; order < skeleton.evaluationCount(); order++) {
            int joint = skeleton.evaluationJoint(order);
            localScratch.identity()
                    .translate(translations[joint])
                    .rotate(rotations[joint])
                    .scale(scales[joint]);
            int parent = skeleton.joint(joint).parentIndex();
            if (parent < 0) {
                globalMatrices[joint].set(localScratch);
            } else {
                globalMatrices[joint].set(globalMatrices[parent]).mul(localScratch);
            }
        }
        globalsDirty = false;
    }

    private void copyBindTransform(int jointIndex) {
        JointTransform bind = skeleton.joint(jointIndex).bindTransform();
        bind.copyTranslation(translations[jointIndex]);
        bind.copyRotation(rotations[jointIndex]);
        bind.copyScale(scales[jointIndex]);
    }

    private void requireSkeleton(Skeleton source) {
        if (source != skeleton) {
            throw new IllegalArgumentException("pose belongs to a different skeleton");
        }
    }

    private void checkJoint(int jointIndex) {
        if (jointIndex < 0 || jointIndex >= translations.length) {
            throw new IndexOutOfBoundsException("joint index " + jointIndex
                    + " is outside 0.." + (translations.length - 1));
        }
    }

    private void changed() {
        globalsDirty = true;
        revision = Math.incrementExact(revision);
    }
}
