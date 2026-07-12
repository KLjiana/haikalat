package com.kaleblangley.haikalat.backend;

import static org.lwjgl.opengl.GL33.*;

/** OpenGL elapsed-time query owned by the render graph. */
public final class GpuTimer implements GlResource {
    private static final int QUERY_RING_SIZE = 8;

    private final int[] queryIds = new int[QUERY_RING_SIZE];
    private final boolean[] pending = new boolean[QUERY_RING_SIZE];
    private int writeIndex;
    private int readIndex;
    private boolean begun;
    private long elapsedNanos;
    private boolean closed;

    public GpuTimer() {
        for (int i = 0; i < queryIds.length; i++) queryIds[i] = glGenQueries();
    }

    public void begin() {
        ensureOpen();
        drainAvailable();
        if (pending[writeIndex]) {
            // Do not stall the CPU when the GPU is more than the query ring behind.
            begun = false;
            return;
        }
        glBeginQuery(GL_TIME_ELAPSED, queryIds[writeIndex]);
        begun = true;
    }

    public void end() {
        ensureOpen();
        if (begun) {
            glEndQuery(GL_TIME_ELAPSED);
            pending[writeIndex] = true;
            writeIndex = (writeIndex + 1) % queryIds.length;
            begun = false;
        }
    }

    public long elapsedNanos() {
        ensureOpen();
        if (!begun) drainAvailable();
        return elapsedNanos;
    }

    @Override public int id() { return queryIds[0]; }
    @Override public boolean isClosed() { return closed; }

    @Override
    public void close() {
        if (closed) return;
        for (int queryId : queryIds) glDeleteQueries(queryId);
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new GlException("Timer closed");
    }

    private void drainAvailable() {
        while (pending[readIndex]
                && glGetQueryObjecti(queryIds[readIndex], GL_QUERY_RESULT_AVAILABLE) == GL_TRUE) {
            elapsedNanos = glGetQueryObjectui64(queryIds[readIndex], GL_QUERY_RESULT);
            pending[readIndex] = false;
            readIndex = (readIndex + 1) % queryIds.length;
        }
    }
}
