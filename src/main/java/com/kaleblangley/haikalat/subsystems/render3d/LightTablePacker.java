package com.kaleblangley.haikalat.subsystems.render3d;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * std430 byte packer for the GPU light table.
 *
 * <p>Header is {@code uvec4(directionalCount, localCount, totalCount, reserved)}
 * followed by 80-byte records.  This class never selects or drops lights; it
 * only serialises the frozen table and resolves shadow slots by stableId.</p>
 */
final class LightTablePacker {
    static final int HEADER_BYTES = 16;
    static final int RECORD_BYTES = 80;
    static final int BOUND_BYTES = 16;
    static final int POSITION_RANGE_OFFSET = 0;
    static final int DIRECTION_OUTER_OFFSET = 16;
    static final int COLOR_INTENSITY_OFFSET = 32;
    static final int EXTRA_OFFSET = 48;
    static final int METADATA_OFFSET = 64;

    private LightTablePacker() {
    }

    static long byteSize(int lightCount) {
        if (lightCount < 0) throw new IllegalArgumentException("lightCount must be non-negative");
        return HEADER_BYTES + Math.multiplyExact((long) RECORD_BYTES, lightCount);
    }

    static long boundsByteSize(int localLightCount) {
        if (localLightCount < 0) {
            throw new IllegalArgumentException("localLightCount must be non-negative");
        }
        return Math.multiplyExact((long) BOUND_BYTES, localLightCount);
    }

    static ByteBuffer allocateBounds(int maxLocalLights) {
        long bytes = boundsByteSize(maxLocalLights);
        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("light bounds exceed a single GL buffer: " + bytes);
        }
        return ByteBuffer.allocateDirect((int) bytes).order(ByteOrder.nativeOrder());
    }

    /**
     * Packs the compact view-space sphere used by cluster assignment:
     * one {@code vec4(viewPosition.xyz, range)} per local light in
     * frameLightIndex order.  Assignment never needs the full 80-byte record.
     */
    static ByteBuffer packBounds(FrameLightTable table, ByteBuffer destination) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(destination, "destination");
        long required = boundsByteSize(table.localCount());
        if (destination.capacity() < required) {
            throw new IllegalArgumentException("light bounds destination is too small: required "
                    + required + ", capacity " + destination.capacity());
        }
        destination.clear();
        destination.order(ByteOrder.nativeOrder());
        for (int index = 0; index < table.localCount(); index++) {
            FrameLightTable.Record record = table.local().get(index);
            int offset = index * BOUND_BYTES;
            destination.putFloat(offset, record.viewPosition().x);
            destination.putFloat(offset + 4, record.viewPosition().y);
            destination.putFloat(offset + 8, record.viewPosition().z);
            destination.putFloat(offset + 12, record.range());
        }
        destination.position(0);
        destination.limit((int) required);
        return destination;
    }

    static ByteBuffer allocate(int lightCount) {
        long bytes = byteSize(lightCount);
        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("light table exceeds a single GL buffer: " + bytes);
        }
        return ByteBuffer.allocateDirect((int) bytes).order(ByteOrder.nativeOrder());
    }

    /** Packs {@code table} into {@code destination}; the buffer is cleared first. */
    static ByteBuffer pack(FrameLightTable table, ShadowFramePlan plan, ByteBuffer destination) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(destination, "destination");
        int total = table.totalCount();
        long required = byteSize(total);
        if (destination.capacity() < required) {
            throw new IllegalArgumentException("light table destination is too small: required "
                    + required + ", capacity " + destination.capacity());
        }
        destination.clear();
        destination.order(ByteOrder.nativeOrder());
        destination.putInt(0, table.directionalCount());
        destination.putInt(4, table.localCount());
        destination.putInt(8, total);
        destination.putInt(12, 0);

        Map<Long, Integer> slots = shadowSlots(plan);
        for (int index = 0; index < total; index++) {
            writeRecord(destination, HEADER_BYTES + index * RECORD_BYTES,
                    table.record(index), slots.getOrDefault(table.record(index).stableId(), -1));
        }
        destination.position(0);
        destination.limit((int) required);
        return destination;
    }

    static Map<Long, Integer> shadowSlots(ShadowFramePlan plan) {
        Map<Long, Integer> slots = new HashMap<>();
        plan.directional().ifPresent(directional ->
                slots.put(directional.entry().stableId(), 0));
        for (PointShadowSlotPlan point : plan.points()) {
            slots.put(point.entry().stableId(), point.slot());
        }
        for (SpotShadowSlotPlan spot : plan.spots()) {
            slots.put(spot.entry().stableId(), spot.slot());
        }
        return slots;
    }

    private static void writeRecord(ByteBuffer destination, int offset,
                                    FrameLightTable.Record record, int shadowSlot) {
        FrameLightTable.Record value = record;
        boolean directional = value.gpuType() == FrameLightTable.TYPE_DIRECTIONAL;
        destination.putFloat(offset + POSITION_RANGE_OFFSET, value.position().x);
        destination.putFloat(offset + POSITION_RANGE_OFFSET + 4, value.position().y);
        destination.putFloat(offset + POSITION_RANGE_OFFSET + 8, value.position().z);
        destination.putFloat(offset + POSITION_RANGE_OFFSET + 12, directional ? 0.0f : value.range());

        destination.putFloat(offset + DIRECTION_OUTER_OFFSET, value.direction().x);
        destination.putFloat(offset + DIRECTION_OUTER_OFFSET + 4, value.direction().y);
        destination.putFloat(offset + DIRECTION_OUTER_OFFSET + 8, value.direction().z);
        destination.putFloat(offset + DIRECTION_OUTER_OFFSET + 12,
                value.type() == LightType.SPOT ? value.outerConeRadians() : 0.0f);

        destination.putFloat(offset + COLOR_INTENSITY_OFFSET, value.color().x);
        destination.putFloat(offset + COLOR_INTENSITY_OFFSET + 4, value.color().y);
        destination.putFloat(offset + COLOR_INTENSITY_OFFSET + 8, value.color().z);
        destination.putFloat(offset + COLOR_INTENSITY_OFFSET + 12, value.intensity());

        destination.putFloat(offset + EXTRA_OFFSET,
                value.type() == LightType.SPOT ? value.innerConeRadians() : 0.0f);
        if (directional) {
            destination.putFloat(offset + EXTRA_OFFSET + 4, 0.0f);
            destination.putFloat(offset + EXTRA_OFFSET + 8, 0.0f);
            destination.putFloat(offset + EXTRA_OFFSET + 12, 0.0f);
        } else {
            destination.putFloat(offset + EXTRA_OFFSET + 4, value.viewPosition().x);
            destination.putFloat(offset + EXTRA_OFFSET + 8, value.viewPosition().y);
            destination.putFloat(offset + EXTRA_OFFSET + 12, value.viewPosition().z);
        }

        destination.putInt(offset + METADATA_OFFSET, value.gpuType());
        destination.putInt(offset + METADATA_OFFSET + 4, shadowSlot);
        destination.putInt(offset + METADATA_OFFSET + 8, value.castShadows() ? 1 : 0);
        destination.putInt(offset + METADATA_OFFSET + 12, 0);
    }
}
