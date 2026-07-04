package com.kaleblangley.haikalat.core.device;

import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.core.command.CommandBuffer;

import java.util.Objects;

public final class GlRenderDevice implements RenderDevice {
    private final StateCache stateCache;

    public GlRenderDevice() {
        this.stateCache = new StateCache();
    }

    @Override
    public CommandBuffer createCommandBuffer() {
        return new CommandBuffer();
    }

    @Override
    public void execute(CommandBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        stateCache.invalidate();
        buffer.execute(stateCache);
    }

    public void executeAll(CommandBuffer... buffers) {
        Objects.requireNonNull(buffers, "buffers");
        stateCache.invalidate();
        for (CommandBuffer buffer : buffers) {
            buffer.execute(stateCache);
        }
    }

    @Override
    public StateCache stateCache() {
        return stateCache;
    }

    @Override
    public void invalidateState() {
        stateCache.invalidate();
    }

    public void invalidateFramebuffer() {
        stateCache.invalidateFramebuffer();
    }
}
