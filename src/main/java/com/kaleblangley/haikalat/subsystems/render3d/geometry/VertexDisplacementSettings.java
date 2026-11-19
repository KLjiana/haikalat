package com.kaleblangley.haikalat.subsystems.render3d.geometry;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

/**
 * Immutable first-version vertex displacement model: a local-space sinusoidal
 * height offset along {@code axis}, with the wave phase advancing along
 * {@code waveDirection} and time.
 *
 * <p>The model is intentionally bounded and analytic:</p>
 * <pre>
 * phase  = 2*pi * (frequency * dot(localPosition, waveDirection) + speed * time) + phase0
 * offset = axis * amplitude * sin(phase)
 * </pre>
 *
 * <p>Normals use the exact inverse-transpose gradient of the same offset field,
 * {@code n' = normalize(n - waveDirection * slope * dot(axis, n)
 * / (1 + slope * dot(axis, waveDirection)))} with
 * {@code slope = 2*pi * frequency * amplitude * cos(phase)}.  The denominator
 * falls back to the unmodified normal only when it is too close to zero to
 * invert safely.  Positions and normals are evaluated in the mesh's local/rest
 * space, before skinning and model transforms.  The caller supplies time; no
 * hidden clock or random seed is used, so the same inputs always produce the
 * same deformation.</p>
 *
 * <p>{@link #disabled()} is the exact no-op value: no offset, unchanged normal,
 * zero bounds padding.  Amplitude, frequency, speed, phase and the two
 * directions are validated at construction; invalid input is rejected instead
 * of silently changing the authored effect.</p>
 */
public record VertexDisplacementSettings(
        boolean enabled,
        float amplitude,
        float frequency,
        float speed,
        float phase,
        Vector3f axis,
        Vector3f waveDirection) {
    public static final float MAX_AMPLITUDE = 0.25f;
    public static final float MAX_FREQUENCY = 4.0f;
    public static final float MAX_SPEED = 4.0f;
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final float NORMAL_EPSILON = 1.0e-12f;

    public VertexDisplacementSettings {
        if (!Float.isFinite(amplitude) || amplitude < 0.0f || amplitude > MAX_AMPLITUDE) {
            throw new IllegalArgumentException("amplitude must be finite and in [0, "
                    + MAX_AMPLITUDE + "]");
        }
        if (!Float.isFinite(frequency) || frequency < 0.0f || frequency > MAX_FREQUENCY) {
            throw new IllegalArgumentException("frequency must be finite and in [0, "
                    + MAX_FREQUENCY + "]");
        }
        if (!Float.isFinite(speed) || Math.abs(speed) > MAX_SPEED) {
            throw new IllegalArgumentException("speed must be finite and in [-"
                    + MAX_SPEED + ", " + MAX_SPEED + "]");
        }
        if (!Float.isFinite(phase)) {
            throw new IllegalArgumentException("phase must be finite");
        }
        axis = normalized(axis, "axis");
        waveDirection = normalized(waveDirection, "waveDirection");
        phase = canonicalPhase(phase);
    }

    /** Default-off value; consumers that do not opt in must use this. */
    public static VertexDisplacementSettings disabled() {
        return new VertexDisplacementSettings(false, 0.0f, 0.0f, 0.0f, 0.0f,
                new Vector3f(0.0f, 1.0f, 0.0f), new Vector3f(1.0f, 0.0f, 0.0f));
    }

    /** Bounded local-space sinusoidal model with the default up/lateral axes. */
    public static VertexDisplacementSettings localSinusoidal(float amplitude, float frequency,
                                                             float speed, float phase) {
        return new VertexDisplacementSettings(true, amplitude, frequency, speed, phase,
                new Vector3f(0.0f, 1.0f, 0.0f), new Vector3f(1.0f, 0.0f, 0.0f));
    }

    /** @return true when the model currently changes vertex positions */
    public boolean active() {
        return enabled && amplitude > 0.0f;
    }

    /** Conservative local-space expansion needed by visibility bounds. */
    public float maxOffset() {
        return active() ? amplitude : 0.0f;
    }

    /** @return the local-space offset added to {@code localPosition} at {@code timeSeconds} */
    public Vector3f offset(Vector3fc localPosition, float timeSeconds, Vector3f destination) {
        Objects.requireNonNull(localPosition, "localPosition");
        Objects.requireNonNull(destination, "destination");
        if (!active()) {
            return destination.set(0.0f);
        }
        float angle = wavePhase(localPosition, timeSeconds);
        return destination.set(axis).mul(amplitude * (float) Math.sin(angle));
    }

    /**
     * @return the displaced local position; the input vector is left untouched
     */
    public Vector3f displacedPosition(Vector3fc localPosition, float timeSeconds,
                                      Vector3f destination) {
        Objects.requireNonNull(localPosition, "localPosition");
        Objects.requireNonNull(destination, "destination");
        if (!active()) {
            return destination.set(localPosition);
        }
        float angle = wavePhase(localPosition, timeSeconds);
        return destination.set(localPosition)
                .fma(amplitude * (float) Math.sin(angle), axis);
    }

    /**
     * Analytic first-order displaced normal.  This is an independent math
     * counterpart of the shader block, not a finite-difference approximation.
     *
     * @return displaced local normal, or the input normal when the model is inactive
     */
    public Vector3f displacedNormal(Vector3fc localPosition, Vector3fc localNormal,
                                    float timeSeconds, Vector3f destination) {
        Objects.requireNonNull(localPosition, "localPosition");
        Objects.requireNonNull(localNormal, "localNormal");
        Objects.requireNonNull(destination, "destination");
        if (!active()) {
            return destination.set(localNormal);
        }
        float angle = wavePhase(localPosition, timeSeconds);
        float slope = TWO_PI * frequency * amplitude * (float) Math.cos(angle);
        float denominator = 1.0f + slope * axis.dot(waveDirection);
        if (Math.abs(denominator) <= 1.0e-4f) {
            return destination.set(localNormal);
        }
        float axisAlongNormal = axis.dot(localNormal);
        destination.set(localNormal).fma(-slope * axisAlongNormal / denominator, waveDirection);
        float lengthSquared = destination.lengthSquared();
        return lengthSquared > NORMAL_EPSILON ? destination.normalize() : destination.set(localNormal);
    }

    /** @return phase angle in radians for the supplied local position and time */
    public float wavePhase(Vector3fc localPosition, float timeSeconds) {
        Objects.requireNonNull(localPosition, "localPosition");
        if (!Float.isFinite(timeSeconds)) {
            throw new IllegalArgumentException("timeSeconds must be finite");
        }
        return TWO_PI * (frequency * waveDirection.dot(localPosition)
                + speed * timeSeconds) + phase;
    }

    /** Axis in the local mesh space; returned copy protects the record value. */
    @Override
    public Vector3f axis() {
        return new Vector3f(axis);
    }

    /** Wave propagation direction in local mesh space; returned copy. */
    @Override
    public Vector3f waveDirection() {
        return new Vector3f(waveDirection);
    }

    /** Axis packed for allocation-free render recording. */
    public void axisTo(Vector3f destination) {
        destination.set(axis);
    }

    /** Wave direction packed for allocation-free render recording. */
    public void waveDirectionTo(Vector3f destination) {
        destination.set(waveDirection);
    }

    private static Vector3f normalized(Vector3fc value, String name) {
        Objects.requireNonNull(value, name);
        Vector3f result = new Vector3f(value);
        if (!finite(result) || result.lengthSquared() <= NORMAL_EPSILON) {
            throw new IllegalArgumentException(name + " must be finite and non-zero");
        }
        return result.normalize();
    }

    private static boolean finite(Vector3fc value) {
        return Float.isFinite(value.x()) && Float.isFinite(value.y())
                && Float.isFinite(value.z());
    }

    private static float canonicalPhase(float value) {
        float result = value % TWO_PI;
        if (result < 0.0f) result += TWO_PI;
        return result;
    }
}
