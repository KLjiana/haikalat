package com.kaleblangley.haikalat.gl.render;

import com.kaleblangley.haikalat.gl.GlResource;

import static org.lwjgl.opengl.GL33.*;

public final class GpuTimer implements GlResource {
    private final int queryId;
    private boolean begun;
    private long elapsedNanos;
    private boolean closed;

    public GpuTimer() {
        this.queryId = glGenQueries();
    }

    public void begin() {
        ensureOpen();
        glBeginQuery(GL_TIME_ELAPSED, queryId);
        begun = true;
    }

    public void end() {
        ensureOpen();
        if (begun) {
            glEndQuery(GL_TIME_ELAPSED);
            begun = false;
        }
    }

    public long elapsedNanos() {
        if (!begun) {
            return elapsedNanos;
        }
        ensureOpen();
        int available = glGetQueryObjecti(queryId, GL_QUERY_RESULT_AVAILABLE);
        if (available == GL_TRUE) {
            elapsedNanos = glGetQueryObjectui64(queryId, GL_QUERY_RESULT);
        }
        return elapsedNanos;
    }

    @Override
    public int id() {
        return queryId;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        glDeleteQueries(queryId);
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new com.kaleblangley.haikalat.gl.GlException("Timer closed");
    }
}
