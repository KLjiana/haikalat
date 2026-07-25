package com.kaleblangley.haikalat.core.device;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlCapabilityContract;
import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.core.command.CommandBuffer;

import java.util.Objects;

public final class GlRenderDevice implements RenderDevice {
    private final StateCache stateCache;
    private long observedContextStateEpoch;

    public GlRenderDevice() {
        this.stateCache = new StateCache();
        this.observedContextStateEpoch = GlDebug.contextStateEpoch();
    }

    @Override
    public RenderBackendKind backendKind() {
        return RenderBackendKind.OPENGL;
    }

    @Override
    public ExecutionModel executionModel() {
        return ExecutionModel.IMMEDIATE;
    }

    @Override
    public CommandBuffer createCommandBuffer() {
        return new CommandBuffer();
    }

    @Override
    public void execute(CommandBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        GlCapabilityContract.requireCurrent();
        synchronizeContextState();
        buffer.execute(stateCache);
    }

    public void executeAll(CommandBuffer... buffers) {
        Objects.requireNonNull(buffers, "buffers");
        GlCapabilityContract.requireCurrent();
        for (CommandBuffer buffer : buffers) {
            Objects.requireNonNull(buffer, "buffer");
            synchronizeContextState();
            buffer.execute(stateCache);
        }
    }

    public StateCache.Statistics stateStatistics() {
        return stateCache.statistics();
    }

    public void resetStateStatistics() {
        stateCache.resetStatistics();
    }

    @Override
    public void invalidateState() {
        stateCache.invalidate();
        observedContextStateEpoch = GlDebug.contextStateEpoch();
    }

    @Override
    public void transition(ResourceBarrier... barriers) {
        Objects.requireNonNull(barriers, "barriers");
        for (ResourceBarrier barrier : barriers) {
            Objects.requireNonNull(barrier, "barrier");
        }
        // OpenGL tracks resource hazards implicitly for the paths used here. The
        // method exists so explicit backends can enforce layout transitions.
    }

    private void synchronizeContextState() {
        long current = GlDebug.contextStateEpoch();
        if (current != observedContextStateEpoch) {
            stateCache.invalidate();
            observedContextStateEpoch = current;
        }
    }
}
