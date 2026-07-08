package com.kaleblangley.haikalat.core.buffer;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripleBufferTest {
    @Test
    void flipPromotesWriteBufferToReadBufferAndRotatesSpare() {
        TripleBuffer<StringBuilder> buffers = new TripleBuffer<>(StringBuilder::new);
        StringBuilder initialRead = buffers.read();
        StringBuilder firstWrite = buffers.write();
        firstWrite.append("frame-1");

        buffers.flip();

        assertNotSame(initialRead, buffers.read());
        assertEquals("frame-1", buffers.read().toString());
        assertEquals("", buffers.write().toString());
    }

    @Test
    void flipIsVisibleAcrossThreads() throws Exception {
        TripleBuffer<AtomicInteger> buffers = new TripleBuffer<>(AtomicInteger::new);
        CountDownLatch flipped = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(1);

        Thread writer = new Thread(() -> {
            buffers.write().set(42);
            buffers.flip();
            flipped.countDown();
        });
        Thread reader = new Thread(() -> {
            try {
                assertTrue(flipped.await(2, TimeUnit.SECONDS));
                if (buffers.read().get() == 42) {
                    observed.countDown();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        writer.start();
        reader.start();
        writer.join();
        reader.join();

        assertTrue(observed.await(0, TimeUnit.SECONDS));
    }
}
