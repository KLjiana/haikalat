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
 *       stable-raster UV displacement of the low-resolution sample, so the
 *       previous stable position is the current output UV plus that
 *       displacement; the expected previous view depth is sampled from the
 *       previous stable-raster depth there.</li>
 *   <li><b>Previous-view-projection mode</b>: both textures are zero.  The
 *       current surface is reconstructed in world space at the stable output
 *       position (the jittered inverse projection recovers it from the
 *       jitter-shifted sample) and projected with the pass's previous successful
 *       stable view-projection.  This follows camera motion; a moving object is
 *       rejected by the depth check instead of smearing.</li>
 * </ul>
 *
 * <p>The stable output position, not the containing low-resolution texel centre,
 * is the reprojection origin.  The texel centre is only used to fetch the
 * depth/velocity/mask channels and the spatial clamp neighbourhood; using it as
 * the reprojection origin would offset history by up to half a low-resolution
 * texel and blur high-frequency detail.</p>
 *
 * <p>{@code jitterUvX/Y} is the geometry displacement in the low-resolution
 * raster's normalized UV produced by the jittered projection that rasterised
 * the colour and depth.  The shader samples
 * {@code outputUv + jitter}, and a surface that would be rasterised at the
 * stable output position {@code s} appears at {@code s + jitter}, so this value
 * is exactly the displacement to add.  It must match the projection encoded in
 * {@code inverseProjection}; it is normalized by the <b>low-resolution</b>
 * extent (a sub-pixel offset in reduced-raster pixels), not by the output
 * extent, because the reduced and output rasters share the same normalized-UV
 * frustum and a UV delta is dimensionally shared.  At a 0.5 render scale a
 * quarter reduced pixel is half an output pixel.  The internal reduced-scene
 * chain passes the value captured in the R frame pose and keeps the output
 * post-process pose unjittered; a caller source passes its own input jitter.
 * The jitter is not stored in history because the output history raster is the
 * stable display raster.</p>
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
