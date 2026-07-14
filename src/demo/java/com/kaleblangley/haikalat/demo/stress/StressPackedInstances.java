package com.kaleblangley.haikalat.demo.stress;

import com.kaleblangley.haikalat.core.mesh.PackedInstanceLayout;

import java.nio.ByteBuffer;

/** CPU-side creation of static compact instance data; never runs in the render loop. */
final class StressPackedInstances {
    private static final float SPACING = 0.14f;

    private StressPackedInstances() {
    }

    static ByteBuffer create(GeneratedStressPrimitive primitive, int count, StressGrid grid) {
        ByteBuffer data = PackedInstanceLayout.allocate(count);
        float scale = primitive == GeneratedStressPrimitive.CUBE ? 0.105f : 0.115f;
        float startX = -(grid.columns() - 1) * SPACING * 0.5f;
        float startY = -(grid.rows() - 1) * SPACING * 0.5f;
        for (int instance = 0; instance < count; instance++) {
            int row = instance >> grid.columnShift();
            int column = instance & grid.columnMask();
            float z = primitive.depthLayers() ? ((instance & 15) - 8) * 0.008f : 0.0f;
            double angle = (instance & 15) * (Math.PI / 8.0);
            int hash = instance * 1_664_525 + 1_013_904_223;
            int color = PackedInstanceLayout.packRgba8(
                    0.25f + 0.75f * ((hash & 0xff) / 255.0f),
                    0.25f + 0.75f * (((hash >>> 8) & 0xff) / 255.0f),
                    0.25f + 0.75f * (((hash >>> 16) & 0xff) / 255.0f),
                    1.0f);
            PackedInstanceLayout.pack(data, instance,
                    startX + column * SPACING,
                    startY + row * SPACING,
                    z, scale, (float) Math.cos(angle), (float) Math.sin(angle), color);
        }
        data.position(0).limit(count * PackedInstanceLayout.STRIDE_BYTES);
        return data;
    }
}
