package com.kaleblangley.haikalat.core.mesh;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackedInstanceLayoutTest {
    @Test
    void std430LayoutUsesSixteenBytesAndRoundTripsWithinQuantizationError() {
        ByteBuffer data = PackedInstanceLayout.allocate(2);
        int color = PackedInstanceLayout.packRgba8(0.2f, 0.4f, 0.8f, 1.0f);
        PackedInstanceLayout.pack(data, 1,
                -19.375f, 11.8125f, -0.064f, 0.105f,
                0.70710677f, -0.70710677f, color);

        PackedInstanceLayout.PackedInstance decoded = PackedInstanceLayout.unpack(data, 1);

        assertEquals(16, PackedInstanceLayout.STRIDE_BYTES);
        assertEquals(-19.375f, decoded.translationX(), 0.01f);
        assertEquals(11.8125f, decoded.translationY(), 0.01f);
        assertEquals(-0.064f, decoded.translationZ(), 0.0001f);
        assertEquals(0.105f, decoded.scale(), 0.0001f);
        assertEquals(0.70710677f, decoded.rotationCos(), 0.00004f);
        assertEquals(-0.70710677f, decoded.rotationSin(), 0.00004f);
        assertEquals(color, decoded.rgba8());
    }

    @Test
    void oneHundredThousandPackedInstancesUseOnePointSixMegabytes() {
        assertEquals(1_600_000, PackedInstanceLayout.allocate(100_000).capacity());
        assertEquals(6_400_000, 100_000 * 16 * Float.BYTES);
    }
}
