package com.kaleblangley.haikalat.subsystems.render3d.water;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

/**
 * Bounded configuration of the first production water surface.
 *
 * <p>World scale is explicit: every distance is in world units, interpreted as
 * metres to match the rest of Render3D.  The surface is one finite horizontal
 * rectangle on the plane {@code y == level}; {@code minX/minZ/maxX/maxZ} are
 * world-space metres.  No hidden clock or random seed is used: wave normals are
 * a pure function of the surface position, the accumulated frame time and these
 * values.</p>
 *
 * <p>The first version deliberately excludes full underwater rendering,
 * refraction and fluid simulation.  It produces a surface colour from a
 * depth-based absorption approximation, a shoreline fade and a Fresnel-weighted
 * IBL/sky reflection; the shader never writes depth and never reads scene colour
 * outside its own finite rectangle.</p>
 *
 * <p>{@link #disabled()} is the exact no-op value: no pass, shader, attachment
 * or draw is created while water is disabled.</p>
 */
public record WaterSurfaceSettings(
        boolean enabled,
        float level,
        float minX,
        float minZ,
        float maxX,
        float maxZ,
        float waveWavelength,
        float waveSpeed,
        float waveAmplitude,
        float waveDirectionRadians,
        Vector3f waterColor,
        float absorptionDensity,
        float shoreFadeDistance,
        Vector3f reflectionFallback) {
    /** Largest accepted absolute water-plane height in world metres. */
    public static final float MAXIMUM_ABSOLUTE_LEVEL = 10000.0f;
    /** Smallest accepted bound extent in world metres. */
    public static final float MINIMUM_EXTENT = 0.05f;
    /** Largest accepted bound extent in world metres. */
    public static final float MAXIMUM_EXTENT = 20000.0f;
    /** Smallest accepted wave wavelength in world metres. */
    public static final float MINIMUM_WAVELENGTH = 0.05f;
    /** Largest accepted wave wavelength in world metres. */
    public static final float MAXIMUM_WAVELENGTH = 100.0f;
    /** Largest accepted absolute wave phase speed in metres per second. */
    public static final float MAXIMUM_WAVE_SPEED = 8.0f;
    /** Largest accepted dimensionless wave slope amplitude. */
    public static final float MAXIMUM_WAVE_AMPLITUDE = 0.35f;
    /** Largest accepted per-channel water absorption density in 1/metre. */
    public static final float MAXIMUM_ABSORPTION_DENSITY = 8.0f;
    /** Largest accepted shoreline fade distance in world metres. */
    public static final float MAXIMUM_SHORE_FADE_DISTANCE = 10.0f;
    /** Largest accepted linear colour component for water and reflection tints. */
    public static final float MAXIMUM_COLOR_COMPONENT = 16.0f;

    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final float SECONDARY_FREQUENCY_SCALE = 1.7f;
    private static final float SECONDARY_WEIGHT = 0.5f;
    private static final float SECONDARY_PHASE = 1.3f;

    public WaterSurfaceSettings {
        if (!finite(level) || Math.abs(level) > MAXIMUM_ABSOLUTE_LEVEL) {
            throw new IllegalArgumentException("level must be finite and in [-"
                    + MAXIMUM_ABSOLUTE_LEVEL + ", " + MAXIMUM_ABSOLUTE_LEVEL + "]");
        }
        validateBounds(minX, minZ, maxX, maxZ);
        if (!finite(waveWavelength) || waveWavelength < MINIMUM_WAVELENGTH
                || waveWavelength > MAXIMUM_WAVELENGTH) {
            throw new IllegalArgumentException("waveWavelength must be finite and in ["
                    + MINIMUM_WAVELENGTH + ", " + MAXIMUM_WAVELENGTH + "]");
        }
        if (!finite(waveSpeed) || Math.abs(waveSpeed) > MAXIMUM_WAVE_SPEED) {
            throw new IllegalArgumentException("waveSpeed must be finite and in [-"
                    + MAXIMUM_WAVE_SPEED + ", " + MAXIMUM_WAVE_SPEED + "]");
        }
        if (!finite(waveAmplitude) || waveAmplitude < 0.0f
                || waveAmplitude > MAXIMUM_WAVE_AMPLITUDE) {
            throw new IllegalArgumentException("waveAmplitude must be finite and in [0, "
                    + MAXIMUM_WAVE_AMPLITUDE + "]");
        }
        if (!finite(waveDirectionRadians)) {
            throw new IllegalArgumentException("waveDirectionRadians must be finite");
        }
        waveDirectionRadians = canonicalAngle(waveDirectionRadians);
        waterColor = color(waterColor, "waterColor");
        reflectionFallback = color(reflectionFallback, "reflectionFallback");
        if (!finite(absorptionDensity) || absorptionDensity < 0.0f
                || absorptionDensity > MAXIMUM_ABSORPTION_DENSITY) {
            throw new IllegalArgumentException("absorptionDensity must be finite and in [0, "
                    + MAXIMUM_ABSORPTION_DENSITY + "]");
        }
        if (!finite(shoreFadeDistance) || shoreFadeDistance < 0.0f
                || shoreFadeDistance > MAXIMUM_SHORE_FADE_DISTANCE) {
            throw new IllegalArgumentException("shoreFadeDistance must be finite and in [0, "
                    + MAXIMUM_SHORE_FADE_DISTANCE + "]");
        }
    }

    /** Exact no-op value used when no water surface was requested. */
    public static WaterSurfaceSettings disabled() {
        return new WaterSurfaceSettings(false, 0.0f, -10.0f, -10.0f, 10.0f, 10.0f,
                8.0f, 0.0f, 0.0f, 0.0f, new Vector3f(0.02f, 0.12f, 0.16f),
                0.0f, 0.0f, new Vector3f(0.35f, 0.5f, 0.65f));
    }

    /** @return true when the surface pass and its draw should exist */
    public boolean active() {
        return enabled;
    }

    /** @return world-space rectangle width in metres */
    public float width() {
        return maxX - minX;
    }

    /** @return world-space rectangle depth in metres */
    public float depth() {
        return maxZ - minZ;
    }

    /**
     * Fraction of the background replaced by the water body at the given
     * vertical thickness (metres).  Mirrors the shader's Beer-Lambert
     * approximation {@code 1 - exp(-density * thickness)}; a zero density is an
     * exact no-op.
     */
    public float absorptionFraction(float thicknessMetres) {
        if (!enabled || !Float.isFinite(thicknessMetres) || thicknessMetres <= 0.0f
                || absorptionDensity == 0.0f) {
            return 0.0f;
        }
        double exponent = -(double) absorptionDensity * Math.min(thicknessMetres, 1.0e6f);
        return (float) (1.0 - Math.exp(exponent));
    }

    /**
     * Shoreline coverage in [0, 1] for the given vertical thickness (metres).
     * Mirrors the shader's {@code smoothstep(0, shoreFadeDistance, thickness)};
     * a zero fade distance is a hard edge.
     */
    public float shoreFraction(float thicknessMetres) {
        if (!enabled || !Float.isFinite(thicknessMetres) || thicknessMetres <= 0.0f) {
            return 0.0f;
        }
        if (shoreFadeDistance == 0.0f) {
            return 1.0f;
        }
        float edge0 = 0.0f;
        float edge1 = shoreFadeDistance;
        float t = Math.min(1.0f, Math.max(0.0f, (thicknessMetres - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    /**
     * Analytic first-order wave normal in world space, the CPU counterpart of
     * the shader's {@code waveNormal()} block.
     *
     * <p>The height field is the deterministic sum of two directional sinusoids
     * (a primary train and a perpendicular secondary train at 1.7x the
     * wavenumber, half the weight and a fixed phase offset).  The normal is
     * {@code normalize(-dH/dx, 1, -dH/dz)} and its {@code y} component is
     * always positive; the function never returns NaN or a zero vector for the
     * accepted settings ranges.</p>
     *
     * @return the world-space unit normal written into {@code destination}
     */
    public Vector3f waveNormal(float x, float z, float timeSeconds, Vector3f destination) {
        Objects.requireNonNull(destination, "destination");
        if (!finite(x) || !finite(z) || !finite(timeSeconds)) {
            throw new IllegalArgumentException("wave normal inputs must be finite");
        }
        float k1 = TWO_PI / waveWavelength;
        float directionX = (float) Math.cos(waveDirectionRadians);
        float directionZ = (float) Math.sin(waveDirectionRadians);
        float k2 = k1 * SECONDARY_FREQUENCY_SCALE;
        float omega1 = k1 * waveSpeed;
        float omega2 = k2 * waveSpeed;
        float phase1 = k1 * (directionX * x + directionZ * z) - omega1 * timeSeconds;
        float phase2 = k2 * (-directionZ * x + directionX * z) - omega2 * timeSeconds
                + SECONDARY_PHASE;
        float primaryCos = (float) Math.cos(phase1);
        float secondaryCos = (float) Math.cos(phase2);
        float gradientX = waveAmplitude * (k1 * directionX * primaryCos
                + SECONDARY_WEIGHT * k2 * -directionZ * secondaryCos);
        float gradientZ = waveAmplitude * (k1 * directionZ * primaryCos
                + SECONDARY_WEIGHT * k2 * directionX * secondaryCos);
        return destination.set(-gradientX, 1.0f, -gradientZ).normalize();
    }

    /** Linear water body colour; returned as a defensive copy. */
    @Override
    public Vector3f waterColor() {
        return new Vector3f(waterColor);
    }

    /** Linear fallback sky tint used when no IBL environment is bound. */
    @Override
    public Vector3f reflectionFallback() {
        return new Vector3f(reflectionFallback);
    }

    /** Zero-allocation water colour getter for render recording. */
    public void waterColorTo(Vector3f destination) {
        Objects.requireNonNull(destination, "destination").set(waterColor);
    }

    /** Zero-allocation reflection fallback getter for render recording. */
    public void reflectionFallbackTo(Vector3f destination) {
        Objects.requireNonNull(destination, "destination").set(reflectionFallback);
    }

    /** Bounded copy of this value with only the enabled flag changed. */
    public WaterSurfaceSettings withEnabled(boolean value) {
        return new WaterSurfaceSettings(value, level, minX, minZ, maxX, maxZ, waveWavelength,
                waveSpeed, waveAmplitude, waveDirectionRadians, new Vector3f(waterColor),
                absorptionDensity, shoreFadeDistance, new Vector3f(reflectionFallback));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder mirroring the record's validated ranges. */
    public static final class Builder {
        private boolean enabled;
        private float level;
        private float minX = -10.0f;
        private float minZ = -10.0f;
        private float maxX = 10.0f;
        private float maxZ = 10.0f;
        private float waveWavelength = 8.0f;
        private float waveSpeed = 0.6f;
        private float waveAmplitude = 0.06f;
        private float waveDirectionRadians;
        private Vector3f waterColor = new Vector3f(0.02f, 0.12f, 0.16f);
        private float absorptionDensity = 0.35f;
        private float shoreFadeDistance = 0.75f;
        private Vector3f reflectionFallback = new Vector3f(0.35f, 0.5f, 0.65f);

        private Builder() {
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder level(float value) {
            level = value;
            return this;
        }

        public Builder bounds(float minimumX, float minimumZ, float maximumX, float maximumZ) {
            minX = minimumX;
            minZ = minimumZ;
            maxX = maximumX;
            maxZ = maximumZ;
            return this;
        }

        public Builder waveWavelength(float value) {
            waveWavelength = value;
            return this;
        }

        public Builder waveSpeed(float value) {
            waveSpeed = value;
            return this;
        }

        public Builder waveAmplitude(float value) {
            waveAmplitude = value;
            return this;
        }

        public Builder waveDirectionRadians(float value) {
            waveDirectionRadians = value;
            return this;
        }

        public Builder waterColor(float red, float green, float blue) {
            waterColor = new Vector3f(red, green, blue);
            return this;
        }

        public Builder absorptionDensity(float value) {
            absorptionDensity = value;
            return this;
        }

        public Builder shoreFadeDistance(float value) {
            shoreFadeDistance = value;
            return this;
        }

        public Builder reflectionFallback(float red, float green, float blue) {
            reflectionFallback = new Vector3f(red, green, blue);
            return this;
        }

        public WaterSurfaceSettings build() {
            return new WaterSurfaceSettings(enabled, level, minX, minZ, maxX, maxZ,
                    waveWavelength, waveSpeed, waveAmplitude, waveDirectionRadians,
                    new Vector3f(waterColor), absorptionDensity, shoreFadeDistance,
                    new Vector3f(reflectionFallback));
        }
    }

    private static void validateBounds(float minX, float minZ, float maxX, float maxZ) {
        requireBound(minX, "minX");
        requireBound(minZ, "minZ");
        requireBound(maxX, "maxX");
        requireBound(maxZ, "maxZ");
        float width = maxX - minX;
        float depth = maxZ - minZ;
        if (!finite(width) || width < MINIMUM_EXTENT || width > MAXIMUM_EXTENT) {
            throw new IllegalArgumentException("water bounds width must be finite and in ["
                    + MINIMUM_EXTENT + ", " + MAXIMUM_EXTENT + "]");
        }
        if (!finite(depth) || depth < MINIMUM_EXTENT || depth > MAXIMUM_EXTENT) {
            throw new IllegalArgumentException("water bounds depth must be finite and in ["
                    + MINIMUM_EXTENT + ", " + MAXIMUM_EXTENT + "]");
        }
    }

    private static void requireBound(float value, String name) {
        if (!finite(value) || Math.abs(value) > MAXIMUM_ABSOLUTE_LEVEL + MAXIMUM_EXTENT) {
            throw new IllegalArgumentException(name + " must be finite and within the world extent");
        }
    }

    private static Vector3f color(Vector3f value, String name) {
        Objects.requireNonNull(value, name);
        if (!finite(value.x) || !finite(value.y) || !finite(value.z)
                || value.x < 0.0f || value.y < 0.0f || value.z < 0.0f
                || value.x > MAXIMUM_COLOR_COMPONENT || value.y > MAXIMUM_COLOR_COMPONENT
                || value.z > MAXIMUM_COLOR_COMPONENT) {
            throw new IllegalArgumentException(name + " must be finite and in [0, "
                    + MAXIMUM_COLOR_COMPONENT + "]");
        }
        return new Vector3f(value);
    }

    private static float canonicalAngle(float value) {
        float result = value % TWO_PI;
        if (result < 0.0f) result += TWO_PI;
        return result;
    }

    private static boolean finite(float value) {
        return Float.isFinite(value);
    }
}
