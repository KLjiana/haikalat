package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import org.joml.Matrix4f;

/** glTF 各解码阶段共享的范围、finite 与版本校验。 */
final class GltfChecks {
    private GltfChecks() {
    }

    static void limit(AssetRef source, String name, long actual, long maximum, String path) {
        if (actual > maximum) {
            throw new GltfAssetException(source, GltfAssetException.Phase.DECODE, path,
                    "limit " + name + " exceeded: " + actual + " > " + maximum);
        }
    }

    static void index(int index, int size, String path) {
        if (index < 0 || index >= size) {
            throw GltfJson.failure(path, "index " + index + " outside 0.." + (size - 1));
        }
    }

    static void requireCount(int actual, int expected, String path) {
        if (actual != expected) {
            throw GltfJson.failure(path, "count " + actual + " differs from POSITION count " + expected);
        }
    }

    static void finite(float[] values, String path) {
        for (float value : values) {
            if (!Float.isFinite(value)) throw GltfJson.failure(path, "contains non-finite value");
        }
    }

    static boolean finite(Matrix4f value) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                if (!Float.isFinite(value.get(column, row))) return false;
            }
        }
        return true;
    }

    static boolean validVersion(String value) {
        return value.matches("[0-9]+(?:\\.[0-9]+)*");
    }

    static int compareVersion(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        for (int index = 0; index < Math.max(leftParts.length, rightParts.length); index++) {
            java.math.BigInteger leftValue = index < leftParts.length
                    ? new java.math.BigInteger(leftParts[index]) : java.math.BigInteger.ZERO;
            java.math.BigInteger rightValue = index < rightParts.length
                    ? new java.math.BigInteger(rightParts[index]) : java.math.BigInteger.ZERO;
            int comparison = leftValue.compareTo(rightValue);
            if (comparison != 0) return comparison;
        }
        return 0;
    }
}
