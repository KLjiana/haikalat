package com.kaleblangley.haikalat.subsystems.render3d.pbr;

/** 启动期环境预计算的尺寸和采样预算。 */
public record PbrEnvironmentSettings(
        int environmentSize,
        int irradianceSize,
        int prefilteredSize,
        int brdfLutSize,
        int irradianceSamples,
        int prefilterSamples,
        int brdfSamples
) {
    public PbrEnvironmentSettings {
        requirePowerOfTwo(environmentSize, "environmentSize");
        requirePowerOfTwo(irradianceSize, "irradianceSize");
        requirePowerOfTwo(prefilteredSize, "prefilteredSize");
        requirePowerOfTwo(brdfLutSize, "brdfLutSize");
        if (irradianceSamples <= 0 || prefilterSamples <= 0 || brdfSamples <= 0) {
            throw new IllegalArgumentException("PBR sample counts must be positive");
        }
    }

    public static PbrEnvironmentSettings testQuality() {
        return new PbrEnvironmentSettings(16, 8, 16, 16, 32, 64, 64);
    }

    public static PbrEnvironmentSettings defaultQuality() {
        return new PbrEnvironmentSettings(512, 32, 128, 256, 1024, 1024, 1024);
    }

    public static PbrEnvironmentSettings quality(String name) {
        return switch (name.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "test" -> testQuality();
            case "default" -> defaultQuality();
            default -> throw new IllegalArgumentException("environment quality must be test or default");
        };
    }

    private static void requirePowerOfTwo(int value, String name) {
        if (value <= 0 || (value & (value - 1)) != 0) {
            throw new IllegalArgumentException(name + " must be a positive power of two");
        }
    }
}
