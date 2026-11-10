package com.kaleblangley.haikalat.subsystems.render3d;

/**
 * Bounded observation of effect-light input for one frame.
 *
 * <p>{@code reason} is empty when input participated, otherwise one of
 * {@code hdr-vfx-disabled}, {@code direct-lighting-disabled},
 * {@code no-active-light} or {@code capacity-culled}.  Requested, selected and
 * culled counts always describe the same frozen input revision.</p>
 */
public record VfxLightDiagnostics(int requested, int selected, int culled,
                                  int capacity, String reason, long inputRevision) {
    public static final VfxLightDiagnostics UNAVAILABLE = new VfxLightDiagnostics(
            0, 0, 0, 0, "unavailable", 0L);

    public VfxLightDiagnostics {
        if (requested < 0 || selected < 0 || culled < 0 || capacity < 0) {
            throw new IllegalArgumentException("vfx light counts must be non-negative");
        }
        if (selected + culled != requested) {
            throw new IllegalArgumentException("selected + culled must equal requested");
        }
        if (inputRevision < 0L) {
            throw new IllegalArgumentException("inputRevision must be non-negative");
        }
        reason = reason == null ? "" : reason;
    }
}
