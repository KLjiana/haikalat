package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Vector3f;

import java.util.List;
import java.util.Objects;

/** One immutable source of all volume parameters, independent of sky and surface fog. */
public record VolumetricFogSettings(boolean enabled, float fogDistance, Quality quality,
                                    FogMediumSettings globalMedium, List<LocalFogVolume> localVolumes,
                                    float anisotropy, boolean history, float historyWeight,
                                    int noiseSeed, Vector3f wind) {
    public static final int MAX_LOCAL_VOLUMES = 8;

    public enum Quality {
        LOW(32, 48), BALANCED(16, 64), HIGH(8, 96);
        private final int tilePixels, depthSlices;
        Quality(int tilePixels, int depthSlices) { this.tilePixels = tilePixels; this.depthSlices = depthSlices; }
        public int tilePixels() { return tilePixels; }
        public int depthSlices() { return depthSlices; }
    }

    public VolumetricFogSettings {
        if (!Float.isFinite(fogDistance) || fogDistance <= 0 || fogDistance > 1024) {
            throw new IllegalArgumentException("fogDistance must be in (0,1024]");
        }
        quality = Objects.requireNonNull(quality, "quality");
        globalMedium = Objects.requireNonNull(globalMedium, "globalMedium");
        localVolumes = List.copyOf(Objects.requireNonNull(localVolumes, "localVolumes"));
        if (localVolumes.size() > MAX_LOCAL_VOLUMES) throw new IllegalArgumentException("at most eight local fog volumes are supported");
        if (!Float.isFinite(anisotropy) || Math.abs(anisotropy) > 0.9f) {
            throw new IllegalArgumentException("anisotropy must be in [-0.9,0.9]");
        }
        if (!Float.isFinite(historyWeight) || historyWeight < 0 || historyWeight > 0.95f) {
            throw new IllegalArgumentException("historyWeight must be in [0,0.95]");
        }
        wind = new Vector3f(Objects.requireNonNull(wind, "wind"));
        if (!wind.isFinite()) throw new IllegalArgumentException("wind must be finite");
    }

    public static VolumetricFogSettings disabled() {
        return new VolumetricFogSettings(false, 80, Quality.BALANCED, FogMediumSettings.vacuum(),
                List.of(), 0, true, 0.9f, 1337, new Vector3f());
    }

    @Override public Vector3f wind() { return new Vector3f(wind); }
    Vector3f windInternal() { return wind; }
    boolean hasEmission() {
        if (globalMedium.hasEmission()) return true;
        for (LocalFogVolume volume : localVolumes) if (volume.emissionInternal().lengthSquared() > 0) return true;
        return false;
    }
}
