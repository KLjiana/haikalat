package com.kaleblangley.haikalat.subsystems.render3d;

import java.util.Objects;

/** Frozen process-local light identity and its independently revisioned value. */
record SceneLightEntry(long stableId, SceneLight light, ShadowLightHints hints, LightVolumeHints volumeHints, long revision) {
    SceneLightEntry {
        if (stableId <= 0L) throw new IllegalArgumentException("stableId must be positive");
        light = Objects.requireNonNull(light, "light");
        hints = Objects.requireNonNull(hints, "hints");
        volumeHints = Objects.requireNonNull(volumeHints, "volumeHints");
        if (revision < 0L) throw new IllegalArgumentException("revision must be non-negative");
    }

    SceneLightEntry withLight(SceneLight replacement) {
        return new SceneLightEntry(stableId, replacement, hints, volumeHints, Math.incrementExact(revision));
    }

    SceneLightEntry withHints(ShadowLightHints replacement) {
        return new SceneLightEntry(stableId, light, replacement, volumeHints, Math.incrementExact(revision));
    }

    SceneLightEntry withVolumeHints(LightVolumeHints replacement) {
        return new SceneLightEntry(stableId, light, hints, replacement, Math.incrementExact(revision));
    }

    SceneLightEntry frozen() {
        SceneLight source = light;
        return new SceneLightEntry(stableId,
                new SceneLight(source.type(), source.color(), source.intensity(),
                        source.direction(), source.position(), source.range(),
                        source.innerConeRadians(), source.outerConeRadians(),
                        source.castShadows()), hints, volumeHints, revision);
    }
}
