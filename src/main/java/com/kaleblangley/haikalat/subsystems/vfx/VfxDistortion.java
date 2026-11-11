package com.kaleblangley.haikalat.subsystems.vfx;

import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.Objects;

/**
 * GL-free screen-space distortion contribution emitted by one effect instance.
 *
 * <p>Unlike the material UV flow ({@link VfxUvFlow}) this is a screen-space
 * contribution: the render3d adapter projects {@link #worldCenter()} and
 * {@link #worldRadius()} into UV space, and the colour chain samples the
 * already-composited HDR image at a displaced UV.  The identity is
 * {@code (effectId, distortionIndex)} and is stable for the life of the
 * instance, mirroring {@link VfxLight}.  The record carries no GPU handle and
 * no render command.</p>
 *
 * <p>{@link #uvOffset()} is authored in UV units at the peak of the shape, and
 * is bounded so a single contribution cannot fold the image.  {@code
 * depthThicknessMeters} is the world-space half thickness used for foreground
 * rejection against the scene depth at the effect's plane; {@code ring}
 * selects between a filled heat-haze falloff ({@code 0}) and a shockwave band
 * ({@code 1}); {@code phaseSeconds} is an explicit, deterministic heat-haze
 * phase so the same time and inputs reproduce the same frame.</p>
 */
public record VfxDistortion(long effectId, int distortionIndex, Vector3f worldCenter,
                            float worldRadius, Vector2f uvOffset, float mask,
                            float depthThicknessMeters, float ring, float haze,
                            float phaseSeconds) {
    /** Largest accepted authored UV offset per axis. */
    public static final float MAXIMUM_AUTHORED_OFFSET_UV = 0.25f;

    public VfxDistortion {
        if (effectId <= 0L) throw new IllegalArgumentException("effectId must be positive");
        if (distortionIndex < 0) {
            throw new IllegalArgumentException("distortionIndex must be non-negative");
        }
        worldCenter = new Vector3f(Objects.requireNonNull(worldCenter, "worldCenter"));
        uvOffset = new Vector2f(Objects.requireNonNull(uvOffset, "uvOffset"));
        requireFinite(worldCenter, "worldCenter");
        if (!Float.isFinite(worldRadius) || worldRadius <= 0.0f) {
            throw new IllegalArgumentException("worldRadius must be finite and positive");
        }
        requireFinite(uvOffset, "uvOffset");
        if (Math.abs(uvOffset.x) > MAXIMUM_AUTHORED_OFFSET_UV
                || Math.abs(uvOffset.y) > MAXIMUM_AUTHORED_OFFSET_UV) {
            throw new IllegalArgumentException("uvOffset must stay within +/-"
                    + MAXIMUM_AUTHORED_OFFSET_UV);
        }
        requireUnit(mask, "mask");
        if (!Float.isFinite(depthThicknessMeters) || depthThicknessMeters < 0.0f) {
            throw new IllegalArgumentException(
                    "depthThicknessMeters must be finite and non-negative");
        }
        requireUnit(ring, "ring");
        requireUnit(haze, "haze");
        if (!Float.isFinite(phaseSeconds) || phaseSeconds < 0.0f) {
            throw new IllegalArgumentException("phaseSeconds must be finite and non-negative");
        }
    }

    @Override
    public Vector3f worldCenter() {
        return new Vector3f(worldCenter);
    }

    @Override
    public Vector2f uvOffset() {
        return new Vector2f(uvOffset);
    }

    public Key key() {
        return new Key(effectId, distortionIndex);
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

    private static void requireFinite(Vector3f value, String name) {
        if (!Float.isFinite(value.x) || !Float.isFinite(value.y) || !Float.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must contain finite values");
        }
    }

    private static void requireFinite(Vector2f value, String name) {
        if (!Float.isFinite(value.x) || !Float.isFinite(value.y)) {
            throw new IllegalArgumentException(name + " must contain finite values");
        }
    }
}
