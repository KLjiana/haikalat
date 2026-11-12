package com.kaleblangley.haikalat.subsystems.render3d.ddgi;

import org.joml.Vector3f;

import java.util.Objects;

/**
 * Bounded configuration of the first dynamic diffuse global illumination probe
 * grid (DDGI, roadmap #44).
 *
 * <p>The probes are a regular axis-aligned grid. Probe centres sit at
 * {@code gridOrigin + (index + 0.5) * spacingMetres}; the probe grid bounds are
 * {@code [gridOrigin, gridOrigin + probeCount * spacing]}. Every probe owns six
 * rasterized depth faces and one six-face irradiance tile; the layout constants
 * below are frozen by {@code ddgi-common.glsl} and the shader contract test.</p>
 *
 * <p>This is a dynamic implementation: every update rasterizes the opaque scene
 * depth from the probe centre and relights the probe from that depth, so moving
 * geometry changes the probes. It is deliberately not a static lightmap bake.
 * Radiance is a bounded analytic estimate (one main directional light, a
 * scene-wide albedo proxy, a constant sky term and the previous probe grid for
 * one bounce); it does not consume the PBR material pass, emissive materials,
 * the clustered light table or shadow maps. See
 * {@code docs/planning/v0.28.1-s5-ddgi.md} for the full limits.</p>
 *
 * <p>{@link #disabled()} is the exact no-op value: no depth atlas, irradiance
 * atlas, program, mesh or draw exists while DDGI is disabled.</p>
 */
public record DdgiSettings(
        boolean enabled,
        float intensity,
        float surfaceAlbedo,
        Vector3f skyRadiance,
        float gridOriginX,
        float gridOriginY,
        float gridOriginZ,
        float spacingMetres,
        int probesX,
        int probesY,
        int probesZ,
        int updateBudget,
        float hysteresis,
        float maximumRayDistance,
        int raySteps,
        int depthFaceResolution,
        float borderFadeMetres,
        float maximumIrradiance) {
    /** Largest supported probe count along one grid axis. */
    public static final int MAXIMUM_GRID_AXIS = 8;
    /** Largest supported total probe count. */
    public static final int MAXIMUM_PROBES = 64;
    /** Largest supported number of probes refreshed per frame. */
    public static final int MAXIMUM_UPDATE_BUDGET = 8;
    /** Smallest supported probe spacing in world metres. */
    public static final float MINIMUM_SPACING = 0.05f;
    /** Largest supported probe spacing in world metres. */
    public static final float MAXIMUM_SPACING = 100.0f;
    /** Largest supported absolute grid origin component in world metres. */
    public static final float MAXIMUM_ABSOLUTE_ORIGIN = 10000.0f;
    /** Largest supported indirect intensity multiplier. */
    public static final float MAXIMUM_INTENSITY = 8.0f;
    /** Largest supported scene-wide albedo proxy. */
    public static final float MAXIMUM_ALBEDO = 1.0f;
    /** Largest supported sky radiance component. */
    public static final float MAXIMUM_RADIANCE_COMPONENT = 100.0f;
    /** Largest supported main-light radiance component accepted per update. */
    public static final float MAXIMUM_LIGHT_RADIANCE_COMPONENT = 100.0f;
    /** Largest accepted history weight of the temporal blend. */
    public static final float MAXIMUM_HYSTERESIS = 0.98f;
    /** Smallest supported ray length in world metres. */
    public static final float MINIMUM_RAY_DISTANCE = 0.1f;
    /** Largest supported ray length in world metres. */
    public static final float MAXIMUM_RAY_DISTANCE = 1000.0f;
    /** Smallest supported ray-march step count. */
    public static final int MINIMUM_RAY_STEPS = 4;
    /** Largest supported ray-march step count. */
    public static final int MAXIMUM_RAY_STEPS = 64;
    /** Smallest supported probe depth-face edge in texels. */
    public static final int MINIMUM_DEPTH_FACE_RESOLUTION = 4;
    /** Largest supported probe depth-face edge in texels. */
    public static final int MAXIMUM_DEPTH_FACE_RESOLUTION = 32;
    /** Supported border fade distance in world metres. */
    public static final float MAXIMUM_BORDER_FADE = 100.0f;
    /** Largest accepted stored incident radiance. */
    public static final float MAXIMUM_IRRADIANCE = 1000.0f;
    /** Largest supported edge of one internally allocated atlas texture. */
    public static final int MAXIMUM_ATLAS_EDGE = 16384;

    /** Cube faces captured per probe; frozen by the shader atlas layout. */
    public static final int FACE_COUNT = 6;
    /** Columns of the six-face irradiance tile. */
    public static final int IRRADIANCE_TILE_COLUMNS = 3;
    /** Rows of the six-face irradiance tile. */
    public static final int IRRADIANCE_TILE_ROWS = 2;
    /**
     * Edge in texels of one irradiance face. Fixed instead of configurable so
     * the atlas layout stays one frozen shader contract; quality is tuned
     * through {@link #depthFaceResolution()}, {@link #raySteps()} and
     * {@link #updateBudget()}.
     */
    public static final int IRRADIANCE_FACE_RESOLUTION = 4;
    /** Edge in texels of one probe's six-face irradiance tile. */
    public static final int IRRADIANCE_TILE_WIDTH =
            IRRADIANCE_TILE_COLUMNS * IRRADIANCE_FACE_RESOLUTION;
    /** Height in texels of one probe's six-face irradiance tile. */
    public static final int IRRADIANCE_TILE_HEIGHT =
            IRRADIANCE_TILE_ROWS * IRRADIANCE_FACE_RESOLUTION;

    private static final DdgiSettings DISABLED = new DdgiSettings(
            false,
            1.0f,
            0.8f,
            new Vector3f(0.08f, 0.09f, 0.11f),
            -8.0f,
            -2.0f,
            -8.0f,
            4.0f,
            3,
            2,
            3,
            2,
            0.85f,
            12.0f,
            24,
            16,
            1.0f,
            32.0f);

    public DdgiSettings {
        if (!finite(intensity) || intensity < 0.0f || intensity > MAXIMUM_INTENSITY) {
            throw new IllegalArgumentException("intensity must be finite and in [0, "
                    + MAXIMUM_INTENSITY + "]");
        }
        if (!finite(surfaceAlbedo) || surfaceAlbedo < 0.0f || surfaceAlbedo > MAXIMUM_ALBEDO) {
            throw new IllegalArgumentException("surfaceAlbedo must be finite and in [0, "
                    + MAXIMUM_ALBEDO + "]");
        }
        skyRadiance = radiance(skyRadiance, "skyRadiance", MAXIMUM_RADIANCE_COMPONENT);
        requireOrigin(gridOriginX, "gridOriginX");
        requireOrigin(gridOriginY, "gridOriginY");
        requireOrigin(gridOriginZ, "gridOriginZ");
        if (!finite(spacingMetres) || spacingMetres < MINIMUM_SPACING
                || spacingMetres > MAXIMUM_SPACING) {
            throw new IllegalArgumentException("spacingMetres must be finite and in ["
                    + MINIMUM_SPACING + ", " + MAXIMUM_SPACING + "]");
        }
        probeAxis(probesX, "probesX");
        probeAxis(probesY, "probesY");
        probeAxis(probesZ, "probesZ");
        long probeCount = (long) probesX * probesY * probesZ;
        if (probeCount > MAXIMUM_PROBES) {
            throw new IllegalArgumentException("probe count " + probeCount + " exceeds "
                    + MAXIMUM_PROBES);
        }
        if (updateBudget < 1 || updateBudget > MAXIMUM_UPDATE_BUDGET) {
            throw new IllegalArgumentException("updateBudget must be in [1, "
                    + MAXIMUM_UPDATE_BUDGET + "]");
        }
        if (!finite(hysteresis) || hysteresis < 0.0f || hysteresis > MAXIMUM_HYSTERESIS) {
            throw new IllegalArgumentException("hysteresis must be finite and in [0, "
                    + MAXIMUM_HYSTERESIS + "]");
        }
        if (!finite(maximumRayDistance) || maximumRayDistance < MINIMUM_RAY_DISTANCE
                || maximumRayDistance > MAXIMUM_RAY_DISTANCE) {
            throw new IllegalArgumentException("maximumRayDistance must be finite and in ["
                    + MINIMUM_RAY_DISTANCE + ", " + MAXIMUM_RAY_DISTANCE + "]");
        }
        if (maximumRayDistance < spacingMetres) {
            throw new IllegalArgumentException(
                    "maximumRayDistance must reach at least one probe spacing");
        }
        if (raySteps < MINIMUM_RAY_STEPS || raySteps > MAXIMUM_RAY_STEPS) {
            throw new IllegalArgumentException("raySteps must be in [" + MINIMUM_RAY_STEPS
                    + ", " + MAXIMUM_RAY_STEPS + "]");
        }
        if (depthFaceResolution < MINIMUM_DEPTH_FACE_RESOLUTION
                || depthFaceResolution > MAXIMUM_DEPTH_FACE_RESOLUTION) {
            throw new IllegalArgumentException("depthFaceResolution must be in ["
                    + MINIMUM_DEPTH_FACE_RESOLUTION + ", " + MAXIMUM_DEPTH_FACE_RESOLUTION + "]");
        }
        if (!finite(borderFadeMetres) || borderFadeMetres < 0.0f
                || borderFadeMetres > MAXIMUM_BORDER_FADE) {
            throw new IllegalArgumentException("borderFadeMetres must be finite and in [0, "
                    + MAXIMUM_BORDER_FADE + "]");
        }
        if (!finite(maximumIrradiance) || maximumIrradiance < 0.0f
                || maximumIrradiance > MAXIMUM_IRRADIANCE) {
            throw new IllegalArgumentException("maximumIrradiance must be finite and in [0, "
                    + MAXIMUM_IRRADIANCE + "]");
        }
        long depthAtlasWidth = probeCount * FACE_COUNT * depthFaceResolution;
        if (depthAtlasWidth > MAXIMUM_ATLAS_EDGE) {
            throw new IllegalArgumentException("probe depth atlas width " + depthAtlasWidth
                    + " exceeds " + MAXIMUM_ATLAS_EDGE);
        }
        long irradianceAtlasWidth = probeCount * IRRADIANCE_TILE_WIDTH;
        if (irradianceAtlasWidth > MAXIMUM_ATLAS_EDGE) {
            throw new IllegalArgumentException("probe irradiance atlas width "
                    + irradianceAtlasWidth + " exceeds " + MAXIMUM_ATLAS_EDGE);
        }
    }

    /** Exact no-op value used when DDGI was not requested. */
    public static DdgiSettings disabled() {
        return DISABLED;
    }

    /** @return true when probe resources, updates and the indirect draw should exist */
    public boolean active() {
        return enabled;
    }

    /** @return product of the three grid dimensions */
    public int probeCount() {
        return probesX * probesY * probesZ;
    }

    /** @return world-space width of the whole probe grid in metres */
    public float gridWidth() {
        return probesX * spacingMetres;
    }

    /** @return world-space height of the whole probe grid in metres */
    public float gridHeight() {
        return probesY * spacingMetres;
    }

    /** @return world-space depth of the whole probe grid in metres */
    public float gridDepth() {
        return probesZ * spacingMetres;
    }

    /** @return inclusive lower grid bound on X in world metres */
    public float gridMinimumX() {
        return gridOriginX;
    }

    /** @return inclusive lower grid bound on Y in world metres */
    public float gridMinimumY() {
        return gridOriginY;
    }

    /** @return inclusive lower grid bound on Z in world metres */
    public float gridMinimumZ() {
        return gridOriginZ;
    }

    /** @return inclusive upper grid bound on X in world metres */
    public float gridMaximumX() {
        return gridOriginX + gridWidth();
    }

    /** @return inclusive upper grid bound on Y in world metres */
    public float gridMaximumY() {
        return gridOriginY + gridHeight();
    }

    /** @return inclusive upper grid bound on Z in world metres */
    public float gridMaximumZ() {
        return gridOriginZ + gridDepth();
    }

    /** @return true when the point is inside the inclusive probe grid bounds */
    public boolean contains(float x, float y, float z) {
        return finite(x) && finite(y) && finite(z)
                && x >= gridOriginX && x <= gridMaximumX()
                && y >= gridOriginY && y <= gridMaximumY()
                && z >= gridOriginZ && z <= gridMaximumZ();
    }

    /**
     * Bounded border weight used by the indirect query. It is 0 outside the
     * probe grid bounds and rises linearly to 1 at {@link #borderFadeMetres()}
     * inside them, so probe influence cannot leak past the grid.
     *
     * @return weight in {@code [0, 1]}
     */
    public float borderWeight(float x, float y, float z) {
        if (!contains(x, y, z)) return 0.0f;
        if (borderFadeMetres <= 0.0f) return 1.0f;
        float distance = Math.min(
                Math.min(x - gridOriginX, gridMaximumX() - x),
                Math.min(Math.min(y - gridOriginY, gridMaximumY() - y),
                        Math.min(z - gridOriginZ, gridMaximumZ() - z)));
        return Math.max(0.0f, Math.min(1.0f, distance / borderFadeMetres));
    }

    /** @return probe irradiance atlas width in texels */
    public int irradianceAtlasWidth() {
        return probeCount() * IRRADIANCE_TILE_WIDTH;
    }

    /** @return probe depth atlas width in texels */
    public int probeDepthAtlasWidth() {
        return probeCount() * FACE_COUNT * depthFaceResolution;
    }

    /** @return probe depth atlas height in texels */
    public int probeDepthAtlasHeight() {
        return depthFaceResolution;
    }

    /** @return defensive copy of the sky radiance */
    @Override
    public Vector3f skyRadiance() {
        return new Vector3f(skyRadiance);
    }

    /** Zero-allocation sky radiance getter for render recording. */
    public void skyRadianceTo(Vector3f destination) {
        Objects.requireNonNull(destination, "destination").set(skyRadiance);
    }

    /** @return a builder pre-populated with this value's fields */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Bounded copy of this value with only the enabled flag changed. */
    public DdgiSettings withEnabled(boolean value) {
        return new DdgiSettings(value, intensity, surfaceAlbedo, new Vector3f(skyRadiance),
                gridOriginX, gridOriginY, gridOriginZ, spacingMetres, probesX, probesY, probesZ,
                updateBudget, hysteresis, maximumRayDistance, raySteps, depthFaceResolution,
                borderFadeMetres, maximumIrradiance);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder mirroring the record's validated ranges. */
    public static final class Builder {
        private boolean enabled;
        private float intensity = 1.0f;
        private float surfaceAlbedo = 0.8f;
        private Vector3f skyRadiance = new Vector3f(0.08f, 0.09f, 0.11f);
        private float gridOriginX = -8.0f;
        private float gridOriginY = -2.0f;
        private float gridOriginZ = -8.0f;
        private float spacingMetres = 4.0f;
        private int probesX = 3;
        private int probesY = 2;
        private int probesZ = 3;
        private int updateBudget = 2;
        private float hysteresis = 0.85f;
        private float maximumRayDistance = 12.0f;
        private int raySteps = 24;
        private int depthFaceResolution = 16;
        private float borderFadeMetres = 1.0f;
        private float maximumIrradiance = 32.0f;

        private Builder() {
        }

        private Builder(DdgiSettings source) {
            enabled = source.enabled;
            intensity = source.intensity;
            surfaceAlbedo = source.surfaceAlbedo;
            skyRadiance = new Vector3f(source.skyRadiance);
            gridOriginX = source.gridOriginX;
            gridOriginY = source.gridOriginY;
            gridOriginZ = source.gridOriginZ;
            spacingMetres = source.spacingMetres;
            probesX = source.probesX;
            probesY = source.probesY;
            probesZ = source.probesZ;
            updateBudget = source.updateBudget;
            hysteresis = source.hysteresis;
            maximumRayDistance = source.maximumRayDistance;
            raySteps = source.raySteps;
            depthFaceResolution = source.depthFaceResolution;
            borderFadeMetres = source.borderFadeMetres;
            maximumIrradiance = source.maximumIrradiance;
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder intensity(float value) {
            intensity = value;
            return this;
        }

        public Builder surfaceAlbedo(float value) {
            surfaceAlbedo = value;
            return this;
        }

        public Builder skyRadiance(float red, float green, float blue) {
            skyRadiance = new Vector3f(red, green, blue);
            return this;
        }

        public Builder gridOrigin(float x, float y, float z) {
            gridOriginX = x;
            gridOriginY = y;
            gridOriginZ = z;
            return this;
        }

        public Builder spacingMetres(float value) {
            spacingMetres = value;
            return this;
        }

        public Builder probes(int x, int y, int z) {
            probesX = x;
            probesY = y;
            probesZ = z;
            return this;
        }

        public Builder updateBudget(int value) {
            updateBudget = value;
            return this;
        }

        public Builder hysteresis(float value) {
            hysteresis = value;
            return this;
        }

        public Builder maximumRayDistance(float value) {
            maximumRayDistance = value;
            return this;
        }

        public Builder raySteps(int value) {
            raySteps = value;
            return this;
        }

        public Builder depthFaceResolution(int value) {
            depthFaceResolution = value;
            return this;
        }

        public Builder borderFadeMetres(float value) {
            borderFadeMetres = value;
            return this;
        }

        public Builder maximumIrradiance(float value) {
            maximumIrradiance = value;
            return this;
        }

        public DdgiSettings build() {
            return new DdgiSettings(enabled, intensity, surfaceAlbedo, skyRadiance,
                    gridOriginX, gridOriginY, gridOriginZ, spacingMetres, probesX, probesY,
                    probesZ, updateBudget, hysteresis, maximumRayDistance, raySteps,
                    depthFaceResolution, borderFadeMetres, maximumIrradiance);
        }
    }

    private static void requireOrigin(float value, String name) {
        if (!finite(value) || Math.abs(value) > MAXIMUM_ABSOLUTE_ORIGIN) {
            throw new IllegalArgumentException(name + " must be finite and in [-"
                    + MAXIMUM_ABSOLUTE_ORIGIN + ", " + MAXIMUM_ABSOLUTE_ORIGIN + "]");
        }
    }

    private static void probeAxis(int value, String name) {
        if (value < 1 || value > MAXIMUM_GRID_AXIS) {
            throw new IllegalArgumentException(name + " must be in [1, " + MAXIMUM_GRID_AXIS + "]");
        }
    }

    private static Vector3f radiance(Vector3f value, String name, float maximum) {
        Objects.requireNonNull(value, name);
        if (!finite(value.x) || !finite(value.y) || !finite(value.z)
                || value.x < 0.0f || value.y < 0.0f || value.z < 0.0f
                || value.x > maximum || value.y > maximum || value.z > maximum) {
            throw new IllegalArgumentException(name + " must be finite and in [0, "
                    + maximum + "]");
        }
        return new Vector3f(value);
    }

    private static boolean finite(float value) {
        return Float.isFinite(value);
    }
}
