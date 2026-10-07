package com.kaleblangley.haikalat.subsystems.render3d;

import java.util.Objects;

/** Immutable sky and sun appearance; physical fog is configured independently. */
public record OutdoorEnvironmentSettings(boolean enabled, String preset,
                                         StylizedSkySettings sky) {
    public OutdoorEnvironmentSettings {
        preset = Objects.requireNonNull(preset, "preset");
        if (preset.isBlank()) throw new IllegalArgumentException("preset must not be blank");
        sky = Objects.requireNonNull(sky, "sky");
    }

    public static OutdoorEnvironmentSettings disabled() {
        return new OutdoorEnvironmentSettings(false, "disabled", StylizedSkySettings.clearDay());
    }

    public static OutdoorEnvironmentSettings morningFog() {
        return new OutdoorEnvironmentSettings(true, "morning_fog", StylizedSkySettings.morningFog());
    }

    public static OutdoorEnvironmentSettings clearDay() {
        return new OutdoorEnvironmentSettings(true, "clear_day", StylizedSkySettings.clearDay());
    }

    public static OutdoorEnvironmentSettings goldenHour() {
        return new OutdoorEnvironmentSettings(true, "golden_hour", StylizedSkySettings.goldenHour());
    }

    public OutdoorEnvironmentSettings withSky(StylizedSkySettings replacement) {
        return new OutdoorEnvironmentSettings(enabled, preset,
                Objects.requireNonNull(replacement, "replacement"));
    }
}
