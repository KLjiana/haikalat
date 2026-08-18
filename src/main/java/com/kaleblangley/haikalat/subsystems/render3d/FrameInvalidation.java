package com.kaleblangley.haikalat.subsystems.render3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Allocation-free hot-path invalidation mask derived from two revision snapshots. */
record FrameInvalidation(int bits) {
    enum Domain {
        MEMBERSHIP(1 << 0),
        TRANSFORM_MODEL(1 << 1),
        LIGHTING(1 << 2),
        MATERIAL_RENDER_STATE(1 << 3),
        CAMERA(1 << 4),
        TOPOLOGY_SETTINGS(1 << 5);

        private final int bit;

        Domain(int bit) {
            this.bit = bit;
        }
    }

    private static final int ALL_BITS = (1 << Domain.values().length) - 1;
    static final FrameInvalidation NONE = new FrameInvalidation(0);
    static final FrameInvalidation ALL = new FrameInvalidation(ALL_BITS);

    FrameInvalidation {
        if ((bits & ~ALL_BITS) != 0) {
            throw new IllegalArgumentException("unknown frame invalidation bits: " + bits);
        }
    }

    static FrameInvalidation between(SceneRevisionSnapshot previous,
                                     SceneRevisionSnapshot current) {
        Objects.requireNonNull(current, "current");
        if (previous == null) return ALL;

        int changed = 0;
        boolean sceneChanged = previous.sceneGeneration() != current.sceneGeneration();
        if (sceneChanged || previous.membershipRevision() != current.membershipRevision()) {
            changed |= Domain.MEMBERSHIP.bit;
        }
        if (sceneChanged || previous.transformModelRevision() != current.transformModelRevision()) {
            changed |= Domain.TRANSFORM_MODEL.bit;
        }
        if (sceneChanged || previous.lightingRevision() != current.lightingRevision()) {
            changed |= Domain.LIGHTING.bit;
        }
        if (sceneChanged
                || previous.materialRenderStateRevision() != current.materialRenderStateRevision()) {
            changed |= Domain.MATERIAL_RENDER_STATE.bit;
        }
        if (previous.cameraRevision() != current.cameraRevision()) {
            changed |= Domain.CAMERA.bit;
        }
        if (previous.topologySettingsRevision() != current.topologySettingsRevision()) {
            changed |= Domain.TOPOLOGY_SETTINGS.bit;
        }
        return changed == 0 ? NONE : new FrameInvalidation(changed);
    }

    boolean invalidated(Domain domain) {
        return (bits & Objects.requireNonNull(domain, "domain").bit) != 0;
    }

    boolean any() {
        return bits != 0;
    }

    /** Allocates only for explicit diagnostics/tests, never during ordinary cache checks. */
    List<Domain> reasons() {
        List<Domain> reasons = new ArrayList<>(Integer.bitCount(bits));
        for (Domain domain : Domain.values()) {
            if (invalidated(domain)) reasons.add(domain);
        }
        return List.copyOf(reasons);
    }
}
