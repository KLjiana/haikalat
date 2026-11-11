package com.kaleblangley.haikalat.subsystems.render3d.surface;

/**
 * Logical channels of the shared scene surface buffers.  The semantics are
 * frozen by docs/planning/v0.24.1-scene-buffers-and-taa.md section 3.1.
 */
public enum SceneBufferChannel {
    /** Raw device depth from the shared surface pass. */
    DEPTH,
    /** World-space geometric surface normal, octahedral encoded. */
    NORMAL,
    /** Current surface to previous successful frame surface UV offset (no jitter). */
    VELOCITY,
    /** Current visible surface's linear depth in the previous frame's view space. */
    PREVIOUS_SURFACE_DEPTH,
    /** 1 when a reliable previous-frame correspondence exists, 0 otherwise. */
    VALIDITY,
    /** 0 = normal history, 1 = strongly reduce or reject history. */
    REACTIVE,
    /**
     * Material roughness in {@code [0,1]} used by screen-space reflections.
     * First version carries the PBR material's scalar roughness factor; legacy
     * materials report 1 (fully rough) so they never qualify as reflectors.
     */
    ROUGHNESS,
    /** Positive view-space depth of the current surface in world units, materialized on demand. */
    LINEAR_DEPTH
}
