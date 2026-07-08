package com.kaleblangley.haikalat.core.device;

import com.kaleblangley.haikalat.core.command.CommandBuffer;

public interface RenderDevice {
    RenderBackendKind backendKind();

    ExecutionModel executionModel();

    CommandBuffer createCommandBuffer();

    void execute(CommandBuffer buffer);

    void transition(ResourceBarrier... barriers);
}
