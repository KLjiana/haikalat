package com.kaleblangley.haikalat.subsystems.postprocess.smaa;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reference contract for the procedural SMAA 1x lookup tables.
 *
 * <p>The expected dimensions, SHA-256 fingerprints and sample bytes come from
 * the author's own generators ({@code Scripts/AreaTex.py} and
 * {@code Scripts/SearchTex.py}, commit {@code 71c806a}), executed independently
 * with CPython/Pillow while preparing the feature.  The Java generator is not
 * used to derive its own oracle.</p>
 */
class SmaaLookupTablesTest {
    @Test
    void areaPayloadMatchesTheAuthorReferenceFingerprintAndSamples() {
        byte[] area = SmaaLookupTables.generateAreaRg();

        assertEquals(160 * 560 * 2, area.length);
        assertEquals(SmaaLookupTables.AREA_SHA256, sha256(area));
        // Orthogonal U pattern (pattern 3, slot 0) for distances (0,0), (1,1),
        // (2,2) and (3,3).  Destination coordinates encode the squared distance.
        assertEquals(125, areaAt(area, 48, 48, 0));
        assertEquals(0, areaAt(area, 48, 48, 1));
        assertEquals(68, areaAt(area, 49, 49, 0));
        assertEquals(32, areaAt(area, 50, 50, 0));
        assertEquals(1, areaAt(area, 52, 52, 0));
        assertEquals(0, areaAt(area, 54, 54, 0));
        // Orthogonal pattern 1 (L shape) and pattern 7 (Z shape).
        assertEquals(63, areaAt(area, 48, 1, 0));
        assertEquals(10, areaAt(area, 49, 1, 0));
        assertEquals(0, areaAt(area, 50, 1, 0));
        assertEquals(10, areaAt(area, 17, 65, 0));
        assertEquals(10, areaAt(area, 17, 65, 1));
        assertEquals(0, areaAt(area, 17, 66, 0));
        assertEquals(63, areaAt(area, 17, 66, 1));
        assertEquals(63, areaAt(area, 18, 65, 0));
        assertEquals(0, areaAt(area, 18, 65, 1));
        // The second orthogonal sub-pixel offset slot starts at y = 80.
        assertEquals(154, areaAt(area, 48, 128, 0));
        assertEquals(84, areaAt(area, 49, 129, 0));
        // Diagonal patterns live in the right half, starting at x = 80.
        assertEquals(64, areaAt(area, 80, 0, 0));
        assertEquals(66, areaAt(area, 90, 10, 0));
        assertEquals(61, areaAt(area, 90, 10, 1));
        assertEquals(131, areaAt(area, 110, 50, 0));
        assertEquals(35, areaAt(area, 110, 30, 0));
        assertEquals(78, areaAt(area, 130, 70, 1));
        // Unused area of the payload stays zero.
        assertEquals(0, areaAt(area, 0, 0, 0));
        assertEquals(0, areaAt(area, 159, 559, 1));
    }

    @Test
    void searchPayloadMatchesTheAuthorReferenceFingerprintAndSamples() {
        byte[] search = SmaaLookupTables.generateSearchR();

        assertEquals(64 * 16, search.length);
        assertEquals(SmaaLookupTables.SEARCH_SHA256, sha256(search));
        assertEquals(254, Byte.toUnsignedInt(search[0 * 64 + 0]));
        assertEquals(127, Byte.toUnsignedInt(search[8 * 64 + 31]));
        assertEquals(127, Byte.toUnsignedInt(search[8 * 64 + 32]));
        assertEquals(0, Byte.toUnsignedInt(search[15 * 64 + 63]));
        assertEquals(127, Byte.toUnsignedInt(search[3 * 64 + 10]));
        assertEquals(0, Byte.toUnsignedInt(search[12 * 64 + 48]));
        for (byte value : search) {
            int unsigned = Byte.toUnsignedInt(value);
            assertTrue(unsigned == 0 || unsigned == 127 || unsigned == 254,
                    "search texture only encodes search lengths 0..2: " + unsigned);
        }
    }

    @Test
    void generationIsDeterministicAndCachedCopiesAreIndependent() {
        byte[] first = SmaaLookupTables.generateAreaRg();
        byte[] second = SmaaLookupTables.generateAreaRg();
        assertArrayEquals(first, second, "area generation must be deterministic");

        byte[] cached = SmaaLookupTables.areaRg();
        assertArrayEquals(first, cached);
        cached[0] = (byte) 0x7f;
        assertNotEquals(cached[0], SmaaLookupTables.areaRg()[0],
                "callers must not be able to mutate the cached payload");

        assertArrayEquals(SmaaLookupTables.generateSearchR(), SmaaLookupTables.searchR());
    }

    private static int areaAt(byte[] payload, int x, int y, int channel) {
        return Byte.toUnsignedInt(payload[(y * SmaaLookupTables.AREA_WIDTH + x) * 2 + channel]);
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError("SHA-256 unavailable", failure);
        }
    }
}
