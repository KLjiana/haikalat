package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Matrix4f;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Stable-identity previous-frame deformation state.  Objects are keyed by their
 * {@link MeshRenderer} identity, never by sorted draw index; adding, removing or
 * replacing a renderer therefore invalidates exactly the affected entries.
 */
final class TemporalSceneState {
    private final Map<MeshRenderer, Snapshot> snapshots = new IdentityHashMap<>();
    private final Map<TemporalDrawBinding, Boolean> preparedBindings = new IdentityHashMap<>();
    private final Map<MeshRenderer, Boolean> currentMembership = new IdentityHashMap<>();
    private final java.util.List<MeshRenderer> retired = new java.util.ArrayList<>();
    private long preparedFrame = -1L;

    void beginFrame(SceneFrame frame) {
        Objects.requireNonNull(frame, "frame");
        preparedBindings.clear();
        currentMembership.clear();
        for (int entry = 0; entry < frame.rendererCount(); entry++) {
            MeshRenderer renderer = frame.renderer(entry);
            currentMembership.put(renderer, Boolean.TRUE);
            Snapshot snapshot = snapshots.computeIfAbsent(renderer, ignored -> new Snapshot());
            snapshot.pending.set(frame.model(entry));
            snapshot.pendingValid = true;
            TemporalDrawBinding temporal = renderer.drawBinding().temporalBinding();
            if (temporal != null && preparedBindings.put(temporal, Boolean.TRUE) == null) {
                temporal.prepareTemporalFrame();
            }
        }
        preparedFrame = frame.frameIndex;
    }

    /** @return committed previous model matrix, or {@code null} when unavailable */
    Matrix4f previousModel(MeshRenderer renderer) {
        Snapshot snapshot = snapshots.get(Objects.requireNonNull(renderer, "renderer"));
        return snapshot != null && snapshot.previousValid ? snapshot.previous : null;
    }

    boolean hasPreviousModel(MeshRenderer renderer) {
        return previousModel(renderer) != null;
    }

    /**
     * @return true when a previous model matrix and all previous deformation
     *         inputs required by this renderer are available
     */
    boolean previousStateValid(MeshRenderer renderer) {
        if (previousModel(renderer) == null) return false;
        TemporalDrawBinding temporal = renderer.drawBinding().temporalBinding();
        if (temporal != null) {
            return temporal.previousDeformationAvailable();
        }
        // A binding without temporal capability is only trustworthy when it is
        // known to be static.  Deforming bindings (skin/morph/custom) would be
        // rendered with the current deformation as if it were the previous one.
        return !renderer.drawBinding().deformsVertices();
    }

    /** Binds the committed previous deformation data for a surface draw. */
    void recordPreviousDeformation(CommandBuffer commands, ShaderProgram shader,
                                   MeshRenderer renderer) {
        TemporalDrawBinding temporal = renderer.drawBinding().temporalBinding();
        if (temporal != null && temporal.previousDeformationAvailable()) {
            temporal.recordPrevious(commands, shader);
        }
    }

    /**
     * Fallible pre-commit phase.  Must run before any temporal owner publishes
     * state so a failure leaves every history and snapshot on the previous
     * successful frame.
     */
    void prepareFinalization() {
        retired.clear();
        for (MeshRenderer renderer : snapshots.keySet()) {
            if (!currentMembership.containsKey(renderer)) retired.add(renderer);
        }
        for (TemporalDrawBinding binding : preparedBindings.keySet()) {
            binding.prepareTemporalCommit();
        }
    }

    void commitSuccessfulFrame() {
        for (Snapshot snapshot : snapshots.values()) {
            if (!snapshot.pendingValid) continue;
            Matrix4f swap = snapshot.previous;
            snapshot.previous = snapshot.pending;
            snapshot.pending = swap;
            snapshot.previousValid = true;
            snapshot.pendingValid = false;
        }
        for (TemporalDrawBinding binding : preparedBindings.keySet()) {
            binding.commitTemporalFrame();
        }
        preparedBindings.clear();
        // Drop objects that left the scene so their bindings and matrices are
        // not retained indefinitely.  Re-adding the same renderer creates a
        // fresh invalid snapshot and therefore a validity=0 first frame.
        // Bindings are borrowed and may still be shared by live renderers or
        // another view. Retiring our model snapshot must not mutate them.
        // Re-entry has no previous model, so its first surface is invalid even
        // if the binding itself still holds a previous deformation.
        for (int index = 0; index < retired.size(); index++) {
            snapshots.remove(retired.get(index));
        }
        retired.clear();
        currentMembership.clear();
    }

    void discardFrame() {
        retired.clear();
        for (Snapshot snapshot : snapshots.values()) {
            snapshot.pendingValid = false;
        }
        for (TemporalDrawBinding binding : preparedBindings.keySet()) {
            binding.discardTemporalFrame();
        }
        preparedBindings.clear();
        currentMembership.clear();
    }

    /** Drops all committed state; used for scene replacement, resize and explicit reset. */
    void invalidate() {
        Map<TemporalDrawBinding, Boolean> invalidated = new IdentityHashMap<>();
        for (MeshRenderer renderer : snapshots.keySet()) {
            TemporalDrawBinding temporal = renderer.drawBinding().temporalBinding();
            if (temporal != null && invalidated.put(temporal, Boolean.TRUE) == null) {
                temporal.invalidatePrevious();
            }
        }
        snapshots.clear();
        preparedBindings.clear();
        currentMembership.clear();
        preparedFrame = -1L;
    }

    long preparedFrame() {
        return preparedFrame;
    }

    /** @return number of retained per-renderer snapshots (test/diagnostic hook) */
    int snapshotCount() {
        return snapshots.size();
    }

    private static final class Snapshot {
        private Matrix4f previous = new Matrix4f();
        private Matrix4f pending = new Matrix4f();
        private boolean previousValid;
        private boolean pendingValid;
    }
}
