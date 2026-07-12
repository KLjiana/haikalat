package com.kaleblangley.haikalat.core.upload;

import com.kaleblangley.haikalat.backend.buffer.BufferUploadTarget;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 收集并批量执行 GPU 上传请求。核心以 {@link ByteBuffer} 为存储单元，
 * {@link #uploadFloats} 作为 FloatBuffer 兼容层自动转换。
 *
 * <p>线程安全：{@link #submit} / {@link #uploadBuffer} 可在任意线程调用；
 * {@link #flush} 必须在持有 GL 上下文的线程调用。
 */
public final class UploadSystem implements AutoCloseable {

    private static final int DEFAULT_MAX_MERGE_BYTES = 8 * 1024 * 1024;
    private static final int DEFAULT_SCRATCH_RETAIN_BYTES = 1024 * 1024;

    private final int maxMergeBytes;
    private final int scratchRetainBytes;

    private final Object stateLock = new Object();
    private final Object flushLock = new Object();

    private List<UploadRequest> customRequests = new ArrayList<>();
    private List<BufferUpload> bufferUploads = new ArrayList<>();
    private long sequence;
    private boolean closed;

    private ByteBuffer scratch;

    private volatile long totalBytesUploaded;
    private volatile long totalGpuUpdates;
    private volatile long totalRequestsExecuted;

    public UploadSystem() {
        this(DEFAULT_MAX_MERGE_BYTES, DEFAULT_SCRATCH_RETAIN_BYTES);
    }

    public UploadSystem(int maxMergeBytes, int scratchRetainBytes) {
        if (maxMergeBytes <= 0) throw new IllegalArgumentException("maxMergeBytes must be > 0");
        if (scratchRetainBytes < 0) throw new IllegalArgumentException("scratchRetainBytes must be >= 0");
        this.maxMergeBytes = maxMergeBytes;
        this.scratchRetainBytes = scratchRetainBytes;
    }

    /** 提交一个自定义上传请求，在 flush 时执行。 */
    public void submit(UploadRequest request) {
        Objects.requireNonNull(request, "request");
        synchronized (stateLock) {
            ensureOpen();
            customRequests.add(request);
        }
    }

    /**
     * 提交缓冲区上传请求（核心 API）。flush 时同一 buffer 的连续/重叠范围会被合并，
     * 重叠区域后提交的数据覆盖先提交的（last-write-wins）。
     *
     * @param offset 偏移量（字节），必须非负
     */
    public void uploadBuffer(BufferUploadTarget buffer, long offset, ByteBuffer data) {
        enqueueBufferUpload(buffer, offset, data, null);
    }

    /** Atomically queues a buffer upload and metadata publication for the same flush batch. */
    public void uploadBuffer(BufferUploadTarget buffer, long offset, ByteBuffer data,
                             UploadRequest afterUpload) {
        enqueueBufferUpload(buffer, offset, data, Objects.requireNonNull(afterUpload, "afterUpload"));
    }

    private void enqueueBufferUpload(BufferUploadTarget buffer, long offset, ByteBuffer data,
                                     UploadRequest afterUpload) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(data, "data");
        if (offset < 0) throw new IllegalArgumentException("offset must be >= 0: " + offset);
        if (!data.hasRemaining()) return;

        ByteBuffer copy = copyByteBuffer(data);
        enqueueCopiedBuffer(buffer, offset, copy, afterUpload);
    }

    private void enqueueCopiedBuffer(BufferUploadTarget buffer, long offset, ByteBuffer copy,
                                     UploadRequest afterUpload) {
        synchronized (stateLock) {
            ensureOpen();
            bufferUploads.add(new BufferUpload(buffer, offset, copy, sequence++, afterUpload));
        }
    }

    /**
     * 提交 FloatBuffer 上传请求。内部转换为 ByteBuffer 后委托给 {@link #uploadBuffer(BufferUploadTarget, long, ByteBuffer)}。
     */
    public void uploadFloats(BufferUploadTarget buffer, long offset, FloatBuffer data) {
        enqueueFloatUpload(buffer, offset, data, null);
    }

    /** Atomically queues a float-buffer upload and metadata publication for the same flush batch. */
    public void uploadFloats(BufferUploadTarget buffer, long offset, FloatBuffer data,
                             UploadRequest afterUpload) {
        enqueueFloatUpload(buffer, offset, data, Objects.requireNonNull(afterUpload, "afterUpload"));
    }

    private void enqueueFloatUpload(BufferUploadTarget buffer, long offset, FloatBuffer data,
                                    UploadRequest afterUpload) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(data, "data");
        if (offset < 0) throw new IllegalArgumentException("offset must be >= 0: " + offset);
        if (!data.hasRemaining()) return;
        int bytes = data.remaining() * Float.BYTES;
        ByteBuffer bb = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        bb.asFloatBuffer().put(data.duplicate());
        bb.position(0).limit(bytes);
        enqueueCopiedBuffer(buffer, offset, bb, afterUpload);
    }

    /** 便捷重载。 */
    public void uploadFloats(BufferUploadTarget buffer, long offset, float[] data) {
        Objects.requireNonNull(data, "data");
        uploadFloats(buffer, offset, FloatBuffer.wrap(data));
    }

    /**
     * 丢弃指定 buffer 的所有待处理上传。
     *
     * @return 被丢弃的请求数量
     */
    public int discardUploadsFor(BufferUploadTarget buffer) {
        Objects.requireNonNull(buffer, "buffer");
        synchronized (stateLock) {
            int before = bufferUploads.size();
            bufferUploads.removeIf(u -> u.buffer() == buffer);
            return before - bufferUploads.size();
        }
    }

    /** @return 待处理的上传请求数量 */
    public int pendingCount() {
        synchronized (stateLock) {
            return customRequests.size() + bufferUploads.size();
        }
    }

    /** @return 待上传的总字节数（不含自定义请求） */
    public long pendingBytes() {
        synchronized (stateLock) {
            long sum = 0;
            for (BufferUpload u : bufferUploads) sum += u.data().remaining();
            return sum;
        }
    }

    /**
     * 执行所有待处理的请求。必须在 GL 线程调用。
     */
    public void flush() {
        synchronized (flushLock) {
            List<UploadRequest> requests;
            List<BufferUpload> uploads;
            synchronized (stateLock) {
                if (customRequests.isEmpty() && bufferUploads.isEmpty()) return;
                requests = customRequests;
                uploads = bufferUploads;
                customRequests = new ArrayList<>();
                bufferUploads = new ArrayList<>();
            }

            List<UploadRequest> afterUploads = uploads.stream()
                    .filter(upload -> upload.afterUpload() != null)
                    .sorted(Comparator.comparingLong(BufferUpload::sequence))
                    .map(BufferUpload::afterUpload)
                    .toList();

            RuntimeException failure = null;
            for (UploadRequest r : requests) {
                try {
                    r.execute();
                    totalRequestsExecuted++;
                } catch (RuntimeException e) {
                    failure = accumulate(failure, e);
                }
            }
            boolean uploadsSucceeded = true;
            try {
                if (!uploads.isEmpty()) flushBuffers(uploads);
            } catch (RuntimeException e) {
                uploadsSucceeded = false;
                failure = accumulate(failure, e);
            }
            if (uploadsSucceeded) {
                for (UploadRequest request : afterUploads) {
                    try {
                        request.execute();
                        totalRequestsExecuted++;
                    } catch (RuntimeException e) {
                        failure = accumulate(failure, e);
                    }
                }
            }
            trimScratch();
            if (failure != null) throw failure;
        }
    }

    /** 丢弃所有待处理的上传请求而不执行。 */
    public void clear() {
        synchronized (stateLock) {
            customRequests.clear();
            bufferUploads.clear();
        }
    }

    /** Rejects new submissions while preserving already accepted work for a final flush. */
    public void seal() {
        synchronized (stateLock) {
            closed = true;
        }
    }

    public boolean isSealed() {
        synchronized (stateLock) {
            return closed;
        }
    }

    @Override
    public void close() {
        synchronized (flushLock) {
            synchronized (stateLock) {
                closed = true;
                customRequests.clear();
                bufferUploads.clear();
            }
            scratch = null;
        }
    }

    public long totalBytesUploaded()    { return totalBytesUploaded; }
    public long totalGpuUpdates()       { return totalGpuUpdates; }
    public long totalRequestsExecuted() { return totalRequestsExecuted; }

    private void flushBuffers(List<BufferUpload> uploads) {
        if (uploads.size() == 1) {
            BufferUpload u = uploads.get(0);
            u.buffer().update(u.offset(), u.data().duplicate());
            totalBytesUploaded += u.data().remaining();
            totalGpuUpdates++;
            return;
        }

        Map<BufferUploadTarget, Integer> targetOrder = new IdentityHashMap<>();
        for (BufferUpload upload : uploads) {
            targetOrder.computeIfAbsent(upload.buffer(), ignored -> targetOrder.size());
        }
        uploads.sort(Comparator
                .comparingInt((BufferUpload u) -> targetOrder.get(u.buffer()))
                .thenComparingLong(BufferUpload::offset)
                .thenComparingLong(BufferUpload::sequence));

        int i = 0;
        while (i < uploads.size()) {
            BufferUpload first = uploads.get(i);
            long start = first.offset();
            long end = start + first.data().remaining();
            int j = i + 1;

            while (j < uploads.size()) {
                BufferUpload next = uploads.get(j);
                if (next.buffer() != first.buffer()) break;
                if (next.offset() > end) break;
                long nextEnd = next.offset() + next.data().remaining();
                if (nextEnd - start > maxMergeBytes) break;
                end = Math.max(end, nextEnd);
                j++;
            }

            if (j - i == 1) {
                first.buffer().update(first.offset(), first.data().duplicate());
                totalBytesUploaded += first.data().remaining();
                totalGpuUpdates++;
            } else {
                ByteBuffer merged = mergeRegion(uploads, i, j, start, end);
                first.buffer().update(start, merged);
                totalBytesUploaded += merged.remaining();
                totalGpuUpdates++;
            }
            i = j;
        }
    }

    private ByteBuffer mergeRegion(List<BufferUpload> uploads, int from, int to, long start, long end) {
        int sizeBytes = Math.toIntExact(end - start);
        ByteBuffer merged = acquireScratch(sizeBytes);

        List<BufferUpload> region = new ArrayList<>(uploads.subList(from, to));
        region.sort(Comparator.comparingLong(BufferUpload::sequence));
        for (BufferUpload u : region) {
            merged.position(Math.toIntExact(u.offset() - start));
            merged.put(u.data().duplicate());
        }

        merged.position(0).limit(sizeBytes);
        return merged;
    }

    private ByteBuffer acquireScratch(int sizeBytes) {
        if (scratch == null || scratch.capacity() < sizeBytes) {
            scratch = ByteBuffer.allocateDirect(ceilPow2(sizeBytes))
                    .order(ByteOrder.nativeOrder());
        }
        scratch.clear();
        return scratch;
    }

    private void trimScratch() {
        if (scratch != null && scratch.capacity() > scratchRetainBytes) {
            scratch = null;
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("UploadSystem is closed");
    }

    private static RuntimeException accumulate(RuntimeException existing, RuntimeException next) {
        if (existing == null) return next;
        existing.addSuppressed(next);
        return existing;
    }

    private static int ceilPow2(int n) {
        int v = Integer.highestOneBit(Math.max(n, 4096));
        return v >= n ? v : v << 1;
    }

    static ByteBuffer copyByteBuffer(ByteBuffer src) {
        ByteBuffer dst = ByteBuffer.allocateDirect(src.remaining()).order(src.order());
        dst.put(src.duplicate());
        dst.flip();
        return dst;
    }

    @FunctionalInterface
    public interface UploadRequest {
        void execute();
    }

    private record BufferUpload(BufferUploadTarget buffer, long offset, ByteBuffer data,
                                long sequence, UploadRequest afterUpload) {}
}
