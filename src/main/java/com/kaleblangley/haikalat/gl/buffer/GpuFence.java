package com.kaleblangley.haikalat.gl.buffer;

import com.kaleblangley.haikalat.gl.GlException;
import com.kaleblangley.haikalat.gl.GlResource;

import static org.lwjgl.opengl.GL32.GL_ALREADY_SIGNALED;
import static org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED;
import static org.lwjgl.opengl.GL32.GL_SYNC_FLUSH_COMMANDS_BIT;
import static org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE;
import static org.lwjgl.opengl.GL32.glClientWaitSync;
import static org.lwjgl.opengl.GL32.glDeleteSync;
import static org.lwjgl.opengl.GL32.glFenceSync;

public final class GpuFence implements GlResource {
    private long handle;
    private boolean closed;

    private GpuFence(long handle) {
        this.handle = handle;
    }

    public static GpuFence insert() {
        return new GpuFence(glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0));
    }

    public boolean waitFor(long timeoutNanos) {
        ensureOpen();
        int result = glClientWaitSync(handle, GL_SYNC_FLUSH_COMMANDS_BIT, timeoutNanos);
        return result == GL_ALREADY_SIGNALED || result == GL_CONDITION_SATISFIED;
    }

    public boolean isSignaled() {
        ensureOpen();
        int result = glClientWaitSync(handle, 0, 0L);
        return result == GL_ALREADY_SIGNALED || result == GL_CONDITION_SATISFIED;
    }

    @Override
    public int id() {
        return (int) handle;
    }

    public long handle() {
        return handle;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        glDeleteSync(handle);
        handle = 0L;
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("GpuFence is closed");
        }
    }
}
