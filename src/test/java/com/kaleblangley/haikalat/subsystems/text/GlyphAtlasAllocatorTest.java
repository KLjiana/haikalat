package com.kaleblangley.haikalat.subsystems.text;

import org.junit.jupiter.api.Test;

import java.nio.ReadOnlyBufferException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlyphAtlasAllocatorTest {
    @Test
    void shelfAllocationKeepsPaddedRegionsDisjointAndAddsPageWhenFull() {
        GlyphAtlasAllocator allocator = new GlyphAtlasAllocator(16, 16, 1, 2);
        List<GlyphAtlasPlacement> placements = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            placements.add(allocator.allocate(4, 4));
        }

        assertEquals(2, allocator.pageCount());
        assertEquals(0, placements.get(0).pageIndex());
        assertEquals(1, placements.get(4).pageIndex());
        for (int left = 0; left < placements.size(); left++) {
            GlyphAtlasPlacement placement = placements.get(left);
            assertTrue(placement.x() >= placement.padding());
            assertTrue(placement.y() >= placement.padding());
            assertTrue(placement.x() + placement.width() + placement.padding() <= placement.pageWidth());
            assertTrue(placement.y() + placement.height() + placement.padding() <= placement.pageHeight());
            for (int right = left + 1; right < placements.size(); right++) {
                assertFalse(placement.overlapsAllocatedRegion(placements.get(right)));
            }
        }
    }

    @Test
    void fullAndOversizedAllocationsFailWithoutCoordinateOverflow() {
        GlyphAtlasAllocator full = new GlyphAtlasAllocator(16, 16, 1, 1);
        for (int index = 0; index < 4; index++) {
            full.allocate(4, 4);
        }

        assertTrue(full.tryAllocate(4, 4).isEmpty());
        assertThrows(GlyphAtlasFullException.class, () -> full.allocate(4, 4));

        GlyphAtlasAllocator oversized = new GlyphAtlasAllocator(16, 16, 1, 1);
        assertTrue(oversized.tryAllocate(15, 1).isEmpty());
        assertTrue(oversized.tryAllocate(Integer.MAX_VALUE, Integer.MAX_VALUE).isEmpty());
        assertEquals(0, oversized.pageCount());
    }

    @Test
    void sameRequestsProduceDeterministicPlacements() {
        GlyphAtlasAllocator first = new GlyphAtlasAllocator(32, 24, 2, 3);
        GlyphAtlasAllocator second = new GlyphAtlasAllocator(32, 24, 2, 3);
        int[][] sizes = {{5, 7}, {2, 3}, {12, 4}, {3, 9}, {8, 2}};

        for (int[] size : sizes) {
            assertEquals(first.allocate(size[0], size[1]), second.allocate(size[0], size[1]));
        }
    }

    @Test
    void bitmapOwnsCoverageAndOnlyPublishesReadOnlyViews() {
        byte[] source = {1, 2, 3, 4};
        GlyphBitmap bitmap = new GlyphBitmap(
                new GlyphKey(new FontFaceId(1), 42, 16), 2, 2, 0, 2, 2.0f, 0.0f, source);
        source[0] = 99;

        assertArrayEquals(new byte[]{1, 2, 3, 4}, bitmap.copyCoverage());
        assertThrows(ReadOnlyBufferException.class, () -> bitmap.coverage().put(0, (byte) 8));
        byte[] copy = bitmap.copyCoverage();
        copy[1] = 88;
        assertArrayEquals(new byte[]{1, 2, 3, 4}, bitmap.copyCoverage());
    }

    @Test
    void paddingCannotBeDisabled() {
        assertThrows(IllegalArgumentException.class, () -> new GlyphAtlasAllocator(16, 16, 0, 1));
    }

    @Test
    void clearingPagePreservesIndexAndRestartsDeterministicShelfLayout() {
        GlyphAtlasAllocator allocator = new GlyphAtlasAllocator(8, 8, 1, 1);
        GlyphAtlasPlacement first = allocator.allocate(6, 6);

        allocator.clearPage(0);
        GlyphAtlasPlacement replacement = allocator.allocate(6, 6);

        assertEquals(first, replacement);
        assertEquals(1, allocator.pageCount());
    }
}
