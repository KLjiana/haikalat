package com.kaleblangley.haikalat.backend;

import org.lwjgl.opengl.GL;

import static org.lwjgl.opengl.ARBPipelineStatisticsQuery.GL_VERTEX_SHADER_INVOCATIONS_ARB;
import static org.lwjgl.opengl.GL15.GL_QUERY_RESULT;
import static org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE;
import static org.lwjgl.opengl.GL15.glBeginQuery;
import static org.lwjgl.opengl.GL15.glDeleteQueries;
import static org.lwjgl.opengl.GL15.glEndQuery;
import static org.lwjgl.opengl.GL15.glGenQueries;
import static org.lwjgl.opengl.GL15.glGetQueryObjecti;
import static org.lwjgl.opengl.GL33.glGetQueryObjectui64;

/** 用于 ARB 管线统计计数器的非阻塞查询环。 */
public final class PipelineStatisticsQuery implements GlResource {
    private static final int QUERY_RING_SIZE = 8;

    private final int target;
    private final int[] queryIds;
    private final boolean[] pending;
    private final boolean supported;
    private int writeIndex;
    private int readIndex;
    private boolean begun;
    private boolean hasResult;
    private long latestValue;
    private boolean closed;

    private PipelineStatisticsQuery(int target, boolean supported) {
        this.target = target;
        this.supported = supported;
        this.queryIds = supported ? new int[QUERY_RING_SIZE] : new int[0];
        this.pending = supported ? new boolean[QUERY_RING_SIZE] : new boolean[0];
        for (int i = 0; i < queryIds.length; i++) queryIds[i] = glGenQueries();
    }

    public static PipelineStatisticsQuery vertexShaderInvocations() {
        boolean available = GL.getCapabilities().GL_ARB_pipeline_statistics_query;
        return new PipelineStatisticsQuery(GL_VERTEX_SHADER_INVOCATIONS_ARB, available);
    }

    public boolean supported() {
        return supported;
    }

    public void begin() {
        ensureOpen();
        if (!supported) return;
        drainAvailable();
        if (pending[writeIndex]) return;
        glBeginQuery(target, queryIds[writeIndex]);
        begun = true;
    }

    public void end() {
        ensureOpen();
        if (!begun) return;
        glEndQuery(target);
        pending[writeIndex] = true;
        writeIndex = (writeIndex + 1) % queryIds.length;
        begun = false;
    }

    public long latestValue() {
        ensureOpen();
        if (supported && !begun) drainAvailable();
        return hasResult ? latestValue : -1L;
    }

    @Override
    public int id() {
        return queryIds.length == 0 ? 0 : queryIds[0];
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        for (int queryId : queryIds) glDeleteQueries(queryId);
        closed = true;
    }

    private void drainAvailable() {
        while (pending[readIndex]
                && glGetQueryObjecti(queryIds[readIndex], GL_QUERY_RESULT_AVAILABLE) != 0) {
            latestValue = glGetQueryObjectui64(queryIds[readIndex], GL_QUERY_RESULT);
            hasResult = true;
            pending[readIndex] = false;
            readIndex = (readIndex + 1) % queryIds.length;
        }
    }

    private void ensureOpen() {
        if (closed) throw new GlException("Pipeline statistics query is closed");
    }
}
