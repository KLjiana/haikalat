package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

/** 不依赖 Scene/Physics/GL 的纯输入姿态约束。 */
public interface AnimationConstraint {
    Skeleton skeleton();

    Result apply(PoseBuffer pose, Context context);

    /** Model-space target、pole、surface normal、pelvis offset 与 solve weight。 */
    record Context(float targetX, float targetY, float targetZ,
                   float poleX, float poleY, float poleZ,
                   float normalX, float normalY, float normalZ,
                   float pelvisOffset, float weight, float deltaSeconds) {
        public Context {
            if (!Float.isFinite(targetX) || !Float.isFinite(targetY)
                    || !Float.isFinite(targetZ) || !Float.isFinite(poleX)
                    || !Float.isFinite(poleY) || !Float.isFinite(poleZ)
                    || !Float.isFinite(normalX) || !Float.isFinite(normalY)
                    || !Float.isFinite(normalZ) || !Float.isFinite(pelvisOffset)
                    || !Float.isFinite(weight) || !Float.isFinite(deltaSeconds)) {
                throw new IllegalArgumentException("constraint context must be finite");
            }
            BoneMask.requireWeight(weight);
            if (deltaSeconds < 0.0f) {
                throw new IllegalArgumentException("deltaSeconds must be non-negative");
            }
        }

        public static Context target(Vector3fc target, Vector3fc pole,
                                     float weight, float deltaSeconds) {
            JointTransform.requireFinite(target, "target");
            JointTransform.requireFinite(pole, "pole");
            return new Context(target.x(), target.y(), target.z(),
                    pole.x(), pole.y(), pole.z(),
                    0.0f, 1.0f, 0.0f, 0.0f, weight, deltaSeconds);
        }

        public static Context foot(Vector3fc target, Vector3fc normal, Vector3fc pole,
                                   float pelvisOffset, float weight, float deltaSeconds) {
            JointTransform.requireFinite(normal, "normal");
            Context base = target(target, pole, weight, deltaSeconds);
            return new Context(base.targetX, base.targetY, base.targetZ,
                    base.poleX, base.poleY, base.poleZ,
                    normal.x(), normal.y(), normal.z(), pelvisOffset,
                    weight, deltaSeconds);
        }

        public Vector3f target() {
            return new Vector3f(targetX, targetY, targetZ);
        }

        public Vector3f pole() {
            return new Vector3f(poleX, poleY, poleZ);
        }

        public Vector3f normal() {
            return new Vector3f(normalX, normalY, normalZ);
        }
    }

    record Result(int iterations, float requestedDistance, float solvedDistance,
                  float residual, boolean clamped) {
        public Result {
            if (iterations < 0 || !Float.isFinite(requestedDistance)
                    || requestedDistance < 0.0f || !Float.isFinite(solvedDistance)
                    || solvedDistance < 0.0f || !Float.isFinite(residual)
                    || residual < 0.0f) {
                throw new IllegalArgumentException("invalid constraint result");
            }
        }

        public static Result unchanged() {
            return new Result(0, 0.0f, 0.0f, 0.0f, false);
        }
    }
}
