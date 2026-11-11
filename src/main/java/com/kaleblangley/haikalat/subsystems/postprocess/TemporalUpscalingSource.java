package com.kaleblangley.haikalat.subsystems.postprocess;

import java.util.Objects;

/**
 * Borrowed low-resolution attachments handed to the colour chain for temporal
 * upscaling.
 *
 * <p>The host renders the scene into these attachments at the reduced extent and
 * keeps ownership of them; the colour chain only imports the descriptors and
 * samples the texture names.  The set is fixed for the lifetime of an assembled
 * generation: a different extent or a reallocated low-resolution target requires
 * a candidate generation, exactly like any other borrowed surface.  The
 * {@code width}/{@code height} pair is the actual low-resolution extent; it does
 * not have to equal {@link TemporalUpscalingSettings#inputWidth(int)} (a dynamic
 * resolution host may pick another positive extent), but the pipeline rejects a
 * non-positive or larger-than-output source so a miswired host fails loudly
 * instead of sampling an invalid descriptor.</p>
 *
 * <p>Formats are fixed by the shared colour-chain contract:</p>
 * <ul>
 *   <li>{@code colorTexture}: linear HDR {@code RGBA16F};</li>
 *   <li>{@code depthTexture}: single-sample device depth
 *       {@code DEPTH_COMPONENT24};</li>
 *   <li>{@code velocityTexture}: stable-raster UV displacement
 *       {@code RG16F} (paired with {@code previousDepthTexture});</li>
 *   <li>{@code previousDepthTexture}: positive view-space depth {@code R32F} in
 *       the previous stable raster (paired with the velocity);</li>
 *   <li>{@code reactiveTexture}/{@code uiMaskTexture}: optional {@code R8}
 *       masks; {@code 0} means absent.</li>
 * </ul>
 */
public record TemporalUpscalingSource(
        int colorTexture,
        int depthTexture,
        int velocityTexture,
        int previousDepthTexture,
        int reactiveTexture,
        int uiMaskTexture,
        int width,
        int height
) {
    public TemporalUpscalingSource {
        if (colorTexture <= 0) {
            throw new IllegalArgumentException(
                    "temporal upscaling requires a low-resolution colour texture");
        }
        if (depthTexture <= 0) {
            throw new IllegalArgumentException(
                    "temporal upscaling requires the low-resolution scene depth");
        }
        if ((velocityTexture > 0) != (previousDepthTexture > 0)) {
            throw new IllegalArgumentException(
                    "velocity and previous depth must be provided together or both omitted");
        }
        if (reactiveTexture < 0 || uiMaskTexture < 0) {
            throw new IllegalArgumentException(
                    "temporal upscaling masks use zero to mean absent");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "temporal upscaling source extent must be positive");
        }
    }

    /** Source without reactive/UI masks and without a velocity pair. */
    public TemporalUpscalingSource(int colorTexture, int depthTexture, int width, int height) {
        this(colorTexture, depthTexture, 0, 0, 0, 0, width, height);
    }

    /** Source with a velocity pair but without reactive/UI masks. */
    public TemporalUpscalingSource(int colorTexture, int depthTexture, int velocityTexture,
                                   int previousDepthTexture, int width, int height) {
        this(colorTexture, depthTexture, velocityTexture, previousDepthTexture, 0, 0,
                width, height);
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

    /**
     * Fails when the imported descriptors and the live settings disagree about
     * the magnification contract.
     *
     * @param settings active temporal-upscaling settings
     * @param outputWidth colour-chain output width
     * @param outputHeight colour-chain output height
     */
    public void validateAgainst(TemporalUpscalingSettings settings, int outputWidth,
                                int outputHeight) {
        Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            throw new IllegalStateException(
                    "a temporal upscaling source requires active temporal upscaling");
        }
        if (outputWidth <= 0 || outputHeight <= 0) {
            throw new IllegalArgumentException("colour-chain extent must be positive");
        }
        if (width > outputWidth || height > outputHeight) {
            throw new IllegalArgumentException("temporal upscaling input extent " + width + "x"
                    + height + " must not exceed the output extent " + outputWidth + "x"
                    + outputHeight);
        }
    }
}
