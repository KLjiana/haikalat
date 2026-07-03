package com.kaleblangley.haikalat.gl.buffer;

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

    @SuppressWarnings("unchecked")
    public synchronized T read() {
        return (T) buffers[readIndex];
    }

    @SuppressWarnings("unchecked")
    public synchronized T write() {
        return (T) buffers[writeIndex];
    }

    public synchronized void flip() {
        int previousRead = readIndex;
        readIndex = writeIndex;
        writeIndex = spareIndex;
        spareIndex = previousRead;
    }
}
