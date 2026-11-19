package com.kaleblangley.haikalat.subsystems.postprocess;

import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One bounded screen-space distortion contribution consumed by the colour
 * chain after the HDR VFX composite.
 *
 * <p>The record is pure data: a radial/elliptical mask in UV space, the peak
 * UV offset, the effect depth used for foreground rejection and the optional
 * shockwave/heat-haze shaping.  The colour chain never samples the colour
 * attachment it is writing; the contribution is applied by a dedicated pass
 * that reads the composited HDR colour and writes a different attachment.</p>
 *
 * <p>Composition order is frozen by {@link #orderForComposite(List, int)}:
 * far-to-near by {@link #effectDepth()}, ties broken by the stable
 * {@code (effectId, distortionIndex)} identity.  Nearer contributions win where
 * masks overlap, matching the transparent far-to-near contract of
 * {@code EffectSnapshot.transparentDrawOrder()}.</p>
 */
public record ScreenDistortion(long effectId, int distortionIndex,
                               Vector2f centerUv, Vector2f extentUv,
                               Vector2f uvOffset, float mask,
                               float effectDepth, float depthThickness,
                               float ring, float haze, float phase) {
    /** Hard upper bound of contributions any single frame may composite. */
    public static final int MAXIMUM_CONTRIBUTIONS = 8;
    /** Largest accepted author-supplied UV offset per axis. */
    public static final float MAXIMUM_AUTHORED_OFFSET_UV = 0.25f;
    /** Largest accepted screen-space half extent; > 1 covers the viewport. */
    public static final float MAXIMUM_EXTENT_UV = 4.0f;

    public ScreenDistortion {
        if (effectId <= 0L) throw new IllegalArgumentException("effectId must be positive");
        if (distortionIndex < 0) {
            throw new IllegalArgumentException("distortionIndex must be non-negative");
        }
        centerUv = new Vector2f(Objects.requireNonNull(centerUv, "centerUv"));
        extentUv = new Vector2f(Objects.requireNonNull(extentUv, "extentUv"));
        uvOffset = new Vector2f(Objects.requireNonNull(uvOffset, "uvOffset"));
        requireFinite(centerUv, "centerUv");
        requireFinite(extentUv, "extentUv");
        requireFinite(uvOffset, "uvOffset");
        if (centerUv.x < 0.0f || centerUv.x > 1.0f
                || centerUv.y < 0.0f || centerUv.y > 1.0f) {
            throw new IllegalArgumentException("centerUv must be inside [0, 1]");
        }
        if (extentUv.x <= 0.0f || extentUv.y <= 0.0f
                || extentUv.x > MAXIMUM_EXTENT_UV || extentUv.y > MAXIMUM_EXTENT_UV) {
            throw new IllegalArgumentException("extentUv must be in (0, "
                    + MAXIMUM_EXTENT_UV + "]");
        }
        if (Math.abs(uvOffset.x) > MAXIMUM_AUTHORED_OFFSET_UV
                || Math.abs(uvOffset.y) > MAXIMUM_AUTHORED_OFFSET_UV) {
            throw new IllegalArgumentException("uvOffset must stay within +/-"
                    + MAXIMUM_AUTHORED_OFFSET_UV);
        }
        requireUnit(mask, "mask");
        requireUnit(effectDepth, "effectDepth");
        requireUnit(depthThickness, "depthThickness");
        requireUnit(ring, "ring");
        requireUnit(haze, "haze");
        if (!Float.isFinite(phase) || phase < 0.0f) {
            throw new IllegalArgumentException("phase must be finite and non-negative");
        }
    }

    @Override
    public Vector2f centerUv() {
        return new Vector2f(centerUv);
    }

    @Override
    public Vector2f extentUv() {
        return new Vector2f(extentUv);
    }

    @Override
    public Vector2f uvOffset() {
        return new Vector2f(uvOffset);
    }

    public Key key() {
        return new Key(effectId, distortionIndex);
    }

    /**
     * Returns the bounded, deterministic composite order.
     *
     * <p>The input may arrive in any order.  The result is sorted far-to-near
     * so nearer contributions overwrite farther ones where masks overlap.  When
     * more than {@code maximum} contributions are supplied the farthest entries
     * are dropped and the nearest {@code maximum} are kept, which bounds both
     * the uniform payload and the visual cost.  Duplicate identities are
     * rejected because they would make overlap resolution ambiguous.</p>
     *
     * @param contributions submitted contributions; may be empty
     * @param maximum       positive bound, at most {@link #MAXIMUM_CONTRIBUTIONS}
     * @return immutable ordered list, never {@code null}
     */
    public static List<ScreenDistortion> orderForComposite(
            List<ScreenDistortion> contributions, int maximum) {
        Objects.requireNonNull(contributions, "contributions");
        if (maximum <= 0 || maximum > MAXIMUM_CONTRIBUTIONS) {
            throw new IllegalArgumentException("maximum must be in [1, "
                    + MAXIMUM_CONTRIBUTIONS + "]");
        }
        List<ScreenDistortion> ordered = new ArrayList<>(contributions.size());
        Set<Key> keys = new HashSet<>(contributions.size() * 2);
        for (ScreenDistortion contribution : contributions) {
            Objects.requireNonNull(contribution, "contribution");
            if (!keys.add(contribution.key())) {
                throw new IllegalArgumentException(
                        "duplicate screen distortion key " + contribution.key());
            }
            ordered.add(contribution);
        }
        ordered.sort(Comparator.comparingDouble(ScreenDistortion::effectDepth).reversed()
                .thenComparingLong(ScreenDistortion::effectId)
                .thenComparingInt(ScreenDistortion::distortionIndex));
        if (ordered.size() > maximum) {
            return List.copyOf(ordered.subList(ordered.size() - maximum, ordered.size()));
        }
        return List.copyOf(ordered);
    }

    /** Stable effect-local identity of a distortion contribution. */
    public record Key(long effectId, int distortionIndex) {
        public Key {
            if (effectId <= 0L) throw new IllegalArgumentException("effectId must be positive");
            if (distortionIndex < 0) {
                throw new IllegalArgumentException("distortionIndex must be non-negative");
            }
        }
    }

    private static void requireUnit(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be finite and in [0, 1]");
        }
    }

    private static void requireFinite(Vector2f value, String name) {
        if (!Float.isFinite(value.x) || !Float.isFinite(value.y)) {
            throw new IllegalArgumentException(name + " must contain finite values");
        }
    }
}
