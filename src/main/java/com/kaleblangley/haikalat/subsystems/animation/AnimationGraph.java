package com.kaleblangley.haikalat.subsystems.animation;

import com.kaleblangley.haikalat.core.curve.Curve1f;
import com.kaleblangley.haikalat.core.curve.Curves;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 不可变、可共享的动画状态机与 motion graph definition。 */
public final class AnimationGraph {
    private final String name;
    private final Skeleton skeleton;
    private final List<ParameterDefinition> parameters;
    private final List<StateDefinition> states;
    private final List<TransitionDefinition> transitions;
    private final Map<String, Integer> parameterIndices;
    private final Map<String, Integer> stateIndices;
    private final int entryStateIndex;
    private final int morphTargetCount;

    private AnimationGraph(String name, Skeleton skeleton,
                           List<ParameterDefinition> parameters,
                           List<StateDefinition> states,
                           List<TransitionDefinition> transitions,
                           Map<String, Integer> parameterIndices,
                           Map<String, Integer> stateIndices,
                           int entryStateIndex, int morphTargetCount) {
        this.name = name;
        this.skeleton = skeleton;
        this.parameters = List.copyOf(parameters);
        this.states = List.copyOf(states);
        this.transitions = List.copyOf(transitions);
        this.parameterIndices = Map.copyOf(parameterIndices);
        this.stateIndices = Map.copyOf(stateIndices);
        this.entryStateIndex = entryStateIndex;
        this.morphTargetCount = morphTargetCount;
    }

    public static Builder builder(String name, Skeleton skeleton) {
        return new Builder(name, skeleton);
    }

    public String name() {
        return name;
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    public int parameterCount() {
        return parameters.size();
    }

    public int stateCount() {
        return states.size();
    }

    public int transitionCount() {
        return transitions.size();
    }

    public int morphTargetCount() {
        return morphTargetCount;
    }

    public AnimationController createController() {
        return new AnimationController(this);
    }

    int parameterIndex(String name) {
        Integer index = parameterIndices.get(name);
        if (index == null) throw new IllegalArgumentException("unknown animation parameter '" + name + "'");
        return index;
    }

    int stateIndex(String name) {
        Integer index = stateIndices.get(name);
        if (index == null) throw new IllegalArgumentException("unknown animation state '" + name + "'");
        return index;
    }

    ParameterDefinition parameter(int index) {
        return parameters.get(index);
    }

    StateDefinition state(int index) {
        return states.get(index);
    }

    List<TransitionDefinition> transitions() {
        return transitions;
    }

    int entryStateIndex() {
        return entryStateIndex;
    }

    public enum ParameterType {
        BOOLEAN,
        FLOAT,
        INTEGER,
        TRIGGER
    }

    public enum InterruptionPolicy {
        NONE,
        SOURCE,
        DESTINATION,
        ANY
    }

    public enum Comparison {
        BOOLEAN_EQUALS,
        FLOAT_LESS,
        FLOAT_LESS_OR_EQUAL,
        FLOAT_GREATER,
        FLOAT_GREATER_OR_EQUAL,
        INTEGER_EQUALS,
        INTEGER_NOT_EQUALS,
        INTEGER_LESS,
        INTEGER_GREATER,
        TRIGGERED
    }

    /** Transition condition definition；参数 slot 在 build 时解析。 */
    public record Condition(String parameter, Comparison comparison,
                            boolean booleanValue, float floatValue, int integerValue) {
        public Condition {
            parameter = BlendTree1D.requireName(parameter, "parameter");
            comparison = Objects.requireNonNull(comparison, "comparison");
            if (!Float.isFinite(floatValue)) {
                throw new IllegalArgumentException("condition floatValue must be finite");
            }
        }

        public static Condition bool(String parameter, boolean expected) {
            return new Condition(parameter, Comparison.BOOLEAN_EQUALS,
                    expected, 0.0f, 0);
        }

        public static Condition floatGreater(String parameter, float threshold) {
            return new Condition(parameter, Comparison.FLOAT_GREATER,
                    false, threshold, 0);
        }

        public static Condition floatLess(String parameter, float threshold) {
            return new Condition(parameter, Comparison.FLOAT_LESS,
                    false, threshold, 0);
        }

        public static Condition floatAtLeast(String parameter, float threshold) {
            return new Condition(parameter, Comparison.FLOAT_GREATER_OR_EQUAL,
                    false, threshold, 0);
        }

        public static Condition floatAtMost(String parameter, float threshold) {
            return new Condition(parameter, Comparison.FLOAT_LESS_OR_EQUAL,
                    false, threshold, 0);
        }

        public static Condition integer(String parameter, int expected) {
            return new Condition(parameter, Comparison.INTEGER_EQUALS,
                    false, 0.0f, expected);
        }

        public static Condition integerNot(String parameter, int expected) {
            return new Condition(parameter, Comparison.INTEGER_NOT_EQUALS,
                    false, 0.0f, expected);
        }

        public static Condition trigger(String parameter) {
            return new Condition(parameter, Comparison.TRIGGERED,
                    false, 0.0f, 0);
        }
    }

    public record StateOptions(float playbackSpeed, String playbackSpeedParameter,
                               String syncGroup, String enterSignal, String exitSignal,
                               float normalizedStartOffset) {
        public StateOptions {
            if (!Float.isFinite(playbackSpeed)) {
                throw new IllegalArgumentException("playbackSpeed must be finite");
            }
            playbackSpeedParameter = normalizeOptional(playbackSpeedParameter);
            syncGroup = normalizeOptional(syncGroup);
            enterSignal = normalizeOptional(enterSignal);
            exitSignal = normalizeOptional(exitSignal);
            if (!Float.isFinite(normalizedStartOffset)
                    || normalizedStartOffset < 0.0f || normalizedStartOffset > 1.0f) {
                throw new IllegalArgumentException(
                        "normalizedStartOffset must be finite and in [0, 1]");
            }
        }

        public static StateOptions defaults() {
            return new StateOptions(1.0f, "", "", "", "", 0.0f);
        }

        public StateOptions speed(float speed) {
            return new StateOptions(speed, "", syncGroup, enterSignal, exitSignal,
                    normalizedStartOffset);
        }

        public StateOptions speedParameter(String parameter) {
            return new StateOptions(playbackSpeed, parameter, syncGroup, enterSignal,
                    exitSignal, normalizedStartOffset);
        }

        public StateOptions syncGroup(String group) {
            return new StateOptions(playbackSpeed, playbackSpeedParameter, group,
                    enterSignal, exitSignal, normalizedStartOffset);
        }

        public StateOptions signals(String enter, String exit) {
            return new StateOptions(playbackSpeed, playbackSpeedParameter, syncGroup,
                    enter, exit, normalizedStartOffset);
        }

        public StateOptions startOffset(float offset) {
            return new StateOptions(playbackSpeed, playbackSpeedParameter, syncGroup,
                    enterSignal, exitSignal, offset);
        }
    }

    public static final class TransitionSpec {
        private final float exitTime;
        private final float durationSeconds;
        private final Curve1f easing;
        private final float destinationOffset;
        private final InterruptionPolicy interruptionPolicy;
        private final boolean queued;
        private final List<Condition> conditions;

        private TransitionSpec(TransitionBuilder builder) {
            exitTime = builder.exitTime;
            durationSeconds = builder.durationSeconds;
            easing = builder.easing;
            destinationOffset = builder.destinationOffset;
            interruptionPolicy = builder.interruptionPolicy;
            queued = builder.queued;
            conditions = List.copyOf(builder.conditions);
        }

        public static TransitionBuilder builder() {
            return new TransitionBuilder();
        }
    }

    public static final class TransitionBuilder {
        private float exitTime = -1.0f;
        private float durationSeconds;
        private Curve1f easing = Curves.LINEAR;
        private float destinationOffset;
        private InterruptionPolicy interruptionPolicy = InterruptionPolicy.NONE;
        private boolean queued;
        private final List<Condition> conditions = new ArrayList<>();

        public TransitionBuilder exitTime(float normalizedExitTime) {
            if (!Float.isFinite(normalizedExitTime)
                    || normalizedExitTime < 0.0f || normalizedExitTime > 1.0f) {
                throw new IllegalArgumentException("exit time must be finite and in [0, 1]");
            }
            exitTime = normalizedExitTime;
            return this;
        }

        public TransitionBuilder duration(float seconds) {
            if (!Float.isFinite(seconds) || seconds < 0.0f) {
                throw new IllegalArgumentException("duration must be finite and non-negative");
            }
            durationSeconds = seconds;
            return this;
        }

        public TransitionBuilder easing(Curve1f curve) {
            easing = Objects.requireNonNull(curve, "curve");
            return this;
        }

        public TransitionBuilder destinationOffset(float normalizedOffset) {
            if (!Float.isFinite(normalizedOffset)
                    || normalizedOffset < 0.0f || normalizedOffset > 1.0f) {
                throw new IllegalArgumentException(
                        "destination offset must be finite and in [0, 1]");
            }
            destinationOffset = normalizedOffset;
            return this;
        }

        public TransitionBuilder interruption(InterruptionPolicy policy) {
            interruptionPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public TransitionBuilder queued(boolean value) {
            queued = value;
            return this;
        }

        public TransitionBuilder when(Condition condition) {
            conditions.add(Objects.requireNonNull(condition, "condition"));
            return this;
        }

        public TransitionSpec build() {
            return new TransitionSpec(this);
        }
    }

    public static final class Builder {
        private final String name;
        private final Skeleton skeleton;
        private final LinkedHashMap<String, ParameterDefinition> parameters = new LinkedHashMap<>();
        private final LinkedHashMap<String, StateDefinition> states = new LinkedHashMap<>();
        private final List<PendingTransition> transitions = new ArrayList<>();
        private String entryState;

        private Builder(String name, Skeleton skeleton) {
            this.name = BlendTree1D.requireName(name, "name");
            this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        }

        public Builder booleanParameter(String name, boolean defaultValue) {
            return parameter(new ParameterDefinition(name, ParameterType.BOOLEAN,
                    defaultValue, 0.0f, 0));
        }

        public Builder floatParameter(String name, float defaultValue) {
            if (!Float.isFinite(defaultValue)) {
                throw new IllegalArgumentException("default float must be finite");
            }
            return parameter(new ParameterDefinition(name, ParameterType.FLOAT,
                    false, defaultValue, 0));
        }

        public Builder integerParameter(String name, int defaultValue) {
            return parameter(new ParameterDefinition(name, ParameterType.INTEGER,
                    false, 0.0f, defaultValue));
        }

        public Builder triggerParameter(String name) {
            return parameter(new ParameterDefinition(name, ParameterType.TRIGGER,
                    false, 0.0f, 0));
        }

        public Builder state(String name, AnimationMotion motion,
                             AnimationPlayer.LoopMode loopMode) {
            return state(name, motion, loopMode, StateOptions.defaults());
        }

        public Builder state(String name, AnimationMotion motion,
                             AnimationPlayer.LoopMode loopMode, StateOptions options) {
            String stateName = BlendTree1D.requireName(name, "state name");
            AnimationMotion stateMotion = Objects.requireNonNull(motion, "motion");
            if (stateMotion.skeleton() != skeleton) {
                throw new IllegalArgumentException("state '" + stateName
                        + "' motion belongs to a different skeleton");
            }
            if (states.containsKey(stateName)) {
                throw new IllegalArgumentException("duplicate animation state '" + stateName + "'");
            }
            states.put(stateName, new StateDefinition(stateName, stateMotion,
                    Objects.requireNonNull(loopMode, "loopMode"),
                    Objects.requireNonNull(options, "options")));
            return this;
        }

        public Builder entry(String stateName) {
            if (entryState != null) {
                throw new IllegalArgumentException("AnimationGraph already has an entry state");
            }
            entryState = BlendTree1D.requireName(stateName, "entry state");
            return this;
        }

        public Builder transition(String source, String destination, TransitionSpec spec) {
            transitions.add(new PendingTransition(
                    BlendTree1D.requireName(source, "source"),
                    BlendTree1D.requireName(destination, "destination"),
                    Objects.requireNonNull(spec, "spec"), false));
            return this;
        }

        public Builder anyTransition(String destination, TransitionSpec spec) {
            transitions.add(new PendingTransition("", BlendTree1D.requireName(
                    destination, "destination"), Objects.requireNonNull(spec, "spec"), true));
            return this;
        }

        public AnimationGraph build() {
            if (states.isEmpty()) throw new IllegalArgumentException("AnimationGraph has no states");
            if (entryState == null) throw new IllegalArgumentException(
                    "AnimationGraph must declare exactly one entry state");
            if (!states.containsKey(entryState)) {
                throw new IllegalArgumentException("unknown entry state '" + entryState + "'");
            }
            LinkedHashMap<String, Integer> parameterIndices = indices(parameters);
            LinkedHashMap<String, Integer> stateIndices = indices(states);
            int morphTargetCount = 0;
            for (StateDefinition state : states.values()) {
                validateMotion(state.motion(), parameterIndices, parameters);
                morphTargetCount = mergeMorphTargetCount(morphTargetCount,
                        motionMorphTargetCount(state.motion()));
                String speedParameter = state.options().playbackSpeedParameter();
                if (!speedParameter.isEmpty()) {
                    requireParameterType(speedParameter, ParameterType.FLOAT,
                            parameterIndices, parameters);
                }
            }
            ArrayList<TransitionDefinition> compiledTransitions = new ArrayList<>();
            for (int declaration = 0; declaration < transitions.size(); declaration++) {
                PendingTransition pending = transitions.get(declaration);
                int source = pending.anyState() ? -1 : requireState(
                        pending.source(), stateIndices);
                int destination = requireState(pending.destination(), stateIndices);
                TransitionSpec spec = pending.spec();
                ArrayList<ConditionDefinition> conditions = new ArrayList<>();
                for (Condition condition : spec.conditions) {
                    int parameterIndex = requireParameter(condition.parameter(), parameterIndices);
                    ParameterType type = parameters.get(condition.parameter()).type();
                    validateComparison(condition.comparison(), type, condition.parameter());
                    conditions.add(new ConditionDefinition(parameterIndex, condition));
                }
                compiledTransitions.add(new TransitionDefinition(source, destination,
                        spec.exitTime, spec.durationSeconds, spec.easing,
                        spec.destinationOffset, spec.interruptionPolicy, spec.queued,
                        List.copyOf(conditions), declaration));
            }
            return new AnimationGraph(name, skeleton, new ArrayList<>(parameters.values()),
                    new ArrayList<>(states.values()), compiledTransitions,
                    parameterIndices, stateIndices, stateIndices.get(entryState),
                    morphTargetCount);
        }

        private Builder parameter(ParameterDefinition definition) {
            if (parameters.putIfAbsent(definition.name(), definition) != null) {
                throw new IllegalArgumentException(
                        "duplicate animation parameter '" + definition.name() + "'");
            }
            return this;
        }
    }

    record ParameterDefinition(String name, ParameterType type, boolean booleanDefault,
                               float floatDefault, int integerDefault) {
        ParameterDefinition {
            name = BlendTree1D.requireName(name, "parameter name");
            type = Objects.requireNonNull(type, "type");
        }
    }

    record StateDefinition(String name, AnimationMotion motion,
                           AnimationPlayer.LoopMode loopMode, StateOptions options) {
    }

    record ConditionDefinition(int parameterIndex, Condition condition) {
    }

    record TransitionDefinition(int sourceState, int destinationState, float exitTime,
                                float durationSeconds, Curve1f easing,
                                float destinationOffset,
                                InterruptionPolicy interruptionPolicy, boolean queued,
                                List<ConditionDefinition> conditions,
                                int declarationOrder) {
    }

    private record PendingTransition(String source, String destination,
                                     TransitionSpec spec, boolean anyState) {
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value;
    }

    private static <T> LinkedHashMap<String, Integer> indices(
            LinkedHashMap<String, T> definitions) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        int index = 0;
        for (String name : definitions.keySet()) result.put(name, index++);
        return result;
    }

    private static int requireParameter(String name, Map<String, Integer> indices) {
        Integer index = indices.get(name);
        if (index == null) throw new IllegalArgumentException(
                "unknown animation parameter '" + name + "'");
        return index;
    }

    private static void requireParameterType(String name, ParameterType expected,
                                             Map<String, Integer> indices,
                                             Map<String, ParameterDefinition> definitions) {
        requireParameter(name, indices);
        ParameterType actual = definitions.get(name).type();
        if (actual != expected) {
            throw new IllegalArgumentException("animation parameter '" + name
                    + "' must be " + expected + " but is " + actual);
        }
    }

    private static int requireState(String name, Map<String, Integer> indices) {
        Integer index = indices.get(name);
        if (index == null) throw new IllegalArgumentException(
                "unknown animation state '" + name + "'");
        return index;
    }

    private static void validateMotion(AnimationMotion motion,
                                       Map<String, Integer> parameters,
                                       Map<String, ParameterDefinition> definitions) {
        if (motion instanceof BlendTree1D tree) {
            requireParameterType(tree.parameter(), ParameterType.FLOAT,
                    parameters, definitions);
            for (BlendTree1D.Child child : tree.children()) {
                validateMotion(child.motion(), parameters, definitions);
            }
        } else if (motion instanceof BlendTree2D tree) {
            requireParameterType(tree.xParameter(), ParameterType.FLOAT,
                    parameters, definitions);
            requireParameterType(tree.yParameter(), ParameterType.FLOAT,
                    parameters, definitions);
            for (BlendTree2D.Child child : tree.children()) {
                validateMotion(child.motion(), parameters, definitions);
            }
        }
    }

    private static int motionMorphTargetCount(AnimationMotion motion) {
        if (motion instanceof ClipMotion clip) return clip.morphTargetCount();
        int count = 0;
        if (motion instanceof BlendTree1D tree) {
            for (BlendTree1D.Child child : tree.children()) {
                count = mergeMorphTargetCount(count,
                        motionMorphTargetCount(child.motion()));
            }
        } else if (motion instanceof BlendTree2D tree) {
            for (BlendTree2D.Child child : tree.children()) {
                count = mergeMorphTargetCount(count,
                        motionMorphTargetCount(child.motion()));
            }
        }
        return count;
    }

    private static int mergeMorphTargetCount(int current, int candidate) {
        if (candidate == 0) return current;
        if (current == 0) return candidate;
        if (current != candidate) {
            throw new IllegalArgumentException(
                    "all morph tracks in an AnimationGraph must use the same target count");
        }
        return current;
    }

    private static void validateComparison(Comparison comparison, ParameterType type,
                                           String parameter) {
        boolean valid = switch (type) {
            case BOOLEAN -> comparison == Comparison.BOOLEAN_EQUALS;
            case FLOAT -> comparison == Comparison.FLOAT_LESS
                    || comparison == Comparison.FLOAT_LESS_OR_EQUAL
                    || comparison == Comparison.FLOAT_GREATER
                    || comparison == Comparison.FLOAT_GREATER_OR_EQUAL;
            case INTEGER -> comparison == Comparison.INTEGER_EQUALS
                    || comparison == Comparison.INTEGER_NOT_EQUALS
                    || comparison == Comparison.INTEGER_LESS
                    || comparison == Comparison.INTEGER_GREATER;
            case TRIGGER -> comparison == Comparison.TRIGGERED;
        };
        if (!valid) {
            throw new IllegalArgumentException("condition comparison " + comparison
                    + " is invalid for " + type + " parameter '" + parameter + "'");
        }
    }
}
