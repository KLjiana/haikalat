package com.kaleblangley.haikalat.subsystems.postprocess.upscale;

import org.joml.Matrix4f;

import java.util.Objects;

/**
 * Structured inputs for one temporal-upscaling resolve.
 *
 * <p>Textures are borrowed for the duration of the recorded frame.  Two
 * reprojection contracts are accepted:</p>
 *
 * <ul>
 *   <li><b>Velocity mode</b>: {@code velocityTexture} and
 *       {@code previousDepthTexture} are non-zero.  The velocity is a
 *       stable-raster UV displacement of the low-resolution sample and the
 *       expected previous view depth is sampled from the previous stable-raster
 *       depth at the reprojected position.</li>
 *   <li><b>Previous-view-projection mode</b>: both textures are zero.  The
 *       current low-resolution surface is reconstructed in world space and
 *       projected with the pass's previous successful stable view-projection.
 *       This follows camera motion; a moving object is rejected by the depth
 *       check instead of smearing.</li>
 * </ul>
 *
 * <p>{@code jitterUvX/Y} is the low-resolution TAA jitter in UV units that was
 * active when the colour and depth were rasterised; the pass uses it to recover
 * the stable sample position and to rebuild the world position from the jittered
 * inverse projection.  It must be zero when the low-resolution signal is
 * jitter-free, and it must match the projection encoded in
 * {@code inverseProjection}; the jitter itself is not stored in history because
 * the output history raster is the stable display raster.</p>
 *
 * <p>{@code exposure} is a positive scalar used only as a history-rejection
 * signal (auto-exposure or a manual exposure change); the pass never applies
 * exposure, which remains the tone-mapping stage's responsibility.</p>
 */
public record TemporalUpscalingInputs(
        int colorTexture,
        int depthTexture,
        int velocityTexture,
        int previousDepthTexture,
        int reactiveTexture,
        int uiMaskTexture,
        int inputWidth,
        int inputHeight,
        float jitterUvX,
        float jitterUvY,
        float exposure,
        Matrix4f inverseProjection,
        Matrix4f inverseView,
        Matrix4f stableViewProjection
) {
    public TemporalUpscalingInputs {
        if (colorTexture <= 0) {
            throw new IllegalArgumentException(
                    "temporal upscaling requires a low-resolution colour texture");
        }
        if (depthTexture <= 0) {
            throw new IllegalArgumentException("temporal upscaling requires the scene depth");
        }
        if ((velocityTexture > 0) != (previousDepthTexture > 0)) {
            throw new IllegalArgumentException(
                    "velocity and previous depth must be provided together or both omitted");
        }
        if (reactiveTexture < 0 || uiMaskTexture < 0) {
            throw new IllegalArgumentException(
                    "temporal upscaling masks use zero to mean absent");
        }
        if (inputWidth <= 0 || inputHeight <= 0) {
            throw new IllegalArgumentException(
                    "temporal upscaling input extent must be positive");
        }
        if (!Float.isFinite(jitterUvX) || !Float.isFinite(jitterUvY)) {
            throw new IllegalArgumentException("temporal upscaling jitter must be finite");
        }
        if (!Float.isFinite(exposure) || exposure <= 0.0f) {
            throw new IllegalArgumentException(
                    "temporal upscaling exposure must be finite and positive");
        }
        inverseProjection = new Matrix4f(
                Objects.requireNonNull(inverseProjection, "inverseProjection"));
        inverseView = new Matrix4f(Objects.requireNonNull(inverseView, "inverseView"));
        stableViewProjection = new Matrix4f(
                Objects.requireNonNull(stableViewProjection, "stableViewProjection"));
    }

    /** Source-compatible no-jitter constructor for fixed-camera consumers. */
    public TemporalUpscalingInputs(int colorTexture, int depthTexture, int velocityTexture,
                                   int previousDepthTexture, int reactiveTexture,
                                   int uiMaskTexture, int inputWidth, int inputHeight,
                                   float exposure, Matrix4f inverseProjection,
                                   Matrix4f inverseView, Matrix4f stableViewProjection) {
        this(colorTexture, depthTexture, velocityTexture, previousDepthTexture,
                reactiveTexture, uiMaskTexture, inputWidth, inputHeight,
                0.0f, 0.0f, exposure, inverseProjection, inverseView,
                stableViewProjection);
    }

    /** @return true when the frozen velocity/previous-depth reprojection is used */
    public boolean usesVelocity() {
        return velocityTexture > 0;
    }

    public boolean hasReactiveMask() {
        return reactiveTexture > 0;
    }

    public boolean hasUiMask() {
        return uiMaskTexture > 0;
    }
}
