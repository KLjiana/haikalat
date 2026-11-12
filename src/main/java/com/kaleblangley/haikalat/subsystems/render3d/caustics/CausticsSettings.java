package com.kaleblangley.haikalat.subsystems.render3d.caustics;

import org.joml.Vector3f;

import java.util.Objects;

/**
 * Bounded configuration of the first production water caustics.
 *
 * <p>This is an artistic light-projection approximation, not a photon-mapping
 * solution: the pattern is a deterministic procedural interference field (or an
 * optional alpha-mask pattern texture) projected from one finite water rectangle
 * along the main directional light onto receiver surfaces below the water. Every
 * distance is in world units (metres), matching the rest of Render3D. The water
 * plane is horizontal at {@code y == waterLevel}; the receiver is any surface
 * inside the configured height band, inside the projected water rectangle and
 * within {@link #maximumProjectionDistance()} metres of the water entry
 * point.</p>
 *
 * <p>The receiving surface is selected from the shared scene depth, never
 * assumed from the screen position. A surface is rejected when it is above the
 * water (within {@link #depthBias()}), outside the height band, outside the
 * water rectangle, farther than the maximum path length, or steeper than
 * {@link #maximumReceiverSlopeDegrees()} from horizontal. That is what keeps
 * caustics off foreground occluders, walls and geometry behind the water; it is
 * still a screen-space approximation and cannot account for light-path
 * occluders that are not represented in the depth buffer.</p>
 *
 * <p>The optional {@link #patternTexture()} is a classpath resource path
 * ({@code ""} means procedural only). It is sampled through its alpha channel,
 * linear filtered and tiled in pattern space. It is loaded and owned by the
 * pass, so a missing or undecodable resource fails pass construction instead of
 * silently falling back. {@link #disabled()} is the exact no-op value: no pass,
 * shader, framebuffer, texture or draw is created while caustics are
 * disabled.</p>
 */
public record CausticsSettings(
        boolean enabled,
        float intensity,
        Vector3f color,
        float worldScaleMetres,
        float animationSpeed,
        float maximumProjectionDistance,
        float waterLevel,
        float waterMinX,
        float waterMinZ,
        float waterMaxX,
        float waterMaxZ,
        float receiverMinimumHeight,
        float receiverMaximumHeight,
        float depthBias,
        float distanceFalloff,
        float edgeFadeDistance,
        float maximumReceiverSlopeDegrees,
        String patternTexture) {
    /** Largest accepted linear caustic intensity multiplier. */
    public static final float MAXIMUM_INTENSITY = 8.0f;
    /** Largest accepted linear colour component. */
    public static final float MAXIMUM_COLOR_COMPONENT = 16.0f;
    /** Smallest accepted pattern tile edge in world metres. */
    public static final float MINIMUM_WORLD_SCALE = 0.05f;
    /** Largest accepted pattern tile edge in world metres. */
    public static final float MAXIMUM_WORLD_SCALE = 1000.0f;
    /** Largest accepted absolute animation speed in pattern cycles per second. */
    public static final float MAXIMUM_ANIMATION_SPEED = 4.0f;
    /** Smallest accepted light-path projection distance in metres. */
    public static final float MINIMUM_PROJECTION_DISTANCE = 0.05f;
    /** Largest accepted light-path projection distance in metres. */
    public static final float MAXIMUM_PROJECTION_DISTANCE = 1000.0f;
    /** Largest accepted absolute water/receiver height in world metres. */
    public static final float MAXIMUM_ABSOLUTE_HEIGHT = 10000.0f;
    /** Smallest accepted water rectangle extent in world metres. */
    public static final float MINIMUM_EXTENT = 0.05f;
    /** Largest accepted water rectangle extent in world metres. */
    public static final float MAXIMUM_EXTENT = 20000.0f;
    /** Smallest accepted receiver height-band thickness in world metres. */
    public static final float MINIMUM_RECEIVER_BAND_THICKNESS = 0.01f;
    /** Largest accepted depth bias in world metres. */
    public static final float MAXIMUM_DEPTH_BIAS = 10.0f;
    /** Largest accepted exponential distance falloff in 1/metre. */
    public static final float MAXIMUM_DISTANCE_FALLOFF = 8.0f;
    /** Largest accepted region/height edge fade distance in world metres. */
    public static final float MAXIMUM_EDGE_FADE_DISTANCE = 100.0f;
    /** Largest accepted receiver slope from horizontal, in degrees. */
    public static final float MAXIMUM_RECEIVER_SLOPE_DEGREES = 89.0f;
    /** Largest accepted classpath pattern texture path length. */
    public static final int MAXIMUM_PATTERN_TEXTURE_PATH = 256;
    /**
     * Smallest accepted absolute downward Y of the main light direction.  The
     * caustics projection is only defined for light travelling downward; the
     * pass and the pipeline dependency policy share this bound.
     */
    public static final float MINIMUM_LIGHT_DOWNWARD_Y = 1.0e-3f;

    private static final CausticsSettings DISABLED = new CausticsSettings(
            false,
            1.0f,
            new Vector3f(0.55f, 0.80f, 1.0f),
            4.0f,
            0.25f,
            25.0f,
            0.0f,
            -20.0f,
            -20.0f,
            20.0f,
            20.0f,
            -8.0f,
            -0.5f,
            0.05f,
            0.06f,
            1.0f,
            55.0f,
            "");

    public CausticsSettings {
        if (!finite(intensity) || intensity < 0.0f || intensity > MAXIMUM_INTENSITY) {
            throw new IllegalArgumentException("intensity must be finite and in [0, "
                    + MAXIMUM_INTENSITY + "]");
        }
        color = color(color, "color");
        if (!finite(worldScaleMetres) || worldScaleMetres < MINIMUM_WORLD_SCALE
                || worldScaleMetres > MAXIMUM_WORLD_SCALE) {
            throw new IllegalArgumentException("worldScaleMetres must be finite and in ["
                    + MINIMUM_WORLD_SCALE + ", " + MAXIMUM_WORLD_SCALE + "]");
        }
        if (!finite(animationSpeed) || Math.abs(animationSpeed) > MAXIMUM_ANIMATION_SPEED) {
            throw new IllegalArgumentException("animationSpeed must be finite and in [-"
                    + MAXIMUM_ANIMATION_SPEED + ", " + MAXIMUM_ANIMATION_SPEED + "]");
        }
        if (!finite(maximumProjectionDistance)
                || maximumProjectionDistance < MINIMUM_PROJECTION_DISTANCE
                || maximumProjectionDistance > MAXIMUM_PROJECTION_DISTANCE) {
            throw new IllegalArgumentException("maximumProjectionDistance must be finite and in ["
                    + MINIMUM_PROJECTION_DISTANCE + ", " + MAXIMUM_PROJECTION_DISTANCE + "]");
        }
        requireHeight(waterLevel, "waterLevel");
        validateWaterBounds(waterMinX, waterMinZ, waterMaxX, waterMaxZ);
        requireHeight(receiverMinimumHeight, "receiverMinimumHeight");
        requireHeight(receiverMaximumHeight, "receiverMaximumHeight");
        if (!(receiverMaximumHeight - receiverMinimumHeight
                >= MINIMUM_RECEIVER_BAND_THICKNESS)) {
            throw new IllegalArgumentException("receiver height band must be at least "
                    + MINIMUM_RECEIVER_BAND_THICKNESS + " metres thick");
        }
        if (!finite(depthBias) || depthBias < 0.0f || depthBias > MAXIMUM_DEPTH_BIAS) {
            throw new IllegalArgumentException("depthBias must be finite and in [0, "
                    + MAXIMUM_DEPTH_BIAS + "]");
        }
        if (!finite(distanceFalloff) || distanceFalloff < 0.0f
                || distanceFalloff > MAXIMUM_DISTANCE_FALLOFF) {
            throw new IllegalArgumentException("distanceFalloff must be finite and in [0, "
                    + MAXIMUM_DISTANCE_FALLOFF + "]");
        }
        if (!finite(edgeFadeDistance) || edgeFadeDistance < 0.0f
                || edgeFadeDistance > MAXIMUM_EDGE_FADE_DISTANCE) {
            throw new IllegalArgumentException("edgeFadeDistance must be finite and in [0, "
                    + MAXIMUM_EDGE_FADE_DISTANCE + "]");
        }
        if (!finite(maximumReceiverSlopeDegrees) || maximumReceiverSlopeDegrees < 0.0f
                || maximumReceiverSlopeDegrees > MAXIMUM_RECEIVER_SLOPE_DEGREES) {
            throw new IllegalArgumentException("maximumReceiverSlopeDegrees must be finite and in [0, "
                    + MAXIMUM_RECEIVER_SLOPE_DEGREES + "]");
        }
        if (patternTexture == null) {
            throw new IllegalArgumentException("patternTexture must not be null");
        }
        if (!patternTexture.isEmpty()
                && (!patternTexture.startsWith("/")
                || patternTexture.length() > MAXIMUM_PATTERN_TEXTURE_PATH)) {
            throw new IllegalArgumentException("patternTexture must be empty or a classpath path "
                    + "starting with '/' and at most " + MAXIMUM_PATTERN_TEXTURE_PATH + " characters");
        }
    }

    /** Exact no-op value used when no caustics were requested. */
    public static CausticsSettings disabled() {
        return DISABLED;
    }

    /** @return true when the caustics pass and its draw should exist */
    public boolean active() {
        return enabled;
    }

    /** @return true when an optional pattern texture resource is configured */
    public boolean hasPatternTexture() {
        return !patternTexture.isEmpty();
    }

    /** @return a defensive copy of the linear caustic tint */
    @Override
    public Vector3f color() {
        return new Vector3f(color);
    }

    /** Zero-allocation caustic tint getter for render recording. */
    public void colorTo(Vector3f destination) {
        Objects.requireNonNull(destination, "destination").set(color);
    }

    /**
     * Cosine of {@link #maximumReceiverSlopeDegrees()}: the smallest accepted
     * absolute world normal {@code |n.y|} of a receiver surface.
     */
    public float minimumReceiverUpDot() {
        return (float) Math.cos(Math.toRadians(maximumReceiverSlopeDegrees));
    }

    /** @return world-space water rectangle width in metres */
    public float waterWidth() {
        return waterMaxX - waterMinX;
    }

    /** @return world-space water rectangle depth in metres */
    public float waterDepth() {
        return waterMaxZ - waterMinZ;
    }

    /** @return a builder pre-populated with this value's fields */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Bounded copy of this value with only the enabled flag changed. */
    public CausticsSettings withEnabled(boolean value) {
        return new CausticsSettings(value, intensity, new Vector3f(color), worldScaleMetres,
                animationSpeed, maximumProjectionDistance, waterLevel, waterMinX, waterMinZ,
                waterMaxX, waterMaxZ, receiverMinimumHeight, receiverMaximumHeight, depthBias,
                distanceFalloff, edgeFadeDistance, maximumReceiverSlopeDegrees, patternTexture);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder mirroring the record's validated ranges. */
    public static final class Builder {
        private boolean enabled;
        private float intensity = 1.0f;
        private Vector3f color = new Vector3f(0.55f, 0.80f, 1.0f);
        private float worldScaleMetres = 4.0f;
        private float animationSpeed = 0.25f;
        private float maximumProjectionDistance = 25.0f;
        private float waterLevel;
        private float waterMinX = -20.0f;
        private float waterMinZ = -20.0f;
        private float waterMaxX = 20.0f;
        private float waterMaxZ = 20.0f;
        private float receiverMinimumHeight = -8.0f;
        private float receiverMaximumHeight = -0.5f;
        private float depthBias = 0.05f;
        private float distanceFalloff = 0.06f;
        private float edgeFadeDistance = 1.0f;
        private float maximumReceiverSlopeDegrees = 55.0f;
        private String patternTexture = "";

        private Builder() {
        }

        private Builder(CausticsSettings source) {
            enabled = source.enabled;
            intensity = source.intensity;
            color = new Vector3f(source.color);
            worldScaleMetres = source.worldScaleMetres;
            animationSpeed = source.animationSpeed;
            maximumProjectionDistance = source.maximumProjectionDistance;
            waterLevel = source.waterLevel;
            waterMinX = source.waterMinX;
            waterMinZ = source.waterMinZ;
            waterMaxX = source.waterMaxX;
            waterMaxZ = source.waterMaxZ;
            receiverMinimumHeight = source.receiverMinimumHeight;
            receiverMaximumHeight = source.receiverMaximumHeight;
            depthBias = source.depthBias;
            distanceFalloff = source.distanceFalloff;
            edgeFadeDistance = source.edgeFadeDistance;
            maximumReceiverSlopeDegrees = source.maximumReceiverSlopeDegrees;
            patternTexture = source.patternTexture;
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder intensity(float value) {
            intensity = value;
            return this;
        }

        public Builder color(float red, float green, float blue) {
            color = new Vector3f(red, green, blue);
            return this;
        }

        public Builder worldScaleMetres(float value) {
            worldScaleMetres = value;
            return this;
        }

        public Builder animationSpeed(float value) {
            animationSpeed = value;
            return this;
        }

        public Builder maximumProjectionDistance(float value) {
            maximumProjectionDistance = value;
            return this;
        }

        public Builder waterPlane(float level) {
            waterLevel = level;
            return this;
        }

        public Builder waterRegion(float minimumX, float minimumZ, float maximumX, float maximumZ) {
            waterMinX = minimumX;
            waterMinZ = minimumZ;
            waterMaxX = maximumX;
            waterMaxZ = maximumZ;
            return this;
        }

        public Builder receiverHeight(float minimumHeight, float maximumHeight) {
            receiverMinimumHeight = minimumHeight;
            receiverMaximumHeight = maximumHeight;
            return this;
        }

        public Builder depthBias(float value) {
            depthBias = value;
            return this;
        }

        public Builder distanceFalloff(float value) {
            distanceFalloff = value;
            return this;
        }

        public Builder edgeFadeDistance(float value) {
            edgeFadeDistance = value;
            return this;
        }

        public Builder maximumReceiverSlopeDegrees(float value) {
            maximumReceiverSlopeDegrees = value;
            return this;
        }

        public Builder patternTexture(String value) {
            patternTexture = value;
            return this;
        }

        public CausticsSettings build() {
            return new CausticsSettings(enabled, intensity, color, worldScaleMetres,
                    animationSpeed, maximumProjectionDistance, waterLevel, waterMinX, waterMinZ,
                    waterMaxX, waterMaxZ, receiverMinimumHeight, receiverMaximumHeight, depthBias,
                    distanceFalloff, edgeFadeDistance, maximumReceiverSlopeDegrees, patternTexture);
        }
    }

    private static void validateWaterBounds(float minX, float minZ, float maxX, float maxZ) {
        requireBound(minX, "waterMinX");
        requireBound(minZ, "waterMinZ");
        requireBound(maxX, "waterMaxX");
        requireBound(maxZ, "waterMaxZ");
        float width = maxX - minX;
        float depth = maxZ - minZ;
        if (!finite(width) || width < MINIMUM_EXTENT || width > MAXIMUM_EXTENT) {
            throw new IllegalArgumentException("water region width must be finite and in ["
                    + MINIMUM_EXTENT + ", " + MAXIMUM_EXTENT + "]");
        }
        if (!finite(depth) || depth < MINIMUM_EXTENT || depth > MAXIMUM_EXTENT) {
            throw new IllegalArgumentException("water region depth must be finite and in ["
                    + MINIMUM_EXTENT + ", " + MAXIMUM_EXTENT + "]");
        }
    }

    private static void requireBound(float value, String name) {
        if (!finite(value) || Math.abs(value) > MAXIMUM_ABSOLUTE_HEIGHT + MAXIMUM_EXTENT) {
            throw new IllegalArgumentException(name + " must be finite and within the world extent");
        }
    }

    private static void requireHeight(float value, String name) {
        if (!finite(value) || Math.abs(value) > MAXIMUM_ABSOLUTE_HEIGHT) {
            throw new IllegalArgumentException(name + " must be finite and in [-"
                    + MAXIMUM_ABSOLUTE_HEIGHT + ", " + MAXIMUM_ABSOLUTE_HEIGHT + "]");
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

    private static boolean finite(float value) {
        return Float.isFinite(value);
    }
}
