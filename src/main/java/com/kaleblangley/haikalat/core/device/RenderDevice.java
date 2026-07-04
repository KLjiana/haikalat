package com.kaleblangley.haikalat.core.device;

import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.core.command.CommandBuffer;

public interface RenderDevice {
    CommandBuffer createCommandBuffer();
    void execute(CommandBuffer buffer);
    StateCache stateCache();
    void invalidateState();
}
