package com.kaleblangley.haikalat.subsystems.animation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 按声明顺序求值的 per-character constraint stack。 */
public final class AnimationConstraintStack {
    private final Skeleton skeleton;
    private final List<Entry> entries;
    private final Map<String, Integer> indices;
    private final AnimationConstraint.Context[] contexts;
    private ConstraintDiagnostics diagnostics = ConstraintDiagnostics.empty();

    private AnimationConstraintStack(Skeleton skeleton, List<Entry> entries,
                                     Map<String, Integer> indices) {
        this.skeleton = skeleton;
        this.entries = List.copyOf(entries);
        this.indices = Map.copyOf(indices);
        contexts = new AnimationConstraint.Context[entries.size()];
        for (int index = 0; index < entries.size(); index++) {
            contexts[index] = entries.get(index).initialContext();
        }
    }

    public static Builder builder(Skeleton skeleton) {
        return new Builder(skeleton);
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    public int size() {
        return entries.size();
    }

    public AnimationConstraintStack context(String name, AnimationConstraint.Context context) {
        Integer index = indices.get(name);
        if (index == null) throw new IllegalArgumentException(
                "unknown animation constraint '" + name + "'");
        contexts[index] = Objects.requireNonNull(context, "context");
        return this;
    }

    public ConstraintDiagnostics apply(PoseBuffer pose) {
        PoseBuffer target = Objects.requireNonNull(pose, "pose");
        if (target.skeleton() != skeleton) {
            throw new IllegalArgumentException("pose belongs to a different skeleton");
        }
        int iterations = 0;
        float maximumResidual = 0.0f;
        int clamped = 0;
        for (int index = 0; index < entries.size(); index++) {
            AnimationConstraint.Result result = entries.get(index)
                    .constraint().apply(target, contexts[index]);
            iterations += result.iterations();
            maximumResidual = Math.max(maximumResidual, result.residual());
            if (result.clamped()) clamped++;
        }
        diagnostics = new ConstraintDiagnostics(entries.size(), iterations,
                maximumResidual, clamped);
        return diagnostics;
    }

    public ConstraintDiagnostics diagnostics() {
        return diagnostics;
    }

    public record ConstraintDiagnostics(int constraintCount, int iterations,
                                        float maximumResidual, int clampedCount) {
        public ConstraintDiagnostics {
            if (constraintCount < 0 || iterations < 0 || clampedCount < 0
                    || !Float.isFinite(maximumResidual) || maximumResidual < 0.0f) {
                throw new IllegalArgumentException("invalid constraint diagnostics");
            }
        }

        public static ConstraintDiagnostics empty() {
            return new ConstraintDiagnostics(0, 0, 0.0f, 0);
        }
    }

    public static final class Builder {
        private final Skeleton skeleton;
        private final List<Entry> entries = new ArrayList<>();
        private final LinkedHashMap<String, Integer> indices = new LinkedHashMap<>();

        private Builder(Skeleton skeleton) {
            this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        }

        public Builder add(String name, AnimationConstraint constraint,
                           AnimationConstraint.Context initialContext) {
            String entryName = BlendTree1D.requireName(name, "constraint name");
            AnimationConstraint value = Objects.requireNonNull(constraint, "constraint");
            if (value.skeleton() != skeleton) {
                throw new IllegalArgumentException(
                        "constraint belongs to a different skeleton");
            }
            if (indices.putIfAbsent(entryName, entries.size()) != null) {
                throw new IllegalArgumentException(
                        "duplicate animation constraint '" + entryName + "'");
            }
            entries.add(new Entry(entryName, value,
                    Objects.requireNonNull(initialContext, "initialContext")));
            return this;
        }

        public Builder twoBone(String name, int root, int middle, int tip,
                               AnimationConstraint.Context initialContext) {
            return add(name, new TwoBoneConstraint(skeleton, root, middle, tip),
                    initialContext);
        }

        public Builder twoHand(String name, int shoulder, int elbow, int hand,
                               AnimationConstraint.Context initialContext) {
            return add(name, new TwoHandIkConstraint(
                    skeleton, shoulder, elbow, hand), initialContext);
        }

        public AnimationConstraintStack build() {
            return new AnimationConstraintStack(skeleton, entries, indices);
        }
    }

    private record Entry(String name, AnimationConstraint constraint,
                         AnimationConstraint.Context initialContext) {
    }

    private static final class TwoBoneConstraint implements AnimationConstraint {
        private final Skeleton skeleton;
        private final int root;
        private final int middle;
        private final int tip;

        private TwoBoneConstraint(Skeleton skeleton, int root, int middle, int tip) {
            this.skeleton = skeleton;
            this.root = root;
            this.middle = middle;
            this.tip = tip;
        }

        @Override
        public Skeleton skeleton() {
            return skeleton;
        }

        @Override
        public Result apply(PoseBuffer pose, Context context) {
            TwoBoneIkSolver.Result result = TwoBoneIkSolver.solve(pose,
                    root, middle, tip, context.target(), context.pole(), context.weight());
            return new Result(1, result.requestedDistance(), result.solvedDistance(),
                    result.tipError(), result.clamped());
        }
    }
}
