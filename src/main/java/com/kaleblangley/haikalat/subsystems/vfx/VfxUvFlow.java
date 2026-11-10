package com.kaleblangley.haikalat.subsystems.vfx;

import org.joml.Vector2f;

import java.util.Objects;

/**
 * Immutable UV flow and local noise distortion for a VFX material.
 *
 * <p>Velocity is expressed in local UV units per second and is driven by the
 * deterministic effect time supplied with the frame snapshot; the shader never
 * reads a wall clock.  Distortion is sampled from the renderer-owned linear
 * noise texture, so neither time nor velocity participates in the texture
 * cache identity.</p>
 */
public record VfxUvFlow(Vector2f velocity, float noiseScale, float noiseAmplitude, long noiseSeed) {
    public VfxUvFlow {
        velocity = new Vector2f(Objects.requireNonNull(velocity, "velocity"));
        if (!Float.isFinite(velocity.x) || !Float.isFinite(velocity.y)) {
            throw new IllegalArgumentException("uv flow velocity must be finite");
        }
        if (!Float.isFinite(noiseScale) || noiseScale <= 0.0f) {
            throw new IllegalArgumentException("uv flow noiseScale must be finite and positive");
        }
        if (!Float.isFinite(noiseAmplitude) || noiseAmplitude < 0.0f || noiseAmplitude > 1.0f) {
            throw new IllegalArgumentException("uv flow noiseAmplitude must be finite and in [0, 1]");
        }
    }

    public static VfxUvFlow of(float velocityU, float velocityV,
                               float noiseScale, float noiseAmplitude, long noiseSeed) {
        return new VfxUvFlow(new Vector2f(velocityU, velocityV),
                noiseScale, noiseAmplitude, noiseSeed);
    }

    /** Applies the documented order to a local UV for the independent reference. */
    public Vector2f sample(float localU, float localV, float timeSeconds, float noise) {
        if (!Float.isFinite(localU) || !Float.isFinite(localV)
                || !Float.isFinite(timeSeconds) || !Float.isFinite(noise)) {
            throw new IllegalArgumentException("uv flow inputs must be finite");
        }
        float u = localU + velocity.x * timeSeconds;
        float v = localV + velocity.y * timeSeconds;
        float centered = noise * 2.0f - 1.0f;
        u += centered * noiseAmplitude;
        v += centered * noiseAmplitude;
        return new Vector2f(clamp01(u), clamp01(v));
    }

    @Override
    public Vector2f velocity() {
        return new Vector2f(velocity);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
