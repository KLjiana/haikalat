package com.kaleblangley.haikalat.gl.command;

import java.util.Objects;

public final class RenderDevice {
    private final StateCache stateCache;

    public RenderDevice() {
        this.stateCache = new StateCache();
    }

    public CommandBuffer createCommandBuffer() {
        return new CommandBuffer();
    }

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

    public StateCache stateCache() {
        return stateCache;
    }

    public void invalidateState() {
        stateCache.invalidate();
    }

    public void invalidateFramebuffer() {
        stateCache.invalidateFramebuffer();
    }
}
