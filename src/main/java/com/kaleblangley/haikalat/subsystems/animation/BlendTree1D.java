package com.kaleblangley.haikalat.subsystems.animation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 仅激活相邻两个 child 的一维 Blend Tree definition。 */
public final class BlendTree1D implements AnimationMotion {
    private final String parameter;
    private final List<Child> children;
    private final Skeleton skeleton;
    private final float durationSeconds;

    private BlendTree1D(String parameter, List<Child> children) {
        this.parameter = requireName(parameter, "parameter");
        if (children.size() < 2) {
            throw new IllegalArgumentException("BlendTree1D requires at least two children");
        }
        ArrayList<Child> ordered = new ArrayList<>(children);
        ordered.sort(Comparator.comparingDouble(Child::threshold));
        Skeleton owner = ordered.getFirst().motion().skeleton();
        float duration = 0.0f;
        float previous = Float.NEGATIVE_INFINITY;
        for (int index = 0; index < ordered.size(); index++) {
            Child child = ordered.get(index);
            if (!Float.isFinite(child.threshold()) || child.threshold() <= previous) {
                throw new IllegalArgumentException(
                        "BlendTree1D thresholds must be finite and strictly increasing");
            }
            if (child.motion().skeleton() != owner) {
                throw new IllegalArgumentException("BlendTree1D children use different skeletons");
            }
            previous = child.threshold();
            duration = Math.max(duration, child.motion().durationSeconds());
        }
        this.children = List.copyOf(ordered);
        skeleton = owner;
        durationSeconds = duration;
    }

    public static Builder builder(String parameter) {
        return new Builder(parameter);
    }

    public String parameter() {
        return parameter;
    }

    public List<Child> children() {
        return children;
    }

    @Override
    public Skeleton skeleton() {
        return skeleton;
    }

    @Override
    public float durationSeconds() {
        return durationSeconds;
    }

    public record Child(float threshold, AnimationMotion motion) {
        public Child {
            motion = Objects.requireNonNull(motion, "motion");
        }
    }

    public static final class Builder {
        private final String parameter;
        private final List<Child> children = new ArrayList<>();

        private Builder(String parameter) {
            this.parameter = requireName(parameter, "parameter");
        }

        public Builder child(float threshold, AnimationMotion motion) {
            children.add(new Child(threshold, motion));
            return this;
        }

        public BlendTree1D build() {
            return new BlendTree1D(parameter, children);
        }
    }

    static String requireName(String value, String label) {
        String result = Objects.requireNonNull(value, label);
        if (result.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
        return result;
    }
}
