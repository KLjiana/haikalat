package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** Per-character mutable AnimationGraph runtime。 */
public final class AnimationController implements AutoCloseable {
    private static final float WEIGHT_EPSILON = 1.0e-6f;

    private final AnimationGraph graph;
    private final boolean[] booleanParameters;
    private final float[] floatParameters;
    private final int[] integerParameters;
    private final boolean[] triggers;
    private final MotionNode[] stateMotions;
    private final AnimationEvaluationContext context;
    private final PoseBuffer outputPose;
    private final PoseBuffer previousOutputPose;
    private final PoseBuffer transitionSourcePose;
    private final PoseBuffer transitionTargetPose;
    private final MorphWeightBuffer outputMorph;
    private final MorphWeightBuffer transitionSourceMorph;
    private final MorphWeightBuffer transitionTargetMorph;
    private final BoneMask fullMask;
    private final ArrayDeque<AnimationSignal> signals;
    private final int signalCapacity;
    private final StateAdvance primaryAdvance = new StateAdvance();
    private final StateAdvance secondaryAdvance = new StateAdvance();

    private int currentState;
    private float currentTime;
    private long currentLoop;
    private AnimationGraph.TransitionDefinition transition;
    private AnimationGraph.TransitionDefinition queuedTransition;
    private int transitionSourceState;
    private float transitionSourceTime;
    private long transitionSourceLoop;
    private int transitionTargetState;
    private float transitionTargetTime;
    private long transitionTargetLoop;
    private float transitionElapsed;
    private boolean transitionSourceFrozen;
    private float transitionWeight = 1.0f;
    private long signalSequence;
    private long evaluatedStates;
    private long evaluatedMotions;
    private long sampledClips;
    private long sampledChannels;
    private long transitionCount;
    private long interruptionCount;
    private long eventCount;
    private long markerCount;
    private long signalCount;
    private long droppedSignals;
    private long syncFallbacks;
    private long updateCpuNanos;
    private String lastTransitionReason = "entry";
    private boolean closed;

    AnimationController(AnimationGraph graph) {
        this(graph, 128);
    }

    public AnimationController(AnimationGraph graph, int signalCapacity) {
        this.graph = Objects.requireNonNull(graph, "graph");
        if (signalCapacity < 1) {
            throw new IllegalArgumentException("signalCapacity must be positive");
        }
        this.signalCapacity = signalCapacity;
        signals = new ArrayDeque<>(signalCapacity);
        booleanParameters = new boolean[graph.parameterCount()];
        floatParameters = new float[graph.parameterCount()];
        integerParameters = new int[graph.parameterCount()];
        triggers = new boolean[graph.parameterCount()];
        for (int index = 0; index < graph.parameterCount(); index++) {
            AnimationGraph.ParameterDefinition parameter = graph.parameter(index);
            booleanParameters[index] = parameter.booleanDefault();
            floatParameters[index] = parameter.floatDefault();
            integerParameters[index] = parameter.integerDefault();
        }
        stateMotions = new MotionNode[graph.stateCount()];
        for (int index = 0; index < graph.stateCount(); index++) {
            stateMotions[index] = compile(graph.state(index).motion());
        }
        context = new AnimationEvaluationContext(graph.skeleton(), graph.morphTargetCount());
        outputPose = graph.skeleton().createPoseBuffer();
        previousOutputPose = graph.skeleton().createPoseBuffer();
        transitionSourcePose = graph.skeleton().createPoseBuffer();
        transitionTargetPose = graph.skeleton().createPoseBuffer();
        outputMorph = graph.morphTargetCount() == 0 ? null
                : new MorphWeightBuffer(graph.morphTargetCount());
        transitionSourceMorph = graph.morphTargetCount() == 0 ? null
                : new MorphWeightBuffer(graph.morphTargetCount());
        transitionTargetMorph = graph.morphTargetCount() == 0 ? null
                : new MorphWeightBuffer(graph.morphTargetCount());
        fullMask = BoneMask.all(graph.skeleton());
        currentState = graph.entryStateIndex();
        AnimationGraph.StateDefinition entry = graph.state(currentState);
        currentTime = entry.options().normalizedStartOffset()
                * entry.motion().durationSeconds();
        evaluateCurrent(outputPose);
        emit(AnimationSignal.Type.STATE_ENTER, currentState, entry.options().enterSignal(),
                "", normalized(currentTime, entry.motion().durationSeconds()),
                currentLoop, AnimationMarker.Priority.NORMAL);
    }

    public AnimationGraph graph() {
        return graph;
    }

    public Skeleton skeleton() {
        return graph.skeleton();
    }

    public int currentStateIndex() {
        requireOpen();
        return currentState;
    }

    public String currentStateName() {
        requireOpen();
        return graph.state(currentState).name();
    }

    public OptionalInt targetStateIndex() {
        requireOpen();
        return transition == null ? OptionalInt.empty() : OptionalInt.of(transitionTargetState);
    }

    public String targetStateName() {
        requireOpen();
        return transition == null ? "" : graph.state(transitionTargetState).name();
    }

    public float transitionWeight() {
        requireOpen();
        return transitionWeight;
    }

    public float currentNormalizedTime() {
        requireOpen();
        AnimationGraph.StateDefinition state = graph.state(currentState);
        return normalized(currentTime, state.motion().durationSeconds());
    }

    /** Current state-local playback time in seconds. */
    public float currentTimeSeconds() {
        requireOpen();
        return currentTime;
    }

    public String lastTransitionReason() {
        requireOpen();
        return lastTransitionReason;
    }

    public int morphTargetCount() {
        return graph.morphTargetCount();
    }

    public MorphWeightBuffer copyMorphWeights(MorphWeightBuffer destination) {
        requireOpen();
        if (outputMorph == null) {
            throw new IllegalStateException("AnimationGraph has no morph output");
        }
        MorphWeightBuffer target = Objects.requireNonNull(destination, "destination");
        if (target.targetCount() != graph.morphTargetCount()) {
            throw new IllegalArgumentException("destination morph target count must be "
                    + graph.morphTargetCount());
        }
        return target.set(outputMorph);
    }

    public boolean isCurrentStateComplete() {
        requireOpen();
        if (transition != null) return false;
        AnimationGraph.StateDefinition state = graph.state(currentState);
        if (state.loopMode() == AnimationPlayer.LoopMode.LOOP) return false;
        float speed = stateSpeed(currentState);
        return speed >= 0.0f
                ? currentTime >= state.motion().durationSeconds()
                : currentTime <= 0.0f;
    }

    public AnimationController setBoolean(String name, boolean value) {
        int index = requireType(name, AnimationGraph.ParameterType.BOOLEAN);
        booleanParameters[index] = value;
        return this;
    }

    public AnimationController setFloat(String name, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("animation float parameter must be finite");
        }
        int index = requireType(name, AnimationGraph.ParameterType.FLOAT);
        floatParameters[index] = value;
        return this;
    }

    public AnimationController setInteger(String name, int value) {
        int index = requireType(name, AnimationGraph.ParameterType.INTEGER);
        integerParameters[index] = value;
        return this;
    }

    public AnimationController fireTrigger(String name) {
        int index = requireType(name, AnimationGraph.ParameterType.TRIGGER);
        triggers[index] = true;
        return this;
    }

    public AnimationController resetTrigger(String name) {
        int index = requireType(name, AnimationGraph.ParameterType.TRIGGER);
        triggers[index] = false;
        return this;
    }

    /** 推进 Graph 并输出姿态；返回不含显式 root joint 选择的 identity delta。 */
    public RootMotionDelta update(float deltaSeconds, PoseBuffer destination) {
        return updateInternal(deltaSeconds, destination, -1, false);
    }

    public RootMotionDelta updateWithRootMotion(float deltaSeconds, PoseBuffer destination,
                                                int rootJointIndex, boolean removeFromPose) {
        if (rootJointIndex < 0 || rootJointIndex >= graph.skeleton().jointCount()) {
            throw new IndexOutOfBoundsException("root joint index is outside skeleton");
        }
        if (graph.skeleton().joint(rootJointIndex).parentIndex() >= 0) {
            throw new IllegalArgumentException("root motion joint must be a skeleton root");
        }
        return updateInternal(deltaSeconds, destination, rootJointIndex, removeFromPose);
    }

    public List<AnimationSignal> pendingSignals() {
        requireOpen();
        return List.copyOf(signals);
    }

    public List<AnimationSignal> drainSignals() {
        requireOpen();
        List<AnimationSignal> result = List.copyOf(signals);
        signals.clear();
        return result;
    }

    public AnimationDiagnostics diagnostics() {
        requireOpen();
        return new AnimationDiagnostics(evaluatedStates, evaluatedMotions, sampledClips,
                sampledChannels, transitionCount, interruptionCount,
                0, 0, 0, eventCount, markerCount, signalCount, droppedSignals,
                syncFallbacks, 0, 0, 0.0f, graph.morphTargetCount(),
                activeMorphWeightCount(),
                outputPose.globalRecomputeCount(), context.estimatedBytes(), updateCpuNanos);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        signals.clear();
    }

    float floatParameter(int index) {
        return floatParameters[index];
    }

    private RootMotionDelta updateInternal(float deltaSeconds, PoseBuffer destination,
                                           int rootJointIndex, boolean removeFromPose) {
        requireOpen();
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
        PoseBuffer target = Objects.requireNonNull(destination, "destination");
        if (target.skeleton() != graph.skeleton()) {
            throw new IllegalArgumentException("destination belongs to a different skeleton");
        }
        long started = System.nanoTime();
        previousOutputPose.load(outputPose);

        if (transition == null) {
            AnimationGraph.TransitionDefinition selected = selectTransition(currentState,
                    normalized(currentTime, graph.state(currentState).motion().durationSeconds()));
            if (selected != null) startTransition(selected, false);
        } else {
            AnimationGraph.TransitionDefinition selected = selectInterruption();
            if (selected != null) {
                if (selected.queued()) {
                    if (queuedTransition == null) queuedTransition = selected;
                } else {
                    startTransition(selected, true);
                }
            }
        }

        RootMotionDelta rootMotion;
        if (transition == null) {
            StateAdvance advance = advanceState(currentState, currentTime,
                    deltaSeconds, primaryAdvance);
            float previousTime = currentTime;
            currentTime = advance.time();
            currentLoop += advance.loops();
            context.reset();
            stateMotions[currentState].evaluate(normalized(currentTime,
                    graph.state(currentState).motion().durationSeconds()), outputPose, context, 1.0f);
            if (outputMorph != null) {
                context.resetMorphs();
                stateMotions[currentState].evaluateMorph(normalized(currentTime,
                        graph.state(currentState).motion().durationSeconds()),
                        outputMorph, context);
            }
            evaluatedStates++;
            dispatchActiveClipSignals(currentState, previousTime, currentTime,
                    advance.loops(), currentLoop);
            rootMotion = rootJointIndex < 0 ? RootMotionDelta.identity()
                    : stateMotions[currentState].rootMotion(normalized(previousTime,
                    graph.state(currentState).motion().durationSeconds()),
                    normalized(currentTime, graph.state(currentState).motion().durationSeconds()),
                    advance.loops(), rootJointIndex, context);
        } else {
            rootMotion = updateTransition(deltaSeconds, rootJointIndex);
        }

        target.load(outputPose);
        if (removeFromPose && rootJointIndex >= 0) removeRoot(target, rootJointIndex);
        updateCpuNanos = System.nanoTime() - started;
        return rootMotion;
    }

    private RootMotionDelta updateTransition(float deltaSeconds, int rootJointIndex) {
        AnimationGraph.StateDefinition sourceState = graph.state(transitionSourceState);
        AnimationGraph.StateDefinition targetState = graph.state(transitionTargetState);
        float sourcePrevious = transitionSourceTime;
        StateAdvance sourceAdvance = transitionSourceFrozen
                ? primaryAdvance.set(transitionSourceTime, 0L)
                : advanceState(transitionSourceState, transitionSourceTime,
                deltaSeconds, primaryAdvance);
        if (!transitionSourceFrozen) {
            transitionSourceTime = sourceAdvance.time();
            transitionSourceLoop += sourceAdvance.loops();
        }
        float targetPrevious = transitionTargetTime;
        StateAdvance targetAdvance = advanceState(transitionTargetState,
                transitionTargetTime, deltaSeconds, secondaryAdvance);
        transitionTargetTime = targetAdvance.time();
        transitionTargetLoop += targetAdvance.loops();

        context.reset();
        if (transitionSourceFrozen) {
            // The blended pose captured at interruption remains immutable for this transition.
        } else {
            stateMotions[transitionSourceState].evaluate(normalized(transitionSourceTime,
                    sourceState.motion().durationSeconds()), transitionSourcePose, context, 1.0f);
        }
        context.reset();
        stateMotions[transitionTargetState].evaluate(normalized(transitionTargetTime,
                targetState.motion().durationSeconds()), transitionTargetPose, context, 1.0f);
        if (outputMorph != null) {
            context.resetMorphs();
            if (!transitionSourceFrozen) {
                stateMotions[transitionSourceState].evaluateMorph(
                        normalized(transitionSourceTime,
                                sourceState.motion().durationSeconds()),
                        transitionSourceMorph, context);
            }
            stateMotions[transitionTargetState].evaluateMorph(
                    normalized(transitionTargetTime, targetState.motion().durationSeconds()),
                    transitionTargetMorph, context);
        }
        dispatchActiveClipSignals(transitionTargetState, targetPrevious, transitionTargetTime,
                targetAdvance.loops(), transitionTargetLoop);

        transitionElapsed = Math.min(transition.durationSeconds(),
                transitionElapsed + deltaSeconds);
        float progress = transition.durationSeconds() == 0.0f ? 1.0f
                : transitionElapsed / transition.durationSeconds();
        float eased = transition.easing().sample(progress);
        if (!Float.isFinite(eased)) {
            throw new IllegalStateException("transition curve produced a non-finite value");
        }
        transitionWeight = clamp01(eased);
        PoseBlender.blend(transitionSourcePose, transitionTargetPose,
                transitionWeight, fullMask, outputPose);
        if (outputMorph != null) {
            outputMorph.blend(transitionSourceMorph, transitionTargetMorph,
                    transitionWeight);
        }
        evaluatedStates += 2;

        RootMotionDelta sourceDelta = rootJointIndex < 0 || transitionSourceFrozen
                ? RootMotionDelta.identity()
                : stateMotions[transitionSourceState].rootMotion(
                normalized(sourcePrevious, sourceState.motion().durationSeconds()),
                normalized(transitionSourceTime, sourceState.motion().durationSeconds()),
                sourceAdvance.loops(), rootJointIndex, context);
        RootMotionDelta targetDelta = rootJointIndex < 0 ? RootMotionDelta.identity()
                : stateMotions[transitionTargetState].rootMotion(
                normalized(targetPrevious, targetState.motion().durationSeconds()),
                normalized(transitionTargetTime, targetState.motion().durationSeconds()),
                targetAdvance.loops(), rootJointIndex, context);
        RootMotionDelta result = blendRootMotion(sourceDelta, targetDelta, transitionWeight);
        if (transitionElapsed >= transition.durationSeconds()) finishTransition();
        return result;
    }

    private void startTransition(AnimationGraph.TransitionDefinition selected,
                                 boolean interrupted) {
        int source = transition == null ? currentState
                : selected.sourceState() == transitionTargetState
                ? transitionTargetState : currentState;
        if (interrupted) {
            transitionSourcePose.load(outputPose);
            if (outputMorph != null) transitionSourceMorph.set(outputMorph);
            transitionSourceFrozen = true;
            interruptionCount++;
        } else {
            transitionSourceFrozen = false;
        }
        transition = selected;
        lastTransitionReason = transitionReason(selected, interrupted);
        transitionSourceState = source;
        transitionSourceTime = currentTime;
        transitionSourceLoop = currentLoop;
        transitionTargetState = selected.destinationState();
        AnimationGraph.StateDefinition destination = graph.state(transitionTargetState);
        transitionTargetTime = selected.destinationOffset()
                * destination.motion().durationSeconds();
        String sourceSyncGroup = graph.state(source).options().syncGroup();
        String destinationSyncGroup = destination.options().syncGroup();
        if (!sourceSyncGroup.isEmpty() && sourceSyncGroup.equals(destinationSyncGroup)) {
            ClipMotion sourceClip = primaryClip(graph.state(source).motion());
            ClipMotion destinationClip = primaryClip(destination.motion());
            float sourcePhase = normalized(transitionSourceTime,
                    graph.state(source).motion().durationSeconds());
            float mapped = MarkerSynchronizer.mapTime(sourceClip.clip(),
                    sourcePhase * sourceClip.clip().durationSeconds(),
                    destinationClip.clip());
            if (mapped >= 0.0f) {
                transitionTargetTime = normalized(mapped,
                        destinationClip.clip().durationSeconds())
                        * destination.motion().durationSeconds();
            } else {
                syncFallbacks++;
            }
        }
        transitionTargetLoop = 0L;
        transitionElapsed = 0.0f;
        transitionWeight = selected.durationSeconds() == 0.0f ? 1.0f : 0.0f;
        consumeTriggers(selected);
        transitionCount++;
        emit(AnimationSignal.Type.STATE_EXIT, source,
                graph.state(source).options().exitSignal(), "",
                normalized(transitionSourceTime,
                        graph.state(source).motion().durationSeconds()),
                transitionSourceLoop, AnimationMarker.Priority.NORMAL);
        emit(AnimationSignal.Type.TRANSITION_START, transitionTargetState, "", "",
                normalized(transitionTargetTime, destination.motion().durationSeconds()),
                transitionTargetLoop, AnimationMarker.Priority.NORMAL);
        if (selected.durationSeconds() == 0.0f) {
            transitionTargetPose.resetToBindPose();
            currentState = transitionTargetState;
            currentTime = transitionTargetTime;
            currentLoop = transitionTargetLoop;
            emit(AnimationSignal.Type.STATE_ENTER, currentState,
                    destination.options().enterSignal(), "",
                    normalized(currentTime, destination.motion().durationSeconds()),
                    currentLoop, AnimationMarker.Priority.NORMAL);
            emit(AnimationSignal.Type.TRANSITION_COMPLETE, currentState, "", "",
                    normalized(currentTime, destination.motion().durationSeconds()),
                    currentLoop, AnimationMarker.Priority.NORMAL);
            transition = null;
            transitionWeight = 1.0f;
        }
    }

    private void finishTransition() {
        currentState = transitionTargetState;
        currentTime = transitionTargetTime;
        currentLoop = transitionTargetLoop;
        AnimationGraph.StateDefinition state = graph.state(currentState);
        emit(AnimationSignal.Type.STATE_ENTER, currentState, state.options().enterSignal(), "",
                normalized(currentTime, state.motion().durationSeconds()),
                currentLoop, AnimationMarker.Priority.NORMAL);
        emit(AnimationSignal.Type.TRANSITION_COMPLETE, currentState, "", "",
                normalized(currentTime, state.motion().durationSeconds()),
                currentLoop, AnimationMarker.Priority.NORMAL);
        transition = null;
        transitionWeight = 1.0f;
        transitionSourceFrozen = false;
        if (queuedTransition != null) {
            AnimationGraph.TransitionDefinition queued = queuedTransition;
            queuedTransition = null;
            startTransition(queued, false);
        }
    }

    private String transitionReason(AnimationGraph.TransitionDefinition definition,
                                    boolean interrupted) {
        StringBuilder reason = new StringBuilder();
        if (interrupted) reason.append("interrupted ");
        else if (definition.queued()) reason.append("queued ");
        if (definition.conditions().isEmpty()) return reason.append("unconditional").toString();
        for (int index = 0; index < definition.conditions().size(); index++) {
            if (index > 0) reason.append(" & ");
            AnimationGraph.Condition condition = definition.conditions().get(index).condition();
            reason.append(condition.parameter()).append(':')
                    .append(condition.comparison().name().toLowerCase(java.util.Locale.ROOT));
        }
        return reason.toString();
    }

    private AnimationGraph.TransitionDefinition selectTransition(int sourceState,
                                                                 float normalizedTime) {
        List<AnimationGraph.TransitionDefinition> transitions = graph.transitions();
        for (int index = 0; index < transitions.size(); index++) {
            AnimationGraph.TransitionDefinition candidate = transitions.get(index);
            if (candidate.sourceState() != -1 && candidate.sourceState() != sourceState) continue;
            if (candidate.destinationState() == sourceState) continue;
            if (!exitTimeSatisfied(candidate, sourceState, normalizedTime)) continue;
            if (conditionsSatisfied(candidate)) return candidate;
        }
        return null;
    }

    private AnimationGraph.TransitionDefinition selectInterruption() {
        AnimationGraph.InterruptionPolicy policy = transition.interruptionPolicy();
        if (policy == AnimationGraph.InterruptionPolicy.NONE) return null;
        int first = policy == AnimationGraph.InterruptionPolicy.DESTINATION
                ? transitionTargetState : transitionSourceState;
        AnimationGraph.TransitionDefinition selected = selectTransition(first,
                normalized(policy == AnimationGraph.InterruptionPolicy.DESTINATION
                                ? transitionTargetTime : transitionSourceTime,
                        graph.state(first).motion().durationSeconds()));
        if (selected == null && policy == AnimationGraph.InterruptionPolicy.ANY) {
            selected = selectTransition(transitionTargetState,
                    normalized(transitionTargetTime,
                            graph.state(transitionTargetState).motion().durationSeconds()));
        }
        return selected;
    }

    private boolean exitTimeSatisfied(AnimationGraph.TransitionDefinition transition,
                                      int stateIndex, float normalizedTime) {
        if (transition.exitTime() < 0.0f) return true;
        float speed = stateSpeed(stateIndex);
        return speed >= 0.0f ? normalizedTime >= transition.exitTime()
                : normalizedTime <= transition.exitTime();
    }

    private boolean conditionsSatisfied(AnimationGraph.TransitionDefinition transition) {
        for (AnimationGraph.ConditionDefinition definition : transition.conditions()) {
            int index = definition.parameterIndex();
            AnimationGraph.Condition condition = definition.condition();
            boolean satisfied = switch (condition.comparison()) {
                case BOOLEAN_EQUALS -> booleanParameters[index] == condition.booleanValue();
                case FLOAT_LESS -> floatParameters[index] < condition.floatValue();
                case FLOAT_LESS_OR_EQUAL -> floatParameters[index] <= condition.floatValue();
                case FLOAT_GREATER -> floatParameters[index] > condition.floatValue();
                case FLOAT_GREATER_OR_EQUAL -> floatParameters[index] >= condition.floatValue();
                case INTEGER_EQUALS -> integerParameters[index] == condition.integerValue();
                case INTEGER_NOT_EQUALS -> integerParameters[index] != condition.integerValue();
                case INTEGER_LESS -> integerParameters[index] < condition.integerValue();
                case INTEGER_GREATER -> integerParameters[index] > condition.integerValue();
                case TRIGGERED -> triggers[index];
            };
            if (!satisfied) return false;
        }
        return true;
    }

    private void consumeTriggers(AnimationGraph.TransitionDefinition selected) {
        for (AnimationGraph.ConditionDefinition condition : selected.conditions()) {
            if (condition.condition().comparison() == AnimationGraph.Comparison.TRIGGERED) {
                triggers[condition.parameterIndex()] = false;
            }
        }
    }

    private StateAdvance advanceState(int stateIndex, float time, float deltaSeconds,
                                      StateAdvance result) {
        AnimationGraph.StateDefinition state = graph.state(stateIndex);
        float duration = state.motion().durationSeconds();
        if (duration == 0.0f) return result.set(0.0f, 0L);
        double advanced = time + (double) deltaSeconds * stateSpeed(stateIndex);
        if (advanced == time) return result.set(time, 0L);
        if (state.loopMode() == AnimationPlayer.LoopMode.LOOP) {
            double floor = Math.floor(advanced / duration);
            long loops = floor >= Long.MAX_VALUE ? Long.MAX_VALUE
                    : floor <= Long.MIN_VALUE ? Long.MIN_VALUE : (long) floor;
            return result.set((float) (advanced - floor * duration), loops);
        }
        return result.set((float) Math.max(0.0, Math.min(duration, advanced)), 0L);
    }

    private float stateSpeed(int stateIndex) {
        AnimationGraph.StateDefinition state = graph.state(stateIndex);
        String parameter = state.options().playbackSpeedParameter();
        return state.options().playbackSpeed()
                * (parameter.isEmpty() ? 1.0f
                : floatParameters[graph.parameterIndex(parameter)]);
    }

    private void evaluateCurrent(PoseBuffer destination) {
        context.reset();
        AnimationGraph.StateDefinition state = graph.state(currentState);
        stateMotions[currentState].evaluate(normalized(currentTime,
                state.motion().durationSeconds()), destination, context, 1.0f);
        if (outputMorph != null) {
            context.resetMorphs();
            stateMotions[currentState].evaluateMorph(normalized(currentTime,
                    state.motion().durationSeconds()), outputMorph, context);
        }
        evaluatedStates++;
    }

    private void dispatchActiveClipSignals(int stateIndex, float previousTime,
                                           float currentTime, long crossedLoops,
                                           long loopIndex) {
        AnimationGraph.StateDefinition state = graph.state(stateIndex);
        float duration = state.motion().durationSeconds();
        float previousPhase = normalized(previousTime, duration);
        float currentPhase = normalized(currentTime, duration);
        for (int index = 0; index < context.activeClipCount(); index++) {
            if (context.activeWeight(index) <= WEIGHT_EPSILON) continue;
            ClipMotion motion = context.activeClip(index);
            dispatchClip(motion, previousPhase, currentPhase, crossedLoops,
                    stateIndex, loopIndex);
        }
    }

    private void dispatchClip(ClipMotion motion, float previousPhase, float currentPhase,
                              long crossedLoops, int stateIndex, long loopIndex) {
        AnimationClip clip = motion.clip();
        float previous = previousPhase * clip.durationSeconds();
        float current = currentPhase * clip.durationSeconds();
        if (crossedLoops == 0L) {
            if (current > previous) dispatchForward(clip, previous, current,
                    false, stateIndex, loopIndex);
            else if (current < previous) dispatchReverse(clip, previous, current,
                    false, stateIndex, loopIndex);
        } else if (crossedLoops > 0L) {
            dispatchForward(clip, previous, clip.durationSeconds(),
                    false, stateIndex, loopIndex - crossedLoops);
            for (long loop = 1; loop < crossedLoops; loop++) {
                dispatchForward(clip, 0.0f, clip.durationSeconds(),
                        true, stateIndex, loopIndex - crossedLoops + loop);
            }
            dispatchForward(clip, 0.0f, current, true, stateIndex, loopIndex);
        } else {
            long cycles = crossedLoops == Long.MIN_VALUE
                    ? Long.MAX_VALUE : Math.abs(crossedLoops);
            dispatchReverse(clip, previous, 0.0f,
                    false, stateIndex, loopIndex - crossedLoops);
            for (long loop = 1; loop < cycles; loop++) {
                dispatchReverse(clip, clip.durationSeconds(), 0.0f,
                        true, stateIndex, loopIndex + cycles - loop);
            }
            dispatchReverse(clip, clip.durationSeconds(), current,
                    true, stateIndex, loopIndex);
        }
    }

    private void dispatchForward(AnimationClip clip, float start, float end,
                                 boolean includeStart, int state, long loop) {
        List<AnimationEvent> events = clip.events();
        for (int index = 0; index < events.size(); index++) {
            AnimationEvent event = events.get(index);
            if ((includeStart ? event.timeSeconds() >= start : event.timeSeconds() > start)
                    && event.timeSeconds() <= end) {
                eventCount++;
                emit(AnimationSignal.Type.EVENT, state, event.name(), event.payload(),
                        normalized(event.timeSeconds(), clip.durationSeconds()), loop,
                        isHigh(event.name()) ? AnimationMarker.Priority.HIGH
                                : AnimationMarker.Priority.NORMAL);
            }
        }
        List<AnimationMarker> markers = clip.markers();
        for (int index = 0; index < markers.size(); index++) {
            AnimationMarker marker = markers.get(index);
            if ((includeStart ? marker.timeSeconds() >= start : marker.timeSeconds() > start)
                    && marker.timeSeconds() <= end) {
                markerCount++;
                emit(AnimationSignal.Type.MARKER, state, marker.name(), "",
                        normalized(marker.timeSeconds(), clip.durationSeconds()),
                        loop, marker.priority());
            }
        }
    }

    private void dispatchReverse(AnimationClip clip, float start, float end,
                                 boolean includeStart, int state, long loop) {
        if (clip.events().isEmpty() && clip.markers().isEmpty()) return;
        ArrayList<TimedSignal> ordered = new ArrayList<>(
                clip.events().size() + clip.markers().size());
        int declaration = 0;
        for (AnimationEvent event : clip.events()) {
            ordered.add(TimedSignal.event(event, declaration++));
        }
        for (AnimationMarker marker : clip.markers()) {
            ordered.add(TimedSignal.marker(marker, declaration++));
        }
        ordered.sort((first, second) -> {
            int time = Float.compare(second.time(), first.time());
            return time != 0 ? time : Integer.compare(first.declaration(), second.declaration());
        });
        for (TimedSignal signal : ordered) {
            if ((includeStart ? signal.time() <= start : signal.time() < start)
                    && signal.time() >= end) {
                if (signal.event() != null) {
                    eventCount++;
                    emit(AnimationSignal.Type.EVENT, state, signal.event().name(),
                            signal.event().payload(),
                            normalized(signal.time(), clip.durationSeconds()), loop,
                            isHigh(signal.event().name()) ? AnimationMarker.Priority.HIGH
                                    : AnimationMarker.Priority.NORMAL);
                } else {
                    markerCount++;
                    emit(AnimationSignal.Type.MARKER, state, signal.marker().name(), "",
                            normalized(signal.time(), clip.durationSeconds()), loop,
                            signal.marker().priority());
                }
            }
        }
    }

    private void emit(AnimationSignal.Type type, int stateIndex, String name,
                      String payload, float normalizedTime, long loop,
                      AnimationMarker.Priority priority) {
        String stateName = graph.state(stateIndex).name();
        String motionName = motionName(graph.state(stateIndex).motion());
        AnimationSignal signal = new AnimationSignal(signalSequence++, type,
                new AnimationSignal.Source(graph.name(), -1), stateName, motionName,
                normalizedTime, name, payload, loop, priority);
        if (signals.size() >= signalCapacity) {
            if (priority == AnimationMarker.Priority.HIGH) {
                AnimationSignal removable = signals.stream()
                        .filter(value -> value.priority() == AnimationMarker.Priority.NORMAL)
                        .findFirst().orElse(null);
                if (removable != null) signals.remove(removable);
                else {
                    droppedSignals++;
                    return;
                }
            } else {
                droppedSignals++;
                return;
            }
        }
        signals.addLast(signal);
        signalCount++;
    }

    private MotionNode compile(AnimationMotion motion) {
        if (motion instanceof ClipMotion clip) return new ClipNode(clip);
        if (motion instanceof BlendTree1D tree) {
            int parameter = requireType(tree.parameter(), AnimationGraph.ParameterType.FLOAT);
            MotionNode[] children = new MotionNode[tree.children().size()];
            float[] thresholds = new float[children.length];
            for (int index = 0; index < children.length; index++) {
                BlendTree1D.Child child = tree.children().get(index);
                thresholds[index] = child.threshold();
                children[index] = compile(child.motion());
            }
            return new Blend1Node(parameter, thresholds, children, tree.durationSeconds());
        }
        BlendTree2D tree = (BlendTree2D) motion;
        int xParameter = requireType(tree.xParameter(), AnimationGraph.ParameterType.FLOAT);
        int yParameter = requireType(tree.yParameter(), AnimationGraph.ParameterType.FLOAT);
        MotionNode[] children = new MotionNode[tree.children().size()];
        for (int index = 0; index < children.length; index++) {
            children[index] = compile(tree.children().get(index).motion());
        }
        return new Blend2Node(xParameter, yParameter, tree, children);
    }

    private ClipMotion primaryClip(AnimationMotion motion) {
        if (motion instanceof ClipMotion clip) return clip;
        if (motion instanceof BlendTree1D tree) {
            float value = floatParameters[graph.parameterIndex(tree.parameter())];
            int selected = 0;
            float distance = Math.abs(value - tree.children().getFirst().threshold());
            for (int index = 1; index < tree.children().size(); index++) {
                float candidate = Math.abs(value - tree.children().get(index).threshold());
                if (candidate < distance) {
                    distance = candidate;
                    selected = index;
                }
            }
            return primaryClip(tree.children().get(selected).motion());
        }
        BlendTree2D tree = (BlendTree2D) motion;
        float x = floatParameters[graph.parameterIndex(tree.xParameter())];
        float y = floatParameters[graph.parameterIndex(tree.yParameter())];
        int selected = 0;
        float distance = Float.POSITIVE_INFINITY;
        for (int index = 0; index < tree.children().size(); index++) {
            BlendTree2D.Child child = tree.children().get(index);
            float candidate = squared(x - child.x()) + squared(y - child.y());
            if (candidate < distance) {
                distance = candidate;
                selected = index;
            }
        }
        return primaryClip(tree.children().get(selected).motion());
    }

    private int requireType(String name, AnimationGraph.ParameterType expected) {
        requireOpen();
        int index = graph.parameterIndex(name);
        AnimationGraph.ParameterType actual = graph.parameter(index).type();
        if (actual != expected) {
            throw new IllegalArgumentException("animation parameter '" + name
                    + "' is " + actual + ", expected " + expected);
        }
        return index;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("AnimationController is closed");
    }

    private int activeMorphWeightCount() {
        if (outputMorph == null) return 0;
        int count = 0;
        for (int index = 0; index < outputMorph.targetCount(); index++) {
            if (outputMorph.weight(index) != 0.0f) count++;
        }
        return count;
    }

    private void removeRoot(PoseBuffer pose, int rootJointIndex) {
        JointTransform sampled = pose.localTransform(rootJointIndex);
        JointTransform bind = graph.skeleton().joint(rootJointIndex).bindTransform();
        pose.setLocalTransform(rootJointIndex, new JointTransform(
                bind.translation(), bind.rotation(), sampled.scale()));
    }

    private interface MotionNode {
        float duration();

        void evaluate(float phase, PoseBuffer destination,
                      AnimationEvaluationContext context, float weight);

        void evaluateMorph(float phase, MorphWeightBuffer destination,
                           AnimationEvaluationContext context);

        RootMotionDelta rootMotion(float previousPhase, float currentPhase, long loops,
                                   int rootJointIndex, AnimationEvaluationContext context);
    }

    private final class ClipNode implements MotionNode {
        private final ClipMotion motion;
        private final AnimationClip.ChannelCursor cursor;

        private ClipNode(ClipMotion motion) {
            this.motion = motion;
            cursor = motion.clip().createCursor();
        }

        @Override
        public float duration() {
            return motion.durationSeconds();
        }

        @Override
        public void evaluate(float phase, PoseBuffer destination,
                             AnimationEvaluationContext context, float weight) {
            motion.clip().sample(clamp01(phase) * motion.clip().durationSeconds(),
                    destination, cursor);
            context.active(motion, weight);
            evaluatedMotions++;
            sampledClips++;
            sampledChannels += motion.clip().channelCount();
        }

        @Override
        public void evaluateMorph(float phase, MorphWeightBuffer destination,
                                  AnimationEvaluationContext context) {
            if (motion.morphTrack().isEmpty()) {
                destination.clear();
                return;
            }
            MorphWeightTrack track = motion.morphTrack().orElseThrow();
            track.sample(clamp01(phase) * track.durationSeconds(), destination);
        }

        @Override
        public RootMotionDelta rootMotion(float previousPhase, float currentPhase, long loops,
                                          int rootJointIndex,
                                          AnimationEvaluationContext context) {
            context.resetPoses();
            float duration = motion.clip().durationSeconds();
            if (loops == 0L) {
                return rootSegment(previousPhase * duration, currentPhase * duration,
                        rootJointIndex, context);
            }
            if (loops > 0L) {
                RootMotionDelta result = rootSegment(previousPhase * duration, duration,
                        rootJointIndex, context);
                if (loops > 1L) {
                    result = result.then(rootSegment(0.0f, duration, rootJointIndex, context)
                            .repeated(loops - 1L));
                }
                return result.then(rootSegment(0.0f, currentPhase * duration,
                        rootJointIndex, context));
            }
            long cycles = loops == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(loops);
            RootMotionDelta result = rootSegment(previousPhase * duration, 0.0f,
                    rootJointIndex, context);
            if (cycles > 1L) {
                result = result.then(rootSegment(duration, 0.0f, rootJointIndex, context)
                        .repeated(cycles - 1L));
            }
            return result.then(rootSegment(duration, currentPhase * duration,
                    rootJointIndex, context));
        }

        private RootMotionDelta rootSegment(float firstTime, float secondTime, int rootJointIndex,
                                            AnimationEvaluationContext context) {
            if (firstTime == secondTime) return RootMotionDelta.identity();
            PoseBuffer first = context.pose();
            PoseBuffer second = context.pose();
            motion.clip().sample(firstTime, first);
            motion.clip().sample(secondTime, second);
            return RootMotionDelta.between(first.localTransform(rootJointIndex),
                    second.localTransform(rootJointIndex));
        }
    }

    private final class Blend1Node implements MotionNode {
        private final int parameter;
        private final float[] thresholds;
        private final MotionNode[] children;
        private final float duration;
        private int firstChild;
        private int secondChild;
        private float childAlpha;

        private Blend1Node(int parameter, float[] thresholds,
                           MotionNode[] children, float duration) {
            this.parameter = parameter;
            this.thresholds = thresholds;
            this.children = children;
            this.duration = duration;
        }

        @Override
        public float duration() {
            return duration;
        }

        @Override
        public void evaluate(float phase, PoseBuffer destination,
                             AnimationEvaluationContext context, float weight) {
            resolveWeights();
            int first = firstChild;
            int second = secondChild;
            float alpha = childAlpha;
            if (first == second) {
                children[first].evaluate(phase, destination, context, weight);
            } else {
                PoseBuffer firstPose = context.pose();
                PoseBuffer secondPose = context.pose();
                children[this.firstChild].evaluate(phase, firstPose, context,
                        weight * (1.0f - alpha));
                children[this.secondChild].evaluate(phase, secondPose, context,
                        weight * alpha);
                PoseBlender.blend(firstPose, secondPose, alpha, fullMask, destination);
            }
            evaluatedMotions++;
        }

        @Override
        public void evaluateMorph(float phase, MorphWeightBuffer destination,
                                  AnimationEvaluationContext context) {
            resolveWeights();
            int firstIndex = firstChild;
            int secondIndex = secondChild;
            float alpha = childAlpha;
            if (firstIndex == secondIndex) {
                children[firstIndex].evaluateMorph(phase, destination, context);
                return;
            }
            MorphWeightBuffer first = context.morph();
            MorphWeightBuffer second = context.morph();
            children[firstIndex].evaluateMorph(phase, first, context);
            children[secondIndex].evaluateMorph(phase, second, context);
            destination.blend(first, second, alpha);
        }

        @Override
        public RootMotionDelta rootMotion(float previousPhase, float currentPhase, long loops,
                                          int rootJointIndex,
                                          AnimationEvaluationContext context) {
            resolveWeights();
            int firstIndex = firstChild;
            int secondIndex = secondChild;
            float alpha = childAlpha;
            RootMotionDelta first = children[firstIndex].rootMotion(
                    previousPhase, currentPhase, loops, rootJointIndex, context);
            if (firstIndex == secondIndex) return first;
            RootMotionDelta second = children[secondIndex].rootMotion(
                    previousPhase, currentPhase, loops, rootJointIndex, context);
            return blendRootMotion(first, second, alpha);
        }

        private void resolveWeights() {
            float value = floatParameters[parameter];
            if (value <= thresholds[0]) {
                setWeights(0, 0, 0.0f);
                return;
            }
            int last = thresholds.length - 1;
            if (value >= thresholds[last]) {
                setWeights(last, last, 0.0f);
                return;
            }
            for (int upper = 1; upper < thresholds.length; upper++) {
                if (value <= thresholds[upper]) {
                    int lower = upper - 1;
                    setWeights(lower, upper,
                            (value - thresholds[lower])
                                    / (thresholds[upper] - thresholds[lower]));
                    return;
                }
            }
            setWeights(last, last, 0.0f);
        }

        private void setWeights(int first, int second, float alpha) {
            firstChild = first;
            secondChild = second;
            childAlpha = alpha;
        }
    }

    private final class Blend2Node implements MotionNode {
        private final int xParameter;
        private final int yParameter;
        private final BlendTree2D tree;
        private final MotionNode[] children;
        private final int[] blendIndices = new int[3];
        private final float[] blendWeights = new float[3];

        private Blend2Node(int xParameter, int yParameter,
                           BlendTree2D tree, MotionNode[] children) {
            this.xParameter = xParameter;
            this.yParameter = yParameter;
            this.tree = tree;
            this.children = children;
        }

        @Override
        public float duration() {
            return tree.durationSeconds();
        }

        @Override
        public void evaluate(float phase, PoseBuffer destination,
                             AnimationEvaluationContext context, float weight) {
            resolveWeights();
            PoseBuffer accumulated = context.pose();
            children[blendIndices[0]].evaluate(phase, accumulated, context,
                    weight * blendWeights[0]);
            float accumulatedWeight = blendWeights[0];
            for (int slot = 1; slot < 3; slot++) {
                if (blendWeights[slot] <= WEIGHT_EPSILON) continue;
                PoseBuffer next = context.pose();
                children[blendIndices[slot]].evaluate(phase, next, context,
                        weight * blendWeights[slot]);
                float combined = accumulatedWeight + blendWeights[slot];
                PoseBlender.blend(accumulated, next,
                        blendWeights[slot] / combined, fullMask, accumulated);
                accumulatedWeight = combined;
            }
            destination.load(accumulated);
            evaluatedMotions++;
        }

        @Override
        public void evaluateMorph(float phase, MorphWeightBuffer destination,
                                  AnimationEvaluationContext context) {
            resolveWeights();
            MorphWeightBuffer accumulated = context.morph();
            children[blendIndices[0]].evaluateMorph(phase, accumulated, context);
            float accumulatedWeight = blendWeights[0];
            for (int slot = 1; slot < 3; slot++) {
                float nextWeight = blendWeights[slot];
                if (nextWeight <= WEIGHT_EPSILON) continue;
                MorphWeightBuffer next = context.morph();
                children[blendIndices[slot]].evaluateMorph(phase, next, context);
                float combined = accumulatedWeight + nextWeight;
                accumulated.blend(accumulated, next, nextWeight / combined);
                accumulatedWeight = combined;
            }
            destination.set(accumulated);
        }

        @Override
        public RootMotionDelta rootMotion(float previousPhase, float currentPhase, long loops,
                                          int rootJointIndex,
                                          AnimationEvaluationContext context) {
            resolveWeights();
            RootMotionDelta result = children[blendIndices[0]].rootMotion(
                    previousPhase, currentPhase, loops, rootJointIndex, context);
            float accumulated = blendWeights[0];
            for (int slot = 1; slot < 3; slot++) {
                float weight = blendWeights[slot];
                if (weight <= WEIGHT_EPSILON) continue;
                RootMotionDelta next = children[blendIndices[slot]].rootMotion(
                        previousPhase, currentPhase, loops, rootJointIndex, context);
                float combined = accumulated + weight;
                result = blendRootMotion(result, next, weight / combined);
                accumulated = combined;
            }
            return result;
        }

        private void resolveWeights() {
            float x = floatParameters[xParameter];
            float y = floatParameters[yParameter];
            for (BlendTree2D.Triangle triangle : tree.triangles()) {
                barycentric(x, y, triangle);
                if (blendWeights[0] >= -WEIGHT_EPSILON
                        && blendWeights[1] >= -WEIGHT_EPSILON
                        && blendWeights[2] >= -WEIGHT_EPSILON) {
                    normalize(blendWeights);
                    blendIndices[0] = triangle.a();
                    blendIndices[1] = triangle.b();
                    blendIndices[2] = triangle.c();
                    return;
                }
            }
            float bestDistance = Float.POSITIVE_INFINITY;
            BlendTree2D.Edge best = null;
            float bestAlpha = 0.0f;
            for (BlendTree2D.Edge edge : tree.hullEdges()) {
                BlendTree2D.Child first = tree.children().get(edge.first());
                BlendTree2D.Child second = tree.children().get(edge.second());
                float dx = second.x() - first.x();
                float dy = second.y() - first.y();
                float lengthSquared = dx * dx + dy * dy;
                float alpha = clamp01(((x - first.x()) * dx + (y - first.y()) * dy)
                        / lengthSquared);
                float projectedX = first.x() + dx * alpha;
                float projectedY = first.y() + dy * alpha;
                float distance = squared(x - projectedX) + squared(y - projectedY);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = edge;
                    bestAlpha = alpha;
                }
            }
            blendIndices[0] = best.first();
            blendIndices[1] = best.second();
            blendIndices[2] = best.first();
            blendWeights[0] = 1.0f - bestAlpha;
            blendWeights[1] = bestAlpha;
            blendWeights[2] = 0.0f;
        }

        private void barycentric(float x, float y, BlendTree2D.Triangle triangle) {
            BlendTree2D.Child a = tree.children().get(triangle.a());
            BlendTree2D.Child b = tree.children().get(triangle.b());
            BlendTree2D.Child c = tree.children().get(triangle.c());
            float denominator = (b.y() - c.y()) * (a.x() - c.x())
                    + (c.x() - b.x()) * (a.y() - c.y());
            float first = ((b.y() - c.y()) * (x - c.x())
                    + (c.x() - b.x()) * (y - c.y())) / denominator;
            float second = ((c.y() - a.y()) * (x - c.x())
                    + (a.x() - c.x()) * (y - c.y())) / denominator;
            blendWeights[0] = first;
            blendWeights[1] = second;
            blendWeights[2] = 1.0f - first - second;
        }
    }

    private static final class StateAdvance {
        private float time;
        private long loops;

        private StateAdvance set(float time, long loops) {
            this.time = time;
            this.loops = loops;
            return this;
        }

        private float time() {
            return time;
        }

        private long loops() {
            return loops;
        }
    }

    private record TimedSignal(float time, int declaration,
                               AnimationEvent event, AnimationMarker marker) {
        static TimedSignal event(AnimationEvent event, int declaration) {
            return new TimedSignal(event.timeSeconds(), declaration, event, null);
        }

        static TimedSignal marker(AnimationMarker marker, int declaration) {
            return new TimedSignal(marker.timeSeconds(), declaration, null, marker);
        }
    }

    private static RootMotionDelta blendRootMotion(RootMotionDelta first,
                                                   RootMotionDelta second, float weight) {
        return new RootMotionDelta(new Vector3f(first.translation())
                .lerp(second.translation(), weight),
                new Quaternionf(first.rotation()).slerp(second.rotation(), weight).normalize());
    }

    private static String motionName(AnimationMotion motion) {
        return motion instanceof ClipMotion clip ? clip.clip().name()
                : motion.getClass().getSimpleName();
    }

    private static boolean isHigh(String name) {
        return "attack-hit".equals(name) || "projectile-release".equals(name);
    }

    private static float normalized(float time, float duration) {
        return duration <= 0.0f ? 0.0f : clamp01(time / duration);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static float squared(float value) {
        return value * value;
    }

    private static void normalize(float[] weights) {
        float sum = Math.max(WEIGHT_EPSILON, weights[0] + weights[1] + weights[2]);
        weights[0] = Math.max(0.0f, weights[0] / sum);
        weights[1] = Math.max(0.0f, weights[1] / sum);
        weights[2] = Math.max(0.0f, weights[2] / sum);
        float normalizedSum = weights[0] + weights[1] + weights[2];
        weights[0] /= normalizedSum;
        weights[1] /= normalizedSum;
        weights[2] /= normalizedSum;
    }
}
