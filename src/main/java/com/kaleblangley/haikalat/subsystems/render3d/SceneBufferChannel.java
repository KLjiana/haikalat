package com.kaleblangley.haikalat.subsystems.render3d;

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
    /** Positive view-space depth of the current surface in world units, materialized on demand. */
    LINEAR_DEPTH
}
