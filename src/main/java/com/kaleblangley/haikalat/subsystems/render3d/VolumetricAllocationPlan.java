package com.kaleblangley.haikalat.subsystems.render3d;

/** Pure CPU, conservative peak estimate frozen before any candidate GL allocation. */
record VolumetricAllocationPlan(long privateBytes, long colorBytes, long peakBytes) {
    long activeBytes() { return Math.addExact(privateBytes, colorBytes); }

    static VolumetricAllocationPlan prepare(int width, int height, VolumetricFogSettings settings,
                                            int lightCapacity, int samples, boolean vfx, long retiringBytes) {
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
        long peak = Math.addExact(active, retiringBytes);
        long budget = (samples == 1 ? 192L : 384L) * 1024 * 1024;
        if (storage > 128L * 1024 * 1024 || active > budget || peak > 2 * budget)
            throw new IllegalArgumentException("volume candidate exceeds allocation/peak budget: active=" + active + ", peak=" + peak);
        return new VolumetricAllocationPlan(storage, colors, peak);
    }
}
