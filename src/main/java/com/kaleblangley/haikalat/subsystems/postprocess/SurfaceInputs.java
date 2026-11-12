package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.core.graph.PassResources;

import java.util.Objects;

/**
 * Borrowed scene-surface inputs as seen by the post-process subsystem.
 *
 * <p>Render3D owns the shared depth/normal/velocity/reactive production and
 * adapts its frame lease to this contract.  The contract carries the actual
 * producer pass name and resolves textures through the live
 * {@link PassResources} callback, so post-process passes can validate their
 * dependency edges without importing Render3D types or holding raw texture
 * ids across frames.</p>
 */
public interface SurfaceInputs {
    enum Channel {
        DEPTH,
        NORMAL,
        ROUGHNESS,
        /** Linear material albedo; consumed by screen-space indirect diffuse. */
        ALBEDO,
        SUBSURFACE,
        VELOCITY,
        PREVIOUS_SURFACE_DEPTH,
        VALIDITY,
        REACTIVE
    }

    /** One single-sampled surface output: logical name plus its actual producer pass. */
    record Input(Channel channel, String name, String producerPass) {
        public Input {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(producerPass, "producerPass");
        }
    }

    /**
     * @return the single-sampled input for {@code channel}, or {@code null}
     * when the active topology has no such output
     */
    Input single(Channel channel);

    /** Fails before the frame is committed when the consumer does not depend on the producer. */
    void validateDependency(String consumerPass, Input input);

    /** Resolves the borrowed texture for the duration of the recorded frame. */
    int texture(PassResources resources, Input input);
}
