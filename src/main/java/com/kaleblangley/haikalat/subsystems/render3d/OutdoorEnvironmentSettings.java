package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.subsystems.postprocess.FogSettings;

import java.util.List;
import java.util.Objects;

/** Immutable v0.24 outdoor environment snapshot shared by sky, sun and fog. */
public record OutdoorEnvironmentSettings(boolean enabled, String preset,
                                         StylizedSkySettings sky,
                                         VolumetricSunSettings volumetricSun,
                                         FogSettings globalFog,
                                         List<LocalFogVolume> localFogVolumes,
                                         int noiseSeed, float windSpeed) {
    public static final int MAX_LOCAL_VOLUMES = 8;

    public OutdoorEnvironmentSettings {
        preset = Objects.requireNonNull(preset, "preset");
        if (preset.isBlank()) throw new IllegalArgumentException("preset must not be blank");
        sky = Objects.requireNonNull(sky, "sky");
        volumetricSun = Objects.requireNonNull(volumetricSun, "volumetricSun");
        globalFog = Objects.requireNonNull(globalFog, "globalFog");
        localFogVolumes = List.copyOf(Objects.requireNonNull(localFogVolumes, "localFogVolumes"));
        if (localFogVolumes.size() > MAX_LOCAL_VOLUMES) {
            throw new IllegalArgumentException("localFogVolumes must contain at most " + MAX_LOCAL_VOLUMES);
        }
        if (!Float.isFinite(windSpeed) || windSpeed < 0.0f) {
            throw new IllegalArgumentException("windSpeed must be finite and non-negative");
        }
        if (!enabled && (volumetricSun.enabled() || !localFogVolumes.isEmpty())) {
            throw new IllegalArgumentException(
                    "disabled outdoor settings cannot enable volumetricSun or local fog volumes");
        }
    }

    public static OutdoorEnvironmentSettings disabled() {
        return new OutdoorEnvironmentSettings(false, "disabled", StylizedSkySettings.clearDay(),
                VolumetricSunSettings.disabled(), FogSettings.disabled(), List.of(), 0, 0.0f);
    }

    public static OutdoorEnvironmentSettings morningFog() {
        return new OutdoorEnvironmentSettings(true, "morning_fog", StylizedSkySettings.morningFog(),
                VolumetricSunSettings.balanced(), FogSettings.builder().enabled(true)
                .color(0.48f, 0.58f, 0.68f).distanceDensity(0.006f).heightDensity(0.018f)
                .heightFalloff(0.16f).baseHeight(0.0f).maximumOpacity(0.55f).build(), List.of(),
                1337, 0.12f);
    }

    public static OutdoorEnvironmentSettings clearDay() {
        return new OutdoorEnvironmentSettings(true, "clear_day", StylizedSkySettings.clearDay(),
                new VolumetricSunSettings(true, 32, 2, 72.0f, 0.005f,
                        StylizedSkySettings.clearDay().sunColor(), 0.2f, 0.82f, 0.08f, 0.25f),
                FogSettings.builder().enabled(true).color(0.60f, 0.72f, 0.82f)
                        .distanceDensity(0.002f).heightDensity(0.004f).heightFalloff(0.12f)
                        .maximumOpacity(0.25f).build(), List.of(), 7331, 0.04f);
    }

    public static OutdoorEnvironmentSettings goldenHour() {
        return new OutdoorEnvironmentSettings(true, "golden_hour", StylizedSkySettings.goldenHour(),
                new VolumetricSunSettings(true, 32, 2, 68.0f, 0.009f,
                        StylizedSkySettings.goldenHour().sunColor(), 0.45f, 0.84f, 0.08f, 0.3f),
                FogSettings.builder().enabled(true).color(0.64f, 0.38f, 0.22f)
                        .distanceDensity(0.004f).heightDensity(0.008f).heightFalloff(0.14f)
                        .maximumOpacity(0.38f).build(), List.of(), 4242, 0.09f);
    }

    public OutdoorEnvironmentSettings withLocalFogVolumes(List<LocalFogVolume> volumes) {
        return new OutdoorEnvironmentSettings(enabled, preset, sky, volumetricSun, globalFog,
                volumes, noiseSeed, windSpeed);
    }

    /** Returns a frame-boundary snapshot with a different volumetric quality preset. */
    public OutdoorEnvironmentSettings withVolumetricSun(VolumetricSunSettings replacement) {
        return new OutdoorEnvironmentSettings(enabled, preset, sky,
                Objects.requireNonNull(replacement, "replacement"), globalFog,
                localFogVolumes, noiseSeed, windSpeed);
    }

    /** Returns a snapshot with a different global analytic fog policy. */
    public OutdoorEnvironmentSettings withGlobalFog(FogSettings replacement) {
        return new OutdoorEnvironmentSettings(enabled, preset, sky, volumetricSun,
                Objects.requireNonNull(replacement, "replacement"), localFogVolumes,
                noiseSeed, windSpeed);
    }

    /** Returns a snapshot with a different deterministic noise seed. */
    public OutdoorEnvironmentSettings withNoiseSeed(int replacement) {
        return new OutdoorEnvironmentSettings(enabled, preset, sky, volumetricSun,
                globalFog, localFogVolumes, replacement, windSpeed);
    }

    /** Returns a snapshot with a different local-volume wind speed. */
    public OutdoorEnvironmentSettings withWindSpeed(float replacement) {
        return new OutdoorEnvironmentSettings(enabled, preset, sky, volumetricSun,
                globalFog, localFogVolumes, noiseSeed, replacement);
    }

    /** Returns a snapshot with a replacement sky/sun group. */
    public OutdoorEnvironmentSettings withSky(StylizedSkySettings replacement) {
        return new OutdoorEnvironmentSettings(enabled, preset,
                Objects.requireNonNull(replacement, "replacement"), volumetricSun,
                globalFog, localFogVolumes, noiseSeed, windSpeed);
    }
}
