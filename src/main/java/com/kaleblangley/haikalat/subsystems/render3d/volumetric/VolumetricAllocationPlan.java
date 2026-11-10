package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import com.kaleblangley.haikalat.subsystems.render3d.VolumetricFogSettings;

import com.kaleblangley.haikalat.subsystems.render3d.lighting.LightVolumePacker;

/** Pure CPU, conservative peak estimate frozen before any candidate GL allocation. */
public record VolumetricAllocationPlan(long privateBytes, long colorBytes, long peakBytes) {
    public long activeBytes() { return Math.addExact(privateBytes, colorBytes); }

    public static VolumetricAllocationPlan prepare(int width, int height, VolumetricFogSettings settings,
                                            int lightCapacity, int samples, boolean vfx, long retiringBytes) {
        return prepare(width, height, settings, lightCapacity, samples, vfx, retiringBytes, 0);
    }

    public static VolumetricAllocationPlan prepare(int width, int height, VolumetricFogSettings settings,
                                            int lightCapacity, int samples, boolean vfx, long retiringBytes,
                                            long borrowedBytes) {
        if (Boolean.getBoolean("haikalat.test.failVolumePreparation"))
            throw new IllegalStateException("injected volume CPU preparation failure");
        if (width <= 0 || height <= 0 || lightCapacity < 0 || samples <= 0 || retiringBytes < 0)
            throw new IllegalArgumentException("invalid volume allocation inputs");
        if (!settings.enabled()) return new VolumetricAllocationPlan(0, 0, retiringBytes);
        int tile = settings.quality().tilePixels();
        int nx = Math.toIntExact(((long) width + tile - 1) / tile);
        int ny = Math.toIntExact(((long) height + tile - 1) / tile);
        long storage = VolumetricResources.estimatedBytes(nx, ny, settings.quality().depthSlices(), settings.hasEmission())
                - VolumetricResources.LIGHT_HINTS_BYTES + Math.multiplyExact((long) lightCapacity, LightVolumePacker.RECORD_BYTES);
        long colors = Math.multiplyExact(Math.multiplyExact((long) width, height), 8L * samples + 1 + (vfx && samples == 1 ? 4 : 0));
        long active = Math.addExact(storage, colors);
        if (borrowedBytes < 0 || borrowedBytes > active || borrowedBytes > retiringBytes)
            throw new IllegalArgumentException("invalid borrowed volume allocation");
        long peak = Math.addExact(active - borrowedBytes, retiringBytes);
        long budget = (samples == 1 ? 192L : 384L) * 1024 * 1024;
        if (storage > 128L * 1024 * 1024 || active > budget || peak > 2 * budget)
            throw new IllegalArgumentException("volume candidate exceeds allocation/peak budget: active=" + active + ", peak=" + peak);
        return new VolumetricAllocationPlan(storage, colors, peak);
    }
}
