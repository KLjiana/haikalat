package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

/** 带 yaw/pitch 角度上限的 model-space Look-at 约束。 */
public final class LookAtConstraint implements AnimationConstraint {
    private static final float EPSILON = 1.0e-8f;

    private final Skeleton skeleton;
    private final int joint;
    private final Vector3f localForward;
    private final Vector3f localUp;
    private final float maxYawRadians;
    private final float maxPitchRadians;

    public LookAtConstraint(Skeleton skeleton, int joint, Vector3fc localForward,
                            Vector3fc localUp, float maxYawRadians,
                            float maxPitchRadians) {
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        ConstraintMath.requireJoint(skeleton, joint, "joint");
        JointTransform.requireFinite(localForward, "localForward");
        JointTransform.requireFinite(localUp, "localUp");
        if (!Float.isFinite(maxYawRadians) || maxYawRadians < 0.0f
                || !Float.isFinite(maxPitchRadians) || maxPitchRadians < 0.0f) {
            throw new IllegalArgumentException("look-at limits must be finite and non-negative");
        }
        this.joint = joint;
        this.localForward = new Vector3f(localForward);
        this.localUp = new Vector3f(localUp);
        if (this.localForward.lengthSquared() <= EPSILON
                || this.localUp.lengthSquared() <= EPSILON) {
            throw new IllegalArgumentException("look-at axes must be non-zero");
        }
        this.localForward.normalize();
        this.localUp.normalize();
        if (Math.abs(this.localForward.dot(this.localUp)) > 0.999f) {
            throw new IllegalArgumentException("look-at forward and up axes must not be parallel");
        }
        this.maxYawRadians = maxYawRadians;
        this.maxPitchRadians = maxPitchRadians;
    }

    @Override
    public Skeleton skeleton() {
        return skeleton;
    }

    @Override
    public Result apply(PoseBuffer pose, Context context) {
        PoseBuffer targetPose = requirePose(pose);
        Context input = Objects.requireNonNull(context, "context");
        Vector3f position = ConstraintMath.position(targetPose, joint);
        Vector3f requested = input.target().sub(position);
        float distance = requested.length();
        if (distance <= EPSILON || input.weight() == 0.0f) {
            return new Result(0, distance, distance, distance <= EPSILON ? 0.0f : distance,
                    distance <= EPSILON);
        }
        Vector3f direction = requested.div(distance);
        Quaternionf global = ConstraintMath.globalRotation(targetPose, joint);
        Vector3f currentForward = global.transform(new Vector3f(localForward));
        Quaternionf delta = new Quaternionf().rotationTo(currentForward, direction);
        Quaternionf desiredGlobal = delta.mul(global, new Quaternionf()).normalize();
        int parent = skeleton.joint(joint).parentIndex();
        Quaternionf desiredLocal = parent < 0 ? desiredGlobal
                : ConstraintMath.globalRotation(targetPose, parent).conjugate()
                .mul(desiredGlobal, new Quaternionf()).normalize();

        Quaternionf bind = new Quaternionf(skeleton.joint(joint).bindTransform().rotation());
        Quaternionf relative = new Quaternionf(bind).conjugate().mul(desiredLocal).normalize();
        Vector3f euler = relative.getEulerAnglesYXZ(new Vector3f());
        float yaw = Math.clamp(euler.y, -maxYawRadians, maxYawRadians);
        float pitch = Math.clamp(euler.x, -maxPitchRadians, maxPitchRadians);
        boolean clamped = yaw != euler.y || pitch != euler.x;
        Quaternionf limited = new Quaternionf().rotateY(yaw).rotateX(pitch);
        Quaternionf targetLocal = bind.mul(limited, new Quaternionf()).normalize();
        Quaternionf original = new Quaternionf(targetPose.localTransform(joint).rotation());
        targetPose.setRotation(joint,
                original.slerp(targetLocal, input.weight()).normalize());
        Vector3f solvedForward = ConstraintMath.globalRotation(targetPose, joint)
                .transform(new Vector3f(localForward));
        float residual = (float) Math.acos(Math.clamp(
                solvedForward.normalize().dot(direction), -1.0f, 1.0f));
        return new Result(1, distance, distance, residual, clamped);
    }

    private PoseBuffer requirePose(PoseBuffer pose) {
        PoseBuffer value = Objects.requireNonNull(pose, "pose");
        if (value.skeleton() != skeleton) {
            throw new IllegalArgumentException("pose belongs to a different skeleton");
        }
        return value;
    }
}
