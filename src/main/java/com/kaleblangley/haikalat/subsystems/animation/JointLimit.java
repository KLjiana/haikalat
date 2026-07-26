package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

/** Local-space swing cone 与 twist/hinge 上限。 */
public final class JointLimit {
    private static final float EPSILON = 1.0e-8f;

    private final Skeleton skeleton;
    private final int joint;
    private final Vector3f twistAxis;
    private final float maxSwingRadians;
    private final float minimumTwistRadians;
    private final float maximumTwistRadians;
    private final Quaternionf referenceRotation;
    private final float softWeight;

    public JointLimit(Skeleton skeleton, int joint, Vector3fc twistAxis,
                      float maxSwingRadians, float minimumTwistRadians,
                      float maximumTwistRadians, float softWeight) {
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        ConstraintMath.requireJoint(skeleton, joint, "joint");
        JointTransform.requireFinite(twistAxis, "twistAxis");
        if (!Float.isFinite(maxSwingRadians) || maxSwingRadians < 0.0f
                || !Float.isFinite(minimumTwistRadians)
                || !Float.isFinite(maximumTwistRadians)
                || minimumTwistRadians > maximumTwistRadians) {
            throw new IllegalArgumentException("invalid joint limit angles");
        }
        BoneMask.requireWeight(softWeight);
        this.joint = joint;
        this.twistAxis = new Vector3f(twistAxis);
        if (this.twistAxis.lengthSquared() <= EPSILON) {
            throw new IllegalArgumentException("twistAxis must be non-zero");
        }
        this.twistAxis.normalize();
        this.maxSwingRadians = maxSwingRadians;
        this.minimumTwistRadians = minimumTwistRadians;
        this.maximumTwistRadians = maximumTwistRadians;
        referenceRotation = new Quaternionf(skeleton.joint(joint)
                .bindTransform().rotation());
        this.softWeight = softWeight;
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    public int jointIndex() {
        return joint;
    }

    public boolean apply(PoseBuffer pose) {
        PoseBuffer target = Objects.requireNonNull(pose, "pose");
        if (target.skeleton() != skeleton) {
            throw new IllegalArgumentException("pose belongs to a different skeleton");
        }
        Quaternionf original = new Quaternionf(target.localTransform(joint).rotation());
        Quaternionf relative = new Quaternionf(referenceRotation).conjugate()
                .mul(original).normalize();
        Vector3f vector = new Vector3f(relative.x, relative.y, relative.z);
        Vector3f projected = new Vector3f(twistAxis).mul(vector.dot(twistAxis));
        Quaternionf twist = new Quaternionf(projected.x, projected.y, projected.z, relative.w);
        if (twist.lengthSquared() <= EPSILON) twist.identity();
        else twist.normalize();
        Quaternionf swing = new Quaternionf(relative)
                .mul(new Quaternionf(twist).conjugate()).normalize();

        float swingAngle = swing.angle();
        Quaternionf limitedSwing = new Quaternionf(swing);
        boolean clamped = false;
        if (swingAngle > maxSwingRadians) {
            limitedSwing.identity().slerp(swing,
                    maxSwingRadians / Math.max(EPSILON, swingAngle)).normalize();
            clamped = true;
        }
        float sign = Math.signum(twist.x * twistAxis.x
                + twist.y * twistAxis.y + twist.z * twistAxis.z);
        float twistAngle = twist.angle() * (sign == 0.0f ? 1.0f : sign);
        float limitedTwistAngle = Math.clamp(twistAngle,
                minimumTwistRadians, maximumTwistRadians);
        if (limitedTwistAngle != twistAngle) clamped = true;
        Quaternionf limitedTwist = new Quaternionf().fromAxisAngleRad(
                twistAxis, limitedTwistAngle);
        Quaternionf limited = new Quaternionf(referenceRotation)
                .mul(limitedSwing).mul(limitedTwist).normalize();
        target.setRotation(joint, original.slerp(limited, softWeight).normalize());
        return clamped;
    }
}
