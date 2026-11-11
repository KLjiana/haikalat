package com.kaleblangley.haikalat.subsystems.postprocess.smaa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the test-side SMAA luma edge-detection oracle on hand-derived fixtures.
 * The real-GL shader test uses the same reference as its independent oracle,
 * so these cases guard the oracle itself: flat input, a hard axis-aligned
 * step, a thin diagonal line, border clamp and local-contrast suppression.
 */
class SmaaEdgeReferenceTest {
    @Test
    void flatImageHasNoEdges() {
        byte[] edges = SmaaEdgeReference.detect(filled(8, 8, 128, 128, 128), 8, 8);

        for (int offset = 0; offset < edges.length; offset += 4) {
            assertEquals(0, Byte.toUnsignedInt(edges[offset]));
            assertEquals(0, Byte.toUnsignedInt(edges[offset + 1]));
        }
    }

    @Test
    void verticalHardEdgeOnlyMarksTheWestPixelOfTheBrightSide() {
        int width = 8;
        int height = 4;
        byte[] source = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = x < 4 ? 0 : 255;
                put(source, width, x, y, value, value, value);
            }
        }

        byte[] edges = SmaaEdgeReference.detect(source, width, height);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int expectedRed = x == 4 ? 255 : 0;
                assertEquals(expectedRed, red(edges, width, x, y),
                        "edge mask at (" + x + "," + y + ")");
                assertEquals(0, green(edges, width, x, y),
                        "a vertical step must not produce a horizontal edge");
            }
        }
    }

    @Test
    void thinDiagonalLineMarksItsWestAndNorthEdges() {
        int width = 8;
        int height = 8;
        byte[] source = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = x == y ? 255 : 0;
                put(source, width, x, y, value, value, value);
            }
        }

        byte[] edges = SmaaEdgeReference.detect(source, width, height);

        for (int i = 1; i < 8; i++) {
            assertEquals(255, red(edges, width, i, i), "west edge at " + i);
            assertEquals(255, green(edges, width, i, i), "north edge at " + i);
        }
        // The top-left pixel clamps to itself and has no neighbour delta.
        assertEquals(0, red(edges, width, 0, 0));
        assertEquals(0, green(edges, width, 0, 0));
        // Pixels below the line see the lit pixel as their west neighbour.
        assertEquals(255, red(edges, width, 2, 1));
        assertEquals(0, green(edges, width, 2, 1));
    }

    @Test
    void weakEdgeBesideAStrongerNeighbourIsSuppressedByLocalContrastAdaptation() {
        byte[] source = new byte[5 * 4];
        int[] values = {0, 166, 128, 128, 128};
        for (int x = 0; x < values.length; x++) {
            put(source, values.length, x, 0, values[x], values[x], values[x]);
        }

        byte[] edges = SmaaEdgeReference.detect(source, values.length, 1);

        assertEquals(255, red(edges, values.length, 1, 0),
                "the strong 0.65 edge survives");
        assertEquals(0, red(edges, values.length, 2, 0),
                "the 0.15 edge is discarded against the 0.65 local maximum");
    }

    @Test
    void thresholdIsInclusiveAndBorderSamplesClampToEdge() {
        byte[] source = new byte[4];
        // 0.1 in 8-bit is 26/255 = 0.1019, exactly at/above the reference threshold.
        put(source, 1, 0, 0, 26, 26, 26);

        assertEquals(0.1f, SmaaEdgeReference.THRESHOLD);
        assertEquals(2.0f, SmaaEdgeReference.LOCAL_CONTRAST_ADAPTATION_FACTOR);
        byte[] edges = SmaaEdgeReference.detect(source, 1, 1);
        assertEquals(0, red(edges, 1, 0, 0),
                "a single clamped pixel has no neighbour delta");
        assertTrue(edges.length == 4);
    }

    private static int red(byte[] pixels, int width, int x, int y) {
        return Byte.toUnsignedInt(pixels[(y * width + x) * 4]);
    }

    private static int green(byte[] pixels, int width, int x, int y) {
        return Byte.toUnsignedInt(pixels[(y * width + x) * 4 + 1]);
    }

    private static void put(byte[] pixels, int width, int x, int y,
                            int red, int green, int blue) {
        int offset = (y * width + x) * 4;
        pixels[offset] = (byte) red;
        pixels[offset + 1] = (byte) green;
        pixels[offset + 2] = (byte) blue;
        pixels[offset + 3] = (byte) 255;
    }

    private static byte[] filled(int width, int height, int red, int green, int blue) {
        byte[] pixels = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                put(pixels, width, x, y, red, green, blue);
            }
        }
        return pixels;
    }
}
