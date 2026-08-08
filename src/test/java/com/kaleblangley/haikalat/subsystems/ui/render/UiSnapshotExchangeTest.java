package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.subsystems.ui.text.TextEffectType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiSnapshotExchangeTest {
    @Test
    void supportsConfiguredDoubleAndTripleBufferingOnly() {
        try (UiSnapshotExchange doubleBuffered = new UiSnapshotExchange(2);
             UiSnapshotExchange tripleBuffered = new UiSnapshotExchange(3)) {
            assertEquals(2, doubleBuffered.slotCount());
            assertEquals(3, tripleBuffered.slotCount());
        }
        assertThrows(IllegalArgumentException.class, () -> new UiSnapshotExchange(1));
        assertThrows(IllegalArgumentException.class, () -> new UiSnapshotExchange(4));
    }

    @Test
    void producerFasterThanConsumerPublishesLatestWithoutOverwritingActiveLease()
            throws InterruptedException {
        try (UiSnapshotExchange exchange = new UiSnapshotExchange()) {
            exchange.publish(snapshot(1));
            exchange.publish(snapshot(2));
            exchange.publish(snapshot(3));

            UiSnapshotExchange.Lease first = exchange.acquire();
            assertEquals(3, first.snapshot().sequence());
            assertEquals(2, exchange.droppedCount());

            exchange.publish(snapshot(4));
            exchange.publish(snapshot(5));
            UiSnapshotExchange.Lease second = exchange.acquire();

            assertEquals(5, second.snapshot().sequence());
            assertEquals(3, exchange.droppedCount());
            assertEquals(3, first.snapshot().sequence(), "active slot must never be overwritten");
            first.close();
            second.close();
            assertEquals(0, exchange.acquiredCount());
            assertEquals(5, exchange.publishedCount());
        }
    }

    @Test
    void slotOwnedArenaIsReusedOnlyAfterItsLeaseIsReleased() throws InterruptedException {
        try (UiSnapshotExchange exchange = new UiSnapshotExchange(2)) {
            UiDisplayList builder = new UiDisplayList()
                    .addSolidQuad(0.0, 0.0, 2.0, 2.0, 0x01020304,
                            UiBlendMode.PREMULTIPLIED_ALPHA);
            exchange.captureAndPublish(1, 32, 32, 32, 32, 1.0, 1.0,
                    builder, List.of());
            UiSnapshotExchange.Lease first = exchange.acquire();

            builder.clear();
            builder.addSolidQuad(4.0, 4.0, 3.0, 3.0, 0xaabbccdd,
                    UiBlendMode.PREMULTIPLIED_ALPHA);
            exchange.captureAndPublish(2, 32, 32, 32, 32, 1.0, 1.0,
                    builder, List.of());
            UiSnapshotExchange.Lease second = exchange.acquire();

            assertEquals(0x01020304, first.snapshot().displayList().quadColor(0),
                    "acquired slot arena must not be overwritten");
            assertEquals(0xaabbccdd, second.snapshot().displayList().quadColor(0));
            assertTrue(first.snapshot().displayList().isFrozen());
            assertThrows(IllegalStateException.class,
                    first.snapshot().displayList()::clear);
            first.close();
            second.close();
        }
    }

    @Test
    void captureIntoSlotPreservesTextEffectsAndTheirBatchBoundary() throws InterruptedException {
        UiDisplayList builder = new UiDisplayList();
        builder.beginGlyphRun(7, 3, UiBlendMode.PREMULTIPLIED_ALPHA);
        builder.setGlyphTextEffect((byte) TextEffectType.GRADIENT.ordinal(),
                0x10203040, 0x50607080, 1.25f, 2.5f, -3.5f, 4.75f, 37.5f);
        builder.addGlyph(new UiScreenRect(0.0, 0.0, 4.0, 6.0),
                UiUvRect.FULL, 0xffffffff);
        builder.endGlyphRun();
        builder.beginGlyphRun(7, 3, UiBlendMode.PREMULTIPLIED_ALPHA);
        builder.addGlyph(new UiScreenRect(4.0, 0.0, 4.0, 6.0),
                UiUvRect.FULL, 0xffffffff);
        builder.endGlyphRun();

        try (UiSnapshotExchange exchange = new UiSnapshotExchange(2)) {
            exchange.captureAndPublish(1, 32, 32, 32, 32, 1.0, 1.0,
                    builder, List.of());
            try (UiSnapshotExchange.Lease lease = exchange.acquire()) {
                UiRenderSnapshot snapshot = lease.snapshot();
                UiDisplayList captured = snapshot.displayList();

                assertEquals(TextEffectType.GRADIENT.ordinal(), captured.textEffectType(0));
                assertEquals(0x10203040, captured.textEffectColor1(0));
                assertEquals(0x50607080, captured.textEffectColor2(0));
                assertEquals(1.25f, captured.textEffectThickness(0));
                assertEquals(2.5f, captured.textEffectOffsetX(0));
                assertEquals(-3.5f, captured.textEffectOffsetY(0));
                assertEquals(4.75f, captured.textEffectBlur(0));
                assertEquals(37.5f, captured.textEffectAngle(0));
                assertEquals(TextEffectType.NONE.ordinal(), captured.textEffectType(1));
                assertEquals(2, snapshot.batches().size());
            }
        }
    }

    @Test
    void threeActiveLeasesApplyBackpressureUntilOneIsReleased() throws Exception {
        try (UiSnapshotExchange exchange = new UiSnapshotExchange();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            UiSnapshotExchange.Lease[] leases = new UiSnapshotExchange.Lease[3];
            for (int index = 0; index < leases.length; index++) {
                exchange.publish(snapshot(index));
                leases[index] = exchange.acquire();
            }
            CountDownLatch entered = new CountDownLatch(1);
            Future<Boolean> publish = executor.submit(() -> {
                entered.countDown();
                return exchange.tryPublish(snapshot(9), 5, TimeUnit.SECONDS);
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> publish.get(100, TimeUnit.MILLISECONDS));

            leases[1].close();

            assertTrue(publish.get(1, TimeUnit.SECONDS));
            try (UiSnapshotExchange.Lease newest = exchange.acquire()) {
                assertEquals(9, newest.snapshot().sequence());
            }
            leases[0].close();
            leases[2].close();
        }
    }

    @Test
    void exceptionalConsumerPathReleasesLeaseAndDoubleCloseIsSafe() throws Exception {
        try (UiSnapshotExchange exchange = new UiSnapshotExchange()) {
            exchange.publish(snapshot(1));
            RuntimeException failure = assertThrows(RuntimeException.class, () -> {
                try (UiSnapshotExchange.Lease ignored = exchange.acquire()) {
                    throw new RuntimeException("render failed");
                }
            });
            assertEquals("render failed", failure.getMessage());
            assertEquals(0, exchange.acquiredCount());

            exchange.publish(snapshot(2));
            UiSnapshotExchange.Lease lease = exchange.acquire();
            lease.close();
            lease.close();
            assertTrue(lease.isReleased());
            assertThrows(IllegalStateException.class, lease::snapshot);
        }
    }

    @Test
    void closeWakesWaitingConsumer() throws Exception {
        UiSnapshotExchange exchange = new UiSnapshotExchange();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CountDownLatch entered = new CountDownLatch(1);
            Future<UiSnapshotExchange.Lease> waiting = executor.submit(() -> {
                entered.countDown();
                return exchange.acquire();
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            exchange.close();

            assertNull(waiting.get(1, TimeUnit.SECONDS));
            assertTrue(exchange.isClosed());
            assertThrows(IllegalStateException.class, () -> exchange.publish(snapshot(2)));
        } finally {
            exchange.close();
        }
    }

    @Test
    void closeWakesBlockedProducerButDoesNotInvalidateActiveLeases() throws Exception {
        UiSnapshotExchange exchange = new UiSnapshotExchange();
        UiSnapshotExchange.Lease[] leases = new UiSnapshotExchange.Lease[3];
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            for (int index = 0; index < leases.length; index++) {
                exchange.publish(snapshot(index));
                leases[index] = exchange.acquire();
            }
            CountDownLatch entered = new CountDownLatch(1);
            Future<Void> waiting = executor.submit(() -> {
                entered.countDown();
                exchange.publish(snapshot(10));
                return null;
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            exchange.close();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> waiting.get(1, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(0, leases[0].snapshot().sequence());
            assertFalse(leases[0].isReleased());
        } finally {
            for (UiSnapshotExchange.Lease lease : leases) {
                if (lease != null) {
                    lease.close();
                }
            }
            exchange.close();
        }
    }

    private static UiRenderSnapshot snapshot(long sequence) {
        UiDisplayList list = new UiDisplayList()
                .addSolidQuad(new UiScreenRect(0.0, 0.0, 1.0, 1.0),
                        0xffffffff, UiBlendMode.PREMULTIPLIED_ALPHA);
        return UiRenderSnapshot.capture(sequence, list);
    }
}
