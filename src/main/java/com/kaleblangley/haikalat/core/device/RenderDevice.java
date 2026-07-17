package com.kaleblangley.haikalat.core.device;

import com.kaleblangley.haikalat.core.command.CommandBuffer;

public interface RenderDevice {
    RenderBackendKind backendKind();

    ExecutionModel executionModel();

    CommandBuffer createCommandBuffer();

    void execute(CommandBuffer buffer);

    /**
     * 使设备对外部或生命周期操作前记录的管线状态失去信任。
     * 下一次提交必须重新应用所有受管状态。
     */
    void invalidateState();

    void transition(ResourceBarrier... barriers);
}
