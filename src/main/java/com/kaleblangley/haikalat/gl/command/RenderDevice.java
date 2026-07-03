package com.kaleblangley.haikalat.gl.command;

public interface RenderDevice {
    CommandBuffer createCommandBuffer();
    void execute(CommandBuffer buffer);
    StateCache stateCache();
    void invalidateState();
}
