package com.kaleblangley.haikalat.subsystems.render3d;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Borrowed, immutable handles to one generation's shared scene surface
 * buffers.  A view never allocates or closes anything; consumers must not
 * retain it beyond the frame that produced it.
 */
public final class SceneBufferView {
    private final long generationId;
    private final long frameSequence;
    private final int width;
    private final int height;
    private final int samples;
    private final SceneBufferRequirements requirements;
    private final Map<SceneBufferChannel, Integer> textures;

    public SceneBufferView(long generationId, long frameSequence, int width, int height,
                           int samples, SceneBufferRequirements requirements,
                           Map<SceneBufferChannel, Integer> textures) {
        if (generationId <= 0L) {
            throw new IllegalArgumentException("scene buffer generation id must be positive");
        }
        if (frameSequence < 0L) {
            throw new IllegalArgumentException("scene buffer frame sequence must be non-negative");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("scene buffer extent must be positive");
        }
        if (samples < 1) {
            throw new IllegalArgumentException("scene buffer samples must be positive");
        }
        this.generationId = generationId;
        this.frameSequence = frameSequence;
        this.width = width;
        this.height = height;
        this.samples = samples;
        this.requirements = Objects.requireNonNull(requirements, "requirements");
        EnumMap<SceneBufferChannel, Integer> copy = new EnumMap<>(SceneBufferChannel.class);
        Objects.requireNonNull(textures, "textures").forEach((channel, id) -> {
            if (id == null || id <= 0) {
                throw new IllegalArgumentException("scene buffer texture id must be positive");
            }
            copy.put(Objects.requireNonNull(channel, "channel"), id);
        });
        this.textures = Map.copyOf(copy);
    }

    public long generationId() {
        return generationId;
    }

    public long frameSequence() {
        return frameSequence;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int samples() {
        return samples;
    }

    public SceneBufferRequirements requirements() {
        return requirements;
    }

    /** @return borrowed texture id or 0 when the channel was not produced */
    public int textureId(SceneBufferChannel channel) {
        return textures.getOrDefault(Objects.requireNonNull(channel, "channel"), 0);
    }

    public boolean available(SceneBufferChannel channel) {
        return textureId(channel) != 0;
    }

    /** @throws IllegalStateException when the channel is absent from this view */
    public int require(SceneBufferChannel channel) {
        int id = textureId(channel);
        if (id == 0) {
            throw new IllegalStateException("scene buffer channel " + channel
                    + " is not produced by generation " + generationId);
        }
        return id;
    }

    public Map<SceneBufferChannel, Integer> textures() {
        return textures;
    }
}
