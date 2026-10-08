package com.kaleblangley.haikalat.subsystems.render3d;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Independent 16-byte std430 adjunct in the unchanged 80-byte light table's frame-index order. */
final class LightVolumePacker {
    static final int RECORD_BYTES = 16;
    static final int INTENSITY_OFFSET = 0, FLAGS_OFFSET = 4;
    static final int SHADOW_FLAG = 1, TEMPORAL_FLAG = 2;
    private LightVolumePacker() { }
    static ByteBuffer pack(FrameLightTable table, ByteBuffer destination) {
        int bytes = Math.multiplyExact(table.totalCount(), RECORD_BYTES);
        if (destination.capacity() < bytes) throw new IllegalArgumentException("volume light adjunct capacity exceeded");
        destination.clear().order(ByteOrder.nativeOrder());
        for (int index=0;index<table.totalCount();index++) {
            LightVolumeHints hints = table.record(index).volumeHints(); int offset=index*RECORD_BYTES;
            destination.putFloat(offset,hints.scatteringIntensity());
            destination.putInt(offset+4,(hints.useAllocatedShadow()?SHADOW_FLAG:0)|(hints.temporalAccumulation()?TEMPORAL_FLAG:0));
            destination.putInt(offset+8,0).putInt(offset+12,0);
        }
        return destination.position(0).limit(bytes);
    }
}
