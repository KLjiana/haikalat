package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;
import java.util.Objects;

/** CPU-only GTAO byte estimate; shared surfaces and driver program memory belong elsewhere. */
record GtaoAllocationPlan(long privateBytes, long graphBytes, long activeBytes, long peakBytes) {
    static final long ACTIVE_LIMIT = 128L * 1024 * 1024;
    static final long TRANSACTION_LIMIT = 256L * 1024 * 1024;

    static void validateDeviceExtent(int width, int height, int maximumDimension) {
        if (width < 1 || height < 1 || maximumDimension < 1 || width > maximumDimension || height > maximumDimension)
            throw new IllegalArgumentException("GTAO/scene extent exceeds device texture capacity");
    }

    static GtaoAllocationPlan prepare(int width, int height, GtaoSettings settings,
                                      boolean shared, long retainedBytes, boolean reused) {
        Objects.requireNonNull(settings);
        if (width < 1 || height < 1 || retainedBytes < 0)
            throw new IllegalArgumentException("invalid GTAO allocation extent or retained bytes");
        long privateBytes = 0, graphBytes = 0;
        if (settings.enabled()) {
            long pixels = Math.multiplyExact((long) width, height);
            long half = Math.multiplyExact((width + 1L) / 2, (height + 1L) / 2);
            privateBytes = Math.addExact(96, settings.temporal() ? Math.multiplyExact(half, 8) : 0);
            // Raw R8 + normal RG16F + bilateral R8, optional temporal RG16F, final R8.
            graphBytes = Math.addExact(Math.multiplyExact(half, settings.temporal() ? 6 : 10), pixels);
            if (!shared) graphBytes = Math.addExact(graphBytes, Math.multiplyExact(pixels, 4));
        }
        long active = Math.addExact(privateBytes, graphBytes);
        long peak = Math.addExact(retainedBytes, reused ? 0 : active);
        if (active > ACTIVE_LIMIT || peak > TRANSACTION_LIMIT)
            throw new IllegalArgumentException("GTAO allocation budget exceeded: active=" + active + ", peak=" + peak);
        return new GtaoAllocationPlan(privateBytes, graphBytes, active, peak);
    }
}
