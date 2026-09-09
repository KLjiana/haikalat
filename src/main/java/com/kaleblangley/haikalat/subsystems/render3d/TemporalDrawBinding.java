package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;

/**
 * Optional capability for draw bindings that can expose the previous
 * successful frame's deformation data at the reserved previous-state binding
 * points.  Implementations own their snapshots and must only publish them
 * through {@link #commitTemporalFrame()}.
 */
public interface TemporalDrawBinding {
    /** Previous binding point for a skin joint palette. */
    int PREVIOUS_JOINT_PALETTE_BINDING = 10;
    /** Previous binding point for morph weights. */
    int PREVIOUS_MORPH_WEIGHT_BINDING = 11;

    /** @return true when a committed previous deformation exists for this binding */
    boolean previousDeformationAvailable();

    /** Captures the current deformation inputs as the pending candidate. */
    void prepareTemporalFrame();

    /**
     * Binds the committed previous-frame deformation to the reserved previous
     * binding points.  Callers must check {@link #previousDeformationAvailable()}
     * first; a binding without previous data must not be drawn as temporally valid.
     */
    void recordPrevious(CommandBuffer commands, ShaderProgram shader);

    /**
     * Fallible finalization step executed before any frame state is published.
     * Implementations move all potentially failing work here so the subsequent
     * {@link #commitTemporalFrame()} can be an infallible publish.
     */
    default void prepareTemporalCommit() {
    }

    /** Publishes the pending candidate; only called after a successful frame. */
    void commitTemporalFrame();

    /** Drops the pending candidate; the committed previous state is untouched. */
    void discardTemporalFrame();

    /** Drops committed and pending state, e.g. after a layout change or resize. */
    void invalidatePrevious();
}
