package com.kaleblangley.haikalat.subsystems.render3d;

/**
 * Bounded, read-only summary of the last CPU shadow caster planning pass.
 *
 * <p>The snapshot deliberately contains no renderer or view collections.  It is
 * safe to expose from diagnostics without retaining the frame arena or any GL
 * object.</p>
 */
public record ShadowCullingStatistics(
        boolean available,
        boolean planReused,
        boolean fullRebuild,
        int activeViews,
        int dirtyViews,
        int reusedViews,
        int emptyViewsCleared,
        int candidateCasters,
        int volatileCasters,
        int casterViewTests,
        int casterViewReferences,
        int culledReferences,
        int directionalReferences,
        int pointReferences,
        int spotReferences,
        long buildNanos) {
    public static final ShadowCullingStatistics UNAVAILABLE = new ShadowCullingStatistics(
            false, false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0L);

    public ShadowCullingStatistics {
        if (activeViews < 0 || dirtyViews < 0 || reusedViews < 0 || emptyViewsCleared < 0
                || candidateCasters < 0 || volatileCasters < 0 || casterViewTests < 0
                || casterViewReferences < 0 || culledReferences < 0
                || directionalReferences < 0 || pointReferences < 0 || spotReferences < 0
                || buildNanos < 0L) {
            throw new IllegalArgumentException("shadow culling statistics must be non-negative");
        }
    }
}
