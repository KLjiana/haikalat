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
    private final boolean[] globalDirty;
    private final Matrix4f localScratch = new Matrix4f();
    private final Quaternionf rotationScratch = new Quaternionf();
    private long revision;
    private long globalRecomputeCount;

    public PoseBuffer(Skeleton skeleton) {
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        int count = skeleton.jointCount();
        translations = new Vector3f[count];
        rotations = new Quaternionf[count];
        scales = new Vector3f[count];
        globalMatrices = new Matrix4f[count];
        globalDirty = new boolean[count];
        for (int joint = 0; joint < count; joint++) {
            translations[joint] = new Vector3f();
            rotations[joint] = new Quaternionf();
            scales[joint] = new Vector3f(1.0f);
            globalMatrices[joint] = new Matrix4f();
            globalDirty[joint] = true;
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

    /** 自创建以来实际重算的模型空间关节矩阵数量。 */
    public long globalRecomputeCount() {
        return globalRecomputeCount;
    }

    public PoseBuffer resetToBindPose() {
        for (int joint = 0; joint < translations.length; joint++) {
            copyBindTransform(joint);
        }
        changedAll();
        return this;
    }

    public PoseBuffer setLocalTransform(int jointIndex, JointTransform transform) {
        checkJoint(jointIndex);
        JointTransform value = Objects.requireNonNull(transform, "transform");
        value.copyTranslation(translations[jointIndex]);
        value.copyRotation(rotations[jointIndex]);
        value.copyScale(scales[jointIndex]);
        changed(jointIndex);
        return this;
    }

    public PoseBuffer setTranslation(int jointIndex, Vector3fc value) {
        checkJoint(jointIndex);
        JointTransform.requireFinite(value, "translation");
        translations[jointIndex].set(value);
        changed(jointIndex);
        return this;
    }

    public PoseBuffer setRotation(int jointIndex, Quaternionfc value) {
        checkJoint(jointIndex);
        JointTransform.setNormalized(rotations[jointIndex], value, "rotation");
        changed(jointIndex);
        return this;
    }

    public PoseBuffer setScale(int jointIndex, Vector3fc value) {
        checkJoint(jointIndex);
        JointTransform.requireFinite(value, "scale");
        scales[jointIndex].set(value);
        changed(jointIndex);
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

    void copyLocalRotation(int jointIndex, Quaternionf destination) {
        checkJoint(jointIndex);
        Objects.requireNonNull(destination, "destination").set(rotations[jointIndex]);
    }

    void globalPosition(int jointIndex, Vector3f destination) {
        checkJoint(jointIndex);
        updateGlobalMatrices();
        Matrix4f matrix = globalMatrices[jointIndex];
        Objects.requireNonNull(destination, "destination")
                .set(matrix.m30(), matrix.m31(), matrix.m32());
    }

    void globalRotation(int jointIndex, Quaternionf destination) {
        checkJoint(jointIndex);
        updateGlobalMatrices();
        globalMatrices[jointIndex].getUnnormalizedRotation(
                Objects.requireNonNull(destination, "destination")).normalize();
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
        changedAll();
        return this;
    }

    /** 无分配地复制同一骨架的可复用姿态。 */
    public PoseBuffer load(PoseBuffer pose) {
        PoseBuffer source = Objects.requireNonNull(pose, "pose");
        requireSkeleton(source.skeleton);
        if (source == this) return this;
        for (int joint = 0; joint < translations.length; joint++) {
            translations[joint].set(source.translations[joint]);
            rotations[joint].set(source.rotations[joint]);
            scales[joint].set(source.scales[joint]);
        }
        changedAll();
        return this;
    }

    void setTranslationSample(int jointIndex, Vector3fc first, Vector3fc second, float alpha) {
        translations[jointIndex].set(first).lerp(second, alpha);
        changed(jointIndex);
    }

    void setScaleSample(int jointIndex, Vector3fc first, Vector3fc second, float alpha) {
        scales[jointIndex].set(first).lerp(second, alpha);
        changed(jointIndex);
    }

    void setRotationSample(int jointIndex, Quaternionfc first, Quaternionfc second, float alpha) {
        rotations[jointIndex].set(first).slerp(second, alpha).normalize();
        changed(jointIndex);
    }

    void setTranslationCubicSample(int jointIndex, Vector3fc first, Vector3fc firstOutTangent,
                                   Vector3fc second, Vector3fc secondInTangent,
                                   float alpha, float durationSeconds) {
        hermite(translations[jointIndex], first, firstOutTangent, second, secondInTangent,
                alpha, durationSeconds);
        changed(jointIndex);
    }

    void setScaleCubicSample(int jointIndex, Vector3fc first, Vector3fc firstOutTangent,
                             Vector3fc second, Vector3fc secondInTangent,
                             float alpha, float durationSeconds) {
        hermite(scales[jointIndex], first, firstOutTangent, second, secondInTangent,
                alpha, durationSeconds);
        changed(jointIndex);
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
        changed(jointIndex);
    }

    void setBlendedLocal(int jointIndex, PoseBuffer first, PoseBuffer second, float alpha) {
        float tx = second.translations[jointIndex].x;
        float ty = second.translations[jointIndex].y;
        float tz = second.translations[jointIndex].z;
        float sx = second.scales[jointIndex].x;
        float sy = second.scales[jointIndex].y;
        float sz = second.scales[jointIndex].z;
        rotationScratch.set(second.rotations[jointIndex]);
        translations[jointIndex].set(first.translations[jointIndex]);
        translations[jointIndex].set(
                translations[jointIndex].x + (tx - translations[jointIndex].x) * alpha,
                translations[jointIndex].y + (ty - translations[jointIndex].y) * alpha,
                translations[jointIndex].z + (tz - translations[jointIndex].z) * alpha);
        rotations[jointIndex].set(first.rotations[jointIndex])
                .slerp(rotationScratch, alpha).normalize();
        scales[jointIndex].set(first.scales[jointIndex]);
        scales[jointIndex].set(
                scales[jointIndex].x + (sx - scales[jointIndex].x) * alpha,
                scales[jointIndex].y + (sy - scales[jointIndex].y) * alpha,
                scales[jointIndex].z + (sz - scales[jointIndex].z) * alpha);
        changed(jointIndex);
    }

    void setAdditiveLocal(int jointIndex, PoseBuffer base, PoseBuffer sample,
                          PoseBuffer reference, float weight) {
        Vector3f outputTranslation = translations[jointIndex];
        Quaternionf outputRotation = rotations[jointIndex];
        Vector3f outputScale = scales[jointIndex];
        Vector3f baseTranslation = base.translations[jointIndex];
        Quaternionf baseRotation = base.rotations[jointIndex];
        Vector3f baseScale = base.scales[jointIndex];
        Vector3f sampleTranslation = sample.translations[jointIndex];
        Quaternionf sampleRotation = sample.rotations[jointIndex];
        Vector3f sampleScale = sample.scales[jointIndex];
        Vector3f referenceTranslation = reference.translations[jointIndex];
        Quaternionf referenceRotation = reference.rotations[jointIndex];
        Vector3f referenceScale = reference.scales[jointIndex];

        float rx = referenceScale.x;
        float ry = referenceScale.y;
        float rz = referenceScale.z;
        if (Math.abs(rx) <= 1.0e-8f || Math.abs(ry) <= 1.0e-8f || Math.abs(rz) <= 1.0e-8f) {
            throw new IllegalArgumentException("additive reference scale is zero at joint "
                    + jointIndex);
        }

        float tx = baseTranslation.x + (sampleTranslation.x - referenceTranslation.x) * weight;
        float ty = baseTranslation.y + (sampleTranslation.y - referenceTranslation.y) * weight;
        float tz = baseTranslation.z + (sampleTranslation.z - referenceTranslation.z) * weight;
        float sx = baseScale.x * (1.0f + (sampleScale.x / rx - 1.0f) * weight);
        float sy = baseScale.y * (1.0f + (sampleScale.y / ry - 1.0f) * weight);
        float sz = baseScale.z * (1.0f + (sampleScale.z / rz - 1.0f) * weight);

        rotationScratch.set(sampleRotation)
                .mul(new Quaternionf(referenceRotation).conjugate())
                .normalize();
        Quaternionf weightedDelta = new Quaternionf().identity()
                .slerp(rotationScratch, weight).normalize();
        Quaternionf resultRotation = new Quaternionf(baseRotation)
                .mul(weightedDelta).normalize();
        outputTranslation.set(tx, ty, tz);
        outputRotation.set(resultRotation);
        outputScale.set(sx, sy, sz);
        changed(jointIndex);
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
        for (int order = 0; order < skeleton.evaluationCount(); order++) {
            int joint = skeleton.evaluationJoint(order);
            if (!globalDirty[joint]) continue;
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
            globalDirty[joint] = false;
            globalRecomputeCount = Math.incrementExact(globalRecomputeCount);
        }
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

    private void changed(int jointIndex) {
        for (int joint = 0; joint < globalDirty.length; joint++) {
            if (joint == jointIndex || skeleton.descendsFrom(joint, jointIndex)) {
                globalDirty[joint] = true;
            }
        }
        revision = Math.incrementExact(revision);
    }

    private void changedAll() {
        java.util.Arrays.fill(globalDirty, true);
        revision = Math.incrementExact(revision);
    }
}
