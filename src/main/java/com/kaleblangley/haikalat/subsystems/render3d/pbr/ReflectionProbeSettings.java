package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

/**
 * Immutable description of one static local reflection probe.
 *
 * <p>A probe owns an HDR source precomputed by {@link PbrEnvironmentLoader}
 * (the same GPU prefilter as the global environment) and an influence volume.
 * The volume is either an axis-aligned box ({@code shape == BOX}) with
 * {@code halfExtents} or a sphere with {@code radius}. {@code blendDistance}
 * extends the influence beyond that volume; outside the volume the weight
 * ramps continuously to zero at {@code volume + blendDistance}. A zero blend
 * distance is a deliberate hard edge and is reported as such by
 * {@link ReflectionProbeSelector}.</p>
 *
 * <p>The weight field is continuous across the influence boundary: the probe
 * reaches full weight at its centre, falls to half weight at the influence
 * boundary and to zero at the end of the blend shell. That half-weight border
 * is intentional so two overlapping probes can hand over without a visible
 * jump when their weights are normalised. See
 * {@link ReflectionProbeSelector#influenceWeight}.</p>
 *
 * <p>Only specular reflections are local in this first version. The probe
 * replaces the sampled prefiltered environment radiance in
 * {@code pbr-forward.frag}; diffuse irradiance, the BRDF LUT and the clearcoat
 * layer stay global. The source is a static HDR file (no per-frame capture).</p>
 */
public record ReflectionProbeSettings(
        String name,
        boolean enabled,
        Vector3f position,
        Shape shape,
        Vector3f halfExtents,
        float radius,
        float blendDistance,
        Quality quality,
        float intensity,
        float rotationRadians,
        String hdrResource) {
    /** Highest number of probes a set and the PBR shader can consume. */
    public static final int MAX_PROBE_COUNT = 2;
    /** Upper bound of the influence volume / blend shell in world units. */
    public static final float MAX_VOLUME_EXTENT = 4096.0f;

    public ReflectionProbeSettings {
        name = requireText(name, "name");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(halfExtents, "halfExtents");
        Objects.requireNonNull(quality, "quality");
        hdrResource = requireText(hdrResource, "hdrResource");
        requireFinite(position, "position");
        requirePositiveExtent(halfExtents.x, "halfExtents.x");
        requirePositiveExtent(halfExtents.y, "halfExtents.y");
        requirePositiveExtent(halfExtents.z, "halfExtents.z");
        if (!Float.isFinite(radius) || radius <= 0.0f || radius > MAX_VOLUME_EXTENT) {
            throw new IllegalArgumentException("radius must be finite and in (0, "
                    + MAX_VOLUME_EXTENT + "]");
        }
        if (!Float.isFinite(blendDistance) || blendDistance < 0.0f
                || blendDistance > MAX_VOLUME_EXTENT) {
            throw new IllegalArgumentException("blendDistance must be finite and in [0, "
                    + MAX_VOLUME_EXTENT + "]");
        }
        if (!Float.isFinite(intensity) || intensity < 0.0f) {
            throw new IllegalArgumentException("intensity must be finite and non-negative");
        }
        if (!Float.isFinite(rotationRadians)) {
            throw new IllegalArgumentException("rotationRadians must be finite");
        }
        position = new Vector3f(position);
        halfExtents = new Vector3f(halfExtents);
    }

    /** Axis-aligned box influence volume. */
    public static ReflectionProbeSettings box(String name, Vector3f position,
                                              Vector3fc halfExtents, float blendDistance,
                                              Quality quality, float intensity,
                                              float rotationRadians, String hdrResource) {
        Objects.requireNonNull(halfExtents, "halfExtents");
        return new ReflectionProbeSettings(name, true, position, Shape.BOX,
                new Vector3f(halfExtents), halfExtents.x(), blendDistance, quality, intensity,
                rotationRadians, hdrResource);
    }

    /** Spherical influence volume. */
    public static ReflectionProbeSettings sphere(String name, Vector3f position, float radius,
                                                 float blendDistance, Quality quality,
                                                 float intensity, float rotationRadians,
                                                 String hdrResource) {
        return new ReflectionProbeSettings(name, true, position, Shape.SPHERE,
                new Vector3f(radius, radius, radius), radius, blendDistance, quality, intensity,
                rotationRadians, hdrResource);
    }

    /** A disabled copy of this description; disabled probes load no GPU resources. */
    public ReflectionProbeSettings disabledCopy() {
        return new ReflectionProbeSettings(name, false, position, shape, halfExtents, radius,
                blendDistance, quality, intensity, rotationRadians, hdrResource);
    }

    /**
     * @return true when the probe is enabled, has a positive intensity and can
     *         contribute reflection radiance
     */
    public boolean active() {
        return enabled && intensity > 0.0f;
    }

    /** @return the prefiltered cubemap resolution and sampling budget */
    public PbrEnvironmentSettings environmentSettings() {
        return quality.environmentSettings();
    }

    /** Defensive copy; the record value itself is immutable. */
    @Override
    public Vector3f position() {
        return new Vector3f(position);
    }

    /** Defensive copy; the record value itself is immutable. */
    @Override
    public Vector3f halfExtents() {
        return new Vector3f(halfExtents);
    }

    /** Influence volume shape. */
    public enum Shape {
        BOX,
        SPHERE
    }

    /**
     * Quality tiers are intentionally coarse and power-of-two. The prefiltered
     * cubemap stores roughness {@code mip / (mipLevels - 1)}, so a consumer
     * samples {@code roughness * (mipLevels - 1)} exactly like the global IBL.
     */
    public enum Quality {
        TEST(new PbrEnvironmentSettings(16, 8, 16, 16, 32, 64, 64)),
        LOW(new PbrEnvironmentSettings(128, 16, 64, 64, 128, 256, 256)),
        MEDIUM(new PbrEnvironmentSettings(256, 32, 128, 128, 256, 512, 512)),
        HIGH(new PbrEnvironmentSettings(512, 32, 256, 256, 512, 1024, 1024));

        private final PbrEnvironmentSettings environmentSettings;

        Quality(PbrEnvironmentSettings environmentSettings) {
            this.environmentSettings = environmentSettings;
        }

        public PbrEnvironmentSettings environmentSettings() {
            return environmentSettings;
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return stripped;
    }

    private static void requireFinite(Vector3fc value, String name) {
        if (!Float.isFinite(value.x()) || !Float.isFinite(value.y())
                || !Float.isFinite(value.z())) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requirePositiveExtent(float value, String name) {
        if (!Float.isFinite(value) || value <= 0.0f || value > MAX_VOLUME_EXTENT) {
            throw new IllegalArgumentException(name + " must be finite and in (0, "
                    + MAX_VOLUME_EXTENT + "]");
        }
    }
}
