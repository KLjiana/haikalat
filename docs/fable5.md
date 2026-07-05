看了你的代码，整体思路不错，但有几个**实际的 bug** 和一些可以成熟化的地方。先指出问题，再给出改进版。

## 现有问题

1. **排序 bug（严重）**：`sorted.sort(comparingInt(buffer.id()))` 只按 buffer 排序，同一 buffer 内的 offset 是乱序的。乱序时合并逻辑直接失效，甚至会漏合并。
2. **`flip()` bug（严重）**：`mergeRange` 里最后一次 `put` 若不是写在区域末尾，`flip()` 会把 limit 截断到中间位置，上传的数据不完整。
3. **覆盖顺序未定义**：同一区域多次上传时，应该"后提交覆盖先提交"（last-write-wins），现在依赖排序的稳定性，语义不明确。
4. **锁粒度太粗**：`flush()` 持锁执行 GPU 调用，期间其他线程的 `submit` 全部阻塞。
5. **异常安全**：某个 `UploadRequest` 抛异常，后面的请求全部丢失。
6. **每次 flush 都 `allocateDirect`**：直接内存分配开销大且回收慢，应复用暂存区。
7. **缺少校验**：负 offset、未对齐 offset、空数据、`(int) totalBytes` 溢出都没处理。

## 改进版

```java
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 收集并批量执行 GPU 上传请求。
 *
 * <p>线程安全：{@link #submit} / {@link #uploadBuffer} 可在任意线程调用；
 * {@link #flush} 必须在持有 GL 上下文的线程调用。flush 期间提交的请求会进入下一批。
 */
public final class UploadSystem implements AutoCloseable {

    /** 默认单次合并上限：8 MiB，避免一次性巨额暂存分配。 */
    private static final int DEFAULT_MAX_MERGE_BYTES = 8 * 1024 * 1024;
    /** 默认暂存区保留上限：1 MiB，flush 后超过则释放，防止峰值内存被长期占用。 */
    private static final int DEFAULT_SCRATCH_RETAIN_BYTES = 1024 * 1024;

    private final int maxMergeBytes;
    private final int scratchRetainBytes;

    private final Object stateLock = new Object();
    private final Object flushLock = new Object();

    private List<UploadRequest> customRequests = new ArrayList<>();
    private List<BufferUpload> bufferUploads = new ArrayList<>();
    private long sequence;
    private boolean closed;

    /** 复用的合并暂存区，仅在 flushLock 内访问。 */
    private ByteBuffer scratch;

    // 统计信息（仅在 flushLock 内写入）
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
     * 提交缓冲区上传请求。flush 时同一 buffer 的连续/重叠范围会被合并，
     * 重叠区域后提交的数据覆盖先提交的（last-write-wins）。
     *
     * @param offset 偏移量（字节），必须非负且 4 字节对齐
     */
    public void uploadBuffer(GlBuffer buffer, long offset, FloatBuffer data) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(data, "data");
        if (offset < 0) throw new IllegalArgumentException("offset must be >= 0: " + offset);
        if (offset % Float.BYTES != 0) throw new IllegalArgumentException("offset must be 4-byte aligned: " + offset);
        if (!data.hasRemaining()) return; // 空上传直接忽略

        FloatBuffer copy = copyFloatBuffer(data); // 在锁外拷贝，缩短临界区
        synchronized (stateLock) {
            ensureOpen();
            bufferUploads.add(new BufferUpload(buffer, offset, copy, sequence++));
        }
    }

    /** 便捷重载。 */
    public void uploadBuffer(GlBuffer buffer, long offset, float[] data) {
        Objects.requireNonNull(data, "data");
        uploadBuffer(buffer, offset, FloatBuffer.wrap(data));
    }

    /**
     * 丢弃指定 buffer 的所有待处理上传（例如 buffer 即将被销毁时）。
     *
     * @return 被丢弃的请求数量
     */
    public int discardUploadsFor(GlBuffer buffer) {
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
            for (BufferUpload u : bufferUploads) sum += u.sizeBytes();
            return sum;
        }
    }

    /**
     * 执行所有待处理的请求。必须在 GL 线程调用。
     *
     * <p>即使部分请求抛出异常，其余请求仍会尽量执行；
     * 首个异常最终被抛出，其余异常作为 suppressed 附加。
     */
    public void flush() {
        synchronized (flushLock) {
            List<UploadRequest> requests;
            List<BufferUpload> uploads;
            // 快速换出待处理列表，不在持锁状态下做 GPU 调用
            synchronized (stateLock) {
                if (customRequests.isEmpty() && bufferUploads.isEmpty()) return;
                requests = customRequests;
                uploads = bufferUploads;
                customRequests = new ArrayList<>();
                bufferUploads = new ArrayList<>();
            }

            RuntimeException failure = null;
            for (UploadRequest r : requests) {
                try {
                    r.execute();
                    totalRequestsExecuted++;
                } catch (RuntimeException e) {
                    failure = accumulate(failure, e);
                }
            }
            try {
                if (!uploads.isEmpty()) flushBuffers(uploads);
            } catch (RuntimeException e) {
                failure = accumulate(failure, e);
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

    /** 关闭系统，丢弃待处理请求并释放暂存区。之后提交将抛出 {@link IllegalStateException}。 */
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

    // ---- 统计 ----

    public long totalBytesUploaded()    { return totalBytesUploaded; }
    public long totalGpuUpdates()       { return totalGpuUpdates; }
    public long totalRequestsExecuted() { return totalRequestsExecuted; }

    // ---- 内部实现 ----

    private void flushBuffers(List<BufferUpload> uploads) {
        if (uploads.size() == 1) {
            BufferUpload u = uploads.get(0);
            update(u.buffer(), u.offset(), u.data().duplicate());
            return;
        }

        // 按 (buffer, offset) 排序才能正确识别连续区间
        uploads.sort(Comparator
                .comparingInt((BufferUpload u) -> u.buffer().id())
                .thenComparingLong(BufferUpload::offset)
                .thenComparingLong(BufferUpload::sequence));

        int i = 0;
        while (i < uploads.size()) {
            BufferUpload first = uploads.get(i);
            long start = first.offset();
            long end = start + first.sizeBytes();
            int j = i + 1;

            while (j < uploads.size()) {
                BufferUpload next = uploads.get(j);
                if (next.buffer().id() != first.buffer().id()) break;
                if (next.offset() > end) break; // 存在间隙：不可合并，否则会覆盖 GPU 上间隙内的现有数据
                long nextEnd = next.offset() + next.sizeBytes();
                if (nextEnd - start > maxMergeBytes) break; // 超出合并上限，拆分为多次
                end = Math.max(end, nextEnd);
                j++;
            }

            if (j - i == 1) {
                update(first.buffer(), first.offset(), first.data().duplicate());
            } else {
                update(first.buffer(), start, mergeRegion(uploads, i, j, start, end));
            }
            i = j;
        }
    }

    private FloatBuffer mergeRegion(List<BufferUpload> uploads, int from, int to, long start, long end) {
        int sizeBytes = Math.toIntExact(end - start);
        FloatBuffer merged = acquireScratch(sizeBytes).asFloatBuffer();

        // 按提交顺序回放，保证重叠区域 last-write-wins
        List<BufferUpload> region = new ArrayList<>(uploads.subList(from, to));
        region.sort(Comparator.comparingLong(BufferUpload::sequence));
        for (BufferUpload u : region) {
            merged.position(Math.toIntExact((u.offset() - start) / Float.BYTES));
            merged.put(u.data().duplicate());
        }

        // 显式设置范围，不能用 flip()：最后一次 put 未必写到区域末尾
        merged.position(0).limit(sizeBytes / Float.BYTES);
        return merged;
    }

    private void update(GlBuffer buffer, long offset, FloatBuffer data) {
        long bytes = (long) data.remaining() * Float.BYTES;
        buffer.update(offset, data);
        totalBytesUploaded += bytes;
        totalGpuUpdates++;
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

    static FloatBuffer copyFloatBuffer(FloatBuffer src) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(src.remaining() * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        FloatBuffer dst = bytes.asFloatBuffer();
        dst.put(src.duplicate()); // duplicate：不改动调用方 buffer 的 position
        dst.flip();
        return dst;
    }

    @FunctionalInterface
    public interface UploadRequest {
        void execute();
    }

    private record BufferUpload(GlBuffer buffer, long offset, FloatBuffer data, long sequence) {
        long sizeBytes() {
            return (long) data.remaining() * Float.BYTES;
        }
    }
}
```

## 改进要点总结

| 类别 | 改进 |
|------|------|
| **正确性** | 排序改为 `(buffer, offset, sequence)`；用显式 `position/limit` 替代 `flip()`；重叠区域按提交顺序回放，明确 last-write-wins；`Math.toIntExact` 防溢出 |
| **性能** | 复用可增长的直接内存暂存区（超过阈值才释放）；拷贝移到锁外；flush 换出列表后不持 `stateLock` 做 GPU 调用 |
| **健壮性** | 参数校验（负值/对齐/空数据）；异常聚合，一个请求失败不影响其他请求；`maxMergeBytes` 防止巨额合并分配 |
| **API** | `AutoCloseable`；`discardUploadsFor`（buffer 销毁前撤销上传）；`pendingBytes`；上传统计；`float[]` 便捷重载；`copyFloatBuffer` 不再破坏调用方的 position |

一个可以留意的点：**不要加"间隙容忍"合并**（即合并相隔几个字节的区间），因为间隙内暂存区的数据是未定义的，上传会破坏 GPU 上的现有内容——除非先回读，而那比多一次 `update` 更贵。

如果需要，我还可以进一步加上：泛化到 `ByteBuffer`/任意类型数据、双缓冲的 per-frame 环形暂存区、或者持久映射（persistent mapping）+ fence 的路径。