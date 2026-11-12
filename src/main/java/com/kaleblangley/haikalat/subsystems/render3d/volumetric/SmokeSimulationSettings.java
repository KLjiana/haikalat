package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import org.joml.Vector3f;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.texture.Texture3D;

import java.util.List;
import java.util.Objects;

/**
 * Bounded, GL-free configuration of the deterministic smoke simulation.
 *
 * <p>The simulation owns a fixed, axis-aligned world-space grid of
 * {@code gridWidth x gridHeight x gridDepth} cells ({@code 8..64} per axis;
 * the documented production tiers are {@code 32}, {@code 48} and {@code 64}).
 * Each cell stores density in the red channel and temperature in the green
 * channel of one {@code RGBA16F} texel. A step is an explicit, conservative
 * finite-volume update:</p>
 *
 * <ul>
 *   <li>cell velocity is {@code wind + (0, buoyancy * temperature, 0)} with the
 *       temperature clamped to {@code [0, maximumTemperature]};</li>
 *   <li>a face carries the arithmetic mean of the two adjacent cell
 *       velocities, and every outer face carries exactly zero normal velocity
 *       (closed, no-flux boundary);</li>
 *   <li>the advective flux of a scalar is upwind:
 *       {@code velocity > 0 ? velocity * left : velocity * right};</li>
 *   <li>the update is {@code S' = S - dt * divergence(flux) - dissipation},
 *       where dissipation is the exact exponential factor
 *       {@code exp(-rate * dt)} so it never becomes negative;</li>
 *   <li>a source adds {@code rate * dt * (1 - q)^2} for
 *       {@code q = |p - center|^2 / radius^2 < 1} while the source is active.
 *       Activity is the half-open simulation-time window
 *       {@code [startSeconds, endSeconds)}, so an emitter can be a burst.</li>
 * </ul>
 *
 * <p>Because the boundary faces carry no flux and the update is written against
 * one old field (Jacobi form), the grid's total density is exactly conserved
 * between injection and dissipation; smoke cannot leak out of the box. The
 * explicit scheme is stable while
 * {@code dt * (|wind.x| / hx + (|wind.y| + buoyancy * maximumTemperature) / hy
 * + |wind.z| / hz) <= 1}, which this constructor enforces. Every value is
 * validated at construction instead of being silently clamped.</p>
 *
 * <p>{@link #disabled()} is the exact no-op configuration: while disabled no
 * simulation, texture, shader or consumer pass exists. Vector components and
 * the source list return defensive copies.</p>
 */
public record SmokeSimulationSettings(
        boolean enabled,
        int gridWidth,
        int gridHeight,
        int gridDepth,
        Vector3f boundsCenter,
        Vector3f boundsHalfExtent,
        float timeStepSeconds,
        Vector3f windVelocity,
        float buoyancy,
        float maximumTemperature,
        float densityDissipation,
        float temperatureDissipation,
        int maximumStepsPerFrame,
        List<Source> sources) {
    /** Smallest accepted grid extent per axis. */
    public static final int MINIMUM_GRID_EXTENT = 8;
    /** Largest accepted grid extent per axis. */
    public static final int MAXIMUM_GRID_EXTENT = 64;
    /** Largest accepted absolute world coordinate of the grid bounds. */
    public static final float MAXIMUM_ABSOLUTE_COORDINATE = 100_000.0f;
    /** Smallest accepted grid half extent per axis in world metres. */
    public static final float MINIMUM_HALF_EXTENT = 1.0e-2f;
    /** Largest accepted grid half extent per axis in world metres. */
    public static final float MAXIMUM_HALF_EXTENT = 10_000.0f;
    /** Smallest accepted fixed-time-step duration in seconds. */
    public static final float MINIMUM_TIME_STEP = 1.0e-4f;
    /** Largest accepted fixed-time-step duration in seconds. */
    public static final float MAXIMUM_TIME_STEP = 0.1f;
    /** Largest accepted wind-speed component in metres per second. */
    public static final float MAXIMUM_WIND_SPEED = 64.0f;
    /** Largest accepted buoyancy coefficient. */
    public static final float MAXIMUM_BUOYANCY = 64.0f;
    /** Largest accepted temperature magnitude. */
    public static final float MAXIMUM_TEMPERATURE = 64.0f;
    /** Largest accepted dissipation/cooling rate in inverse seconds. */
    public static final float MAXIMUM_DISSIPATION = 16.0f;
    /** Smallest accepted per-frame step cap. */
    public static final int MINIMUM_STEPS_PER_FRAME = 1;
    /** Largest accepted per-frame step cap. */
    public static final int MAXIMUM_STEPS_PER_FRAME = 8;
    /** Largest number of injection sources. */
    public static final int MAXIMUM_SOURCES = 4;
    /** Largest accepted source radius in world metres. */
    public static final float MAXIMUM_SOURCE_RADIUS = 10_000.0f;
    /** Largest accepted density injection rate in density units per second. */
    public static final float MAXIMUM_DENSITY_RATE = 64.0f;
    /** Largest accepted temperature injection rate in temperature units per second. */
    public static final float MAXIMUM_TEMPERATURE_RATE = 64.0f;
    /** Largest accepted source end time in seconds. */
    public static final float MAXIMUM_SOURCE_TIME = 3_600.0f;
    /** Largest fixed frame delta consumed by one {@code advance} call in seconds. */
    public static final float MAXIMUM_FRAME_DELTA = 0.25f;

    private static final SmokeSimulationSettings DISABLED = new SmokeSimulationSettings(
            false, 32, 32, 32, new Vector3f(), new Vector3f(4.0f),
            1.0f / 60.0f, new Vector3f(), 1.0f, 4.0f, 1.0f, 2.0f, 4, List.of());

    public SmokeSimulationSettings {
        boundsCenter = coordinate(boundsCenter, "boundsCenter");
        boundsHalfExtent = coordinate(boundsHalfExtent, "boundsHalfExtent");
        if (gridWidth < MINIMUM_GRID_EXTENT || gridWidth > MAXIMUM_GRID_EXTENT
                || gridHeight < MINIMUM_GRID_EXTENT || gridHeight > MAXIMUM_GRID_EXTENT
                || gridDepth < MINIMUM_GRID_EXTENT || gridDepth > MAXIMUM_GRID_EXTENT) {
            throw new IllegalArgumentException("grid extents must be in ["
                    + MINIMUM_GRID_EXTENT + ", " + MAXIMUM_GRID_EXTENT + "] per axis");
        }
        if (boundsHalfExtent.x < MINIMUM_HALF_EXTENT || boundsHalfExtent.y < MINIMUM_HALF_EXTENT
                || boundsHalfExtent.z < MINIMUM_HALF_EXTENT
                || boundsHalfExtent.x > MAXIMUM_HALF_EXTENT
                || boundsHalfExtent.y > MAXIMUM_HALF_EXTENT
                || boundsHalfExtent.z > MAXIMUM_HALF_EXTENT) {
            throw new IllegalArgumentException("boundsHalfExtent components must be in ["
                    + MINIMUM_HALF_EXTENT + ", " + MAXIMUM_HALF_EXTENT + "]");
        }
        if (!finite(timeStepSeconds) || timeStepSeconds < MINIMUM_TIME_STEP
                || timeStepSeconds > MAXIMUM_TIME_STEP) {
            throw new IllegalArgumentException("timeStepSeconds must be finite and in ["
                    + MINIMUM_TIME_STEP + ", " + MAXIMUM_TIME_STEP + "]");
        }
        Objects.requireNonNull(windVelocity, "windVelocity");
        if (!windVelocity.isFinite()
                || Math.abs(windVelocity.x) > MAXIMUM_WIND_SPEED
                || Math.abs(windVelocity.y) > MAXIMUM_WIND_SPEED
                || Math.abs(windVelocity.z) > MAXIMUM_WIND_SPEED) {
            throw new IllegalArgumentException("windVelocity components must be finite and within ±"
                    + MAXIMUM_WIND_SPEED);
        }
        windVelocity = new Vector3f(windVelocity);
        if (!finite(buoyancy) || buoyancy < 0.0f || buoyancy > MAXIMUM_BUOYANCY) {
            throw new IllegalArgumentException("buoyancy must be finite and in [0, "
                    + MAXIMUM_BUOYANCY + "]");
        }
        if (!finite(maximumTemperature) || maximumTemperature <= 0.0f
                || maximumTemperature > MAXIMUM_TEMPERATURE) {
            throw new IllegalArgumentException("maximumTemperature must be finite and in (0, "
                    + MAXIMUM_TEMPERATURE + "]");
        }
        if (!finite(densityDissipation) || densityDissipation < 0.0f
                || densityDissipation > MAXIMUM_DISSIPATION) {
            throw new IllegalArgumentException("densityDissipation must be finite and in [0, "
                    + MAXIMUM_DISSIPATION + "] per second");
        }
        if (!finite(temperatureDissipation) || temperatureDissipation < 0.0f
                || temperatureDissipation > MAXIMUM_DISSIPATION) {
            throw new IllegalArgumentException("temperatureDissipation must be finite and in [0, "
                    + MAXIMUM_DISSIPATION + "] per second");
        }
        if (maximumStepsPerFrame < MINIMUM_STEPS_PER_FRAME
                || maximumStepsPerFrame > MAXIMUM_STEPS_PER_FRAME) {
            throw new IllegalArgumentException("maximumStepsPerFrame must be in ["
                    + MINIMUM_STEPS_PER_FRAME + ", " + MAXIMUM_STEPS_PER_FRAME + "]");
        }
        Objects.requireNonNull(sources, "sources");
        if (sources.size() > MAXIMUM_SOURCES) {
            throw new IllegalArgumentException("at most " + MAXIMUM_SOURCES
                    + " injection sources are supported");
        }
        List<Source> copiedSources = List.copyOf(sources);
        for (Source source : copiedSources) {
            if (source.densityRate() == 0.0f && source.temperatureRate() == 0.0f) {
                continue;
            }
            requireInside(source, boundsCenter, boundsHalfExtent);
        }
        sources = copiedSources;
        double cellX = 2.0 * boundsHalfExtent.x / gridWidth;
        double cellY = 2.0 * boundsHalfExtent.y / gridHeight;
        double cellZ = 2.0 * boundsHalfExtent.z / gridDepth;
        double speedX = Math.abs(windVelocity.x);
        double speedY = Math.abs(windVelocity.y) + (double) buoyancy * maximumTemperature;
        double speedZ = Math.abs(windVelocity.z);
        double cfl = timeStepSeconds * (speedX / cellX + speedY / cellY + speedZ / cellZ);
        if (!(cfl <= 1.0)) {
            throw new IllegalArgumentException("timeStepSeconds " + timeStepSeconds
                    + " violates the explicit upwind CFL bound (" + cfl + " > 1); the largest"
                    + " accepted step is " + maximumStableTimeStepSeconds(boundsHalfExtent,
                    gridWidth, gridHeight, gridDepth, windVelocity, buoyancy, maximumTemperature));
        }
    }

    /** Exact no-op value: no simulation, GPU resource or consumer pass exists while disabled. */
    public static SmokeSimulationSettings disabled() {
        return DISABLED;
    }

    /** @return true when the simulation and renderer should exist */
    public boolean active() {
        return enabled;
    }

    /** @return a bounded copy with only the enabled flag changed */
    public SmokeSimulationSettings withEnabled(boolean value) {
        return new SmokeSimulationSettings(value, gridWidth, gridHeight, gridDepth,
                new Vector3f(boundsCenter), new Vector3f(boundsHalfExtent), timeStepSeconds,
                new Vector3f(windVelocity), buoyancy, maximumTemperature, densityDissipation,
                temperatureDissipation, maximumStepsPerFrame, sources);
    }

    /** @return a defensive copy of the grid center */
    @Override
    public Vector3f boundsCenter() {
        return new Vector3f(boundsCenter);
    }

    /** @return a defensive copy of the grid half extent */
    @Override
    public Vector3f boundsHalfExtent() {
        return new Vector3f(boundsHalfExtent);
    }

    /** @return a defensive copy of the uniform wind velocity */
    @Override
    public Vector3f windVelocity() {
        return new Vector3f(windVelocity);
    }

    /** @return an immutable copy of the injection sources */
    @Override
    public List<Source> sources() {
        return sources;
    }

    Vector3f boundsCenterInternal() {
        return boundsCenter;
    }

    Vector3f boundsHalfExtentInternal() {
        return boundsHalfExtent;
    }

    /** @return per-axis cell size in world metres */
    public Vector3f cellSize() {
        return new Vector3f(2.0f * boundsHalfExtent.x / gridWidth,
                2.0f * boundsHalfExtent.y / gridHeight,
                2.0f * boundsHalfExtent.z / gridDepth);
    }

    /** @return the smallest world-space cell edge in metres */
    public float minimumCellSize() {
        Vector3f size = cellSize();
        return Math.min(size.x, Math.min(size.y, size.z));
    }

    /** @return the world-space minimum corner of the grid bounds */
    public Vector3f gridMinimum() {
        return new Vector3f(boundsCenter).sub(boundsHalfExtent);
    }

    /** @return the total number of cells in the density field */
    public int cellCount() {
        return Math.multiplyExact(Math.multiplyExact(gridWidth, gridHeight), gridDepth);
    }

    /** @return estimated resident bytes of the two ping-pong RGBA16F fields */
    public long estimatedBytes() {
        return 2L * Texture3D.estimatedByteSize(gridWidth, gridHeight, gridDepth,
                RenderFormat.RGBA16F);
    }

    /**
     * Largest fixed step this configuration can accept without violating the
     * explicit upwind CFL bound.
     */
    public float maximumStableTimeStepSeconds() {
        return maximumStableTimeStepSeconds(boundsHalfExtent, gridWidth, gridHeight, gridDepth,
                windVelocity, buoyancy, maximumTemperature);
    }

    /** GL-free helper shared by validation, tests and the technical card. */
    static float maximumStableTimeStepSeconds(Vector3f halfExtent, int gridWidth, int gridHeight,
                                              int gridDepth, Vector3f wind, float buoyancy,
                                              float maximumTemperature) {
        double cellX = 2.0 * halfExtent.x / gridWidth;
        double cellY = 2.0 * halfExtent.y / gridHeight;
        double cellZ = 2.0 * halfExtent.z / gridDepth;
        double rate = Math.abs(wind.x) / cellX
                + (Math.abs(wind.y) + (double) buoyancy * maximumTemperature) / cellY
                + Math.abs(wind.z) / cellZ;
        if (!(rate > 0.0) || !Double.isFinite(rate)) {
            return MAXIMUM_TIME_STEP;
        }
        double raw = 1.0 / rate;
        if (raw >= MAXIMUM_TIME_STEP) {
            return MAXIMUM_TIME_STEP;
        }
        float stable = (float) raw;
        return stable > 0.0f ? Math.nextDown(stable) : stable;
    }

    private static void requireInside(Source source, Vector3f center, Vector3f halfExtent) {
        Vector3f position = source.centerInternal();
        float radius = source.radius();
        for (int axis = 0; axis < 3; axis++) {
            float value = axis == 0 ? position.x : axis == 1 ? position.y : position.z;
            float bound = axis == 0 ? halfExtent.x : axis == 1 ? halfExtent.y : halfExtent.z;
            float gridCenter = axis == 0 ? center.x : axis == 1 ? center.y : center.z;
            if (value - radius < gridCenter - bound - 1.0e-5f
                    || value + radius > gridCenter + bound + 1.0e-5f) {
                throw new IllegalArgumentException("source center/radius must lie inside the grid"
                        + " bounds on every axis");
            }
        }
    }

    private static Vector3f coordinate(Vector3f value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isFinite() || Math.abs(value.x) > MAXIMUM_ABSOLUTE_COORDINATE
                || Math.abs(value.y) > MAXIMUM_ABSOLUTE_COORDINATE
                || Math.abs(value.z) > MAXIMUM_ABSOLUTE_COORDINATE) {
            throw new IllegalArgumentException(name + " must be finite and within ±"
                    + MAXIMUM_ABSOLUTE_COORDINATE);
        }
        return new Vector3f(value);
    }

    private static boolean finite(float value) {
        return Float.isFinite(value);
    }

    /**
     * One bounded injection source. The sphere described by {@code center} and
     * {@code radius} must be fully inside the grid bounds (when either rate is
     * non-zero), and injection is active for simulation times in
     * {@code [startSeconds, endSeconds)}.
     */
    public record Source(
            Vector3f center,
            float radius,
            float densityRate,
            float temperatureRate,
            float startSeconds,
            float endSeconds) {
        public Source {
            center = coordinate(center, "source center");
            if (!finite(radius) || radius < 1.0e-3f || radius > MAXIMUM_SOURCE_RADIUS) {
                throw new IllegalArgumentException("source radius must be finite and in [1e-3, "
                        + MAXIMUM_SOURCE_RADIUS + "]");
            }
            if (!finite(densityRate) || densityRate < 0.0f || densityRate > MAXIMUM_DENSITY_RATE) {
                throw new IllegalArgumentException("source densityRate must be finite and in [0, "
                        + MAXIMUM_DENSITY_RATE + "]");
            }
            if (!finite(temperatureRate) || temperatureRate < 0.0f
                    || temperatureRate > MAXIMUM_TEMPERATURE_RATE) {
                throw new IllegalArgumentException("source temperatureRate must be finite and in"
                        + " [0, " + MAXIMUM_TEMPERATURE_RATE + "]");
            }
            if (!finite(startSeconds) || startSeconds < 0.0f
                    || startSeconds > MAXIMUM_SOURCE_TIME) {
                throw new IllegalArgumentException("source startSeconds must be finite and in [0, "
                        + MAXIMUM_SOURCE_TIME + "]");
            }
            if (!finite(endSeconds) || endSeconds <= startSeconds
                    || endSeconds > MAXIMUM_SOURCE_TIME) {
                throw new IllegalArgumentException("source endSeconds must be finite, greater than"
                        + " startSeconds and at most " + MAXIMUM_SOURCE_TIME);
            }
        }

        /** @return true while the half-open simulation-time window contains {@code timeSeconds} */
        public boolean activeAt(double timeSeconds) {
            return timeSeconds >= startSeconds && timeSeconds < endSeconds;
        }

        /** @return a defensive copy of the source center */
        @Override
        public Vector3f center() {
            return new Vector3f(center);
        }

        Vector3f centerInternal() {
            return center;
        }
    }
}
