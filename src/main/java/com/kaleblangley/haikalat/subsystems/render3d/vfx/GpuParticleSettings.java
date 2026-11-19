package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import org.joml.Vector3f;

import java.util.Objects;

/**
 * Bounded, GL-free configuration of the production GPU particle owner.
 *
 * <p>This is the value type behind
 * {@link GpuParticleSystem}: it describes a finite capacity and the pure
 * emission/lifetime rules executed by the compute pass. Positions and speeds
 * are world-space units per second; gravity is a downward acceleration on the
 * Y axis; {@code particleSize} is the world-space billboard half-width and is
 * divided by the clip depth in the geometry stage, so screen coverage shrinks
 * with distance. No texture, sorting or collision input is part of this
 * version.</p>
 *
 * <p>Every component is validated at construction. Values are deliberately
 * bounded instead of silently clamped, so an author error fails at the owner
 * boundary rather than producing unbounded particle counts or non-finite
 * positions. The record returns defensive copies of its vectors.</p>
 */
public record GpuParticleSettings(
        int capacity,
        Vector3f spawnMinimum,
        Vector3f spawnMaximum,
        float lifetimeSeconds,
        float gravity,
        float minimumSpeed,
        float maximumSpeed,
        float particleSize,
        Vector3f color,
        float intensity) {
    /** Hard upper bound for one particle storage allocation. */
    public static final int MAXIMUM_CAPACITY = 1_048_576;
    /** Largest accepted lifetime in seconds. */
    public static final float MAXIMUM_LIFETIME_SECONDS = 3_600.0f;
    /** Largest accepted absolute spawn coordinate in world units. */
    public static final float MAXIMUM_ABSOLUTE_COORDINATE = 100_000.0f;
    /** Largest accepted absolute gravity acceleration in world units/s². */
    public static final float MAXIMUM_GRAVITY = 1_000.0f;
    /** Largest accepted initial speed in world units/s. */
    public static final float MAXIMUM_SPEED = 1_000.0f;
    /** Largest accepted billboard half-size in world units. */
    public static final float MAXIMUM_PARTICLE_SIZE = 100.0f;
    /** Largest accepted colour or intensity component. */
    public static final float MAXIMUM_COLOR_COMPONENT = 16.0f;

    public GpuParticleSettings {
        if (capacity <= 0 || capacity > MAXIMUM_CAPACITY) {
            throw new IllegalArgumentException("capacity must be in [1, "
                    + MAXIMUM_CAPACITY + "]");
        }
        spawnMinimum = coordinate(spawnMinimum, "spawnMinimum");
        spawnMaximum = coordinate(spawnMaximum, "spawnMaximum");
        if (spawnMinimum.x > spawnMaximum.x || spawnMinimum.y > spawnMaximum.y
                || spawnMinimum.z > spawnMaximum.z) {
            throw new IllegalArgumentException(
                    "spawnMinimum must not exceed spawnMaximum on any axis");
        }
        if (!Float.isFinite(lifetimeSeconds) || lifetimeSeconds <= 0.0f
                || lifetimeSeconds > MAXIMUM_LIFETIME_SECONDS) {
            throw new IllegalArgumentException("lifetimeSeconds must be finite and in (0, "
                    + MAXIMUM_LIFETIME_SECONDS + "]");
        }
        if (!Float.isFinite(gravity) || Math.abs(gravity) > MAXIMUM_GRAVITY) {
            throw new IllegalArgumentException("gravity must be finite and in [-"
                    + MAXIMUM_GRAVITY + ", " + MAXIMUM_GRAVITY + "]");
        }
        if (!Float.isFinite(minimumSpeed) || !Float.isFinite(maximumSpeed)
                || minimumSpeed < 0.0f || minimumSpeed > MAXIMUM_SPEED
                || maximumSpeed < minimumSpeed || maximumSpeed > MAXIMUM_SPEED) {
            throw new IllegalArgumentException("speeds must be finite and satisfy 0 <= "
                    + "minimumSpeed <= maximumSpeed <= " + MAXIMUM_SPEED);
        }
        if (!Float.isFinite(particleSize) || particleSize <= 0.0f
                || particleSize > MAXIMUM_PARTICLE_SIZE) {
            throw new IllegalArgumentException("particleSize must be finite and in (0, "
                    + MAXIMUM_PARTICLE_SIZE + "]");
        }
        color = color(color);
        if (!Float.isFinite(intensity) || intensity < 0.0f
                || intensity > MAXIMUM_COLOR_COMPONENT) {
            throw new IllegalArgumentException("intensity must be finite and in [0, "
                    + MAXIMUM_COLOR_COMPONENT + "]");
        }
    }

    /**
     * Bounded default value for a first integration or test fixture.
     *
     * @param capacity particle storage capacity in [1, {@link #MAXIMUM_CAPACITY}]
     * @return a warm additive spark value in a 3x2x2 world box
     */
    public static GpuParticleSettings defaults(int capacity) {
        return new GpuParticleSettings(capacity,
                new Vector3f(-1.5f, -1.0f, -1.0f), new Vector3f(1.5f, 1.0f, 1.0f),
                1.2f, 0.6f, 0.25f, 0.75f, 0.05f,
                new Vector3f(1.0f, 0.45f, 0.12f), 0.4f);
    }

    /** @return a copy with only the storage capacity changed */
    public GpuParticleSettings withCapacity(int value) {
        return new GpuParticleSettings(value, spawnMinimum, spawnMaximum, lifetimeSeconds,
                gravity, minimumSpeed, maximumSpeed, particleSize, color, intensity);
    }

    /** @return a copy with only the lifetime changed, used to re-arm live spawns */
    public GpuParticleSettings withLifetimeSeconds(float value) {
        return new GpuParticleSettings(capacity, spawnMinimum, spawnMaximum, value,
                gravity, minimumSpeed, maximumSpeed, particleSize, color, intensity);
    }

    /** Minimum-corner copy for render recording. */
    @Override
    public Vector3f spawnMinimum() {
        return new Vector3f(spawnMinimum);
    }

    /** Maximum-corner copy for render recording. */
    @Override
    public Vector3f spawnMaximum() {
        return new Vector3f(spawnMaximum);
    }

    /** Linear additive colour copy. */
    @Override
    public Vector3f color() {
        return new Vector3f(color);
    }

    private static Vector3f coordinate(Vector3f value, String name) {
        Objects.requireNonNull(value, name);
        if (!Float.isFinite(value.x) || !Float.isFinite(value.y) || !Float.isFinite(value.z)
                || Math.abs(value.x) > MAXIMUM_ABSOLUTE_COORDINATE
                || Math.abs(value.y) > MAXIMUM_ABSOLUTE_COORDINATE
                || Math.abs(value.z) > MAXIMUM_ABSOLUTE_COORDINATE) {
            throw new IllegalArgumentException(name + " must be finite and within ±"
                    + MAXIMUM_ABSOLUTE_COORDINATE);
        }
        return new Vector3f(value);
    }

    private static Vector3f color(Vector3f value) {
        Objects.requireNonNull(value, "color");
        if (!Float.isFinite(value.x) || !Float.isFinite(value.y) || !Float.isFinite(value.z)
                || value.x < 0.0f || value.y < 0.0f || value.z < 0.0f
                || value.x > MAXIMUM_COLOR_COMPONENT || value.y > MAXIMUM_COLOR_COMPONENT
                || value.z > MAXIMUM_COLOR_COMPONENT) {
            throw new IllegalArgumentException("color must be finite and in [0, "
                    + MAXIMUM_COLOR_COMPONENT + "]");
        }
        return new Vector3f(value);
    }
}
