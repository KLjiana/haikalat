package com.kaleblangley.haikalat.subsystems.render3d.vfx;

/**
 * Immutable controls shared by a projected-decal batch.
 *
 * <p>The decal volume starts {@code surfaceOffset} world units in front of the
 * decal plane and extends {@code thickness} world units into the receiver.
 * A depth-reconstructed receiver is accepted only when its position along the
 * decal normal lies in {@code [-thickness, +surfaceOffset]}, its reconstructed
 * facing normal passes {@code facingCosine}, and its tangent coordinates stay
 * inside the per-decal rectangle.  Distances are world units.</p>
 *
 * <p>The default is disabled and contributes no draw, texture load or image
 * change.  Settings are a batch argument rather than mutable renderer state;
 * {@code maximumDecals} bounds the ordered draw list and keeps the nearest
 * overlapping set.</p>
 *
 * @param enabled        whether projected decals may contribute
 * @param thickness      depth extent into the receiver in {@code (0, 10]}
 * @param surfaceOffset  front tolerance in {@code [0, thickness]}
 * @param facingCosine   minimum receiver/decal normal alignment in {@code [0, 1]}
 * @param receiveBackFaces accept receivers whose facing normal is opposite
 * @param alphaMask      multiply coverage by the decal texture alpha
 * @param maximumDecals  bounded draw count in {@code [1, 64]}
 */
public record ProjectedDecalSettings(boolean enabled, float thickness,
                                     float surfaceOffset, float facingCosine,
                                     boolean receiveBackFaces, boolean alphaMask,
                                     int maximumDecals) {
    /** Largest supported receiver depth extent in world units. */
    public static final float MAX_THICKNESS = 10.0f;
    /** Largest supported draw count. */
    public static final int MAX_DECALS = 64;

    private static final ProjectedDecalSettings DISABLED =
            new ProjectedDecalSettings(false, 0.5f, 0.02f, 0.5f, false, true, 32);

    public ProjectedDecalSettings {
        if (!Float.isFinite(thickness) || thickness <= 0.0f
                || thickness > MAX_THICKNESS) {
            throw new IllegalArgumentException("thickness must be finite and in (0, "
                    + MAX_THICKNESS + "]");
        }
        if (!Float.isFinite(surfaceOffset) || surfaceOffset < 0.0f
                || surfaceOffset > thickness) {
            throw new IllegalArgumentException(
                    "surfaceOffset must be finite and in [0, thickness]");
        }
        if (!Float.isFinite(facingCosine) || facingCosine < 0.0f
                || facingCosine > 1.0f) {
            throw new IllegalArgumentException("facingCosine must be finite and in [0, 1]");
        }
        if (maximumDecals < 1 || maximumDecals > MAX_DECALS) {
            throw new IllegalArgumentException("maximumDecals must be in [1, "
                    + MAX_DECALS + "]");
        }
    }

    /** Default-off configuration; changing only {@code enabled} keeps the shared tuning. */
    public static ProjectedDecalSettings disabled() {
        return DISABLED;
    }

    /** True only when the enabled flag can produce at least one draw. */
    public boolean active() {
        return enabled && maximumDecals > 0 && thickness > 0.0f;
    }

    public ProjectedDecalSettings withEnabled(boolean value) {
        return new ProjectedDecalSettings(value, thickness, surfaceOffset,
                facingCosine, receiveBackFaces, alphaMask, maximumDecals);
    }
}
