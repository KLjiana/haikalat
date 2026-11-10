package com.kaleblangley.haikalat.subsystems.render3d.ao;

import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;
import java.util.Objects;

/** CPU-only GTAO byte estimate; shared surfaces and driver program memory belong elsewhere. */
public record GtaoAllocationPlan(long privateBytes, long graphBytes, long activeBytes, long peakBytes) {
    static final long ACTIVE_LIMIT = 128L * 1024 * 1024;
    public static final long TRANSACTION_LIMIT = 256L * 1024 * 1024;

    public static void validateDeviceExtent(int width, int height, int maximumDimension) {
        if (width < 1 || height < 1 || maximumDimension < 1 || width > maximumDimension || height > maximumDimension)
            throw new IllegalArgumentException("GTAO/scene extent exceeds device texture capacity");
    }

    public static GtaoAllocationPlan prepare(int width, int height, GtaoSettings settings,
                                      boolean shared, long retainedBytes, boolean reused) {
        return prepare(width, height, settings, shared, retainedBytes, reused, 0);
    }

    public static long compatibleBytes(int width, int height, GtaoSettings settings, boolean shared,
                                       GtaoSettings previous, boolean previousShared) {
        if (!settings.enabled() || !previous.enabled()) return 0;
        long pixels = Math.multiplyExact((long) width, height);
        long half = Math.multiplyExact((width + 1L) / 2, (height + 1L) / 2);
        // Estimate, bilateral and upsample targets have the same descriptors in both temporal modes.
        long compatible = Math.addExact(Math.multiplyExact(half, 6), pixels);
        if (!settings.temporal() && !previous.temporal()) compatible += Math.multiplyExact(half, 4);
        if (!shared && !previousShared) compatible += Math.multiplyExact(pixels, 4);
        if (settings.temporal() == previous.temporal() && shared == previousShared) {
            compatible += 96 + (settings.temporal() ? Math.multiplyExact(half, 8) : 0);
        }
        return compatible;
    }

    public static GtaoAllocationPlan prepare(int width, int height, GtaoSettings settings,
                                      boolean shared, long retainedBytes, long borrowedBytes) {
        return prepare(width, height, settings, shared, retainedBytes, false, borrowedBytes);
    }

    private static GtaoAllocationPlan prepare(int width, int height, GtaoSettings settings,
                                      boolean shared, long retainedBytes, boolean reused, long borrowedBytes) {
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
        if (borrowedBytes < 0 || borrowedBytes > active) throw new IllegalArgumentException("invalid borrowed GTAO allocation");
        long newlyAllocated = reused ? 0 : active - borrowedBytes;
        long peak = Math.addExact(retainedBytes, newlyAllocated);
        if (active > ACTIVE_LIMIT || peak > TRANSACTION_LIMIT)
            throw new IllegalArgumentException("GTAO allocation budget exceeded: active=" + active + ", peak=" + peak);
        return new GtaoAllocationPlan(privateBytes, graphBytes, active, peak);
    }
}
