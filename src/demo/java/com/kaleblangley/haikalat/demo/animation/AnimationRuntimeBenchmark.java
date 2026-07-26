package com.kaleblangley.haikalat.demo.animation;

import com.kaleblangley.haikalat.subsystems.animation.AnimationClip;
import com.kaleblangley.haikalat.subsystems.animation.AnimationController;
import com.kaleblangley.haikalat.subsystems.animation.AnimationGraph;
import com.kaleblangley.haikalat.subsystems.animation.AnimationPlayer;
import com.kaleblangley.haikalat.subsystems.animation.ClipMotion;
import com.kaleblangley.haikalat.subsystems.animation.FabrikSolver;
import com.kaleblangley.haikalat.subsystems.animation.JointTransform;
import com.kaleblangley.haikalat.subsystems.animation.MorphWeightBuffer;
import com.kaleblangley.haikalat.subsystems.animation.MorphWeightTrack;
import com.kaleblangley.haikalat.subsystems.animation.PoseBuffer;
import com.kaleblangley.haikalat.subsystems.animation.Skeleton;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Five-round deterministic CPU benchmark for graph, constraints and morph weights. */
public final class AnimationRuntimeBenchmark {
    private static final int ROUNDS = 5;
    private static final int MINIMUM_WARMUP_FRAMES = 60;
    private static final int MINIMUM_WARMUP_UPDATES = 10_000;
    private static final int FRAMES = 120;
    private static final float DELTA = 1.0f / 60.0f;
    private static final AllocationCounter ALLOCATIONS = AllocationCounter.create();

    private AnimationRuntimeBenchmark() {
    }

    public static void main(String[] arguments) {
        Mode mode = arguments.length == 0 ? Mode.RUNTIME
                : Mode.valueOf(arguments[0].substring("--mode=".length())
                .toUpperCase(Locale.ROOT));
        int[] populations = mode == Mode.MORPH
                ? new int[]{100, 1_000} : new int[]{1, 100, 1_000};
        for (int population : populations) {
            Measurement[] rounds = new Measurement[ROUNDS];
            int warmupFrames = warmupFrames(population);
            for (int round = 0; round < ROUNDS; round++) {
                rounds[round] = switch (mode) {
                    case RUNTIME -> runtime(population, warmupFrames);
                    case CONSTRAINTS -> constraints(population, warmupFrames);
                    case MORPH -> morph(population, population == 100 ? 4 : 8,
                            warmupFrames);
                };
            }
            Arrays.sort(rounds, (left, right) ->
                    Double.compare(left.elapsedMillis(), right.elapsedMillis()));
            Measurement median = rounds[ROUNDS / 2];
            double allocatedPerCharacterFrame = median.allocatedBytes()
                    / (double) (population * FRAMES);
            if ((mode == Mode.RUNTIME || mode == Mode.MORPH)
                    && allocatedPerCharacterFrame > 16.0) {
                throw new IllegalStateException("steady-state " + mode
                        + " allocation exceeded 16 B/character/frame: "
                        + allocatedPerCharacterFrame);
            }
            System.out.printf(Locale.ROOT,
                    "ANIMATION_BENCHMARK mode=%s characters=%d targets=%d "
                            + "warmup=%d frames=%d rounds=%d medianMs=%.4f "
                            + "allocatedBytes=%d bytesPerCharacterFrame=%.3f%n",
                    mode.name().toLowerCase(Locale.ROOT), population,
                    mode == Mode.MORPH ? population == 100 ? 4 : 8 : 0,
                    warmupFrames, FRAMES, ROUNDS, median.elapsedMillis(),
                    median.allocatedBytes(), allocatedPerCharacterFrame);
        }
    }

    private static int warmupFrames(int population) {
        return Math.max(MINIMUM_WARMUP_FRAMES,
                (MINIMUM_WARMUP_UPDATES + population - 1) / population);
    }

    private static Measurement runtime(int population, int warmupFrames) {
        Skeleton skeleton = skeleton();
        AnimationGraph graph = graph(skeleton);
        AnimationController[] controllers = new AnimationController[population];
        PoseBuffer[] poses = new PoseBuffer[population];
        for (int index = 0; index < population; index++) {
            controllers[index] = graph.createController();
            poses[index] = skeleton.createPoseBuffer();
        }
        evaluateRuntime(controllers, poses, warmupFrames);
        long allocatedBefore = ALLOCATIONS.currentThreadBytes();
        long start = System.nanoTime();
        evaluateRuntime(controllers, poses, FRAMES);
        long elapsed = System.nanoTime() - start;
        long allocated = allocatedSince(allocatedBefore);
        for (AnimationController controller : controllers) controller.close();
        return new Measurement(elapsed / 1_000_000.0, allocated);
    }

    private static void evaluateRuntime(AnimationController[] controllers,
                                        PoseBuffer[] poses, int frames) {
        for (int frame = 0; frame < frames; frame++) {
            float speed = (frame % 60) / 59.0f;
            for (int index = 0; index < controllers.length; index++) {
                controllers[index].setFloat("speed", speed).update(DELTA, poses[index]);
            }
        }
    }

    private static Measurement constraints(int population, int warmupFrames) {
        Skeleton skeleton = skeleton();
        FabrikSolver[] solvers = new FabrikSolver[population];
        PoseBuffer[] poses = new PoseBuffer[population];
        for (int index = 0; index < population; index++) {
            solvers[index] = new FabrikSolver(skeleton, 0, 1, 2, 3);
            poses[index] = skeleton.createPoseBuffer();
        }
        evaluateConstraints(solvers, poses, warmupFrames);
        long allocatedBefore = ALLOCATIONS.currentThreadBytes();
        long start = System.nanoTime();
        evaluateConstraints(solvers, poses, FRAMES);
        return new Measurement((System.nanoTime() - start) / 1_000_000.0,
                allocatedSince(allocatedBefore));
    }

    private static void evaluateConstraints(FabrikSolver[] solvers,
                                            PoseBuffer[] poses, int frames) {
        for (int frame = 0; frame < frames; frame++) {
            float y = 0.5f + 0.2f * (float) Math.sin(frame * DELTA);
            var context = com.kaleblangley.haikalat.subsystems.animation
                    .AnimationConstraint.Context.target(
                    new Vector3f(2.4f, y, 0.0f),
                    new Vector3f(0.0f, 0.0f, 1.0f), 1.0f, DELTA);
            for (int index = 0; index < solvers.length; index++) {
                poses[index].resetToBindPose();
                solvers[index].apply(poses[index], context);
            }
        }
    }

    private static Measurement morph(int population, int targets, int warmupFrames) {
        float[] values = new float[targets * 2];
        for (int index = 0; index < targets; index++) values[targets + index] = 1.0f;
        MorphWeightTrack track = new MorphWeightTrack(targets,
                MorphWeightTrack.Interpolation.LINEAR,
                new float[]{0.0f, 1.0f}, values);
        MorphWeightBuffer[] outputs = new MorphWeightBuffer[population];
        for (int index = 0; index < population; index++) {
            outputs[index] = new MorphWeightBuffer(targets);
        }
        evaluateMorph(track, outputs, warmupFrames);
        long allocatedBefore = ALLOCATIONS.currentThreadBytes();
        long start = System.nanoTime();
        evaluateMorph(track, outputs, FRAMES);
        return new Measurement((System.nanoTime() - start) / 1_000_000.0,
                allocatedSince(allocatedBefore));
    }

    private static void evaluateMorph(MorphWeightTrack track,
                                      MorphWeightBuffer[] outputs, int frames) {
        for (int frame = 0; frame < frames; frame++) {
            float time = frames == 1 ? 0.0f : frame / (float) (frames - 1);
            for (MorphWeightBuffer output : outputs) track.sample(time, output);
        }
    }

    private static long allocatedSince(long before) {
        long after = ALLOCATIONS.currentThreadBytes();
        return before < 0L || after < before ? 0L : after - before;
    }

    private static AnimationGraph graph(Skeleton skeleton) {
        ClipMotion idle = new ClipMotion(clip("idle", skeleton, 0.15f));
        ClipMotion run = new ClipMotion(clip("run", skeleton, 0.75f));
        return AnimationGraph.builder("benchmark", skeleton)
                .floatParameter("speed", 0.0f)
                .state("move", com.kaleblangley.haikalat.subsystems.animation
                        .BlendTree1D.builder("speed")
                        .child(0.0f, idle).child(1.0f, run).build(),
                        AnimationPlayer.LoopMode.LOOP)
                .entry("move").build();
    }

    private static AnimationClip clip(String name, Skeleton skeleton, float rotation) {
        return AnimationClip.builder(name, skeleton)
                .rotation(0, AnimationClip.Interpolation.LINEAR,
                        new float[]{0.0f, 1.0f},
                        new Quaternionf().rotateZ(-rotation),
                        new Quaternionf().rotateZ(rotation))
                .rotation(1, AnimationClip.Interpolation.LINEAR,
                        new float[]{0.0f, 1.0f},
                        new Quaternionf().rotateZ(rotation),
                        new Quaternionf().rotateZ(-rotation))
                .build();
    }

    private static Skeleton skeleton() {
        return new Skeleton(List.of(
                new Skeleton.Joint("root", -1, JointTransform.identity()),
                new Skeleton.Joint("one", 0, translated()),
                new Skeleton.Joint("two", 1, translated()),
                new Skeleton.Joint("tip", 2, translated())));
    }

    private static JointTransform translated() {
        return new JointTransform(new Vector3f(1.0f, 0.0f, 0.0f),
                new Quaternionf(), new Vector3f(1.0f));
    }

    private enum Mode {
        RUNTIME,
        CONSTRAINTS,
        MORPH
    }

    private record Measurement(double elapsedMillis, long allocatedBytes) {
    }

    private interface AllocationCounter {
        long currentThreadBytes();

        static AllocationCounter create() {
            java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean extended
                    && extended.isThreadAllocatedMemorySupported()) {
                if (!extended.isThreadAllocatedMemoryEnabled()) {
                    extended.setThreadAllocatedMemoryEnabled(true);
                }
                long threadId = Thread.currentThread().threadId();
                return () -> extended.getThreadAllocatedBytes(threadId);
            }
            return () -> -1L;
        }
    }
}
