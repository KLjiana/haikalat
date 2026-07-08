package com.kaleblangley.haikalat.core.buffer;

import java.util.Objects;
import java.util.function.Supplier;

public final class TripleBuffer<T> {
    private final Object[] buffers = new Object[3];
    private int readIndex;
    private int writeIndex = 1;
    private int spareIndex = 2;

    public TripleBuffer(Supplier<? extends T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = Objects.requireNonNull(supplier.get(), "supplier returned null");
        }
    }

    /**
     * 获取当前可读缓冲区（读取线程使用）。
     *
     * @return 只读缓冲区
     */
    @SuppressWarnings("unchecked")
    public synchronized T read() {
        return (T) buffers[readIndex];
    }

    /**
     * 获取当前可写缓冲区（写入线程使用）。
     *
     * @return 可写缓冲区
     */
    @SuppressWarnings("unchecked")
    public synchronized T write() {
        return (T) buffers[writeIndex];
    }

    /**
     * 切换缓冲区角色：当前写入缓冲变成可读，空闲缓冲变为可写，原可读缓冲变为空闲。
     */
    public synchronized void flip() {
        int previousRead = readIndex;
        readIndex = writeIndex;
        writeIndex = spareIndex;
        spareIndex = previousRead;
    }
}
