package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

/** 单次更新产生的模型空间根位移与旋转增量。 */
public final class RootMotionDelta {
    private static final RootMotionDelta IDENTITY = new RootMotionDelta(
            new Vector3f(), new Quaternionf());

    private final Vector3f translation;
    private final Quaternionf rotation;

    public RootMotionDelta(Vector3fc translation, Quaternionfc rotation) {
        JointTransform.requireFinite(translation, "translation");
        this.translation = new Vector3f(translation);
        this.rotation = new Quaternionf();
        JointTransform.setNormalized(this.rotation, rotation, "rotation");
    }

    public static RootMotionDelta identity() {
        return IDENTITY;
    }

    public Vector3fc translation() {
        return new Vector3f(translation);
    }

    public Quaternionfc rotation() {
        return new Quaternionf(rotation);
    }

    /** 按时间顺序把 next 接到当前增量之后。 */
    public RootMotionDelta then(RootMotionDelta next) {
        RootMotionDelta value = Objects.requireNonNull(next, "next");
        return new RootMotionDelta(new Vector3f(translation).add(value.translation),
                new Quaternionf(value.rotation).mul(rotation).normalize());
    }

    RootMotionDelta repeated(long count) {
        if (count < 0L) throw new IllegalArgumentException("count must be non-negative");
        RootMotionDelta result = identity();
        RootMotionDelta factor = this;
        long remaining = count;
        while (remaining != 0L) {
            if ((remaining & 1L) != 0L) result = result.then(factor);
            remaining >>>= 1;
            if (remaining != 0L) factor = factor.then(factor);
        }
        return result;
    }

    static RootMotionDelta between(JointTransform first, JointTransform second) {
        Vector3f translation = new Vector3f(second.translation()).sub(first.translation());
        Quaternionf rotation = new Quaternionf(second.rotation())
                .mul(new Quaternionf(first.rotation()).conjugate()).normalize();
        return new RootMotionDelta(translation, rotation);
    }

    @Override
    public String toString() {
        return "RootMotionDelta[translation=" + translation + ", rotation=" + rotation + ']';
    }
}
