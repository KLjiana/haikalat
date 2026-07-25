package com.kaleblangley.haikalat.subsystems.resources;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncResourceDecoderTest {
    @Test
    void inFlightDecodeCannotPublishAfterInvalidation() throws Exception {
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch continueRead = new CountDownLatch(1);
        ResourceSource source = (id, maxBytes) -> {
            readStarted.countDown();
            try {
                if (!continueRead.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("timed out waiting for test release");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", interrupted);
            }
            return "decoded".getBytes(StandardCharsets.UTF_8);
        };
        ResourceGenerationTracker generations = new ResourceGenerationTracker();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AsyncResourceDecoder decoder = new AsyncResourceDecoder(
                source, generations, executor, 1024);
        AssetId id = AssetId.of("game", "data/value.txt");
        try {
            var future = decoder.decode(id,
                    (assetId, bytes) -> new String(bytes, StandardCharsets.UTF_8));
            assertTrue(readStarted.await(5, TimeUnit.SECONDS));
            decoder.invalidate(id);
            continueRead.countDown();
            AsyncResourceDecoder.Result<String> stale = future.get(5, TimeUnit.SECONDS);

            AtomicReference<String> published = new AtomicReference<>();
            assertFalse(decoder.publishIfCurrent(stale, published::set));

            AsyncResourceDecoder.Result<String> fresh = decoder.decode(id,
                    (assetId, bytes) -> new String(bytes, StandardCharsets.UTF_8))
                    .get(5, TimeUnit.SECONDS);
            assertTrue(decoder.publishIfCurrent(fresh, published::set));
            assertEquals("decoded", published.get());
            assertEquals(new ResourceGeneration(1L), fresh.generation());
        } finally {
            continueRead.countDown();
            decoder.close();
            executor.shutdownNow();
        }
    }

    @Test
    void ownedDecoderRejectsRequestsAfterClose() {
        AsyncResourceDecoder decoder = new AsyncResourceDecoder(
                (id, maxBytes) -> new byte[0]);
        decoder.close();

        assertThrows(IllegalStateException.class,
                () -> decoder.decode(AssetId.of("closed.bin"), (id, bytes) -> bytes.length));
    }
}
