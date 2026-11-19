package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import com.kaleblangley.haikalat.subsystems.render3d.LocalFogVolume;
import com.kaleblangley.haikalat.subsystems.render3d.VolumetricFogSettings;

import org.joml.Vector3f;

import java.util.Objects;

/**
 * Bounded, GL-free configuration of the local analytic volumetric-particle medium.
 *
 * <p>This value describes one finite ellipsoidal density body (a smoke or dust
 * puff) whose bounding box is {@code center ± halfExtent}. The density weight at
 * world position {@code p} is {@code max(0, 1 - r^2)^2} with
 * {@code r^2 = sum(((p - center) / halfExtent)^2)}; it is exactly zero on and
 * outside the ellipsoid. Coefficients are per world metre at unit density:
 * {@code density * (absorption + scattering)} is the extinction and
 * {@code density * scattering} the scattering coefficient. Because both
 * coefficients share the same density weight, the single-scatter term of a
 * constant light over a view ray is exactly
 * {@code light * phase * albedo * (1 - transmittance)} with
 * {@code albedo = scattering / (absorption + scattering)}; no multiple
 * scattering or participating-light coupling is modelled.</p>
 *
 * <p>{@link FogBlend} selects how the particle medium composites over an HDR
 * input that may already contain the volumetric-fog medium. The ordering
 * contract is fixed: this pass is recorded <em>after</em> the volumetric-fog
 * colour composite and applies its own medium exactly once to that colour. It
 * never samples the fog density or re-integrates the fog medium, so the same
 * fog medium cannot be accumulated twice. {@link #fogWeight()} scales the
 * particle optical depth (and therefore the in-scatter through the
 * transmittance) exactly once; {@code 0} is an exact no-op for the particle
 * medium and {@code 1} applies it at full strength. See
 * {@code docs/planning/v0.28.1-s4-volumetric-particles.md}.</p>
 *
 * <p>{@link #validateFogComposition(VolumetricFogSettings)} rejects the one
 * declaration shape that would represent the same medium twice: a positive
 * {@link LocalFogVolume} whose center and extent equal this particle body.
 * Overlapping but different media are distinct media and are each integrated
 * once by their owning pass.</p>
 *
 * <p>Every component is validated at construction. Values are bounded instead
 * of silently clamped, so an author error fails at the configuration boundary.
 * {@link #disabled()} is the exact no-op value: no pass, shader, framebuffer or
 * draw is created while the medium is disabled. Vector components return
 * defensive copies.</p>
 */
public record VolumetricParticleSettings(
        boolean enabled,
        float density,
        float absorption,
        float scattering,
        Vector3f boundsCenter,
        Vector3f boundsHalfExtent,
        int maximumSteps,
        float stepSizeMetres,
        Vector3f lightDirection,
        Vector3f lightColor,
        float anisotropy,
        FogBlend fogBlend,
        float fogWeight,
        float maximumScreenCoverage) {
    /** Largest accepted density scale. */
    public static final float MAXIMUM_DENSITY = 64.0f;
    /** Largest accepted absorption or scattering coefficient per world metre. */
    public static final float MAXIMUM_COEFFICIENT = 64.0f;
    /** Largest accepted absolute bounds coordinate in world units. */
    public static final float MAXIMUM_ABSOLUTE_COORDINATE = 100_000.0f;
    /** Smallest accepted semi-axis of the local ellipsoid in world units. */
    public static final float MINIMUM_EXTENT = 1.0e-3f;
    /** Largest accepted semi-axis of the local ellipsoid in world units. */
    public static final float MAXIMUM_EXTENT = 10_000.0f;
    /** Smallest accepted per-pixel march step budget. */
    public static final int MINIMUM_STEPS = 1;
    /** Largest accepted per-pixel march step budget. */
    public static final int MAXIMUM_STEPS = 64;
    /** Largest accepted target march step in world metres. */
    public static final float MAXIMUM_STEP_SIZE = 64.0f;
    /** Largest accepted linear light-radiance component. */
    public static final float MAXIMUM_LIGHT_COMPONENT = 16.0f;
    /** Largest accepted absolute Henyey-Greenstein anisotropy. */
    public static final float MAXIMUM_ANISOTROPY = 0.9f;

    /** How the particle medium composites over the already fog-composited HDR colour. */
    public enum FogBlend {
        /** Occlude the input colour with the particle transmittance and add the in-scatter. */
        OCCLUDING,
        /** Keep the input colour unchanged and add the in-scatter only. */
        ADDITIVE
    }

    private static final VolumetricParticleSettings DISABLED = new VolumetricParticleSettings(
            false, 1.0f, 0.2f, 0.8f, new Vector3f(), new Vector3f(1.0f),
            32, 0.1f, new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f),
            0.0f, FogBlend.OCCLUDING, 1.0f, 1.0f);

    public VolumetricParticleSettings {
        if (!finite(density) || density < 0.0f || density > MAXIMUM_DENSITY) {
            throw new IllegalArgumentException("density must be finite and in [0, "
                    + MAXIMUM_DENSITY + "]");
        }
        if (!finite(absorption) || absorption < 0.0f || absorption > MAXIMUM_COEFFICIENT) {
            throw new IllegalArgumentException("absorption must be finite and in [0, "
                    + MAXIMUM_COEFFICIENT + "] per world metre");
        }
        if (!finite(scattering) || scattering < 0.0f || scattering > MAXIMUM_COEFFICIENT) {
            throw new IllegalArgumentException("scattering must be finite and in [0, "
                    + MAXIMUM_COEFFICIENT + "] per world metre");
        }
        boundsCenter = coordinate(boundsCenter, "boundsCenter");
        boundsHalfExtent = coordinate(boundsHalfExtent, "boundsHalfExtent");
        if (boundsHalfExtent.x < MINIMUM_EXTENT || boundsHalfExtent.y < MINIMUM_EXTENT
                || boundsHalfExtent.z < MINIMUM_EXTENT
                || boundsHalfExtent.x > MAXIMUM_EXTENT || boundsHalfExtent.y > MAXIMUM_EXTENT
                || boundsHalfExtent.z > MAXIMUM_EXTENT) {
            throw new IllegalArgumentException("boundsHalfExtent components must be in ["
                    + MINIMUM_EXTENT + ", " + MAXIMUM_EXTENT + "]");
        }
        if (maximumSteps < MINIMUM_STEPS || maximumSteps > MAXIMUM_STEPS) {
            throw new IllegalArgumentException("maximumSteps must be in ["
                    + MINIMUM_STEPS + ", " + MAXIMUM_STEPS + "]");
        }
        if (!finite(stepSizeMetres) || stepSizeMetres <= 0.0f
                || stepSizeMetres > MAXIMUM_STEP_SIZE) {
            throw new IllegalArgumentException("stepSizeMetres must be finite and in (0, "
                    + MAXIMUM_STEP_SIZE + "]");
        }
        lightDirection = normalizeDirection(lightDirection);
        lightColor = lightColor(lightColor);
        if (!finite(anisotropy) || Math.abs(anisotropy) > MAXIMUM_ANISOTROPY) {
            throw new IllegalArgumentException("anisotropy must be finite and in [-"
                    + MAXIMUM_ANISOTROPY + ", " + MAXIMUM_ANISOTROPY + "]");
        }
        if (fogBlend == null) {
            throw new IllegalArgumentException("fogBlend must not be null");
        }
        if (!finite(fogWeight) || fogWeight < 0.0f || fogWeight > 1.0f) {            throw new IllegalArgumentException("fogWeight must be finite and in [0, 1]");
        }
        if (!finite(maximumScreenCoverage) || maximumScreenCoverage <= 0.0f
                || maximumScreenCoverage > 1.0f) {
            throw new IllegalArgumentException("maximumScreenCoverage must be finite and in (0, 1]");
        }
    }

    /** Exact no-op value: no particle pass or GPU resource exists while disabled. */
    public static VolumetricParticleSettings disabled() {
        return DISABLED;
    }

    /** @return true when the particle pass and its draw should exist */
    public boolean active() {
        return enabled;
    }

    /** @return a bounded copy with only the enabled flag changed */
    public VolumetricParticleSettings withEnabled(boolean value) {
        return new VolumetricParticleSettings(value, density, absorption, scattering,
                new Vector3f(boundsCenter), new Vector3f(boundsHalfExtent), maximumSteps,
                stepSizeMetres, new Vector3f(lightDirection), new Vector3f(lightColor),
                anisotropy, fogBlend, fogWeight, maximumScreenCoverage);
    }

    /** Extinction coefficient at unit density, in 1/metre. */
    public float extinction() {
        return density * (absorption + scattering);
    }

    /**
     * Single-scattering albedo: {@code scattering / (absorption + scattering)}
     * with an exact {@code 0} fallback when both coefficients are zero.
     */
    public float scatteringAlbedo() {
        float sum = absorption + scattering;
        return sum > 0.0f ? scattering / sum : 0.0f;
    }

    /** @return a defensive copy of the local-body center */
    @Override
    public Vector3f boundsCenter() {
        return new Vector3f(boundsCenter);
    }

    /** @return a defensive copy of the local-body semi-axes */
    @Override
    public Vector3f boundsHalfExtent() {
        return new Vector3f(boundsHalfExtent);
    }

    /** @return a defensive copy of the world-space light-travel direction */
    @Override
    public Vector3f lightDirection() {
        return new Vector3f(lightDirection);
    }

    /** @return a defensive copy of the linear light radiance */
    @Override
    public Vector3f lightColor() {
        return new Vector3f(lightColor);
    }

    Vector3f boundsCenterInternal() {
        return boundsCenter;
    }

    Vector3f boundsHalfExtentInternal() {
        return boundsHalfExtent;
    }

    Vector3f lightDirectionInternal() {
        return lightDirection;
    }

    Vector3f lightColorInternal() {
        return lightColor;
    }

    /**
     * Rejects a volumetric-fog configuration that declares this exact particle
     * body a second time as a positive {@link LocalFogVolume}.
     *
     * <p>The particle medium is owned by this pass and is integrated once by
     * it; the fog medium is owned by the volumetric-fog pass and is integrated
     * once by that pass. A copied local volume with the same center, extent and
     * positive extinction would represent the same visible medium twice, so it
     * is rejected here instead of being composited twice. Different or merely
     * overlapping media are not rejected; the caller then owns the artistic
     * decision and each pass still integrates only its own medium.</p>
     *
     * @param fog the volumetric-fog settings composed in the same frame
     * @throws IllegalArgumentException when a duplicate positive local volume exists
     */
    public void validateFogComposition(VolumetricFogSettings fog) {
        if (fog == null) {
            throw new IllegalArgumentException("fog must not be null");
        }
        if (!fog.enabled()) {
            return;
        }
        for (LocalFogVolume volume : fog.localVolumes()) {
            if (!(volume.extinction() > 0.0f)) {
                continue;
            }
            if (sameExtent(volume, boundsCenter, boundsHalfExtent)) {
                throw new IllegalArgumentException("the particle medium is also declared as a "
                        + "positive LocalFogVolume; the same medium would be composited twice");
            }
        }
    }

    private static boolean sameExtent(LocalFogVolume volume, Vector3f center, Vector3f halfExtent) {
        if (!close(volume.centerInternal(), center)) {
            return false;
        }
        Vector3f extent = volume.extentInternal();
        if (volume.shape() == LocalFogVolume.Shape.SPHERE) {
            return close(extent.x, halfExtent.x) && close(extent.x, halfExtent.y)
                    && close(extent.x, halfExtent.z);
        }
        return close(extent.x, halfExtent.x) && close(extent.y, halfExtent.y)
                && close(extent.z, halfExtent.z);
    }

    private static boolean close(Vector3f left, Vector3f right) {
        return close(left.x, right.x) && close(left.y, right.y) && close(left.z, right.z);
    }

    private static boolean close(float left, float right) {
        float tolerance = 1.0e-4f + 1.0e-4f * Math.max(Math.abs(left), Math.abs(right));
        return Math.abs(left - right) <= tolerance;
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

    private static Vector3f normalizeDirection(Vector3f value) {
        Objects.requireNonNull(value, "lightDirection");
        float lengthSquared = value.lengthSquared();
        if (!Float.isFinite(lengthSquared) || lengthSquared < 1.0e-12f) {
            throw new IllegalArgumentException("lightDirection must be finite and non-zero");
        }
        return new Vector3f(value).mul(1.0f / (float) Math.sqrt(lengthSquared));
    }

    private static Vector3f lightColor(Vector3f value) {
        Objects.requireNonNull(value, "lightColor");
        if (!value.isFinite() || value.x < 0.0f || value.y < 0.0f || value.z < 0.0f
                || value.x > MAXIMUM_LIGHT_COMPONENT || value.y > MAXIMUM_LIGHT_COMPONENT
                || value.z > MAXIMUM_LIGHT_COMPONENT) {
            throw new IllegalArgumentException("lightColor components must be in [0, "
                    + MAXIMUM_LIGHT_COMPONENT + "]");
        }
        return new Vector3f(value);
    }

    private static boolean finite(float value) {
        return Float.isFinite(value);
    }
}
