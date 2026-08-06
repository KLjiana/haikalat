package com.kaleblangley.haikalat.subsystems.animation;

import java.util.Map;
import java.util.Objects;

/** Compiles an immutable sidecar document into the existing graph runtime. */
public final class AnimationGraphCompiler {
    private AnimationGraphCompiler() {}

    /**
     * Compiles a graph against the clips and skeleton owned by one character
     * instance. No asset or GL lookup occurs here.
     */
    public static AnimationGraph compile(String graphName, AnimationGraphDocument document,
                                         Skeleton skeleton,
                                         Map<String, AnimationClip> clips) {
        return compile(graphName, document, "", skeleton, clips);
    }

    /** Compiles while also checking the content-declared skeleton identity. */
    public static AnimationGraph compile(String graphName, AnimationGraphDocument document,
                                         String expectedSkeleton, Skeleton skeleton,
                                         Map<String, AnimationClip> clips) {
        Objects.requireNonNull(graphName, "graphName");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(skeleton, "skeleton");
        Objects.requireNonNull(clips, "clips");
        if (expectedSkeleton != null && !expectedSkeleton.isBlank()
                && !document.skeleton().equals(expectedSkeleton)) {
            throw new IllegalArgumentException("animation graph skeleton '"
                    + document.skeleton() + "' does not match model skeleton '"
                    + expectedSkeleton + "'");
        }
        AnimationGraph.Builder builder = AnimationGraph.builder(graphName, skeleton);
        for (AnimationGraphDocument.Parameter parameter : document.parameters()) {
            switch (parameter.type()) {
                case BOOLEAN -> builder.booleanParameter(parameter.name(), parameter.booleanDefault());
                case FLOAT -> builder.floatParameter(parameter.name(), parameter.floatDefault());
                case INTEGER -> builder.integerParameter(parameter.name(), parameter.integerDefault());
                case TRIGGER -> builder.triggerParameter(parameter.name());
            }
        }
        for (AnimationGraphDocument.State state : document.states()) {
            AnimationClip clip = requireClip(clips, state.clip());
            if (clip.skeleton() != skeleton) {
                throw new IllegalArgumentException("clip '" + state.clip()
                        + "' belongs to a different skeleton");
            }
            AnimationGraph.StateOptions options = new AnimationGraph.StateOptions(
                    state.playbackSpeed(), state.playbackSpeedParameter(), state.syncGroup(),
                    state.enterSignal(), state.exitSignal(), state.normalizedStartOffset());
            builder.state(state.id(), new ClipMotion(clip, state.loopMode(), 1.0f),
                    state.loopMode(), options);
        }
        builder.entry(document.entry());
        for (AnimationGraphDocument.Transition transition : document.transitions()) {
            AnimationGraph.TransitionBuilder transitionBuilder =
                    AnimationGraph.TransitionSpec.builder()
                            .duration(transition.durationSeconds())
                            .destinationOffset(transition.destinationOffset())
                            .interruption(transition.interruptionPolicy())
                            .queued(transition.queued());
            if (transition.exitTime() >= 0.0f) transitionBuilder.exitTime(transition.exitTime());
            for (AnimationGraphDocument.Condition condition : transition.conditions()) {
                transitionBuilder.when(new AnimationGraph.Condition(condition.parameter(),
                        condition.comparison(), condition.booleanValue(), condition.floatValue(),
                        condition.integerValue()));
            }
            AnimationGraph.TransitionSpec spec = transitionBuilder.build();
            if (transition.from().equals("__ANY__")) builder.anyTransition(transition.to(), spec);
            else builder.transition(transition.from(), transition.to(), spec);
        }
        return builder.build();
    }

    private static AnimationClip requireClip(Map<String, AnimationClip> clips, String name) {
        AnimationClip clip = clips.get(name);
        if (clip == null) throw new IllegalArgumentException("animation clip not found: " + name);
        return clip;
    }
}
