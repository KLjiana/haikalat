package com.kaleblangley.haikalat.subsystems.text;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlyphAtlasTest {
    @Test
    void missBuildsPaddedStableDirectUploadAndSuccessPublishesPlacement() {
        try (GlyphAtlas atlas = new GlyphAtlas(16, 16, 1, 1)) {
            GlyphKey key = key(1);
            AtomicInteger rasterizations = new AtomicInteger();
            GlyphAtlasLookup miss = atlas.lookup(key, ignored -> {
                rasterizations.incrementAndGet();
                return bitmap(key, 2, 2, new byte[]{1, 2, 3, 4});
            });
            GlyphUploadRequest request = miss.uploadRequest().orElseThrow();

            assertEquals(GlyphAtlasLookup.Status.UPLOAD_REQUIRED, miss.status());
            assertEquals(0, request.x());
            assertEquals(0, request.y());
            assertEquals(4, request.width());
            assertEquals(4, request.height());
            assertTrue(request.payload().isDirect());
            assertTrue(request.payload().isReadOnly());
            assertThrows(ReadOnlyBufferException.class, () -> request.payload().put(0, (byte) 9));
            assertArrayEquals(new byte[]{
                    0, 0, 0, 0,
                    0, 1, 2, 0,
                    0, 3, 4, 0,
                    0, 0, 0, 0
            }, bytes(request.payload()));
            ByteBuffer page = atlas.pageCoverage(0);
            assertTrue(page.isDirect());
            assertTrue(page.isReadOnly());
            assertEquals(1, Byte.toUnsignedInt(page.get(1 * 16 + 1)));
            assertEquals(4, Byte.toUnsignedInt(page.get(2 * 16 + 2)));
            assertEquals(0L, atlas.statistics().uploadBytes());
            assertEquals(1, atlas.statistics().pendingUploads());

            GlyphAtlasGlyph published = atlas.publishUpload(request);
            GlyphAtlasLookup hit = atlas.lookup(key, ignored -> {
                throw new AssertionError("ready glyph must not rasterize again");
            });

            assertSame(published, hit.glyph().orElseThrow());
            assertEquals(1, rasterizations.get());
            assertEquals(1L, atlas.statistics().hits());
            assertEquals(1L, atlas.statistics().misses());
            assertEquals(16L, atlas.statistics().uploadBytes());
            assertEquals(1, atlas.statistics().readyGlyphs());
            assertEquals(0, atlas.statistics().pendingUploads());
        }
    }

    @Test
    void failedUploadRemainsUnpublishedAndReturnsSameRequestForRetry() {
        try (GlyphAtlas atlas = new GlyphAtlas(8, 8, 1, 1)) {
            GlyphKey key = key(1);
            GlyphUploadRequest request = atlas.lookup(key, ignored -> solidBitmap(key, 2, 2))
                    .uploadRequest().orElseThrow();

            atlas.uploadFailed(request);
            GlyphAtlasLookup retry = atlas.lookup(key, ignored -> {
                throw new AssertionError("pending glyph must reuse its raster and payload");
            });

            assertEquals(GlyphAtlasLookup.Status.UPLOAD_REQUIRED, retry.status());
            assertSame(request, retry.uploadRequest().orElseThrow());
            assertEquals(0, atlas.statistics().readyGlyphs());
            assertEquals(1, atlas.statistics().pendingUploads());
            atlas.publishUpload(request);
            assertTrue(atlas.lookup(key, ignored -> null).ready());
        }
    }

    @Test
    void inFlightGenerationBlocksEvictionUntilLeaseIsReleased() {
        GlyphAtlas atlas = new GlyphAtlas(8, 8, 1, 1);
        GlyphKey firstKey = key(1);
        GlyphUploadRequest firstRequest = atlas.lookup(firstKey, ignored -> solidBitmap(firstKey, 6, 6))
                .uploadRequest().orElseThrow();
        GlyphAtlasGlyph first = atlas.publishUpload(firstRequest);
        GlyphAtlasGenerationLease lease = atlas.acquireGeneration();
        GlyphKey secondKey = key(2);

        GlyphAtlasLookup blocked = atlas.lookup(secondKey, ignored -> solidBitmap(secondKey, 6, 6));
        assertEquals(GlyphAtlasLookup.Status.CAPACITY_BLOCKED, blocked.status());
        assertEquals(0L, atlas.statistics().evictions());
        assertThrows(IllegalStateException.class, atlas::close);

        lease.close();
        lease.close();
        GlyphUploadRequest replacement = atlas.lookup(secondKey, ignored -> solidBitmap(secondKey, 6, 6))
                .uploadRequest().orElseThrow();

        assertEquals(first.placement().orElseThrow().pageIndex(), replacement.pageIndex());
        assertTrue(replacement.generation() > first.generation());
        assertEquals(1L, atlas.statistics().evictions());
        atlas.publishUpload(replacement);
        atlas.close();
        assertTrue(atlas.isClosed());
    }

    @Test
    void generationLeaseCanBeReleasedByRenderThread() throws Exception {
        try (GlyphAtlas atlas = new GlyphAtlas(8, 8, 1, 1)) {
            GlyphAtlasGenerationLease lease = atlas.acquireGeneration();
            Thread renderThread = new Thread(lease::close, "atlas-render-release");
            renderThread.start();
            renderThread.join();

            assertTrue(lease.released());
            assertEquals(0, atlas.statistics().activeGenerationLeases());
        }
    }

    @Test
    void pendingRequestPinsPageAndCancelMakesWholePageRecyclable() {
        try (GlyphAtlas atlas = new GlyphAtlas(8, 8, 1, 1)) {
            GlyphKey first = key(1);
            GlyphUploadRequest pending = atlas.lookup(first, ignored -> solidBitmap(first, 6, 6))
                    .uploadRequest().orElseThrow();
            byte[] stablePayload = bytes(pending.payload());
            GlyphKey second = key(2);

            assertEquals(GlyphAtlasLookup.Status.CAPACITY_BLOCKED,
                    atlas.lookup(second, ignored -> solidBitmap(second, 6, 6)).status());
            atlas.uploadFailed(pending);
            atlas.cancelUpload(pending);
            GlyphUploadRequest replacement = atlas.lookup(second, ignored -> solidBitmap(second, 6, 6))
                    .uploadRequest().orElseThrow();

            assertEquals(0, replacement.pageIndex());
            assertEquals(0L, atlas.statistics().evictions());
            assertArrayEquals(stablePayload, bytes(pending.payload()));
            assertThrows(IllegalArgumentException.class, () -> atlas.publishUpload(pending));
        }
    }

    @Test
    void pageLruRecyclesOldestCompatiblePageWithoutGlobalRepack() {
        try (GlyphAtlas atlas = new GlyphAtlas(8, 8, 1, 2)) {
            GlyphKey first = key(1);
            GlyphKey second = key(2);
            atlas.publishUpload(atlas.lookup(first, ignored -> solidBitmap(first, 6, 6))
                    .uploadRequest().orElseThrow());
            atlas.publishUpload(atlas.lookup(second, ignored -> solidBitmap(second, 6, 6))
                    .uploadRequest().orElseThrow());
            atlas.lookup(first, ignored -> null);
            GlyphKey third = key(3);

            GlyphUploadRequest replacement = atlas.lookup(third, ignored -> solidBitmap(third, 6, 6))
                    .uploadRequest().orElseThrow();

            assertEquals(1, replacement.pageIndex());
            assertEquals(2, atlas.statistics().pages());
            assertEquals(1L, atlas.statistics().evictions());
        }
    }

    @Test
    void zeroCoverageGlyphIsReadyWithoutPageOrUpload() {
        try (GlyphAtlas atlas = new GlyphAtlas(8, 8, 1, 1)) {
            GlyphKey key = key(10);
            GlyphAtlasLookup result = atlas.lookup(key,
                    ignored -> new GlyphBitmap(key, 0, 0, 0, 0, 4.0f, 0.0f, new byte[0]));

            assertTrue(result.ready());
            assertFalse(result.glyph().orElseThrow().drawable());
            assertEquals(0, atlas.statistics().pages());
            assertEquals(0, atlas.statistics().pendingUploads());
        }
    }

    @Test
    void oversizedGlyphAndMismatchedRasterResultFailExplicitly() {
        try (GlyphAtlas atlas = new GlyphAtlas(8, 8, 1, 1)) {
            GlyphKey key = key(1);
            assertThrows(GlyphAtlasCapacityException.class,
                    () -> atlas.lookup(key, ignored -> solidBitmap(key, 7, 1)));
            GlyphKey wrong = key(2);
            assertThrows(IllegalArgumentException.class,
                    () -> atlas.lookup(key, ignored -> solidBitmap(wrong, 2, 2)));
        }
    }

    private static GlyphKey key(int glyphId) {
        return new GlyphKey(new FontFaceId(1), glyphId, 16);
    }

    private static GlyphBitmap solidBitmap(GlyphKey key, int width, int height) {
        byte[] coverage = new byte[width * height];
        java.util.Arrays.fill(coverage, (byte) 0x7F);
        return bitmap(key, width, height, coverage);
    }

    private static GlyphBitmap bitmap(GlyphKey key, int width, int height, byte[] coverage) {
        return new GlyphBitmap(key, width, height, 0, height, width, 0.0f, coverage);
    }

    private static byte[] bytes(ByteBuffer buffer) {
        ByteBuffer copy = buffer.duplicate();
        byte[] result = new byte[copy.remaining()];
        copy.get(result);
        return result;
    }
}
