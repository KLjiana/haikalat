package com.kaleblangley.haikalat.backend;

import static org.lwjgl.opengl.GL33.*;

/** OpenGL elapsed-time query owned by the render graph. */
public final class GpuTimer implements GlResource {
    private final int queryId = glGenQueries();
    private boolean begun;
    private long elapsedNanos;
    private boolean closed;

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
        ensureOpen();
        if (!begun && glGetQueryObjecti(queryId, GL_QUERY_RESULT_AVAILABLE) == GL_TRUE) {
            elapsedNanos = glGetQueryObjectui64(queryId, GL_QUERY_RESULT);
        }
        return elapsedNanos;
    }

    @Override public int id() { return queryId; }
    @Override public boolean isClosed() { return closed; }

    @Override
    public void close() {
        if (closed) return;
        glDeleteQueries(queryId);
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new GlException("Timer closed");
    }
}
