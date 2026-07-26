package com.kaleblangley.haikalat.subsystems.animation;

import com.kaleblangley.haikalat.core.curve.Curve1f;
import com.kaleblangley.haikalat.core.curve.Curves;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/** 以稳定 slot 顺序合成最多八层 Override/Additive 动画。 */
public final class AnimationLayerStack implements AutoCloseable {
    private final AnimationController baseController;
    private final Skeleton skeleton;
    private final Layer[] layers;
    private final int[] generations;
    private final PoseBuffer basePose;
    private final PoseBuffer layerPose;
    private final PoseBuffer bindReference;
    private final MorphWeightBuffer baseMorph;
    private final MorphWeightBuffer layerMorph;
    private final MorphWeightBuffer outputMorph;
    private final MorphWeightBuffer zeroMorphReference;
    private final ArrayDeque<AnimationSignal> signals = new ArrayDeque<>();
    private long signalSequence;
    private boolean closed;

    public AnimationLayerStack(AnimationController baseController) {
        this(baseController, 4);
    }

    public AnimationLayerStack(AnimationController baseController, int maximumLayers) {
        this.baseController = Objects.requireNonNull(baseController, "baseController");
        if (maximumLayers < 1 || maximumLayers > 8) {
            throw new IllegalArgumentException("maximumLayers must be in [1, 8]");
        }
        skeleton = baseController.skeleton();
        layers = new Layer[maximumLayers];
        generations = new int[maximumLayers];
        basePose = skeleton.createPoseBuffer();
        layerPose = skeleton.createPoseBuffer();
        bindReference = skeleton.createPoseBuffer();
        int morphTargets = baseController.morphTargetCount();
        baseMorph = morphTargets == 0 ? null : new MorphWeightBuffer(morphTargets);
        layerMorph = morphTargets == 0 ? null : new MorphWeightBuffer(morphTargets);
        outputMorph = morphTargets == 0 ? null : new MorphWeightBuffer(morphTargets);
        zeroMorphReference = morphTargets == 0 ? null
                : new MorphWeightBuffer(morphTargets);
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    public int maximumLayers() {
        return layers.length;
    }

    public int activeLayerCount() {
        requireOpen();
        int count = 0;
        for (Layer layer : layers) if (layer != null) count++;
        return count;
    }

    public int morphTargetCount() {
        return baseController.morphTargetCount();
    }

    public LayerHandle play(String name, AnimationMotion motion,
                            AnimationPlayer.LoopMode loopMode,
                            AnimationLayerMode mode, BoneMask mask,
                            float weight, boolean removeOnComplete) {
        AnimationMotion definition = Objects.requireNonNull(motion, "motion");
        if (definition.skeleton() != skeleton) {
            throw new IllegalArgumentException("layer motion belongs to a different skeleton");
        }
        String layerName = BlendTree1D.requireName(name, "layer name");
        AnimationGraph graph = AnimationGraph.builder("layer:" + layerName, skeleton)
                .state("motion", definition, Objects.requireNonNull(loopMode, "loopMode"))
                .entry("motion").build();
        return playController(layerName, graph.createController(), mode, mask,
                weight, removeOnComplete, true, null);
    }

    public LayerHandle playAdditive(String name, AnimationMotion motion,
                                    AnimationPlayer.LoopMode loopMode, BoneMask mask,
                                    float weight, Pose referencePose,
                                    boolean removeOnComplete) {
        Pose reference = Objects.requireNonNull(referencePose, "referencePose");
        if (reference.skeleton() != skeleton) {
            throw new IllegalArgumentException("reference pose belongs to a different skeleton");
        }
        return playController(name, AnimationGraph.builder("layer:" + name, skeleton)
                        .state("motion", motion, loopMode).entry("motion").build()
                        .createController(),
                AnimationLayerMode.ADDITIVE, mask, weight, removeOnComplete,
                true, reference);
    }

    public LayerHandle playController(String name, AnimationController controller,
                                      AnimationLayerMode mode, BoneMask mask,
                                      float weight, boolean removeOnComplete) {
        return playController(name, controller, mode, mask, weight,
                removeOnComplete, false, null);
    }

    public RootMotionDelta update(float deltaSeconds, PoseBuffer destination) {
        return updateInternal(deltaSeconds, destination, null, -1, false);
    }

    public RootMotionDelta update(float deltaSeconds, PoseBuffer destination,
                                  MorphWeightBuffer morphDestination) {
        return updateInternal(deltaSeconds, destination,
                Objects.requireNonNull(morphDestination, "morphDestination"),
                -1, false);
    }

    public RootMotionDelta updateWithRootMotion(float deltaSeconds, PoseBuffer destination,
                                                int rootJointIndex, boolean removeFromPose) {
        return updateInternal(deltaSeconds, destination, null,
                rootJointIndex, removeFromPose);
    }

    public RootMotionDelta updateWithRootMotion(float deltaSeconds, PoseBuffer destination,
                                                MorphWeightBuffer morphDestination,
                                                int rootJointIndex, boolean removeFromPose) {
        return updateInternal(deltaSeconds, destination,
                Objects.requireNonNull(morphDestination, "morphDestination"),
                rootJointIndex, removeFromPose);
    }

    public List<AnimationSignal> drainSignals() {
        requireOpen();
        List<AnimationSignal> result = List.copyOf(signals);
        signals.clear();
        return result;
    }

    public AnimationDiagnostics diagnostics() {
        requireOpen();
        AnimationDiagnostics base = baseController.diagnostics();
        long override = 0L;
        long additive = 0L;
        for (Layer layer : layers) {
            if (layer == null) continue;
            if (layer.mode == AnimationLayerMode.OVERRIDE) override++;
            else additive++;
        }
        return new AnimationDiagnostics(base.evaluatedStates(), base.evaluatedMotions(),
                base.sampledClips(), base.sampledChannels(), base.transitions(),
                base.interruptions(), override + additive, override, additive,
                base.events(), base.markers(), base.signals(), base.droppedSignals(),
                base.syncFallbacks(), base.constraints(), base.constraintIterations(),
                base.constraintResidual(), base.morphTargets(), base.activeMorphWeights(),
                base.globalMatrixRecomputes(), base.scratchEstimatedBytes(),
                base.updateCpuNanos());
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (int index = 0; index < layers.length; index++) remove(index);
        baseController.close();
        signals.clear();
    }

    private LayerHandle playController(String name, AnimationController controller,
                                       AnimationLayerMode mode, BoneMask mask,
                                       float weight, boolean removeOnComplete,
                                       boolean ownsController, Pose referencePose) {
        requireOpen();
        String layerName = BlendTree1D.requireName(name, "layer name");
        AnimationController runtime = Objects.requireNonNull(controller, "controller");
        if (runtime.skeleton() != skeleton) {
            throw new IllegalArgumentException("layer controller belongs to a different skeleton");
        }
        if (runtime.morphTargetCount() != 0
                && runtime.morphTargetCount() != morphTargetCount()) {
            throw new IllegalArgumentException(
                    "layer morph target count must match the base graph");
        }
        AnimationLayerMode layerMode = Objects.requireNonNull(mode, "mode");
        BoneMask layerMask = Objects.requireNonNull(mask, "mask");
        if (layerMask.skeleton() != skeleton) {
            throw new IllegalArgumentException("layer mask belongs to a different skeleton");
        }
        BoneMask.requireWeight(weight);
        int slot = freeSlot();
        PoseBuffer reference = skeleton.createPoseBuffer();
        if (referencePose != null) reference.load(referencePose);
        else reference.load(bindReference);
        Layer layer = new Layer(layerName, runtime, ownsController, layerMode,
                layerMask, reference, weight, removeOnComplete);
        layers[slot] = layer;
        int generation = ++generations[slot];
        return new LayerHandle(this, slot, generation);
    }

    private RootMotionDelta updateInternal(float deltaSeconds, PoseBuffer destination,
                                           MorphWeightBuffer morphDestination,
                                           int rootJointIndex, boolean removeFromPose) {
        requireOpen();
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
        PoseBuffer target = Objects.requireNonNull(destination, "destination");
        if (target.skeleton() != skeleton) {
            throw new IllegalArgumentException("destination belongs to a different skeleton");
        }
        RootMotionDelta root = rootJointIndex < 0
                ? baseController.update(deltaSeconds, basePose)
                : baseController.updateWithRootMotion(deltaSeconds, basePose,
                rootJointIndex, false);
        forwardSignals(baseController.drainSignals(), -1);
        target.load(basePose);
        if (outputMorph != null) {
            baseController.copyMorphWeights(baseMorph);
            outputMorph.set(baseMorph);
        } else if (morphDestination != null) {
            throw new IllegalStateException("base AnimationGraph has no morph output");
        }

        for (int slot = 0; slot < layers.length; slot++) {
            Layer layer = layers[slot];
            if (layer == null) continue;
            layer.advanceFade(deltaSeconds);
            RootMotionDelta layerRoot = RootMotionDelta.identity();
            if (!layer.paused) {
                if (rootJointIndex >= 0 && layer.rootMotionMode != LayerRootMotionMode.IGNORE) {
                    layerRoot = layer.controller.updateWithRootMotion(deltaSeconds,
                            layerPose, rootJointIndex, false);
                } else {
                    layer.controller.update(deltaSeconds, layerPose);
                }
            } else {
                layer.controller.update(0.0f, layerPose);
            }
            forwardSignals(layer.controller.drainSignals(), slot);
            if (outputMorph != null) {
                if (layer.controller.morphTargetCount() == 0) layerMorph.clear();
                else layer.controller.copyMorphWeights(layerMorph);
            }
            if (layer.mode == AnimationLayerMode.OVERRIDE) {
                PoseBlender.blend(target, layerPose, layer.weight, layer.mask, target);
                if (outputMorph != null) {
                    outputMorph.blend(outputMorph, layerMorph, layer.weight);
                }
            } else {
                PoseBlender.additive(target, layerPose, layer.reference,
                        layer.weight, layer.mask, target);
                if (outputMorph != null) {
                    outputMorph.additive(outputMorph, layerMorph,
                            zeroMorphReference, layer.weight);
                }
            }
            if (rootJointIndex >= 0) {
                float effectiveRootWeight = layer.weight * layer.mask.weight(rootJointIndex);
                root = switch (layer.rootMotionMode) {
                    case IGNORE -> root;
                    case ADDITIVE -> root.then(blendRootMotion(
                            RootMotionDelta.identity(), layerRoot, effectiveRootWeight));
                    case OVERRIDE -> blendRootMotion(root, layerRoot, effectiveRootWeight);
                };
            }
            if (layer.removeOnComplete && layer.controller.isCurrentStateComplete()) {
                emitLayerComplete(slot, layer);
                remove(slot);
            }
        }
        if (removeFromPose && rootJointIndex >= 0) {
            JointTransform sampled = target.localTransform(rootJointIndex);
            JointTransform bind = skeleton.joint(rootJointIndex).bindTransform();
            target.setLocalTransform(rootJointIndex, new JointTransform(
                    bind.translation(), bind.rotation(), sampled.scale()));
        }
        if (morphDestination != null) morphDestination.set(outputMorph);
        return root;
    }

    private void forwardSignals(List<AnimationSignal> source, int layerIndex) {
        for (AnimationSignal signal : source) {
            signals.addLast(new AnimationSignal(signalSequence++, signal.type(),
                    new AnimationSignal.Source(signal.source().graph(), layerIndex),
                    signal.state(), signal.motion(), signal.normalizedTime(), signal.name(),
                    signal.payload(), signal.loopIndex(), signal.priority()));
        }
    }

    private void emitLayerComplete(int slot, Layer layer) {
        signals.addLast(new AnimationSignal(signalSequence++,
                AnimationSignal.Type.LAYER_COMPLETE,
                new AnimationSignal.Source(layer.controller.graph().name(), slot),
                layer.controller.currentStateName(), layer.name,
                layer.controller.currentNormalizedTime(), layer.name, "", 0L,
                AnimationMarker.Priority.NORMAL));
    }

    private int freeSlot() {
        for (int index = 0; index < layers.length; index++) {
            if (layers[index] == null) return index;
        }
        throw new IllegalStateException("animation layer stack is full");
    }

    private Layer requireLayer(int index, int generation) {
        requireOpen();
        if (index < 0 || index >= layers.length
                || generations[index] != generation || layers[index] == null) {
            throw new IllegalStateException("animation layer handle is stale");
        }
        return layers[index];
    }

    private void remove(int index) {
        Layer layer = layers[index];
        if (layer == null) return;
        if (layer.ownsController) layer.controller.close();
        layers[index] = null;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("AnimationLayerStack is closed");
    }

    public enum LayerRootMotionMode {
        IGNORE,
        ADDITIVE,
        OVERRIDE
    }

    public static final class LayerHandle {
        private final AnimationLayerStack owner;
        private final int index;
        private final int generation;

        private LayerHandle(AnimationLayerStack owner, int index, int generation) {
            this.owner = owner;
            this.index = index;
            this.generation = generation;
        }

        public int index() {
            return index;
        }

        public String name() {
            return layer().name;
        }

        public float weight() {
            return layer().weight;
        }

        public LayerHandle weight(float value) {
            BoneMask.requireWeight(value);
            Layer layer = layer();
            layer.weight = value;
            layer.fadeCurve = null;
            return this;
        }

        public LayerHandle fadeTo(float value, float durationSeconds, Curve1f curve) {
            BoneMask.requireWeight(value);
            if (!Float.isFinite(durationSeconds) || durationSeconds < 0.0f) {
                throw new IllegalArgumentException(
                        "durationSeconds must be finite and non-negative");
            }
            Layer layer = layer();
            if (durationSeconds == 0.0f) return weight(value);
            layer.fadeStart = layer.weight;
            layer.fadeTarget = value;
            layer.fadeElapsed = 0.0f;
            layer.fadeDuration = durationSeconds;
            layer.fadeCurve = Objects.requireNonNull(curve, "curve");
            return this;
        }

        public LayerHandle fadeOut(float durationSeconds) {
            return fadeTo(0.0f, durationSeconds, Curves.LINEAR);
        }

        public LayerHandle pause() {
            layer().paused = true;
            return this;
        }

        public LayerHandle resume() {
            layer().paused = false;
            return this;
        }

        public LayerHandle rootMotion(LayerRootMotionMode mode) {
            Layer layer = layer();
            LayerRootMotionMode value = Objects.requireNonNull(mode, "mode");
            if (value == LayerRootMotionMode.OVERRIDE && !layer.mask.isFullBody()) {
                throw new IllegalArgumentException(
                        "OVERRIDE root motion requires a full-body mask");
            }
            layer.rootMotionMode = value;
            return this;
        }

        public void cancel() {
            layer();
            owner.remove(index);
        }

        private Layer layer() {
            return owner.requireLayer(index, generation);
        }
    }

    private static final class Layer {
        private final String name;
        private final AnimationController controller;
        private final boolean ownsController;
        private final AnimationLayerMode mode;
        private final BoneMask mask;
        private final PoseBuffer reference;
        private final boolean removeOnComplete;
        private float weight;
        private float fadeStart;
        private float fadeTarget;
        private float fadeElapsed;
        private float fadeDuration;
        private Curve1f fadeCurve;
        private boolean paused;
        private LayerRootMotionMode rootMotionMode = LayerRootMotionMode.IGNORE;

        private Layer(String name, AnimationController controller, boolean ownsController,
                      AnimationLayerMode mode, BoneMask mask, PoseBuffer reference,
                      float weight, boolean removeOnComplete) {
            this.name = name;
            this.controller = controller;
            this.ownsController = ownsController;
            this.mode = mode;
            this.mask = mask;
            this.reference = reference;
            this.weight = weight;
            this.removeOnComplete = removeOnComplete;
        }

        private void advanceFade(float deltaSeconds) {
            if (fadeCurve == null) return;
            fadeElapsed = Math.min(fadeDuration, fadeElapsed + deltaSeconds);
            float sampled = fadeCurve.sample(fadeElapsed / fadeDuration);
            if (!Float.isFinite(sampled)) {
                throw new IllegalStateException("layer fade curve produced a non-finite value");
            }
            float alpha = Math.max(0.0f, Math.min(1.0f, sampled));
            weight = fadeStart + (fadeTarget - fadeStart) * alpha;
            if (fadeElapsed >= fadeDuration) {
                weight = fadeTarget;
                fadeCurve = null;
            }
        }
    }

    private static RootMotionDelta blendRootMotion(RootMotionDelta first,
                                                   RootMotionDelta second, float weight) {
        return new RootMotionDelta(new Vector3f(first.translation())
                .lerp(second.translation(), weight),
                new Quaternionf(first.rotation()).slerp(second.rotation(), weight).normalize());
    }
}
