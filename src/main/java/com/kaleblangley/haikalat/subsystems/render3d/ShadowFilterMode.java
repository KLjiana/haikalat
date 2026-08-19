package com.kaleblangley.haikalat.subsystems.render3d;

/** Fixed shadow filtering presets shared by directional, point and spot shadows. */
public enum ShadowFilterMode {
    HARD(0),
    PCF_3X3(1),
    PCF_5X5(2);

    private final int kernelRadius;

    ShadowFilterMode(int kernelRadius) {
        this.kernelRadius = kernelRadius;
    }

    public int kernelRadius() {
        return kernelRadius;
    }

    public int sampleCount() {
        int width = kernelRadius * 2 + 1;
        return width * width;
    }
}
