package com.kaleblangley.haikalat.core.assets.gltf;

import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** glTF streaming document 转换后的受检 JSON value 访问器。 */
final class GltfJson {
    private GltfJson() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value, String path) {
        if (value == null) return null;
        if (!(value instanceof Map<?, ?>)) throw failure(path, "must be an object");
        return (Map<String, Object>) value;
    }

    static Map<String, Object> object(Map<String, Object> owner, String key,
                                      boolean required, String path) {
        Map<String, Object> value = map(owner.get(key), path);
        if (required && value == null) throw failure(path, "is required");
        return value;
    }

    static List<Map<String, Object>> objects(Map<String, Object> owner, String key) {
        Object value = owner.get(key);
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw failure(key, "must be an array");
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index++) {
            result.add(map(list.get(index), key + "[" + index + "]"));
        }
        return List.copyOf(result);
    }

    static String string(Map<String, Object> owner, String key, boolean required, String path) {
        Object value = owner.get(key);
        if (value == null) {
            if (required) throw failure(path, "is required");
            return null;
        }
        if (!(value instanceof String text)) throw failure(path, "must be a string");
        return text;
    }

    static int integer(Map<String, Object> owner, String key, boolean required, String path) {
        return integer(owner, key, required, path, 0);
    }

    static int integer(Map<String, Object> owner, String key, boolean required,
                       String path, int fallback) {
        Object value = owner.get(key);
        if (value == null) {
            if (required) throw failure(path, "is required");
            return fallback;
        }
        if (!(value instanceof Number number)
                || number.longValue() != number.doubleValue()
                || number.longValue() < Integer.MIN_VALUE
                || number.longValue() > Integer.MAX_VALUE) {
            throw failure(path, "must be an integer");
        }
        return number.intValue();
    }

    static float decimal(Map<String, Object> owner, String key, float fallback, String path) {
        Object value = owner.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw failure(path + "." + key, "must be numeric");
        float result = number.floatValue();
        if (!Float.isFinite(result)) throw failure(path + "." + key, "must be finite");
        return result;
    }

    static boolean bool(Map<String, Object> owner, String key, boolean fallback, String path) {
        Object value = owner.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean result)) throw failure(path, "must be boolean");
        return result;
    }

    static List<Integer> integers(Object value, String path) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw failure(path, "must be an array");
        List<Integer> result = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof Number number) || number.longValue() != number.doubleValue()) {
                throw failure(path, "must contain integers");
            }
            try {
                result.add(Math.toIntExact(number.longValue()));
            } catch (ArithmeticException error) {
                throw failure(path, "contains an integer outside the supported range");
            }
        }
        return List.copyOf(result);
    }

    static List<String> strings(Object value, String path) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw failure(path, "must be an array");
        List<String> result = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof String text)) throw failure(path, "must contain strings");
            result.add(text);
        }
        return List.copyOf(result);
    }

    static float[] floatArray(Object value, int length, String path) {
        if (!(value instanceof List<?> list) || list.size() != length) {
            throw failure(path, "must contain " + length + " numbers");
        }
        float[] result = new float[length];
        for (int index = 0; index < length; index++) {
            if (!(list.get(index) instanceof Number number)) {
                throw failure(path, "must contain numbers");
            }
            result[index] = number.floatValue();
            if (!Float.isFinite(result[index])) throw failure(path, "contains non-finite value");
        }
        return result;
    }

    static Vector3f vec3(Object value, Vector3f fallback, String path) {
        if (value == null) return new Vector3f(fallback);
        float[] values = floatArray(value, 3, path);
        return new Vector3f(values[0], values[1], values[2]);
    }

    static Vector4f vec4(Object value, Vector4f fallback, String path) {
        if (value == null) return new Vector4f(fallback);
        float[] values = floatArray(value, 4, path);
        return new Vector4f(values[0], values[1], values[2], values[3]);
    }

    static GltfDecodeException failure(String path, String message) {
        return new GltfDecodeException(path, message);
    }
}
