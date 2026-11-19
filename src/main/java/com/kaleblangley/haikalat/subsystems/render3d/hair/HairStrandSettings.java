package com.kaleblangley.haikalat.subsystems.render3d.hair;

import org.joml.Vector3f;

import java.util.Objects;

/**
 * Bounded, GL-free configuration of a deterministic hair-strand set (roadmap
 * #46).
 *
 * <p>The value describes one finite lock of swept strand ribbons generated from
 * deterministic control points. Geometry is produced procedurally from
 * {@link #seed()}; no external asset, texture or authoring data is required.
 * The lock is rooted on a disc perpendicular to {@link #flowDirection()} and
 * follows that direction with a bounded lateral curl, so every strand is a real
 * polyline before it is expanded to a ribbon. The expansion is a camera-aware
 * screen-space width in the vertex stage; it is <em>not</em> a camera-facing
 * card: the centreline, tangent and per-strand parameter come from generated
 * strand points, and the ribbon width is clamped by
 * {@link #minimumWidthPixels()} / {@link #maximumWidthPixels()}.
 * {@link #strandRadiusMetres()} is the bounded world-space radius used by the
 * shadow caster, where a world silhouette is required instead of a pixel
 * width.</p>
 *
 * <h2>LOD</h2>
 *
 * <p>{@link #selectLod(double, double)} chooses {@link Lod#HIGH},
 * {@link Lod#MEDIUM} or {@link Lod#LOW} from the camera distance and the
 * projected lock radius in pixels. The two thresholds are inclusive lower
 * bounds; the nearest matching level wins. A lock that is close but projects
 * smaller than {@link #lodMinimumPixels()} falls to a lower level, so a
 * sub-pixel lock never pays for the full strand count. The selected level also
 * scales the requested ribbon width through
 * {@link #widthScaleFor(double)}; the shader then clamps the result again to
 * the configured pixel bounds.</p>
 *
 * <h2>Transparency</h2>
 *
 * <p>{@link Transparency#CUTOUT} is an opaque alpha-tested strand: it writes
 * depth and casts opaque shadows. {@link Transparency#SORTED_ALPHA} blends
 * straight alpha with {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA}, keeps depth
 * testing but disables depth writes, and requires the caller to record strands
 * back to front. The pass implements that ordering per strand (far view depth
 * first, stable by strand index) and therefore stays consistent with the
 * existing sorted transparent queue contract; it is not a second OIT
 * implementation.</p>
 *
 * <p>{@link #disabled()} is the exact no-op value: while disabled no pass,
 * mesh, shader or draw exists and a per-frame record is a byte-preserving
 * no-op. All vector components are copied in the canonical constructor and
 * defensive copies are returned by the public accessors.</p>
 */
public record HairStrandSettings(
        boolean enabled,
        int strandCount,
        int segmentsPerStrand,
        float strandLengthMetres,
        float rootSpreadMetres,
        Vector3f flowDirection,
        float curlMetres,
        float strandRadiusMetres,
        float widthPixels,
        float minimumWidthPixels,
        float maximumWidthPixels,
        float nearDistanceMetres,
        float farDistanceMetres,
        float lodMinimumPixels,
        float lodWidthScale,
        float alpha,
        float alphaCutoff,
        Transparency transparency,
        Vector3f rootColor,
        Vector3f tipColor,
        float diffuseStrength,
        float specularStrength,
        float primaryExponent,
        float secondaryExponent,
        float secondaryWeight,
        float colorVariation,
        boolean castsShadow,
        long seed) {

    /** Smallest accepted high-detail strand count. */
    public static final int MINIMUM_STRANDS = 1;
    /** Largest accepted high-detail strand count. */
    public static final int MAXIMUM_STRANDS = 512;
    /** Smallest accepted high-detail segment count. */
    public static final int MINIMUM_SEGMENTS = 2;
    /** Largest accepted high-detail segment count. */
    public static final int MAXIMUM_SEGMENTS = 16;
    /** Smallest accepted strand length in world metres. */
    public static final float MINIMUM_LENGTH_METRES = 0.02f;
    /** Largest accepted strand length in world metres. */
    public static final float MAXIMUM_LENGTH_METRES = 5.0f;
    /** Largest accepted root-disc radius in world metres. */
    public static final float MAXIMUM_ROOT_SPREAD_METRES = 2.0f;
    /** Largest accepted lateral curl amplitude in world metres. */
    public static final float MAXIMUM_CURL_METRES = 2.0f;
    /** Smallest accepted world-space strand radius for the shadow caster. */
    public static final float MINIMUM_STRAND_RADIUS_METRES = 5.0e-4f;
    /** Largest accepted world-space strand radius for the shadow caster. */
    public static final float MAXIMUM_STRAND_RADIUS_METRES = 0.2f;
    /** Smallest accepted requested strand width in pixels. */
    public static final float MINIMUM_WIDTH_PIXELS = 1.0f;
    /** Largest accepted requested strand width in pixels. */
    public static final float MAXIMUM_WIDTH_PIXELS = 32.0f;
    /** Smallest accepted clamped pixel width. */
    public static final float MINIMUM_CLAMPED_WIDTH_PIXELS = 0.5f;
    /** Largest accepted clamped pixel width. */
    public static final float MAXIMUM_CLAMPED_WIDTH_PIXELS = 64.0f;
    /** Smallest accepted high-detail distance threshold in metres. */
    public static final float MINIMUM_NEAR_DISTANCE_METRES = 0.1f;
    /** Largest accepted far distance threshold in metres. */
    public static final float MAXIMUM_FAR_DISTANCE_METRES = 10_000.0f;
    /** Smallest accepted projected-radius threshold for the high level. */
    public static final float MINIMUM_LOD_PIXELS = 1.0f;
    /** Largest accepted projected-radius threshold for the high level. */
    public static final float MAXIMUM_LOD_PIXELS = 1_024.0f;
    /** Smallest accepted far-level width scale. */
    public static final float MINIMUM_LOD_WIDTH_SCALE = 0.25f;
    /** Largest accepted linear colour component. */
    public static final float MAXIMUM_COLOR_COMPONENT = 16.0f;
    /** Largest accepted Kajiya-Kay exponent. */
    public static final float MAXIMUM_EXPONENT = 512.0f;

    /** Alpha handling of the strand collection. */
    public enum Transparency {
        /** Alpha-tested opaque strands that write depth and cast opaque shadows. */
        CUTOUT,
        /** Straight-alpha strands, depth-tested but not depth-writing, sorted per strand. */
        SORTED_ALPHA
    }

    /** Bounded strand/segment budget of the generated geometry. */
    public enum Lod {
        /** All strands and segments. */
        HIGH,
        /** Half of the strands and two fewer segments. */
        MEDIUM,
        /** A quarter of the strands and half of the segments. */
        LOW
    }

    /** Default seed for the procedural lock. */
    public static final long DEFAULT_SEED = 0x5EED_1A17L;
    private static final float DEFAULT_LENGTH = 0.6f;
    private static final float DEFAULT_ROOT_SPREAD = 0.05f;
    private static final float DEFAULT_CURL = 0.08f;
    private static final float DEFAULT_STRAND_RADIUS = 0.004f;
    private static final float DEFAULT_WIDTH = 3.0f;
    private static final float DEFAULT_MINIMUM_WIDTH = 1.5f;
    private static final float DEFAULT_MAXIMUM_WIDTH = 8.0f;
    private static final float DEFAULT_NEAR_DISTANCE = 4.0f;
    private static final float DEFAULT_FAR_DISTANCE = 12.0f;
    private static final float DEFAULT_LOD_PIXELS = 48.0f;
    private static final float DEFAULT_LOD_WIDTH_SCALE = 0.5f;
    private static final float DEFAULT_ALPHA = 0.85f;
    private static final float DEFAULT_ALPHA_CUTOFF = 0.35f;

    private static final HairStrandSettings DISABLED = new HairStrandSettings(
            false, 64, 8, DEFAULT_LENGTH, DEFAULT_ROOT_SPREAD,
            new Vector3f(0.0f, -1.0f, 0.0f), DEFAULT_CURL,
            DEFAULT_STRAND_RADIUS, DEFAULT_WIDTH, DEFAULT_MINIMUM_WIDTH, DEFAULT_MAXIMUM_WIDTH,
            DEFAULT_NEAR_DISTANCE, DEFAULT_FAR_DISTANCE, DEFAULT_LOD_PIXELS,
            DEFAULT_LOD_WIDTH_SCALE, DEFAULT_ALPHA, DEFAULT_ALPHA_CUTOFF,
            Transparency.SORTED_ALPHA,
            new Vector3f(0.10f, 0.07f, 0.05f), new Vector3f(0.42f, 0.30f, 0.18f),
            0.9f, 2.5f, 48.0f, 128.0f, 0.5f, 0.25f, true, DEFAULT_SEED);

    public HairStrandSettings {
        if (strandCount < MINIMUM_STRANDS || strandCount > MAXIMUM_STRANDS) {
            throw new IllegalArgumentException("strandCount must be in ["
                    + MINIMUM_STRANDS + ", " + MAXIMUM_STRANDS + "]");
        }
        if (segmentsPerStrand < MINIMUM_SEGMENTS || segmentsPerStrand > MAXIMUM_SEGMENTS) {
            throw new IllegalArgumentException("segmentsPerStrand must be in ["
                    + MINIMUM_SEGMENTS + ", " + MAXIMUM_SEGMENTS + "]");
        }
        if (!finite(strandLengthMetres) || strandLengthMetres < MINIMUM_LENGTH_METRES
                || strandLengthMetres > MAXIMUM_LENGTH_METRES) {
            throw new IllegalArgumentException("strandLengthMetres must be finite and in ["
                    + MINIMUM_LENGTH_METRES + ", " + MAXIMUM_LENGTH_METRES + "]");
        }
        if (!finite(rootSpreadMetres) || rootSpreadMetres < 0.0f
                || rootSpreadMetres > MAXIMUM_ROOT_SPREAD_METRES) {
            throw new IllegalArgumentException("rootSpreadMetres must be finite and in [0, "
                    + MAXIMUM_ROOT_SPREAD_METRES + "]");
        }
        flowDirection = normalize(flowDirection, "flowDirection");
        if (!finite(curlMetres) || curlMetres < 0.0f || curlMetres > MAXIMUM_CURL_METRES) {
            throw new IllegalArgumentException("curlMetres must be finite and in [0, "
                    + MAXIMUM_CURL_METRES + "]");
        }
        if (!finite(strandRadiusMetres) || strandRadiusMetres < MINIMUM_STRAND_RADIUS_METRES
                || strandRadiusMetres > MAXIMUM_STRAND_RADIUS_METRES) {
            throw new IllegalArgumentException("strandRadiusMetres must be finite and in ["
                    + MINIMUM_STRAND_RADIUS_METRES + ", " + MAXIMUM_STRAND_RADIUS_METRES + "]");
        }
        if (!finite(widthPixels) || widthPixels < MINIMUM_WIDTH_PIXELS
                || widthPixels > MAXIMUM_WIDTH_PIXELS) {
            throw new IllegalArgumentException("widthPixels must be finite and in ["
                    + MINIMUM_WIDTH_PIXELS + ", " + MAXIMUM_WIDTH_PIXELS + "]");
        }
        if (!finite(minimumWidthPixels) || minimumWidthPixels < MINIMUM_CLAMPED_WIDTH_PIXELS
                || minimumWidthPixels > MAXIMUM_CLAMPED_WIDTH_PIXELS) {
            throw new IllegalArgumentException("minimumWidthPixels must be finite and in ["
                    + MINIMUM_CLAMPED_WIDTH_PIXELS + ", " + MAXIMUM_CLAMPED_WIDTH_PIXELS + "]");
        }
        if (!finite(maximumWidthPixels) || maximumWidthPixels < MINIMUM_CLAMPED_WIDTH_PIXELS
                || maximumWidthPixels > MAXIMUM_CLAMPED_WIDTH_PIXELS) {
            throw new IllegalArgumentException("maximumWidthPixels must be finite and in ["
                    + MINIMUM_CLAMPED_WIDTH_PIXELS + ", " + MAXIMUM_CLAMPED_WIDTH_PIXELS + "]");
        }
        if (minimumWidthPixels > maximumWidthPixels) {
            throw new IllegalArgumentException("minimumWidthPixels must not exceed maximumWidthPixels");
        }
        if (widthPixels < minimumWidthPixels || widthPixels > maximumWidthPixels) {
            throw new IllegalArgumentException(
                    "widthPixels must lie inside [minimumWidthPixels, maximumWidthPixels]");
        }
        if (!finite(nearDistanceMetres) || nearDistanceMetres < MINIMUM_NEAR_DISTANCE_METRES
                || nearDistanceMetres > MAXIMUM_FAR_DISTANCE_METRES) {
            throw new IllegalArgumentException("nearDistanceMetres must be finite and in ["
                    + MINIMUM_NEAR_DISTANCE_METRES + ", " + MAXIMUM_FAR_DISTANCE_METRES + "]");
        }
        if (!finite(farDistanceMetres) || farDistanceMetres <= nearDistanceMetres
                || farDistanceMetres > MAXIMUM_FAR_DISTANCE_METRES) {
            throw new IllegalArgumentException("farDistanceMetres must be finite, greater than "
                    + "nearDistanceMetres and at most " + MAXIMUM_FAR_DISTANCE_METRES);
        }
        if (!finite(lodMinimumPixels) || lodMinimumPixels < MINIMUM_LOD_PIXELS
                || lodMinimumPixels > MAXIMUM_LOD_PIXELS) {
            throw new IllegalArgumentException("lodMinimumPixels must be finite and in ["
                    + MINIMUM_LOD_PIXELS + ", " + MAXIMUM_LOD_PIXELS + "]");
        }
        if (!finite(lodWidthScale) || lodWidthScale < MINIMUM_LOD_WIDTH_SCALE
                || lodWidthScale > 1.0f) {
            throw new IllegalArgumentException("lodWidthScale must be finite and in ["
                    + MINIMUM_LOD_WIDTH_SCALE + ", 1]");
        }
        if (!finite(alpha) || alpha < 0.0f || alpha > 1.0f) {
            throw new IllegalArgumentException("alpha must be finite and in [0, 1]");
        }
        if (!finite(alphaCutoff) || alphaCutoff < 0.0f || alphaCutoff > 0.99f) {
            throw new IllegalArgumentException("alphaCutoff must be finite and in [0, 0.99]");
        }
        Objects.requireNonNull(transparency, "transparency");
        rootColor = color(rootColor, "rootColor");
        tipColor = color(tipColor, "tipColor");
        if (!finite(diffuseStrength) || diffuseStrength < 0.0f || diffuseStrength > 4.0f) {
            throw new IllegalArgumentException("diffuseStrength must be finite and in [0, 4]");
        }
        if (!finite(specularStrength) || specularStrength < 0.0f || specularStrength > 16.0f) {
            throw new IllegalArgumentException("specularStrength must be finite and in [0, 16]");
        }
        if (!finite(primaryExponent) || primaryExponent < 1.0f
                || primaryExponent > MAXIMUM_EXPONENT) {
            throw new IllegalArgumentException("primaryExponent must be finite and in [1, "
                    + MAXIMUM_EXPONENT + "]");
        }
        if (!finite(secondaryExponent) || secondaryExponent < 1.0f
                || secondaryExponent > MAXIMUM_EXPONENT) {
            throw new IllegalArgumentException("secondaryExponent must be finite and in [1, "
                    + MAXIMUM_EXPONENT + "]");
        }
        if (!finite(secondaryWeight) || secondaryWeight < 0.0f || secondaryWeight > 1.0f) {
            throw new IllegalArgumentException("secondaryWeight must be finite and in [0, 1]");
        }
        if (!finite(colorVariation) || colorVariation < 0.0f || colorVariation > 1.0f) {
            throw new IllegalArgumentException("colorVariation must be finite and in [0, 1]");
        }
    }

    /** Exact no-op value: no pass or GPU resource exists while disabled. */
    public static HairStrandSettings disabled() {
        return DISABLED;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** @return true when geometry, shaders and draws should exist */
    public boolean active() {
        return enabled;
    }

    /** @return a bounded copy with only the enabled flag changed */
    public HairStrandSettings withEnabled(boolean value) {
        return new HairStrandSettings(value, strandCount, segmentsPerStrand,
                strandLengthMetres, rootSpreadMetres, new Vector3f(flowDirection), curlMetres,
                strandRadiusMetres, widthPixels, minimumWidthPixels, maximumWidthPixels,
                nearDistanceMetres, farDistanceMetres, lodMinimumPixels, lodWidthScale, alpha,
                alphaCutoff, transparency, new Vector3f(rootColor), new Vector3f(tipColor),
                diffuseStrength, specularStrength, primaryExponent, secondaryExponent,
                secondaryWeight, colorVariation, castsShadow, seed);
    }

    /**
     * Independently reproducible LOD decision.
     *
     * @param distanceMetres       camera to lock-centre distance in metres
     * @param projectedRadiusPixels projected lock radius in screen pixels
     * @return the nearest matching level; both thresholds are inclusive
     */
    public Lod selectLod(double distanceMetres, double projectedRadiusPixels) {
        if (!Double.isFinite(distanceMetres) || distanceMetres < 0.0) {
            throw new IllegalArgumentException("distanceMetres must be finite and non-negative");
        }
        if (!Double.isFinite(projectedRadiusPixels) || projectedRadiusPixels < 0.0) {
            throw new IllegalArgumentException(
                    "projectedRadiusPixels must be finite and non-negative");
        }
        double minimum = lodMinimumPixels;
        if (distanceMetres <= nearDistanceMetres && projectedRadiusPixels >= minimum) {
            return Lod.HIGH;
        }
        if (distanceMetres <= farDistanceMetres && projectedRadiusPixels >= minimum * 0.5) {
            return Lod.MEDIUM;
        }
        return Lod.LOW;
    }

    /** Strand count generated for a level. */
    public int strandCountFor(Lod lod) {
        Objects.requireNonNull(lod, "lod");
        return switch (lod) {
            case HIGH -> strandCount;
            case MEDIUM -> Math.max(1, (strandCount + 1) / 2);
            case LOW -> Math.max(1, (strandCount + 3) / 4);
        };
    }

    /** Segment count generated for a level. */
    public int segmentsFor(Lod lod) {
        Objects.requireNonNull(lod, "lod");
        return switch (lod) {
            case HIGH -> segmentsPerStrand;
            case MEDIUM -> Math.max(2, segmentsPerStrand - 2);
            case LOW -> Math.max(1, segmentsPerStrand / 2);
        };
    }

    /**
     * Pixel-width scale applied before the shader clamp. It decreases linearly
     * from {@code 1} at {@link #nearDistanceMetres()} to {@link #lodWidthScale()}
     * at {@link #farDistanceMetres()} and stays bounded outside that window.
     */
    public double widthScaleFor(double distanceMetres) {
        if (!Double.isFinite(distanceMetres) || distanceMetres < 0.0) {
            throw new IllegalArgumentException("distanceMetres must be finite and non-negative");
        }
        double span = (double) farDistanceMetres - nearDistanceMetres;
        double t = Math.max(0.0, Math.min(1.0, (distanceMetres - nearDistanceMetres) / span));
        return 1.0 + ((double) lodWidthScale - 1.0) * t;
    }

    /** @return a defensive copy of the world-space flow direction */
    @Override
    public Vector3f flowDirection() {
        return new Vector3f(flowDirection);
    }

    /** @return a defensive copy of the root colour */
    @Override
    public Vector3f rootColor() {
        return new Vector3f(rootColor);
    }

    /** @return a defensive copy of the tip colour */
    @Override
    public Vector3f tipColor() {
        return new Vector3f(tipColor);
    }

    Vector3f flowDirectionInternal() {
        return flowDirection;
    }

    Vector3f rootColorInternal() {
        return rootColor;
    }

    Vector3f tipColorInternal() {
        return tipColor;
    }

    private static Vector3f normalize(Vector3f value, String name) {
        Objects.requireNonNull(value, name);
        float lengthSquared = value.lengthSquared();
        if (!Float.isFinite(lengthSquared) || lengthSquared < 1.0e-12f) {
            throw new IllegalArgumentException(name + " must be finite and non-zero");
        }
        return new Vector3f(value).mul(1.0f / (float) Math.sqrt(lengthSquared));
    }

    private static Vector3f color(Vector3f value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isFinite() || value.x < 0.0f || value.y < 0.0f || value.z < 0.0f
                || value.x > MAXIMUM_COLOR_COMPONENT || value.y > MAXIMUM_COLOR_COMPONENT
                || value.z > MAXIMUM_COLOR_COMPONENT) {
            throw new IllegalArgumentException(name + " components must be in [0, "
                    + MAXIMUM_COLOR_COMPONENT + "]");
        }
        return new Vector3f(value);
    }

    private static boolean finite(float value) {
        return Float.isFinite(value);
    }

    /** Mutable builder mirroring the record's validated ranges. */
    public static final class Builder {
        private boolean enabled = true;
        private int strandCount = 64;
        private int segmentsPerStrand = 8;
        private float strandLengthMetres = DEFAULT_LENGTH;
        private float rootSpreadMetres = DEFAULT_ROOT_SPREAD;
        private Vector3f flowDirection = new Vector3f(0.0f, -1.0f, 0.0f);
        private float curlMetres = DEFAULT_CURL;
        private float strandRadiusMetres = DEFAULT_STRAND_RADIUS;
        private float widthPixels = DEFAULT_WIDTH;
        private float minimumWidthPixels = DEFAULT_MINIMUM_WIDTH;
        private float maximumWidthPixels = DEFAULT_MAXIMUM_WIDTH;
        private float nearDistanceMetres = DEFAULT_NEAR_DISTANCE;
        private float farDistanceMetres = DEFAULT_FAR_DISTANCE;
        private float lodMinimumPixels = DEFAULT_LOD_PIXELS;
        private float lodWidthScale = DEFAULT_LOD_WIDTH_SCALE;
        private float alpha = DEFAULT_ALPHA;
        private float alphaCutoff = DEFAULT_ALPHA_CUTOFF;
        private Transparency transparency = Transparency.SORTED_ALPHA;
        private Vector3f rootColor = new Vector3f(0.10f, 0.07f, 0.05f);
        private Vector3f tipColor = new Vector3f(0.42f, 0.30f, 0.18f);
        private float diffuseStrength = 0.9f;
        private float specularStrength = 2.5f;
        private float primaryExponent = 48.0f;
        private float secondaryExponent = 128.0f;
        private float secondaryWeight = 0.5f;
        private float colorVariation = 0.25f;
        private boolean castsShadow = true;
        private long seed = DEFAULT_SEED;

        private Builder() {
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder strandCount(int value) {
            strandCount = value;
            return this;
        }

        public Builder segmentsPerStrand(int value) {
            segmentsPerStrand = value;
            return this;
        }

        public Builder strandLengthMetres(float value) {
            strandLengthMetres = value;
            return this;
        }

        public Builder rootSpreadMetres(float value) {
            rootSpreadMetres = value;
            return this;
        }

        public Builder flowDirection(Vector3f value) {
            flowDirection = new Vector3f(Objects.requireNonNull(value, "flowDirection"));
            return this;
        }

        public Builder curlMetres(float value) {
            curlMetres = value;
            return this;
        }

        public Builder strandRadiusMetres(float value) {
            strandRadiusMetres = value;
            return this;
        }

        public Builder widthPixels(float value) {
            widthPixels = value;
            return this;
        }

        public Builder minimumWidthPixels(float value) {
            minimumWidthPixels = value;
            return this;
        }

        public Builder maximumWidthPixels(float value) {
            maximumWidthPixels = value;
            return this;
        }

        public Builder nearDistanceMetres(float value) {
            nearDistanceMetres = value;
            return this;
        }

        public Builder farDistanceMetres(float value) {
            farDistanceMetres = value;
            return this;
        }

        public Builder lodMinimumPixels(float value) {
            lodMinimumPixels = value;
            return this;
        }

        public Builder lodWidthScale(float value) {
            lodWidthScale = value;
            return this;
        }

        public Builder alpha(float value) {
            alpha = value;
            return this;
        }

        public Builder alphaCutoff(float value) {
            alphaCutoff = value;
            return this;
        }

        public Builder transparency(Transparency value) {
            transparency = value;
            return this;
        }

        public Builder rootColor(Vector3f value) {
            rootColor = new Vector3f(Objects.requireNonNull(value, "rootColor"));
            return this;
        }

        public Builder tipColor(Vector3f value) {
            tipColor = new Vector3f(Objects.requireNonNull(value, "tipColor"));
            return this;
        }

        public Builder diffuseStrength(float value) {
            diffuseStrength = value;
            return this;
        }

        public Builder specularStrength(float value) {
            specularStrength = value;
            return this;
        }

        public Builder primaryExponent(float value) {
            primaryExponent = value;
            return this;
        }

        public Builder secondaryExponent(float value) {
            secondaryExponent = value;
            return this;
        }

        public Builder secondaryWeight(float value) {
            secondaryWeight = value;
            return this;
        }

        public Builder colorVariation(float value) {
            colorVariation = value;
            return this;
        }

        public Builder castsShadow(boolean value) {
            castsShadow = value;
            return this;
        }

        public Builder seed(long value) {
            seed = value;
            return this;
        }

        public HairStrandSettings build() {
            return new HairStrandSettings(enabled, strandCount, segmentsPerStrand,
                    strandLengthMetres, rootSpreadMetres, flowDirection, curlMetres,
                    strandRadiusMetres, widthPixels, minimumWidthPixels, maximumWidthPixels,
                    nearDistanceMetres, farDistanceMetres, lodMinimumPixels, lodWidthScale,
                    alpha, alphaCutoff, transparency, rootColor, tipColor, diffuseStrength,
                    specularStrength, primaryExponent, secondaryExponent, secondaryWeight,
                    colorVariation, castsShadow, seed);
        }
    }
}
