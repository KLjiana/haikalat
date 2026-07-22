package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Locale;
import java.util.Properties;

/** 转换场景清单支持的标量和向量值，并在失败时提供可操作的上下文。 */
final class SceneValueParser {
    private SceneValueParser() {
    }

    static TextureColorSpace colorSpace(String value) {
        try {
            return TextureColorSpace.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new GlException("Unsupported texture color space: " + value
                    + ". Supported values: linear, srgb", error);
        }
    }

    static float floatValue(Properties properties, String key, float fallback) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        try {
            float parsed = Float.parseFloat(value.strip());
            if (!Float.isFinite(parsed)) throw new NumberFormatException("non-finite value");
            return parsed;
        } catch (NumberFormatException error) {
            throw new GlException("Invalid floating-point scene property " + key + ": " + value, error);
        }
    }

    static int intValue(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException error) {
            throw new GlException("Invalid integer scene property " + key + ": " + value, error);
        }
    }

    static boolean booleanValue(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new GlException("Invalid boolean scene property " + key + ": " + value);
        };
    }

    static <E extends Enum<E>> E enumValue(Class<E> type, String value, String key) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new GlException("Invalid enum scene property " + key + ": " + value, error);
        }
    }

    static Vector4f vector4(String value, String key) {
        String[] parts = value.split("\\s*,\\s*");
        if (parts.length != 4) {
            throw new GlException("Expected vector property with 4 components: " + key);
        }
        try {
            Vector4f parsed = new Vector4f(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]),
                    Float.parseFloat(parts[2]), Float.parseFloat(parts[3]));
            if (!parsed.isFinite()) throw new NumberFormatException("non-finite value");
            return parsed;
        } catch (NumberFormatException error) {
            throw new GlException("Invalid vector scene property " + key + ": " + value, error);
        }
    }

    static Vector3f vector3(String value, String key) {
        String[] parts = value.split("\\s*,\\s*");
        if (parts.length != 3) {
            throw new GlException("Expected vector property with 3 components: " + key);
        }
        try {
            Vector3f parsed = new Vector3f(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]),
                    Float.parseFloat(parts[2]));
            if (!parsed.isFinite()) throw new NumberFormatException("non-finite value");
            return parsed;
        } catch (NumberFormatException error) {
            throw new GlException("Invalid vector scene property " + key + ": " + value, error);
        }
    }
}
