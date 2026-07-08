package com.kaleblangley.haikalat.core.device;

import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.core.command.CommandBuffer;

public interface RenderDevice extends RenderResourceFactory {
    RenderBackendKind backendKind();

    ExecutionModel executionModel();

    CommandBuffer createCommandBuffer();

    void execute(CommandBuffer buffer);

    StateCache stateCache();

    void invalidateState();

    void transition(ResourceBarrier... barriers);
}
