package com.kaleblangley.haikalat.core.buffer;

/**
 * 实例 ring 的累计上传与 GPU fence 等待统计。
 *
 * @param uploadedBytes  已写入实例 ring 的总字节数
 * @param fenceWaitNanos 等待复用 ring slot 的总纳秒数
 * @param fenceWaitCount 实际调用 fence wait 的次数
 */
public record InstanceBufferStatistics(
        long uploadedBytes,
        long fenceWaitNanos,
        long fenceWaitCount
) {
    public static InstanceBufferStatistics empty() {
        return new InstanceBufferStatistics(0L, 0L, 0L);
    }

    public double fenceWaitMillis() {
        return fenceWaitNanos / 1_000_000.0;
    }
}
