package com.kaleblangley.haikalat.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatestFrameMailboxTest {
    @Test
    void latestPublicationReplacesUnconsumedFrames() {
        LatestFrameMailbox<String> mailbox = new LatestFrameMailbox<>("initial");

        mailbox.publish("frame-1");
        long sequence = mailbox.publish("frame-2");

        assertEquals(2L, sequence);
        assertEquals(new LatestFrameMailbox.Snapshot<>(2L, "frame-2"), mailbox.latest());
    }

    @Test
    void publicationIsVisibleAcrossThreadsAsOneSnapshot() throws Exception {
        LatestFrameMailbox<String> mailbox = new LatestFrameMailbox<>("initial");
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(1);

        Thread producer = new Thread(() -> {
            mailbox.publish("complete-frame");
            published.countDown();
        });
        Thread consumer = new Thread(() -> {
            try {
                assertTrue(published.await(2, TimeUnit.SECONDS));
                LatestFrameMailbox.Snapshot<String> snapshot = mailbox.latest();
                if (snapshot.sequence() == 1L && snapshot.value().equals("complete-frame")) {
                    observed.countDown();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        producer.start();
        consumer.start();
        producer.join();
        consumer.join();

        assertTrue(observed.await(0, TimeUnit.SECONDS));
    }
}
