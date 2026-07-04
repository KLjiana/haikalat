package com.kaleblangley.haikalat.core.upload;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class UploadSystem {
    private final List<UploadRequest> customRequests = new ArrayList<>();
    private final List<BufferUpload> bufferUploads = new ArrayList<>();

    /**
     * 提交一个自定义上传请求，在 flush 时执行。
     *
     * @param request 自定义上传请求
     */
    public synchronized void submit(UploadRequest request) {
        customRequests.add(Objects.requireNonNull(request, "request"));
    }

    /**
     * 提交缓冲区上传请求，在 flush 时合并连续范围的更新。
     *
     * @param buffer 目标缓冲区
     * @param offset 偏移量（字节）
     * @param data   要上传的浮点数据
     */
    public synchronized void uploadBuffer(GlBuffer buffer, long offset, FloatBuffer data) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(data, "data");
        bufferUploads.add(new BufferUpload(buffer, offset, copyFloatBuffer(data)));
    }

    /** @return 待处理的上传请求数量 */
    public synchronized int pendingCount() {
        return customRequests.size() + bufferUploads.size();
    }

    /**
     * 执行所有待处理的自定义请求和缓冲区上传。
     * 缓冲区上传会按 buffer 合并连续范围的更新以减少 GPU 交互次数。
     */
    public synchronized void flush() {
        if (!customRequests.isEmpty()) {
            for (UploadRequest r : customRequests) r.execute();
            customRequests.clear();
        }
        if (!bufferUploads.isEmpty()) {
            flushMerged();
            bufferUploads.clear();
        }
    }

    /** 丢弃所有待处理的上传请求而不执行。 */
    public synchronized void clear() {
        customRequests.clear();
        bufferUploads.clear();
    }

    private void flushMerged() {
        if (bufferUploads.size() == 1) {
            BufferUpload u = bufferUploads.get(0);
            u.buffer.update(u.offset, u.data);
            return;
        }

        List<BufferUpload> sorted = new ArrayList<>(bufferUploads);
        sorted.sort(Comparator.comparingInt(u -> u.buffer.id()));

        int i = 0;
        while (i < sorted.size()) {
            BufferUpload base = sorted.get(i);
            long start = base.offset;
            long end = start + base.dataSize();
            int j = i + 1;

            while (j < sorted.size()) {
                BufferUpload next = sorted.get(j);
                if (next.buffer.id() != base.buffer.id()) break;
                long nextEnd = next.offset + next.dataSize();
                if (next.offset <= end) {
                    end = Math.max(end, nextEnd);
                    j++;
                } else {
                    break;
                }
            }

            FloatBuffer merged = mergeRange(sorted, i, j, end - start);
            base.buffer.update(start, merged);
            i = j;
        }
    }

    private FloatBuffer mergeRange(List<BufferUpload> sorted, int from, int to, long totalBytes) {
        ByteBuffer bytes = ByteBuffer.allocateDirect((int) totalBytes)
                .order(ByteOrder.nativeOrder());
        FloatBuffer merged = bytes.asFloatBuffer();
        for (int k = from; k < to; k++) {
            BufferUpload u = sorted.get(k);
            merged.position((int) (u.offset - sorted.get(from).offset) / Float.BYTES);
            merged.put(u.data);
        }
        merged.flip();
        return merged;
    }

    static FloatBuffer copyFloatBuffer(FloatBuffer src) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(src.remaining() * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        FloatBuffer dst = bytes.asFloatBuffer();
        dst.put(src);
        dst.flip();
        return dst;
    }

    @FunctionalInterface
    public interface UploadRequest {
        void execute();
    }

    private record BufferUpload(GlBuffer buffer, long offset, FloatBuffer data) {
        long dataSize() {
            return (long) data.remaining() * Float.BYTES;
        }
    }
}
