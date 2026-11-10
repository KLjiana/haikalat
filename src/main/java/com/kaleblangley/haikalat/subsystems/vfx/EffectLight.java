package com.kaleblangley.haikalat.subsystems.vfx;

import com.kaleblangley.haikalat.core.curve.FloatTrack;
import org.joml.Vector3f;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable effect-space light definition.
 *
 * <p>An instance samples it against its own deterministic effect time.  The
 * light is a shadowless point light whose lifetime is the half-open interval
 * {@code [startSeconds, endSeconds)}; it does not exist before or after that
 * window, which lets the release/impact/fade phases own independent lights
 * without spawning a light per particle.  The optional curves are normalized
 * multipliers over the active window: the final intensity/range is
 * {@code base × curve}, so an authored zero stays zero.</p>
 */
public record EffectLight(float startSeconds, float endSeconds, Vector3f offset,
                          Vector3f color, float intensity, float range, int priority,
                          FloatTrack intensityOverLife, FloatTrack rangeOverLife) {
    public EffectLight {
        if (!Float.isFinite(startSeconds) || startSeconds < 0.0f) {
            throw new IllegalArgumentException("light startSeconds must be finite and non-negative");
        }
        if (!Float.isFinite(endSeconds) || endSeconds <= startSeconds) {
            throw new IllegalArgumentException("light endSeconds must be finite and greater than startSeconds");
        }
        offset = new Vector3f(Objects.requireNonNull(offset, "offset"));
        color = new Vector3f(Objects.requireNonNull(color, "color"));
        requireFinite(offset, "offset");
        requireFinite(color, "color");
        if (color.x < 0.0f || color.y < 0.0f || color.z < 0.0f) {
            throw new IllegalArgumentException("light color components must be non-negative");
        }
        if (!Float.isFinite(intensity) || intensity < 0.0f) {
            throw new IllegalArgumentException("light intensity must be finite and non-negative");
        }
        if (!Float.isFinite(range) || range <= 0.0f) {
            throw new IllegalArgumentException("light range must be finite and positive");
        }
    }

    public static EffectLight steady(float startSeconds, float endSeconds, Vector3f offset,
                                     Vector3f color, float intensity, float range, int priority) {
        return new EffectLight(startSeconds, endSeconds, offset, color, intensity, range,
                priority, null, null);
    }

    public boolean activeAt(float effectTimeSeconds) {
        if (!Float.isFinite(effectTimeSeconds)) {
            throw new IllegalArgumentException("effectTimeSeconds must be finite");
        }
        return effectTimeSeconds >= startSeconds && effectTimeSeconds < endSeconds;
    }

    /** Normalized progress inside the active window; callers must check {@link #activeAt}. */
    public float progressAt(float effectTimeSeconds) {
        if (!activeAt(effectTimeSeconds)) {
            throw new IllegalArgumentException("effect time is outside the light window");
        }
        return (effectTimeSeconds - startSeconds) / (endSeconds - startSeconds);
    }

    public float intensityAt(float effectTimeSeconds) {
        float progress = progressAt(effectTimeSeconds);
        if (intensityOverLife == null) return intensity;
        float sampled = intensityOverLife.sample(progress);
        if (!Float.isFinite(sampled) || sampled < 0.0f) {
            throw new IllegalStateException("effect light intensity curve must produce finite non-negative values");
        }
        float result = intensity * sampled;
        if (!Float.isFinite(result)) {
            throw new IllegalStateException("effect light intensity curve overflowed");
        }
        return result;
    }

    public float rangeAt(float effectTimeSeconds) {
        float progress = progressAt(effectTimeSeconds);
        if (rangeOverLife == null) return range;
        float sampled = rangeOverLife.sample(progress);
        if (!Float.isFinite(sampled) || sampled <= 0.0f) {
            throw new IllegalStateException("effect light range curve must produce finite positive values");
        }
        float result = range * sampled;
        if (!Float.isFinite(result)) {
            throw new IllegalStateException("effect light range curve overflowed");
        }
        return result;
    }

    public Optional<FloatTrack> intensityTrack() {
        return Optional.ofNullable(intensityOverLife);
    }

    public Optional<FloatTrack> rangeTrack() {
        return Optional.ofNullable(rangeOverLife);
    }

    @Override
    public Vector3f offset() {
        return new Vector3f(offset);
    }

    @Override
    public Vector3f color() {
        return new Vector3f(color);
    }

    private static void requireFinite(Vector3f value, String name) {
        if (!Float.isFinite(value.x) || !Float.isFinite(value.y) || !Float.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must contain finite values");
        }
    }
}
