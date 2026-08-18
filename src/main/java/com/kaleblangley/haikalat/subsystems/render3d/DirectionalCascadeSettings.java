package com.kaleblangley.haikalat.subsystems.render3d;

/** Optional directional cascade atlas policy. A count of one preserves the legacy path. */
public record DirectionalCascadeSettings(int cascadeCount, int atlasSize,
                                         float splitLambda, float blendRange) {
    public DirectionalCascadeSettings {
        if (cascadeCount < 1 || cascadeCount > 4) {
            throw new IllegalArgumentException("cascadeCount must be in [1, 4]");
        }
        if (atlasSize <= 0) throw new IllegalArgumentException("atlasSize must be positive");
        if (cascadeCount > 1 && (atlasSize & 1) != 0) {
            throw new IllegalArgumentException("multi-cascade atlasSize must be even");
        }
        if (!Float.isFinite(splitLambda) || splitLambda < 0.0f || splitLambda > 1.0f) {
            throw new IllegalArgumentException("splitLambda must be in [0, 1]");
        }
        if (!Float.isFinite(blendRange) || blendRange < 0.0f || blendRange > 0.25f) {
            throw new IllegalArgumentException("blendRange must be in [0, 0.25]");
        }
    }

    public static DirectionalCascadeSettings disabled() {
        return new DirectionalCascadeSettings(1, 2048, 0.5f, 0.0f);
    }

    public static DirectionalCascadeSettings defaults() {
        return new DirectionalCascadeSettings(4, 4096, 0.6f, 0.08f);
    }

    public boolean enabled() { return cascadeCount > 1; }

    public int columns() { return cascadeCount == 2 ? 2 : cascadeCount > 2 ? 2 : 1; }

    public int rows() { return cascadeCount > 2 ? 2 : 1; }

    public int tileSize() { return atlasSize / Math.max(columns(), rows()); }
}
