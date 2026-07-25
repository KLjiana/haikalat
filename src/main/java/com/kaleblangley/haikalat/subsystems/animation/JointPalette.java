package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Objects;

/** Reusable mesh-local skinning matrices for one {@link Skin}. */
public final class JointPalette {
    private final Skin skin;
    private final Matrix4f[] matrices;
    private final Matrix4f meshInverse = new Matrix4f();
    private final Matrix4f jointGlobal = new Matrix4f();
    private long revision;

    JointPalette(Skin skin) {
        this.skin = Objects.requireNonNull(skin, "skin");
        matrices = new Matrix4f[skin.jointCount()];
        for (int index = 0; index < matrices.length; index++) {
            matrices[index] = new Matrix4f();
        }
    }

    public Skin skin() {
        return skin;
    }

    public int jointCount() {
        return matrices.length;
    }

    public long revision() {
        return revision;
    }

    /** Computes {@code inverse(meshGlobal) * jointGlobal * inverseBind}. */
    public JointPalette update(PoseBuffer pose, Matrix4fc meshGlobalTransform) {
        PoseBuffer source = Objects.requireNonNull(pose, "pose");
        if (source.skeleton() != skin.skeleton()) {
            throw new IllegalArgumentException("pose belongs to a different skeleton");
        }
        Matrix4fc mesh = Objects.requireNonNull(meshGlobalTransform, "meshGlobalTransform");
        float determinant = mesh.determinant();
        if (!Float.isFinite(determinant) || Math.abs(determinant) <= 1.0e-12f) {
            throw new IllegalArgumentException("meshGlobalTransform must be finite and invertible");
        }
        meshInverse.set(mesh).invert();
        for (int paletteIndex = 0; paletteIndex < matrices.length; paletteIndex++) {
            source.globalMatrix(skin.skeletonJointIndex(paletteIndex), jointGlobal);
            matrices[paletteIndex].set(meshInverse).mul(jointGlobal)
                    .mul(skin.inverseBindMatrix(paletteIndex));
        }
        revision = Math.incrementExact(revision);
        return this;
    }

    public Matrix4fc matrix(int paletteIndex) {
        return new Matrix4f(matrices[paletteIndex]);
    }

    public int floatCount() {
        return Math.multiplyExact(matrices.length, 16);
    }

    public int byteSize() {
        return Math.multiplyExact(floatCount(), Float.BYTES);
    }

    public void copyTo(float[] destination, int offset) {
        Objects.requireNonNull(destination, "destination");
        if (offset < 0 || offset > destination.length - floatCount()) {
            throw new IndexOutOfBoundsException("joint palette destination range is too small");
        }
        for (int index = 0; index < matrices.length; index++) {
            matrices[index].get(destination, offset + index * 16);
        }
    }
}
