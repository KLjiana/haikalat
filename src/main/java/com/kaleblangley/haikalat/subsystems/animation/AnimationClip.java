package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 绑定到单一骨架的不可变关键帧动画。 */
public final class AnimationClip {
    private final String name;
    private final Skeleton skeleton;
    private final List<Channel> channels;
    private final List<AnimationEvent> events;
    private final float durationSeconds;

    private AnimationClip(String name, Skeleton skeleton, List<Channel> channels,
                          List<AnimationEvent> events, float durationSeconds) {
        this.name = name;
        this.skeleton = skeleton;
        this.channels = List.copyOf(channels);
        this.events = List.copyOf(events);
        this.durationSeconds = durationSeconds;
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

    public int channelCount() {
        return channels.size();
    }

    public float durationSeconds() {
        return durationSeconds;
    }

    public List<AnimationEvent> events() {
        return events;
    }

    /** 采样并返回一张独立的不可变姿态。 */
    public Pose sample(float timeSeconds) {
        PoseBuffer destination = skeleton.createPoseBuffer();
        sample(timeSeconds, destination);
        return destination.snapshot();
    }

    /** 将指定时间的姿态写入可复用缓冲；未被动画驱动的关节分量保持绑定姿态。 */
    public void sample(float timeSeconds, PoseBuffer destination) {
        requireFiniteNonNegative(timeSeconds, "timeSeconds");
        PoseBuffer target = Objects.requireNonNull(destination, "destination");
        if (target.skeleton() != skeleton) {
            throw new IllegalArgumentException("destination belongs to a different skeleton");
        }
        float clampedTime = Math.min(timeSeconds, durationSeconds);
        target.resetToBindPose();
        for (Channel channel : channels) {
            channel.sample(clampedTime, target);
        }
    }

    public enum Interpolation {
        STEP,
        LINEAR,
        CUBIC_SPLINE
    }

    public static final class Builder {
        private static final int TRANSLATION = 0;
        private static final int ROTATION = 1;
        private static final int SCALE = 2;

        private final String name;
        private final Skeleton skeleton;
        private final List<Channel> channels = new ArrayList<>();
        private final List<AnimationEvent> events = new ArrayList<>();
        private final boolean[][] assigned;
        private float durationSeconds;

        private Builder(String name, Skeleton skeleton) {
            this.name = Objects.requireNonNull(name, "name");
            if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
            assigned = new boolean[skeleton.jointCount()][3];
        }

        public Builder translation(int jointIndex, Interpolation interpolation,
                                   float[] timesSeconds, Vector3fc... values) {
            validateSlot(jointIndex, TRANSLATION, "translation");
            VectorChannel channel = new VectorChannel(jointIndex, false, interpolation,
                    timesSeconds, values);
            assigned[jointIndex][TRANSLATION] = true;
            channels.add(channel);
            durationSeconds = Math.max(durationSeconds, channel.endTime());
            return this;
        }

        public Builder translationCubic(int jointIndex, float[] timesSeconds,
                                        Vector3fc[] inTangents, Vector3fc[] values,
                                        Vector3fc[] outTangents) {
            validateSlot(jointIndex, TRANSLATION, "translation");
            VectorChannel channel = new VectorChannel(jointIndex, false, timesSeconds,
                    inTangents, values, outTangents);
            assigned[jointIndex][TRANSLATION] = true;
            channels.add(channel);
            durationSeconds = Math.max(durationSeconds, channel.endTime());
            return this;
        }

        public Builder rotation(int jointIndex, Interpolation interpolation,
                                float[] timesSeconds, Quaternionfc... values) {
            validateSlot(jointIndex, ROTATION, "rotation");
            RotationChannel channel = new RotationChannel(jointIndex, interpolation,
                    timesSeconds, values);
            assigned[jointIndex][ROTATION] = true;
            channels.add(channel);
            durationSeconds = Math.max(durationSeconds, channel.endTime());
            return this;
        }

        public Builder rotationCubic(int jointIndex, float[] timesSeconds,
                                     Quaternionfc[] inTangents, Quaternionfc[] values,
                                     Quaternionfc[] outTangents) {
            validateSlot(jointIndex, ROTATION, "rotation");
            RotationChannel channel = new RotationChannel(jointIndex, timesSeconds,
                    inTangents, values, outTangents);
            assigned[jointIndex][ROTATION] = true;
            channels.add(channel);
            durationSeconds = Math.max(durationSeconds, channel.endTime());
            return this;
        }

        public Builder scale(int jointIndex, Interpolation interpolation,
                             float[] timesSeconds, Vector3fc... values) {
            validateSlot(jointIndex, SCALE, "scale");
            VectorChannel channel = new VectorChannel(jointIndex, true, interpolation,
                    timesSeconds, values);
            assigned[jointIndex][SCALE] = true;
            channels.add(channel);
            durationSeconds = Math.max(durationSeconds, channel.endTime());
            return this;
        }

        public Builder scaleCubic(int jointIndex, float[] timesSeconds,
                                  Vector3fc[] inTangents, Vector3fc[] values,
                                  Vector3fc[] outTangents) {
            validateSlot(jointIndex, SCALE, "scale");
            VectorChannel channel = new VectorChannel(jointIndex, true, timesSeconds,
                    inTangents, values, outTangents);
            assigned[jointIndex][SCALE] = true;
            channels.add(channel);
            durationSeconds = Math.max(durationSeconds, channel.endTime());
            return this;
        }

        public Builder event(float timeSeconds, String name) {
            return event(timeSeconds, name, "");
        }

        public Builder event(float timeSeconds, String name, String payload) {
            events.add(new AnimationEvent(timeSeconds, name, payload));
            return this;
        }

        public AnimationClip build() {
            for (AnimationEvent event : events) {
                if (event.timeSeconds() > durationSeconds) {
                    throw new IllegalArgumentException("event '" + event.name()
                            + "' is after clip duration " + durationSeconds);
                }
            }
            List<AnimationEvent> orderedEvents = new ArrayList<>(events);
            orderedEvents.sort(Comparator.comparingDouble(AnimationEvent::timeSeconds));
            return new AnimationClip(name, skeleton, channels, orderedEvents, durationSeconds);
        }

        private void validateSlot(int jointIndex, int path, String pathName) {
            if (jointIndex < 0 || jointIndex >= skeleton.jointCount()) {
                throw new IndexOutOfBoundsException("joint index " + jointIndex
                        + " is outside 0.." + (skeleton.jointCount() - 1));
            }
            if (assigned[jointIndex][path]) {
                throw new IllegalArgumentException("joint[" + jointIndex + "] already has a "
                        + pathName + " channel");
            }
        }
    }

    private interface Channel {
        float endTime();

        void sample(float timeSeconds, PoseBuffer destination);
    }

    private static final class VectorChannel implements Channel {
        private final int jointIndex;
        private final boolean scale;
        private final Interpolation interpolation;
        private final float[] times;
        private final Vector3f[] values;
        private final Vector3f[] inTangents;
        private final Vector3f[] outTangents;

        private VectorChannel(int jointIndex, boolean scale, Interpolation interpolation,
                              float[] timesSeconds, Vector3fc[] values) {
            this.jointIndex = jointIndex;
            this.scale = scale;
            this.interpolation = Objects.requireNonNull(interpolation, "interpolation");
            if (interpolation == Interpolation.CUBIC_SPLINE) {
                throw new IllegalArgumentException(
                        "CUBIC_SPLINE requires explicit in/out tangents");
            }
            times = copyTimes(timesSeconds, values == null ? -1 : values.length);
            this.values = copyVectors(values, "values");
            inTangents = null;
            outTangents = null;
        }

        private VectorChannel(int jointIndex, boolean scale, float[] timesSeconds,
                              Vector3fc[] inTangents, Vector3fc[] values,
                              Vector3fc[] outTangents) {
            this.jointIndex = jointIndex;
            this.scale = scale;
            interpolation = Interpolation.CUBIC_SPLINE;
            times = copyTimes(timesSeconds, values == null ? -1 : values.length);
            this.values = copyVectors(values, "values");
            this.inTangents = copyVectorsWithCount(inTangents, values.length, "inTangents");
            this.outTangents = copyVectorsWithCount(outTangents, values.length, "outTangents");
        }

        @Override
        public float endTime() {
            return times[times.length - 1];
        }

        @Override
        public void sample(float timeSeconds, PoseBuffer destination) {
            Segment segment = segment(times, timeSeconds, interpolation);
            if (segment.first == segment.second || interpolation != Interpolation.CUBIC_SPLINE) {
                if (scale) {
                    destination.setScaleSample(jointIndex, values[segment.first], values[segment.second],
                            segment.alpha);
                } else {
                    destination.setTranslationSample(jointIndex,
                            values[segment.first], values[segment.second], segment.alpha);
                }
                return;
            }
            float duration = times[segment.second] - times[segment.first];
            if (scale) {
                destination.setScaleCubicSample(jointIndex, values[segment.first],
                        outTangents[segment.first], values[segment.second],
                        inTangents[segment.second], segment.alpha, duration);
            } else {
                destination.setTranslationCubicSample(jointIndex, values[segment.first],
                        outTangents[segment.first], values[segment.second],
                        inTangents[segment.second], segment.alpha, duration);
            }
        }
    }

    private static final class RotationChannel implements Channel {
        private final int jointIndex;
        private final Interpolation interpolation;
        private final float[] times;
        private final Quaternionf[] values;
        private final Quaternionf[] inTangents;
        private final Quaternionf[] outTangents;

        private RotationChannel(int jointIndex, Interpolation interpolation,
                                float[] timesSeconds, Quaternionfc[] values) {
            this.jointIndex = jointIndex;
            this.interpolation = Objects.requireNonNull(interpolation, "interpolation");
            if (interpolation == Interpolation.CUBIC_SPLINE) {
                throw new IllegalArgumentException(
                        "CUBIC_SPLINE requires explicit in/out tangents");
            }
            times = copyTimes(timesSeconds, values == null ? -1 : values.length);
            this.values = copyRotations(values, "values", true);
            inTangents = null;
            outTangents = null;
        }

        private RotationChannel(int jointIndex, float[] timesSeconds,
                                Quaternionfc[] inTangents, Quaternionfc[] values,
                                Quaternionfc[] outTangents) {
            this.jointIndex = jointIndex;
            interpolation = Interpolation.CUBIC_SPLINE;
            times = copyTimes(timesSeconds, values == null ? -1 : values.length);
            this.values = copyRotations(values, "values", true);
            this.inTangents = copyRotationsWithCount(inTangents, values.length,
                    "inTangents", false);
            this.outTangents = copyRotationsWithCount(outTangents, values.length,
                    "outTangents", false);
        }

        @Override
        public float endTime() {
            return times[times.length - 1];
        }

        @Override
        public void sample(float timeSeconds, PoseBuffer destination) {
            Segment segment = segment(times, timeSeconds, interpolation);
            if (segment.first == segment.second || interpolation != Interpolation.CUBIC_SPLINE) {
                destination.setRotationSample(jointIndex,
                        values[segment.first], values[segment.second], segment.alpha);
                return;
            }
            destination.setRotationCubicSample(jointIndex, values[segment.first],
                    outTangents[segment.first], values[segment.second],
                    inTangents[segment.second], segment.alpha,
                    times[segment.second] - times[segment.first]);
        }
    }

    private static Vector3f[] copyVectors(Vector3fc[] source, String name) {
        Objects.requireNonNull(source, name);
        Vector3f[] result = new Vector3f[source.length];
        for (int index = 0; index < source.length; index++) {
            Vector3fc value = Objects.requireNonNull(source[index], name + "[" + index + "]");
            JointTransform.requireFinite(value, name + "[" + index + "]");
            result[index] = new Vector3f(value);
        }
        return result;
    }

    private static Vector3f[] copyVectorsWithCount(Vector3fc[] source, int expected,
                                                    String name) {
        if (source == null || source.length != expected) {
            throw new IllegalArgumentException(name + " count must match keyframe count");
        }
        return copyVectors(source, name);
    }

    private static Quaternionf[] copyRotations(Quaternionfc[] source, String name,
                                               boolean normalize) {
        Objects.requireNonNull(source, name);
        Quaternionf[] result = new Quaternionf[source.length];
        for (int index = 0; index < source.length; index++) {
            Quaternionfc value = Objects.requireNonNull(source[index], name + "[" + index + "]");
            Quaternionf copy = new Quaternionf(value);
            float lengthSquared = copy.lengthSquared();
            if (!Float.isFinite(lengthSquared) || normalize && lengthSquared <= 1.0e-12f) {
                throw new IllegalArgumentException(name + "[" + index
                        + "] must contain finite components"
                        + (normalize ? " and be non-zero" : ""));
            }
            if (normalize) copy.normalize();
            result[index] = copy;
        }
        return result;
    }

    private static Quaternionf[] copyRotationsWithCount(Quaternionfc[] source, int expected,
                                                        String name, boolean normalize) {
        if (source == null || source.length != expected) {
            throw new IllegalArgumentException(name + " count must match keyframe count");
        }
        return copyRotations(source, name, normalize);
    }

    private static float[] copyTimes(float[] source, int valueCount) {
        Objects.requireNonNull(source, "timesSeconds");
        if (source.length == 0) {
            throw new IllegalArgumentException("animation channel must contain at least one keyframe");
        }
        if (source.length != valueCount) {
            throw new IllegalArgumentException("keyframe time and value counts must match");
        }
        float[] result = source.clone();
        float previous = -1.0f;
        for (int index = 0; index < result.length; index++) {
            float time = result[index];
            requireFiniteNonNegative(time, "timesSeconds[" + index + "]");
            if (index > 0 && time <= previous) {
                throw new IllegalArgumentException("keyframe times must be strictly increasing");
            }
            result[index] = time == 0.0f ? 0.0f : time;
            previous = time;
        }
        return result;
    }

    private static Segment segment(float[] times, float timeSeconds, Interpolation interpolation) {
        int exact = Arrays.binarySearch(times, timeSeconds);
        if (exact >= 0) return new Segment(exact, exact, 0.0f);
        int upper = -exact - 1;
        if (upper <= 0) return new Segment(0, 0, 0.0f);
        if (upper >= times.length) {
            int last = times.length - 1;
            return new Segment(last, last, 0.0f);
        }
        int lower = upper - 1;
        if (interpolation == Interpolation.STEP) {
            return new Segment(lower, lower, 0.0f);
        }
        float alpha = (timeSeconds - times[lower]) / (times[upper] - times[lower]);
        return new Segment(lower, upper, alpha);
    }

    private static void requireFiniteNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private record Segment(int first, int second, float alpha) {
    }
}
