package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.subsystems.render3d.LocalFogVolume;
import com.kaleblangley.haikalat.subsystems.render3d.OutdoorEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.StylizedSkySettings;
import com.kaleblangley.haikalat.subsystems.render3d.VisualSettings;
import com.kaleblangley.haikalat.subsystems.render3d.VolumetricFogSettings;
import com.kaleblangley.haikalat.subsystems.render3d.FogMediumSettings;
import com.kaleblangley.haikalat.subsystems.render3d.LightVolumeHints;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** Strict, atomic properties codec for schema 2 physical fog and global visual profiles. */
public final class VisualProfileCodec {
    private static final Set<String> KEYS = Set.of(
            "schemaVersion", "profileId", "description", "author",
            "exposure.manual", "ibl.intensity", "ibl.rotation_radians", "aa.mode",
            "directional.direction", "directional.color", "directional.intensity",
            "bloom.enabled", "bloom.threshold", "bloom.soft_knee", "bloom.intensity",
            "bloom.max_levels", "outdoor.enabled", "outdoor.preset", "sky.zenith", "sky.horizon", "sky.nadir",
            "sky.sun_direction", "sky.sun_color", "sky.sun_intensity",
            "sky.sun_angular_radius", "sky.halo_intensity", "sky.environment_intensity",
            "volume.enabled", "volume.quality", "volume.distance", "volume.anisotropy",
            "volume.history", "volume.history_weight", "volume.noise_seed", "volume.wind",
            "medium.extinction", "medium.albedo", "medium.emission", "medium.base_height",
            "medium.height_falloff", "local.count", "light.count");

    private VisualProfileCodec() {
    }

    public static void save(Path path, VisualProfile profile) {
        Path output = normalize(path);
        VisualProfile required = java.util.Objects.requireNonNull(profile, "profile");
        Properties properties = encode(required);
        Path temporary = null;
        try {
            Path parent = output.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tempDirectory = parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
            temporary = Files.createTempFile(tempDirectory, output.getFileName().toString(), ".tmp");
            try (var writer = Files.newBufferedWriter(temporary)) {
                properties.store(writer, "Haikalat visual profile schema " + VisualProfile.CURRENT_SCHEMA);
            }
            VisualProfile decoded = load(temporary, required);
            if (!decoded.equals(required)) {
                throw new IllegalStateException("visual profile validation changed values for " + output);
            }
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException failure) {
            throw new IllegalStateException("failed to save visual profile " + output, failure);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    public static VisualProfile load(Path path, VisualProfile fallback) {
        Path input = normalize(path);
        VisualProfile defaults = java.util.Objects.requireNonNull(fallback, "fallback");
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(input)) {
            properties.load(reader);
        } catch (IOException failure) {
            throw new IllegalStateException("failed to load visual profile " + input, failure);
        }
        if (properties.getProperty("schemaVersion") == null) {
            throw invalid(input, "schemaVersion", "schema 2 is required; legacy profiles must be migrated explicitly");
        }
        int schema = integer(input, properties, "schemaVersion", -1);
        if (schema != VisualProfile.CURRENT_SCHEMA) {
            throw invalid(input, "schemaVersion", "unsupported schema " + schema
                    + "; schema 1 fog color is not physical albedo");
        }
        rejectUnknown(input, properties);
        OutdoorEnvironmentSettings baseOutdoor = defaults.settings().outdoor();
        VolumetricFogSettings baseVolume = defaults.settings().volumetricFog();
        String profileId = value(properties, "profileId", defaults.profileId());
        String description = value(properties, "description", defaults.description());
        String author = value(properties, "author", defaults.author());
        float exposure = floating(input, properties, "exposure.manual",
                defaults.settings().manualExposure());
        float iblIntensity = floating(input, properties, "ibl.intensity",
                defaults.settings().iblIntensity());
        float iblRotation = floating(input, properties, "ibl.rotation_radians",
                defaults.settings().iblRotationRadians());
        AntiAliasingMode aa = enumeration(input, properties, "aa.mode",
                defaults.settings().antiAliasingMode(), AntiAliasingMode.class);
        Vector3f directDirection = vector(input, properties, "directional.direction",
                defaults.settings().directionalLightDirection());
        Vector3f directColor = vector(input, properties, "directional.color",
                defaults.settings().directionalLightColor());
        float directIntensity = floating(input, properties, "directional.intensity",
                defaults.settings().directionalLightIntensity());
        BloomSettings defaultBloom = defaults.settings().bloom();
        BloomSettings bloom = new BloomSettings(
                bool(input, properties, "bloom.enabled", defaultBloom.enabled()),
                floating(input, properties, "bloom.threshold", defaultBloom.threshold()),
                floating(input, properties, "bloom.soft_knee", defaultBloom.softKnee()),
                floating(input, properties, "bloom.intensity", defaultBloom.intensity()),
                integer(input, properties, "bloom.max_levels", defaultBloom.maxLevels()));

        String preset = value(properties, "outdoor.preset", baseOutdoor.preset());
        StylizedSkySettings baseSky = baseOutdoor.sky();
        StylizedSkySettings sky = new StylizedSkySettings(
                vector(input, properties, "sky.zenith", baseSky.zenithColor()),
                vector(input, properties, "sky.horizon", baseSky.horizonColor()),
                vector(input, properties, "sky.nadir", baseSky.nadirColor()),
                vector(input, properties, "sky.sun_direction", baseSky.sunDirection()),
                vector(input, properties, "sky.sun_color", baseSky.sunColor()),
                floating(input, properties, "sky.sun_intensity", baseSky.sunIntensity()),
                floating(input, properties, "sky.sun_angular_radius", baseSky.sunAngularRadius()),
                floating(input, properties, "sky.halo_intensity", baseSky.haloIntensity()),
                floating(input, properties, "sky.environment_intensity", baseSky.environmentIntensity()));
        FogMediumSettings baseMedium = baseVolume.globalMedium();
        FogMediumSettings medium;
        try {
            medium = new FogMediumSettings(
                    floating(input, properties, "medium.extinction", baseMedium.extinction()),
                    vector(input, properties, "medium.albedo", baseMedium.albedo()),
                    vector(input, properties, "medium.emission", baseMedium.emission()),
                    floating(input, properties, "medium.base_height", baseMedium.baseHeight()),
                    floating(input, properties, "medium.height_falloff", baseMedium.heightFalloff()));
        } catch (IllegalArgumentException failure) {
            throw invalid(input, "medium", failure.getMessage());
        }
        int localCount = integer(input, properties, "local.count",
                baseVolume.localVolumes().size());
        if (localCount < 0 || localCount > VolumetricFogSettings.MAX_LOCAL_VOLUMES) {
            throw invalid(input, "local.count", "must be in [0, "
                    + VolumetricFogSettings.MAX_LOCAL_VOLUMES + "]");
        }
        List<LocalFogVolume> local = new ArrayList<>();
        for (int index = 0; index < localCount; index++) {
            LocalFogVolume existing = index < baseVolume.localVolumes().size()
                    ? baseVolume.localVolumes().get(index) : null;
            String prefix = "local." + index + ".";
            LocalFogVolume.Shape shape = enumeration(input, properties, prefix + "shape",
                    existing == null ? LocalFogVolume.Shape.SPHERE : existing.shape(),
                    LocalFogVolume.Shape.class);
            local.add(new LocalFogVolume(shape,
                    vector(input, properties, prefix + "center",
                            existing == null ? new Vector3f() : existing.center()),
                    vector(input, properties, prefix + "extent",
                            existing == null ? new Vector3f(1.0f) : existing.extent()),
                    floating(input, properties, prefix + "extinction",
                            existing == null ? 0.0f : existing.extinction()),
                    vector(input, properties, prefix + "albedo",
                            existing == null ? new Vector3f(1.0f) : existing.albedo()),
                    vector(input, properties, prefix + "emission",
                            existing == null ? new Vector3f() : existing.emission()),
                    floating(input, properties, prefix + "falloff",
                            existing == null ? 0.0f : existing.falloff()),
                    floating(input, properties, prefix + "noise_scale",
                            existing == null ? 0.0f : existing.noiseScale()),
                    floating(input, properties, prefix + "noise_amount",
                            existing == null ? 0.0f : existing.noiseAmount())));
        }
        int lightCount = integer(input, properties, "light.count", defaults.lightVolumes().size());
        if (lightCount < 0 || lightCount > 4097) throw invalid(input, "light.count", "must be in [0,4097]");
        rejectUndeclaredEntries(input, properties, "local", localCount);
        rejectUndeclaredEntries(input, properties, "light", lightCount);
        List<VisualProfile.LightVolumeOverride> lights = new ArrayList<>();
        for (int index = 0; index < lightCount; index++) {
            VisualProfile.LightVolumeOverride existing = index < defaults.lightVolumes().size()
                    ? defaults.lightVolumes().get(index) : null;
            String prefix = "light." + index + ".";
            if (existing == null && properties.getProperty(prefix + "scene_index") == null)
                throw invalid(input, prefix + "scene_index", "required for a new override");
            LightVolumeHints hint = existing == null ? LightVolumeHints.DEFAULT : existing.hints();
            try {
                lights.add(new VisualProfile.LightVolumeOverride(
                        integer(input, properties, prefix + "scene_index", existing == null ? 0 : existing.sceneLightIndex()),
                        new LightVolumeHints(floating(input, properties, prefix + "intensity", hint.scatteringIntensity()),
                                bool(input, properties, prefix + "shadow", hint.useAllocatedShadow()),
                                bool(input, properties, prefix + "history", hint.temporalAccumulation()))));
            } catch (IllegalArgumentException failure) {
                throw invalid(input, prefix, failure.getMessage());
            }
        }
        OutdoorEnvironmentSettings outdoor = new OutdoorEnvironmentSettings(
                bool(input, properties, "outdoor.enabled", baseOutdoor.enabled()), preset,
                sky);
        try {
            VolumetricFogSettings volume = new VolumetricFogSettings(
                    bool(input, properties, "volume.enabled", baseVolume.enabled()),
                    floating(input, properties, "volume.distance", baseVolume.fogDistance()),
                    enumeration(input, properties, "volume.quality", baseVolume.quality(), VolumetricFogSettings.Quality.class),
                    medium, local,
                    floating(input, properties, "volume.anisotropy", baseVolume.anisotropy()),
                    bool(input, properties, "volume.history", baseVolume.history()),
                    floating(input, properties, "volume.history_weight", baseVolume.historyWeight()),
                    integer(input, properties, "volume.noise_seed", baseVolume.noiseSeed()),
                    vector(input, properties, "volume.wind", baseVolume.wind()));
            return new VisualProfile(schema, profileId, description, author,
                    new VisualSettings(exposure, iblIntensity, iblRotation,
                            directDirection, directColor, directIntensity,
                            aa, bloom, outdoor, volume), lights);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(input + ": invalid visual profile: "
                    + failure.getMessage(), failure);
        }
    }

    private static Properties encode(VisualProfile profile) {
        Properties result = new Properties();
        result.setProperty("schemaVersion", Integer.toString(profile.schemaVersion()));
        result.setProperty("profileId", profile.profileId());
        result.setProperty("description", profile.description());
        result.setProperty("author", profile.author());
        VisualSettings settings = profile.settings();
        result.setProperty("exposure.manual", Float.toString(settings.manualExposure()));
        result.setProperty("ibl.intensity", Float.toString(settings.iblIntensity()));
        result.setProperty("ibl.rotation_radians", Float.toString(settings.iblRotationRadians()));
        result.setProperty("aa.mode", settings.antiAliasingMode().name());
        putVector(result, "directional.direction", settings.directionalLightDirection());
        putVector(result, "directional.color", settings.directionalLightColor());
        result.setProperty("directional.intensity",
                Float.toString(settings.directionalLightIntensity()));
        BloomSettings bloom = settings.bloom();
        result.setProperty("bloom.enabled", Boolean.toString(bloom.enabled()));
        result.setProperty("bloom.threshold", Float.toString(bloom.threshold()));
        result.setProperty("bloom.soft_knee", Float.toString(bloom.softKnee()));
        result.setProperty("bloom.intensity", Float.toString(bloom.intensity()));
        result.setProperty("bloom.max_levels", Integer.toString(bloom.maxLevels()));
        OutdoorEnvironmentSettings outdoor = settings.outdoor();
        result.setProperty("outdoor.enabled", Boolean.toString(outdoor.enabled()));
        result.setProperty("outdoor.preset", outdoor.preset());
        StylizedSkySettings sky = outdoor.sky();
        putVector(result, "sky.zenith", sky.zenithColor());
        putVector(result, "sky.horizon", sky.horizonColor());
        putVector(result, "sky.nadir", sky.nadirColor());
        putVector(result, "sky.sun_direction", sky.sunDirection());
        putVector(result, "sky.sun_color", sky.sunColor());
        result.setProperty("sky.sun_intensity", Float.toString(sky.sunIntensity()));
        result.setProperty("sky.sun_angular_radius", Float.toString(sky.sunAngularRadius()));
        result.setProperty("sky.halo_intensity", Float.toString(sky.haloIntensity()));
        result.setProperty("sky.environment_intensity", Float.toString(sky.environmentIntensity()));
        VolumetricFogSettings volume = settings.volumetricFog();
        result.setProperty("volume.enabled", Boolean.toString(volume.enabled()));
        result.setProperty("volume.quality", volume.quality().name());
        result.setProperty("volume.distance", Float.toString(volume.fogDistance()));
        result.setProperty("volume.anisotropy", Float.toString(volume.anisotropy()));
        result.setProperty("volume.history", Boolean.toString(volume.history()));
        result.setProperty("volume.history_weight", Float.toString(volume.historyWeight()));
        result.setProperty("volume.noise_seed", Integer.toString(volume.noiseSeed()));
        putVector(result, "volume.wind", volume.wind());
        FogMediumSettings medium = volume.globalMedium();
        result.setProperty("medium.extinction", Float.toString(medium.extinction()));
        putVector(result, "medium.albedo", medium.albedo());
        putVector(result, "medium.emission", medium.emission());
        result.setProperty("medium.base_height", Float.toString(medium.baseHeight()));
        result.setProperty("medium.height_falloff", Float.toString(medium.heightFalloff()));
        result.setProperty("local.count", Integer.toString(volume.localVolumes().size()));
        for (int index = 0; index < volume.localVolumes().size(); index++) {
            LocalFogVolume local = volume.localVolumes().get(index);
            String prefix = "local." + index + ".";
            result.setProperty(prefix + "shape", local.shape().name());
            putVector(result, prefix + "center", local.center());
            putVector(result, prefix + "extent", local.extent());
            result.setProperty(prefix + "extinction", Float.toString(local.extinction()));
            putVector(result, prefix + "albedo", local.albedo());
            putVector(result, prefix + "emission", local.emission());
            result.setProperty(prefix + "falloff", Float.toString(local.falloff()));
            result.setProperty(prefix + "noise_scale", Float.toString(local.noiseScale()));
            result.setProperty(prefix + "noise_amount", Float.toString(local.noiseAmount()));
        }
        result.setProperty("light.count", Integer.toString(profile.lightVolumes().size()));
        for (int index = 0; index < profile.lightVolumes().size(); index++) {
            VisualProfile.LightVolumeOverride entry = profile.lightVolumes().get(index);
            String prefix = "light." + index + ".";
            result.setProperty(prefix + "scene_index", Integer.toString(entry.sceneLightIndex()));
            result.setProperty(prefix + "intensity", Float.toString(entry.hints().scatteringIntensity()));
            result.setProperty(prefix + "shadow", Boolean.toString(entry.hints().useAllocatedShadow()));
            result.setProperty(prefix + "history", Boolean.toString(entry.hints().temporalAccumulation()));
        }
        return result;
    }

    private static void rejectUnknown(Path path, Properties properties) {
        for (String key : properties.stringPropertyNames()) {
            if (KEYS.contains(key)
                    || key.matches("local\\.\\d+\\.(shape|center|extent|extinction|albedo|emission|falloff|noise_scale|noise_amount)")
                    || key.matches("light\\.\\d+\\.(scene_index|intensity|shadow|history)")) continue;
            throw invalid(path, key, "unknown schema 2 field");
        }
    }

    private static void rejectUndeclaredEntries(Path path, Properties properties, String kind, int count) {
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(kind + ".") || key.equals(kind + ".count")) continue;
            String ordinal = key.split("\\.")[1];
            int index;
            try { index = Integer.parseInt(ordinal); }
            catch (NumberFormatException failure) { throw invalid(path, key, "invalid entry index"); }
            if (index >= count || !ordinal.equals(Integer.toString(index)))
                throw invalid(path, key, "entry index outside declared " + kind + ".count");
        }
    }

    private static Path normalize(Path path) {
        return java.util.Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    private static String value(Properties values, String key, String fallback) {
        return values.getProperty(key, fallback);
    }

    private static float floating(Path path, Properties values, String key, float fallback) {
        String value = values.getProperty(key);
        if (value == null) return fallback;
        try {
            float result = Float.parseFloat(value.trim());
            if (!Float.isFinite(result)) throw new NumberFormatException("non-finite");
            return result;
        } catch (NumberFormatException failure) {
            throw invalid(path, key, "expected finite float, got '" + value + "'");
        }
    }

    private static int integer(Path path, Properties values, String key, int fallback) {
        String value = values.getProperty(key);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException failure) {
            throw invalid(path, key, "expected integer, got '" + value + "'");
        }
    }

    private static boolean bool(Path path, Properties values, String key, boolean fallback) {
        String value = values.getProperty(key);
        if (value == null) return fallback;
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw invalid(path, key, "expected true or false, got '" + value + "'");
    }

    private static <E extends Enum<E>> E enumeration(Path path, Properties values, String key,
                                                      E fallback, Class<E> type) {
        String value = values.getProperty(key);
        if (value == null) return fallback;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw invalid(path, key, "unknown value '" + value + "'");
        }
    }

    private static Vector3f vector(Path path, Properties values, String key, Vector3f fallback) {
        String value = values.getProperty(key);
        if (value == null) return new Vector3f(fallback);
        String[] parts = value.split(",", -1);
        if (parts.length != 3) throw invalid(path, key, "expected three comma-separated floats");
        Properties components = new Properties();
        components.setProperty(key + ".x", parts[0]);
        components.setProperty(key + ".y", parts[1]);
        components.setProperty(key + ".z", parts[2]);
        return new Vector3f(floating(path, components, key + ".x", 0.0f),
                floating(path, components, key + ".y", 0.0f),
                floating(path, components, key + ".z", 0.0f));
    }

    private static void putVector(Properties values, String key, Vector3f value) {
        values.setProperty(key, value.x + "," + value.y + "," + value.z);
    }

    private static IllegalArgumentException invalid(Path path, String field, String reason) {
        return new IllegalArgumentException(path + ": field '" + field + "': " + reason);
    }
}
