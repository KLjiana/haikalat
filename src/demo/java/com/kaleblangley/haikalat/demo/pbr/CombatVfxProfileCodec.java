package com.kaleblangley.haikalat.demo.pbr;

import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Strict, atomic properties codec for {@link CombatVfxProfile} schema 1.
 *
 * <p>It is intentionally independent of {@code VisualProfileCodec}: schema 2
 * visual profiles are not interpreted as combat VFX files.  Missing fields,
 * unknown keys, unknown versions, non-finite numbers, out-of-range values and
 * unknown resource identifiers all fail before an active effect is replaced.</p>
 */
public final class CombatVfxProfileCodec {
    private static final Set<String> KEYS = Set.of(
            "schemaVersion", "profileId",
            "rim.enabled", "rim.color", "rim.intensity", "rim.exponent",
            "uv.enabled", "uv.speed_u", "uv.speed_v", "uv.noise_scale", "uv.noise_amplitude",
            "dissolve.enabled", "dissolve.explicit", "dissolve.edge_width",
            "dissolve.edge_color", "dissolve.edge_intensity",
            "light.enabled", "light.color", "light.intensity", "light.range", "light.priority",
            "timing.charge", "timing.release", "timing.fade", "timing.light_end",
            "seed", "resource.particle", "resource.ribbon", "resource.decal", "resource.mesh");

    private CombatVfxProfileCodec() {
    }

    public static void save(Path path, CombatVfxProfile profile) {
        Path output = normalize(path);
        CombatVfxProfile required = java.util.Objects.requireNonNull(profile, "profile");
        Properties properties = encode(required);
        Path temporary = null;
        try {
            Path parent = output.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tempDirectory = parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
            temporary = Files.createTempFile(tempDirectory, output.getFileName().toString(), ".tmp");
            try (var writer = Files.newBufferedWriter(temporary)) {
                properties.store(writer, "Haikalat combat VFX profile schema "
                        + CombatVfxProfile.CURRENT_SCHEMA);
            }
            CombatVfxProfile decoded = load(temporary);
            if (!decoded.equals(required)) {
                throw new IllegalStateException("combat VFX profile validation changed values for " + output);
            }
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException failure) {
            throw new IllegalStateException("failed to save combat VFX profile " + output, failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static CombatVfxProfile load(Path path) {
        Path input = normalize(path);
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(input)) {
            properties.load(reader);
        } catch (IOException failure) {
            throw new IllegalStateException("failed to load combat VFX profile " + input, failure);
        }
        String schemaText = properties.getProperty("schemaVersion");
        if (schemaText == null) {
            throw invalid(input, "schemaVersion", "schema 1 is required");
        }
        int schema = integer(input, properties, "schemaVersion");
        if (schema != CombatVfxProfile.CURRENT_SCHEMA) {
            throw invalid(input, "schemaVersion", "unsupported schema " + schema);
        }
        for (String key : properties.stringPropertyNames()) {
            if (!KEYS.contains(key)) throw invalid(input, key, "unknown key");
        }
        CombatVfxProfile.Builder builder = CombatVfxProfile.builder()
                .profileId(text(input, properties, "profileId"))
                .rimEnabled(bool(input, properties, "rim.enabled"))
                .rimColor(vector(input, properties, "rim.color"))
                .rimIntensity(floating(input, properties, "rim.intensity"))
                .rimExponent(floating(input, properties, "rim.exponent"))
                .uvFlowEnabled(bool(input, properties, "uv.enabled"))
                .uvSpeedU(floating(input, properties, "uv.speed_u"))
                .uvSpeedV(floating(input, properties, "uv.speed_v"))
                .uvNoiseScale(floating(input, properties, "uv.noise_scale"))
                .uvNoiseAmplitude(floating(input, properties, "uv.noise_amplitude"))
                .dissolveEnabled(bool(input, properties, "dissolve.enabled"))
                .dissolveExplicit(bool(input, properties, "dissolve.explicit"))
                .dissolveEdgeWidth(floating(input, properties, "dissolve.edge_width"))
                .dissolveEdgeColor(vector(input, properties, "dissolve.edge_color"))
                .dissolveEdgeIntensity(floating(input, properties, "dissolve.edge_intensity"))
                .lightEnabled(bool(input, properties, "light.enabled"))
                .lightColor(vector(input, properties, "light.color"))
                .lightIntensity(floating(input, properties, "light.intensity"))
                .lightRange(floating(input, properties, "light.range"))
                .lightPriority(integer(input, properties, "light.priority"))
                .chargeSeconds(floating(input, properties, "timing.charge"))
                .releaseSeconds(floating(input, properties, "timing.release"))
                .fadeSeconds(floating(input, properties, "timing.fade"))
                .lightEndSeconds(floating(input, properties, "timing.light_end"))
                .seed(longValue(input, properties, "seed"))
                .particleTextureId(text(input, properties, "resource.particle"))
                .ribbonTextureId(text(input, properties, "resource.ribbon"))
                .decalTextureId(text(input, properties, "resource.decal"))
                .meshId(text(input, properties, "resource.mesh"));
        try {
            return builder.build();
        } catch (IllegalArgumentException failure) {
            throw invalid(input, "value", failure.getMessage());
        }
    }

    private static Properties encode(CombatVfxProfile profile) {
        Properties properties = new Properties();
        properties.setProperty("schemaVersion", Integer.toString(profile.schemaVersion()));
        properties.setProperty("profileId", profile.profileId());
        properties.setProperty("rim.enabled", Boolean.toString(profile.rimEnabled()));
        properties.setProperty("rim.color", vector(profile.rimColor()));
        properties.setProperty("rim.intensity", floating(profile.rimIntensity()));
        properties.setProperty("rim.exponent", floating(profile.rimExponent()));
        properties.setProperty("uv.enabled", Boolean.toString(profile.uvFlowEnabled()));
        properties.setProperty("uv.speed_u", floating(profile.uvSpeedU()));
        properties.setProperty("uv.speed_v", floating(profile.uvSpeedV()));
        properties.setProperty("uv.noise_scale", floating(profile.uvNoiseScale()));
        properties.setProperty("uv.noise_amplitude", floating(profile.uvNoiseAmplitude()));
        properties.setProperty("dissolve.enabled", Boolean.toString(profile.dissolveEnabled()));
        properties.setProperty("dissolve.explicit", Boolean.toString(profile.dissolveExplicit()));
        properties.setProperty("dissolve.edge_width", floating(profile.dissolveEdgeWidth()));
        properties.setProperty("dissolve.edge_color", vector(profile.dissolveEdgeColor()));
        properties.setProperty("dissolve.edge_intensity", floating(profile.dissolveEdgeIntensity()));
        properties.setProperty("light.enabled", Boolean.toString(profile.lightEnabled()));
        properties.setProperty("light.color", vector(profile.lightColor()));
        properties.setProperty("light.intensity", floating(profile.lightIntensity()));
        properties.setProperty("light.range", floating(profile.lightRange()));
        properties.setProperty("light.priority", Integer.toString(profile.lightPriority()));
        properties.setProperty("timing.charge", floating(profile.chargeSeconds()));
        properties.setProperty("timing.release", floating(profile.releaseSeconds()));
        properties.setProperty("timing.fade", floating(profile.fadeSeconds()));
        properties.setProperty("timing.light_end", floating(profile.lightEndSeconds()));
        properties.setProperty("seed", Long.toString(profile.seed()));
        properties.setProperty("resource.particle", profile.particleTextureId());
        properties.setProperty("resource.ribbon", profile.ribbonTextureId());
        properties.setProperty("resource.decal", profile.decalTextureId());
        properties.setProperty("resource.mesh", profile.meshId());
        return properties;
    }

    private static Path normalize(Path path) {
        return java.util.Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    private static String text(Path path, Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw invalid(path, key, "value is required");
        return value.trim();
    }

    private static boolean bool(Path path, Properties properties, String key) {
        String value = text(path, properties, key);
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid(path, key, "expected true or false");
        };
    }

    private static float floating(Path path, Properties properties, String key) {
        String value = text(path, properties, key);
        float parsed;
        try {
            parsed = Float.parseFloat(value);
        } catch (NumberFormatException failure) {
            throw invalid(path, key, "expected a decimal number");
        }
        if (!Float.isFinite(parsed)) throw invalid(path, key, "must be finite");
        return parsed;
    }

    private static int integer(Path path, Properties properties, String key) {
        String value = text(path, properties, key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw invalid(path, key, "expected an integer");
        }
    }

    private static long longValue(Path path, Properties properties, String key) {
        String value = text(path, properties, key);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw invalid(path, key, "expected a long integer");
        }
    }

    private static Vector3f vector(Path path, Properties properties, String key) {
        String value = text(path, properties, key);
        String[] parts = value.split(",", -1);
        if (parts.length != 3) throw invalid(path, key, "expected r,g,b");
        float[] components = new float[3];
        for (int index = 0; index < 3; index++) {
            try {
                components[index] = Float.parseFloat(parts[index].trim());
            } catch (NumberFormatException failure) {
                throw invalid(path, key, "expected r,g,b decimal numbers");
            }
            if (!Float.isFinite(components[index])) throw invalid(path, key, "must be finite");
        }
        return new Vector3f(components[0], components[1], components[2]);
    }

    private static String vector(Vector3f value) {
        return String.format(Locale.ROOT, "%s,%s,%s", floating(value.x), floating(value.y), floating(value.z));
    }

    private static String floating(float value) {
        return String.format(Locale.ROOT, "%.9g", value);
    }

    private static IllegalArgumentException invalid(Path path, String key, String message) {
        return new IllegalArgumentException("invalid combat VFX profile " + path
                + " [" + key + "]: " + message);
    }
}
