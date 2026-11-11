package com.kaleblangley.haikalat.subsystems.postprocess.denoise;

import org.joml.Matrix4f;

import java.util.Objects;

/**
 * Structured inputs for one temporal-denoise resolve.
 *
 * <p>Textures are borrowed for the duration of the recorded frame; zero means
 * the channel is unavailable.  Two reprojection contracts are accepted:</p>
 *
 * <ul>
 *   <li><b>Velocity mode</b>: {@code velocityTexture} and
 *       {@code previousDepthTexture} are non-zero.  The history UV is
 *       {@code currentUv + velocity} and the expected previous view depth is
 *       sampled from {@code previousDepthTexture}.  This mode follows camera
 *       and rigid-object motion and matches the frozen TAA surface contract.</li>
 *   <li><b>Previous-view-projection mode</b>: both textures are zero.  The
 *       current surface is reconstructed in world space and projected with the
 *       previous successful stable view-projection held by the pass.  This
 *       mode follows camera motion; a moving object whose previous surface is
 *       no longer at the projected point is rejected by the depth/normal
 *       checks.</li>
 * </ul>
 *
 * <p>{@code inverseProjection} is the inverse of the raster projection used to
 * produce the depth (jitter included when a TAA jitter was active).
 * {@code stableViewProjection} is the jitter-free view-projection stored as the
 * next frame's reprojection matrix after a successful commit.
 * {@code jitterUvX/Y} is the current TAA UV jitter; the pass remembers it and
 * converts both reprojection modes back to the previous jittered raster.</p>
 */
public record TemporalDenoiseInputs(
        int noisyColorTexture,
        int depthTexture,
        int normalTexture,
        int velocityTexture,
        int previousDepthTexture,
        float jitterUvX,
        float jitterUvY,
        Matrix4f inverseProjection,
        Matrix4f inverseView,
        Matrix4f stableViewProjection
) {
    public TemporalDenoiseInputs {
        if (noisyColorTexture <= 0) {
            throw new IllegalArgumentException("temporal denoise requires a noisy colour texture");
        }
        if (depthTexture <= 0) {
            throw new IllegalArgumentException("temporal denoise requires scene depth");
        }
        if (normalTexture <= 0) {
            throw new IllegalArgumentException("temporal denoise requires the world normal");
        }
        if ((velocityTexture > 0) != (previousDepthTexture > 0)) {
            throw new IllegalArgumentException(
                    "velocity and previous depth must be provided together or both omitted");
        }
        if (!Float.isFinite(jitterUvX) || !Float.isFinite(jitterUvY)) {
            throw new IllegalArgumentException("temporal denoise jitter must be finite");
        }
        inverseProjection = new Matrix4f(
                Objects.requireNonNull(inverseProjection, "inverseProjection"));
        inverseView = new Matrix4f(Objects.requireNonNull(inverseView, "inverseView"));
        stableViewProjection = new Matrix4f(
                Objects.requireNonNull(stableViewProjection, "stableViewProjection"));
    }

    /** Source-compatible no-jitter constructor for fixed-camera consumers. */
    public TemporalDenoiseInputs(int noisyColorTexture, int depthTexture, int normalTexture,
                                 int velocityTexture, int previousDepthTexture,
                                 Matrix4f inverseProjection, Matrix4f inverseView,
                                 Matrix4f stableViewProjection) {
        this(noisyColorTexture, depthTexture, normalTexture, velocityTexture,
                previousDepthTexture, 0.0f, 0.0f, inverseProjection, inverseView,
                stableViewProjection);
    }

    /** @return true when the frozen velocity/previous-depth reprojection is used */
    public boolean usesVelocity() {
        return velocityTexture > 0;
    }
}
