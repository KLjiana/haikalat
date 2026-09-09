package com.kaleblangley.haikalat.subsystems.render3d;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Consumer-merged request for shared scene surface channels.  Requirements are
 * value objects: they participate in pipeline topology and must not allocate
 * anything by themselves.
 */
public final class SceneBufferRequirements {
    private static final SceneBufferRequirements NONE =
            new SceneBufferRequirements(EnumSet.noneOf(SceneBufferChannel.class), 1);

    private final EnumSet<SceneBufferChannel> channels;
    private final int samples;

    private SceneBufferRequirements(EnumSet<SceneBufferChannel> channels, int samples) {
        if (samples < 1) {
            throw new IllegalArgumentException("scene buffer samples must be positive");
        }
        this.channels = channels.clone();
        this.samples = samples;
    }

    public static SceneBufferRequirements none() {
        return NONE;
    }

    public static SceneBufferRequirements of(Set<SceneBufferChannel> channels, int samples) {
        Objects.requireNonNull(channels, "channels");
        if (channels.isEmpty()) {
            return NONE;
        }
        return new SceneBufferRequirements(EnumSet.copyOf(channels), samples);
    }

    public static SceneBufferRequirements of(SceneBufferChannel... channels) {
        Objects.requireNonNull(channels, "channels");
        if (channels.length == 0) {
            return NONE;
        }
        EnumSet<SceneBufferChannel> set = EnumSet.noneOf(SceneBufferChannel.class);
        for (SceneBufferChannel channel : channels) {
            set.add(Objects.requireNonNull(channel, "channel"));
        }
        return new SceneBufferRequirements(set, 1);
    }

    /** Analytic fog only needs the shared depth. */
    public static SceneBufferRequirements fog() {
        return of(SceneBufferChannel.DEPTH);
    }

    /** GTAO always needs depth and world normals; temporal AO also needs motion data. */
    public static SceneBufferRequirements gtao(boolean temporal) {
        return temporal
                ? of(SceneBufferChannel.DEPTH, SceneBufferChannel.NORMAL,
                SceneBufferChannel.VELOCITY, SceneBufferChannel.PREVIOUS_SURFACE_DEPTH,
                SceneBufferChannel.VALIDITY)
                : of(SceneBufferChannel.DEPTH, SceneBufferChannel.NORMAL);
    }

    /** Native TAA surface data; reactive is requested only when a producer exists. */
    public static SceneBufferRequirements taa(boolean reactive) {
        EnumSet<SceneBufferChannel> set = EnumSet.of(SceneBufferChannel.DEPTH,
                SceneBufferChannel.VELOCITY, SceneBufferChannel.PREVIOUS_SURFACE_DEPTH,
                SceneBufferChannel.VALIDITY);
        if (reactive) {
            set.add(SceneBufferChannel.REACTIVE);
        }
        return new SceneBufferRequirements(set, 1);
    }

    public SceneBufferRequirements merge(SceneBufferRequirements other) {
        Objects.requireNonNull(other, "other");
        EnumSet<SceneBufferChannel> merged = channels.clone();
        merged.addAll(other.channels);
        return new SceneBufferRequirements(merged, Math.max(samples, other.samples));
    }

    public SceneBufferRequirements withSamples(int sampleCount) {
        return new SceneBufferRequirements(channels, sampleCount);
    }

    public boolean requires(SceneBufferChannel channel) {
        return channels.contains(Objects.requireNonNull(channel, "channel"));
    }

    public Set<SceneBufferChannel> channels() {
        return Set.copyOf(channels);
    }

    public int samples() {
        return samples;
    }

    public boolean isEmpty() {
        return channels.isEmpty();
    }

    /** Whether a dedicated surface prepass (and therefore shared attachments) is required. */
    public boolean requiresSurfacePass() {
        return !channels.isEmpty();
    }

    /** Velocity and its validation companions must always be requested together. */
    public SceneBufferRequirements validated() {
        if (!channels.contains(SceneBufferChannel.VELOCITY)) {
            return this;
        }
        EnumSet<SceneBufferChannel> set = channels.clone();
        set.add(SceneBufferChannel.PREVIOUS_SURFACE_DEPTH);
        set.add(SceneBufferChannel.VALIDITY);
        return new SceneBufferRequirements(set, samples);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SceneBufferRequirements that)) return false;
        return samples == that.samples && channels.equals(that.channels);
    }

    @Override
    public int hashCode() {
        return 31 * channels.hashCode() + samples;
    }

    @Override
    public String toString() {
        return "SceneBufferRequirements" + channels + "@" + samples + "x";
    }
}
