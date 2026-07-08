package com.kaleblangley.haikalat.core.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VertexPackingTest {
    @Test
    void normalizedU16ClampsAndRoundTripsBoundaries() {
        assertEquals(0, VertexPacking.packNormalizedU16(-1.0f));
        assertEquals(0, VertexPacking.packNormalizedU16(0.0f));
        assertEquals(0xFFFF, VertexPacking.packNormalizedU16(1.0f));
        assertEquals(0xFFFF, VertexPacking.packNormalizedU16(2.0f));

        assertEquals(0.0f, VertexPacking.unpackNormalizedU16(0), 0.0f);
        assertEquals(1.0f, VertexPacking.unpackNormalizedU16(0xFFFF), 0.0f);
        float value = VertexPacking.unpackNormalizedU16(VertexPacking.packNormalizedU16(0.42f));
        assertEquals(0.42f, value, 1.0f / 65535.0f);
    }

    @Test
    void octNormalRoundTripsRepresentativeDirections() {
        assertRoundTrip(1.0f, 0.0f, 0.0f);
        assertRoundTrip(0.0f, 1.0f, 0.0f);
        assertRoundTrip(0.0f, 0.0f, 1.0f);
        assertRoundTrip(0.5f, 0.5f, 0.70710677f);
        assertRoundTrip(-0.3f, 0.9f, 0.3f);
    }

    @Test
    void octNormalRejectsUndefinedInputs() {
        assertThrows(IllegalArgumentException.class, () -> VertexPacking.packOctNormal(0.0f, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> VertexPacking.packOctNormal(Float.NaN, 0.0f, 1.0f));
    }

    private static void assertRoundTrip(float x, float y, float z) {
        float[] unpacked = VertexPacking.unpackOctNormal(VertexPacking.packOctNormal(x, y, z));
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        assertEquals(x / length, unpacked[0], 1.0e-3f);
        assertEquals(y / length, unpacked[1], 1.0e-3f);
        assertEquals(z / length, unpacked[2], 1.0e-3f);
        assertTrue(Float.isFinite(unpacked[0]));
        assertTrue(Float.isFinite(unpacked[1]));
        assertTrue(Float.isFinite(unpacked[2]));
    }
}
