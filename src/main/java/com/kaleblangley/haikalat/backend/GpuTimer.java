package com.kaleblangley.haikalat.backend;

import static org.lwjgl.opengl.GL33.*;

/**
 * RenderGraph 使用的非阻塞 OpenGL elapsed-time 查询环。
 *
 * <p>计时结果携带提交序号，调用方因此可以区分当前帧、延迟结果、尚未完成和查询环饱和。
 * 查询环满时会跳过本次采样，绝不等待 GPU。</p>
 */
public final class GpuTimer implements GlResource {
    private static final int QUERY_RING_SIZE = 8;

    private final int[] queryIds = new int[QUERY_RING_SIZE];
    private final boolean[] pending = new boolean[QUERY_RING_SIZE];
    private final long[] submissionSequences = new long[QUERY_RING_SIZE];
    private int writeIndex;
    private int readIndex;
    private boolean begun;
    private long activeSequence = -1L;
    private long lastAttemptSequence = -1L;
    private boolean lastAttemptSubmitted;
    private boolean lastAttemptFailed;
    private long resultSequence = -1L;
    private long elapsedNanos;
    private long skippedSubmissions;
    private boolean closed;
    private final long resourceSequence;

    public GpuTimer() {
        for (int i = 0; i < queryIds.length; i++) queryIds[i] = glGenQueries();
        resourceSequence = GlDebug.trackResource("QUERY", queryIds[0],
                "GpuTimer ring=" + QUERY_RING_SIZE, 0L);
    }

    /** 兼容入口；没有帧身份的调用使用内部单调序号。 */
    public void begin() {
        begin(lastAttemptSequence + 1L);
    }

    /**
     * 尝试开始一次带身份的计时。
     *
     * @param submissionSequence 调用方的单调递增帧序号
     * @return 查询已提交时返回 {@code true}；ring 满时返回 {@code false}
     */
    public boolean begin(long submissionSequence) {
        ensureOpen();
        if (submissionSequence < 0L) throw new IllegalArgumentException("submissionSequence must be non-negative");
        drainAvailable();
        lastAttemptSequence = submissionSequence;
        lastAttemptFailed = false;
        if (pending[writeIndex]) {
            skippedSubmissions++;
            lastAttemptSubmitted = false;
            begun = false;
            return false;
        }
        glBeginQuery(GL_TIME_ELAPSED, queryIds[writeIndex]);
        activeSequence = submissionSequence;
        lastAttemptSubmitted = true;
        begun = true;
        return true;
    }

    public void end() {
        ensureOpen();
        if (begun) {
            glEndQuery(GL_TIME_ELAPSED);
            pending[writeIndex] = true;
            submissionSequences[writeIndex] = activeSequence;
            writeIndex = (writeIndex + 1) % queryIds.length;
            begun = false;
        }
    }

    /**
     * 结束但丢弃当前 active query，供命令执行异常清理使用。
     *
     * <p>OpenGL 没有取消 elapsed query 的入口，因此仍必须调用 {@code glEndQuery}，但不会把
     * 该不完整区间放入可用结果环。</p>
     */
    public void abort() {
        ensureOpen();
        if (!begun) return;
        glEndQuery(GL_TIME_ELAPSED);
        begun = false;
        lastAttemptSubmitted = false;
        lastAttemptFailed = true;
        activeSequence = -1L;
    }

    /** @return 最近完成的值；尚无可用样本时为 0，仅供旧 API 兼容。 */
    public long elapsedNanos() {
        ensureOpen();
        if (!begun) drainAvailable();
        return elapsedNanos;
    }

    /** 返回相对于指定帧的不可变、非歧义计时样本。 */
    public Sample sample(long currentSequence) {
        ensureOpen();
        if (!begun) drainAvailable();
        Status status;
        if (lastAttemptSequence == currentSequence && lastAttemptFailed) {
            status = Status.FAILED;
        } else if (lastAttemptSequence == currentSequence && !lastAttemptSubmitted) {
            status = Status.SKIPPED;
        } else if (resultSequence >= 0L) {
            status = Status.AVAILABLE;
        } else {
            status = Status.PENDING;
        }
        long age = resultSequence < 0L ? 0L : Math.max(0L, currentSequence - resultSequence);
        return new Sample(status, status == Status.AVAILABLE ? elapsedNanos : 0L,
                resultSequence, age, skippedSubmissions);
    }

    @Override public int id() { return queryIds[0]; }
    @Override public boolean isClosed() { return closed; }

    @Override
    public void close() {
        if (closed) return;
        abort();
        for (int queryId : queryIds) glDeleteQueries(queryId);
        GlDebug.closeResource(resourceSequence);
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new GlException("Timer closed");
    }

    private void drainAvailable() {
        while (pending[readIndex]
                && glGetQueryObjecti(queryIds[readIndex], GL_QUERY_RESULT_AVAILABLE) == GL_TRUE) {
            elapsedNanos = glGetQueryObjectui64(queryIds[readIndex], GL_QUERY_RESULT);
            resultSequence = submissionSequences[readIndex];
            pending[readIndex] = false;
            readIndex = (readIndex + 1) % queryIds.length;
        }
    }

    /** 查询样本状态。 */
    public enum Status { PENDING, AVAILABLE, SKIPPED, UNSUPPORTED, FAILED }

    /**
     * 不持有 GL 对象的计时值快照。
     *
     * @param status 查询状态
     * @param elapsedNanos 已完成样本的耗时纳秒数
     * @param resultSequence 结果对应的提交序号
     * @param sampleAgeFrames 样本相对当前帧的年龄
     * @param skippedSubmissions 跳过的提交数量
     */
    public record Sample(Status status, long elapsedNanos, long resultSequence,
                         long sampleAgeFrames, long skippedSubmissions) {
    }
}
