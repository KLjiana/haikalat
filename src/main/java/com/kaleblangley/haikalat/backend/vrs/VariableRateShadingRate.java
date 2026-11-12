package com.kaleblangley.haikalat.backend.vrs;

import static org.lwjgl.opengl.NVShadingRateImage.GL_SHADING_RATE_1_INVOCATION_PER_1X2_PIXELS_NV;
import static org.lwjgl.opengl.NVShadingRateImage.GL_SHADING_RATE_1_INVOCATION_PER_2X1_PIXELS_NV;
import static org.lwjgl.opengl.NVShadingRateImage.GL_SHADING_RATE_1_INVOCATION_PER_2X2_PIXELS_NV;
import static org.lwjgl.opengl.NVShadingRateImage.GL_SHADING_RATE_1_INVOCATION_PER_2X4_PIXELS_NV;
import static org.lwjgl.opengl.NVShadingRateImage.GL_SHADING_RATE_1_INVOCATION_PER_4X2_PIXELS_NV;
import static org.lwjgl.opengl.NVShadingRateImage.GL_SHADING_RATE_1_INVOCATION_PER_4X4_PIXELS_NV;
import static org.lwjgl.opengl.NVShadingRateImage.GL_SHADING_RATE_1_INVOCATION_PER_PIXEL_NV;

/**
 * One shading-rate value accepted by {@code GL_NV_shading_rate_image}'s palette.
 *
 * <p>Each value describes the pixel footprint covered by a single fragment
 * shader invocation.  {@link #ONE_BY_ONE} is the ordinary full-rate path; the
 * other values trade fragment shading resolution for throughput.  The enum
 * deliberately omits {@code GL_SHADING_RATE_NO_INVOCATIONS_NV}, which would
 * skip shading entirely and cannot produce a usable colour result.</p>
 *
 * <p>This type carries no OpenGL state and is safe to use from pure decision
 * and plan code.  The constant values match the values LWJGL exposes for
 * {@code NV_shading_rate_image}.</p>
 */
public enum VariableRateShadingRate {
    /** One fragment invocation per pixel (the unmodified full-rate path). */
    ONE_BY_ONE(1, 1, GL_SHADING_RATE_1_INVOCATION_PER_PIXEL_NV),
    /** One fragment invocation per 1x2 pixel rectangle. */
    ONE_BY_TWO(1, 2, GL_SHADING_RATE_1_INVOCATION_PER_1X2_PIXELS_NV),
    /** One fragment invocation per 2x1 pixel rectangle. */
    TWO_BY_ONE(2, 1, GL_SHADING_RATE_1_INVOCATION_PER_2X1_PIXELS_NV),
    /** One fragment invocation per 2x2 pixel rectangle. */
    TWO_BY_TWO(2, 2, GL_SHADING_RATE_1_INVOCATION_PER_2X2_PIXELS_NV),
    /** One fragment invocation per 2x4 pixel rectangle. */
    TWO_BY_FOUR(2, 4, GL_SHADING_RATE_1_INVOCATION_PER_2X4_PIXELS_NV),
    /** One fragment invocation per 4x2 pixel rectangle. */
    FOUR_BY_TWO(4, 2, GL_SHADING_RATE_1_INVOCATION_PER_4X2_PIXELS_NV),
    /** One fragment invocation per 4x4 pixel rectangle. */
    FOUR_BY_FOUR(4, 4, GL_SHADING_RATE_1_INVOCATION_PER_4X4_PIXELS_NV);

    private final int widthPixels;
    private final int heightPixels;
    private final int glValue;

    VariableRateShadingRate(int widthPixels, int heightPixels, int glValue) {
        this.widthPixels = widthPixels;
        this.heightPixels = heightPixels;
        this.glValue = glValue;
    }

    /** @return horizontal pixels covered by one fragment invocation */
    public int widthPixels() {
        return widthPixels;
    }

    /** @return vertical pixels covered by one fragment invocation */
    public int heightPixels() {
        return heightPixels;
    }

    /** @return pixels covered by one fragment invocation */
    public int pixelCount() {
        return widthPixels * heightPixels;
    }

    /** @return the {@code GL_NV_shading_rate_image} palette enum value */
    public int glValue() {
        return glValue;
    }

    /** @return whether this rate shades at the ordinary one-invocation-per-pixel rate */
    public boolean fullRate() {
        return this == ONE_BY_ONE;
    }

    /** @return a compact human-readable footprint such as {@code 2x2} */
    public String footprint() {
        return widthPixels + "x" + heightPixels;
    }
}
