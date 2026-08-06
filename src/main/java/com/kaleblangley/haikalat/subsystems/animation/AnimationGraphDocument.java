package com.kaleblangley.haikalat.subsystems.animation;

import java.util.List;
import java.util.Objects;

/** Immutable, GL-free representation of a haikalat.animation-graph/1 sidecar. */
public final class AnimationGraphDocument {
    public static final String FORMAT = "haikalat.animation-graph/1";

    private final String skeleton;
    private final List<Parameter> parameters;
    private final List<State> states;
    private final String entry;
    private final List<Transition> transitions;

    public AnimationGraphDocument(String skeleton, List<Parameter> parameters,
                                  List<State> states, String entry,
                                  List<Transition> transitions) {
        this.skeleton = requireText(skeleton, "skeleton");
        this.parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        this.states = List.copyOf(Objects.requireNonNull(states, "states"));
        this.entry = requireText(entry, "entry");
        this.transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions"));
    }

    public String skeleton() { return skeleton; }
    public List<Parameter> parameters() { return parameters; }
    public List<State> states() { return states; }
    public String entry() { return entry; }
    public List<Transition> transitions() { return transitions; }

    public AnimationGraphDocument withEntry(String state) {
        String requested = requireText(state, "entry");
        if (states.stream().noneMatch(value -> value.id().equals(requested))) {
            throw new IllegalArgumentException("unknown entry state: " + requested);
        }
        return new AnimationGraphDocument(skeleton, parameters, states, requested, transitions);
    }

    public record Parameter(String name, AnimationGraph.ParameterType type,
                            boolean booleanDefault, float floatDefault, int integerDefault) {
        public Parameter {
            name = requireText(name, "parameter name");
            type = Objects.requireNonNull(type, "type");
            if (!Float.isFinite(floatDefault)) {
                throw new IllegalArgumentException("parameter float default must be finite");
            }
        }

        public Object defaultValue() {
            return switch (type) {
                case BOOLEAN -> booleanDefault;
                case FLOAT -> floatDefault;
                case INTEGER -> integerDefault;
                case TRIGGER -> null;
            };
        }
    }

    public record State(String id, String clip, AnimationPlayer.LoopMode loopMode,
                        float playbackSpeed, String playbackSpeedParameter,
                        String syncGroup, String enterSignal, String exitSignal,
                        float normalizedStartOffset) {
        public State {
            id = requireText(id, "state id");
            clip = requireText(clip, "state clip");
            loopMode = Objects.requireNonNull(loopMode, "loopMode");
            if (!Float.isFinite(playbackSpeed)) {
                throw new IllegalArgumentException("state playbackSpeed must be finite");
            }
            playbackSpeedParameter = optional(playbackSpeedParameter);
            syncGroup = optional(syncGroup);
            enterSignal = optional(enterSignal);
            exitSignal = optional(exitSignal);
            if (!Float.isFinite(normalizedStartOffset)
                    || normalizedStartOffset < 0.0f || normalizedStartOffset > 1.0f) {
                throw new IllegalArgumentException("state startOffset must be finite and in [0, 1]");
            }
        }

        public State(String id, String clip, AnimationPlayer.LoopMode loopMode) {
            this(id, clip, loopMode, 1.0f, "", "", "", "", 0.0f);
        }
    }

    public record Condition(String parameter, AnimationGraph.Comparison comparison,
                            boolean booleanValue, float floatValue, int integerValue) {
        public Condition {
            parameter = requireText(parameter, "condition parameter");
            comparison = Objects.requireNonNull(comparison, "comparison");
            if (!Float.isFinite(floatValue)) {
                throw new IllegalArgumentException("condition float value must be finite");
            }
        }
    }

    public record Transition(String from, String to, float durationSeconds,
                             float exitTime, float destinationOffset,
                             AnimationGraph.InterruptionPolicy interruptionPolicy,
                             boolean queued, List<Condition> conditions) {
        public Transition {
            from = requireText(from, "transition from");
            to = requireText(to, "transition to");
            if (!Float.isFinite(durationSeconds) || durationSeconds < 0.0f) {
                throw new IllegalArgumentException("transition duration must be finite and non-negative");
            }
            if (!Float.isFinite(exitTime) && exitTime != -1.0f) {
                throw new IllegalArgumentException("transition exitTime must be finite or -1");
            }
            if (exitTime < -1.0f || exitTime > 1.0f) {
                throw new IllegalArgumentException("transition exitTime must be -1 or in [0, 1]");
            }
            if (!Float.isFinite(destinationOffset)
                    || destinationOffset < 0.0f || destinationOffset > 1.0f) {
                throw new IllegalArgumentException("transition destinationOffset must be in [0, 1]");
            }
            interruptionPolicy = Objects.requireNonNull(interruptionPolicy,
                    "interruptionPolicy");
            conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions"));
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static String optional(String value) {
        return value == null ? "" : value;
    }
}
