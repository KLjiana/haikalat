package com.kaleblangley.haikalat.subsystems.render3d.environment;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

/**
 * Physical single-scattering atmosphere and sky-view LUT configuration.
 *
 * <p>Lengths are metres, scattering coefficients are per metre at sea level,
 * and {@code sunIntensityWattsPerSquareMetre} is the top-of-atmosphere solar
 * irradiance used to scale the dimensionless single-scattering integral.  The
 * luminance written by {@link AtmosphereSkyPass} is therefore
 * {@code W / (m^2 sr)} before {@code exposure}, a linear display gain.  The
 * model is intentionally limited to Rayleigh and Mie single scattering; it has
 * no multiple scattering, ozone absorption, weather or surface reflection.</p>
 *
 * <p>{@code sunDirection} follows the engine's directional-light convention: it
 * is the unit direction in which sunlight travels (from the sun toward the
 * scene).  The sun itself therefore lies at {@code -sunDirection}, and its
 * elevation is {@code asin(-sunDirection.y)}.  See the S2 #29 technical card in
 * {@code docs/planning/v0.28.1-s2-atmosphere.md}.</p>
 */
public record AtmosphereSettings(
        float planetRadiusMeters,
        float atmosphereThicknessMeters,
        Vector3f rayleighScatteringPerMeter,
        float rayleighScaleHeightMeters,
        float mieScatteringPerMeter,
        float mieAbsorptionPerMeter,
        float mieScaleHeightMeters,
        float mieAnisotropy,
        Vector3f sunDirection,
        float sunIntensityWattsPerSquareMetre,
        float exposure,
        float sunAngularRadiusRadians,
        float sunDiscIntensity,
        int transmittanceLutWidth,
        int transmittanceLutHeight,
        int skyViewLutWidth,
        int skyViewLutHeight,
        int transmittanceSamples,
        int skyViewSamples) {

    public static final float EARTH_RADIUS_METERS = 6_371_000.0f;
    public static final float EARTH_ATMOSPHERE_THICKNESS_METERS = 100_000.0f;
    public static final float MIN_PLANET_RADIUS_METERS = 100_000.0f;
    public static final float MAX_PLANET_RADIUS_METERS = 1_000_000_000.0f;
    public static final float MIN_ATMOSPHERE_THICKNESS_METERS = 1_000.0f;
    public static final float MAX_ATMOSPHERE_THICKNESS_METERS = 1_000_000.0f;
    public static final float MAX_SCATTERING_PER_METER = 1.0e-3f;
    public static final float MIN_SCALE_HEIGHT_METERS = 100.0f;
    public static final float MAX_SCALE_HEIGHT_METERS = 100_000.0f;
    public static final float MAX_SUN_IRRADIANCE = 100_000.0f;
    public static final float MAX_EXPOSURE = 10_000.0f;
    public static final float MAX_SUN_ANGULAR_RADIUS = 0.2f;
    public static final float MAX_SUN_DISC_INTENSITY = 100_000.0f;
    public static final int MIN_LUT_EXTENT = 8;
    public static final int MAX_TRANSMITTANCE_LUT_EXTENT = 1024;
    public static final int MAX_SKY_VIEW_LUT_EXTENT = 512;
    public static final int MIN_TRANSMITTANCE_SAMPLES = 4;
    public static final int MAX_TRANSMITTANCE_SAMPLES = 256;
    public static final int MIN_SKY_VIEW_SAMPLES = 4;
    public static final int MAX_SKY_VIEW_SAMPLES = 128;

    /** Bounded table/sample tiers; explicit record values remain the source of truth. */
    public enum Quality {
        LOW(128, 32, 16, 16, 32, 16),
        MEDIUM(256, 64, 32, 32, 64, 64),
        HIGH(512, 128, 64, 64, 128, 128);

        private final int transmittanceLutWidth;
        private final int transmittanceLutHeight;
        private final int skyViewLutWidth;
        private final int skyViewLutHeight;
        private final int transmittanceSamples;
        private final int skyViewSamples;

        Quality(int transmittanceLutWidth, int transmittanceLutHeight, int skyViewLutWidth,
                int skyViewLutHeight, int transmittanceSamples, int skyViewSamples) {
            this.transmittanceLutWidth = transmittanceLutWidth;
            this.transmittanceLutHeight = transmittanceLutHeight;
            this.skyViewLutWidth = skyViewLutWidth;
            this.skyViewLutHeight = skyViewLutHeight;
            this.transmittanceSamples = transmittanceSamples;
            this.skyViewSamples = skyViewSamples;
        }

        public int transmittanceLutWidth() { return transmittanceLutWidth; }
        public int transmittanceLutHeight() { return transmittanceLutHeight; }
        public int skyViewLutWidth() { return skyViewLutWidth; }
        public int skyViewLutHeight() { return skyViewLutHeight; }
        public int transmittanceSamples() { return transmittanceSamples; }
        public int skyViewSamples() { return skyViewSamples; }
    }

    public AtmosphereSettings {
        if (!Float.isFinite(planetRadiusMeters) || planetRadiusMeters < MIN_PLANET_RADIUS_METERS
                || planetRadiusMeters > MAX_PLANET_RADIUS_METERS) {
            throw new IllegalArgumentException("planetRadiusMeters must be finite and in ["
                    + MIN_PLANET_RADIUS_METERS + "," + MAX_PLANET_RADIUS_METERS + "]");
        }
        if (!Float.isFinite(atmosphereThicknessMeters)
                || atmosphereThicknessMeters < MIN_ATMOSPHERE_THICKNESS_METERS
                || atmosphereThicknessMeters > MAX_ATMOSPHERE_THICKNESS_METERS
                || atmosphereThicknessMeters > planetRadiusMeters) {
            throw new IllegalArgumentException("atmosphereThicknessMeters must be finite, in ["
                    + MIN_ATMOSPHERE_THICKNESS_METERS + "," + MAX_ATMOSPHERE_THICKNESS_METERS
                    + "] and no larger than planetRadiusMeters");
        }
        rayleighScatteringPerMeter = scattering(rayleighScatteringPerMeter,
                "rayleighScatteringPerMeter");
        rayleighScaleHeightMeters = scaleHeight(rayleighScaleHeightMeters,
                atmosphereThicknessMeters, "rayleighScaleHeightMeters");
        mieScatteringPerMeter = coefficient(mieScatteringPerMeter, "mieScatteringPerMeter");
        mieAbsorptionPerMeter = coefficient(mieAbsorptionPerMeter, "mieAbsorptionPerMeter");
        mieScaleHeightMeters = scaleHeight(mieScaleHeightMeters, atmosphereThicknessMeters,
                "mieScaleHeightMeters");
        if (!Float.isFinite(mieAnisotropy) || mieAnisotropy < -0.9f || mieAnisotropy > 0.9f) {
            throw new IllegalArgumentException("mieAnisotropy must be in [-0.9,0.9]");
        }
        sunDirection = direction(sunDirection, "sunDirection");
        if (!Float.isFinite(sunIntensityWattsPerSquareMetre)
                || sunIntensityWattsPerSquareMetre < 0.0f
                || sunIntensityWattsPerSquareMetre > MAX_SUN_IRRADIANCE) {
            throw new IllegalArgumentException("sunIntensityWattsPerSquareMetre must be in [0,"
                    + MAX_SUN_IRRADIANCE + "]");
        }
        if (!Float.isFinite(exposure) || exposure < 0.0f || exposure > MAX_EXPOSURE) {
            throw new IllegalArgumentException("exposure must be in [0," + MAX_EXPOSURE + "]");
        }
        if (!Float.isFinite(sunAngularRadiusRadians) || sunAngularRadiusRadians < 1.0e-4f
                || sunAngularRadiusRadians > MAX_SUN_ANGULAR_RADIUS) {
            throw new IllegalArgumentException("sunAngularRadiusRadians must be in [1e-4,"
                    + MAX_SUN_ANGULAR_RADIUS + "]");
        }
        if (!Float.isFinite(sunDiscIntensity) || sunDiscIntensity < 0.0f
                || sunDiscIntensity > MAX_SUN_DISC_INTENSITY) {
            throw new IllegalArgumentException("sunDiscIntensity must be in [0,"
                    + MAX_SUN_DISC_INTENSITY + "]");
        }
        lutExtent(transmittanceLutWidth, MAX_TRANSMITTANCE_LUT_EXTENT,
                "transmittanceLutWidth");
        lutExtent(transmittanceLutHeight, MAX_TRANSMITTANCE_LUT_EXTENT,
                "transmittanceLutHeight");
        lutExtent(skyViewLutWidth, MAX_SKY_VIEW_LUT_EXTENT, "skyViewLutWidth");
        lutExtent(skyViewLutHeight, MAX_SKY_VIEW_LUT_EXTENT, "skyViewLutHeight");
        sampleCount(transmittanceSamples, MIN_TRANSMITTANCE_SAMPLES,
                MAX_TRANSMITTANCE_SAMPLES, "transmittanceSamples");
        sampleCount(skyViewSamples, MIN_SKY_VIEW_SAMPLES, MAX_SKY_VIEW_SAMPLES, "skyViewSamples");
    }

    /** Earth clear-day calibration at 1361 W/m^2 and 0.02 linear exposure. */
    public static AtmosphereSettings earth() {
        return earth(Quality.MEDIUM);
    }

    public static AtmosphereSettings earth(Quality quality) {
        Objects.requireNonNull(quality, "quality");
        return new AtmosphereSettings(EARTH_RADIUS_METERS, EARTH_ATMOSPHERE_THICKNESS_METERS,
                new Vector3f(5.802e-6f, 13.558e-6f, 33.1e-6f), 8_000.0f,
                3.996e-6f, 0.444e-6f, 1_200.0f, 0.76f,
                new Vector3f(0.0f, -0.8660254f, -0.5f), 1_361.0f, 0.02f,
                0.00465f, 200.0f,
                quality.transmittanceLutWidth(), quality.transmittanceLutHeight(),
                quality.skyViewLutWidth(), quality.skyViewLutHeight(),
                quality.transmittanceSamples(), quality.skyViewSamples());
    }

    /** @return the top-of-atmosphere radius in metres */
    public float atmosphereTopRadiusMeters() {
        return planetRadiusMeters + atmosphereThicknessMeters;
    }

    public AtmosphereSettings withSunDirection(Vector3fc replacement) {
        Objects.requireNonNull(replacement, "replacement");
        return new AtmosphereSettings(planetRadiusMeters, atmosphereThicknessMeters,
                rayleighScatteringPerMeter, rayleighScaleHeightMeters, mieScatteringPerMeter,
                mieAbsorptionPerMeter, mieScaleHeightMeters, mieAnisotropy,
                new Vector3f(replacement), sunIntensityWattsPerSquareMetre, exposure,
                sunAngularRadiusRadians, sunDiscIntensity, transmittanceLutWidth,
                transmittanceLutHeight, skyViewLutWidth, skyViewLutHeight, transmittanceSamples,
                skyViewSamples);
    }

    public AtmosphereSettings withExposure(float replacement) {
        return new AtmosphereSettings(planetRadiusMeters, atmosphereThicknessMeters,
                rayleighScatteringPerMeter, rayleighScaleHeightMeters, mieScatteringPerMeter,
                mieAbsorptionPerMeter, mieScaleHeightMeters, mieAnisotropy, sunDirection,
                sunIntensityWattsPerSquareMetre, replacement, sunAngularRadiusRadians,
                sunDiscIntensity, transmittanceLutWidth, transmittanceLutHeight, skyViewLutWidth,
                skyViewLutHeight, transmittanceSamples, skyViewSamples);
    }

    public AtmosphereSettings withSunAngularRadius(float replacement) {
        return new AtmosphereSettings(planetRadiusMeters, atmosphereThicknessMeters,
                rayleighScatteringPerMeter, rayleighScaleHeightMeters, mieScatteringPerMeter,
                mieAbsorptionPerMeter, mieScaleHeightMeters, mieAnisotropy, sunDirection,
                sunIntensityWattsPerSquareMetre, exposure, replacement, sunDiscIntensity,
                transmittanceLutWidth, transmittanceLutHeight, skyViewLutWidth, skyViewLutHeight,
                transmittanceSamples, skyViewSamples);
    }

    public AtmosphereSettings withSunDiscIntensity(float replacement) {
        return new AtmosphereSettings(planetRadiusMeters, atmosphereThicknessMeters,
                rayleighScatteringPerMeter, rayleighScaleHeightMeters, mieScatteringPerMeter,
                mieAbsorptionPerMeter, mieScaleHeightMeters, mieAnisotropy, sunDirection,
                sunIntensityWattsPerSquareMetre, exposure, sunAngularRadiusRadians, replacement,
                transmittanceLutWidth, transmittanceLutHeight, skyViewLutWidth, skyViewLutHeight,
                transmittanceSamples, skyViewSamples);
    }

    @Override public Vector3f rayleighScatteringPerMeter() {
        return new Vector3f(rayleighScatteringPerMeter);
    }

    @Override public Vector3f sunDirection() {
        return new Vector3f(sunDirection);
    }

    Vector3f rayleighScatteringInternal() {
        return rayleighScatteringPerMeter;
    }

    Vector3f sunDirectionInternal() {
        return sunDirection;
    }

    private static Vector3f scattering(Vector3f value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isFinite() || value.x < 0.0f || value.y < 0.0f || value.z < 0.0f
                || value.x > MAX_SCATTERING_PER_METER || value.y > MAX_SCATTERING_PER_METER
                || value.z > MAX_SCATTERING_PER_METER) {
            throw new IllegalArgumentException(name + " components must be in [0,"
                    + MAX_SCATTERING_PER_METER + "]");
        }
        return new Vector3f(value);
    }

    private static float coefficient(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > MAX_SCATTERING_PER_METER) {
            throw new IllegalArgumentException(name + " must be in [0,"
                    + MAX_SCATTERING_PER_METER + "]");
        }
        return value;
    }

    private static float scaleHeight(float value, float thickness, String name) {
        if (!Float.isFinite(value) || value < MIN_SCALE_HEIGHT_METERS
                || value > MAX_SCALE_HEIGHT_METERS || value > thickness) {
            throw new IllegalArgumentException(name + " must be in ["
                    + MIN_SCALE_HEIGHT_METERS + "," + MAX_SCALE_HEIGHT_METERS
                    + "] and no larger than atmosphereThicknessMeters");
        }
        return value;
    }

    private static Vector3f direction(Vector3f value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isFinite()) throw new IllegalArgumentException(name + " must be finite");
        Vector3f result = new Vector3f(value);
        float lengthSquared = result.lengthSquared();
        if (lengthSquared <= 1.0e-10f) {
            throw new IllegalArgumentException(name + " must be non-zero");
        }
        return Math.abs(lengthSquared - 1.0f) <= 1.0e-6f ? result : result.normalize();
    }

    private static void lutExtent(int value, int maximum, String name) {
        if (value < MIN_LUT_EXTENT || value > maximum || value % 8 != 0) {
            throw new IllegalArgumentException(name + " must be a multiple of 8 in ["
                    + MIN_LUT_EXTENT + "," + maximum + "]");
        }
    }

    private static void sampleCount(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be in [" + minimum + ","
                    + maximum + "]");
        }
    }
}
