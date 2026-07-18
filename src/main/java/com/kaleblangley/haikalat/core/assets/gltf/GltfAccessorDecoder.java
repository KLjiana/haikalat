package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.finite;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.index;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.requireCount;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.bool;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.integer;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.map;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.object;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.string;

/** accessor、stride、normalized 与 sparse overlay 的独立解码阶段。 */
final class GltfAccessorDecoder {
    static final Set<Integer> NO_NORMALIZED_COMPONENTS = Set.of();
    static final Set<Integer> SIGNED_NORMALIZED_COMPONENTS = Set.of(5120, 5122);
    static final Set<Integer> UNSIGNED_NORMALIZED_COMPONENTS = Set.of(5121, 5123);
    private static final ByteBuffer ZERO_ACCESSOR_BUFFER = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);

    private final AssetRef source;
    private final List<Map<String, Object>> accessors;
    private final GltfBufferTable buffers;

    GltfAccessorDecoder(AssetRef source, List<Map<String, Object>> accessors,
                        GltfBufferTable buffers) {
        this.source = source;
        this.accessors = accessors;
        this.buffers = buffers;
    }

    Map<String, Object> definition(int accessorIndex, String path) {
        index(accessorIndex, accessors.size(), path);
        return accessors.get(accessorIndex);
    }

    float[] floats(int accessorIndex, int components,
                   Set<Integer> normalizedTypes, String path) {
        Map<String, Object> accessor = definition(accessorIndex, path);
        int actualComponents = componentCount(string(accessor, "type", true,
                "accessors[" + accessorIndex + "].type"),
                "accessors[" + accessorIndex + "].type");
        if (actualComponents != components) {
            throw fail(path, "expected " + components + " components, got " + actualComponents);
        }
        int componentType = integer(accessor, "componentType", true,
                "accessors[" + accessorIndex + "].componentType");
        boolean normalized = bool(accessor, "normalized", false,
                "accessors[" + accessorIndex + "].normalized");
        if (componentType != 5126 && !(normalized && normalizedTypes.contains(componentType))) {
            throw fail(path, "unsupported componentType " + componentType
                    + (normalized ? " normalized" : ""));
        }
        int count = integer(accessor, "count", true,
                "accessors[" + accessorIndex + "].count");
        float[] result = new float[Math.multiplyExact(count, components)];
        read(accessorIndex, components, (output, component, buffer, offset, type, norm) ->
                result[output] = readFloat(buffer, offset, type, norm));
        finite(result, path);
        return result;
    }

    int[] indices(int accessorIndex, int vertexCount, String path) {
        Map<String, Object> accessor = definition(accessorIndex, path);
        if (!"SCALAR".equals(accessor.get("type"))) {
            throw fail(path, "indices accessor must be SCALAR");
        }
        int type = integer(accessor, "componentType", true, path);
        if (!Set.of(5121, 5123, 5125).contains(type)) {
            throw fail(path, "indices must use unsigned byte/short/int");
        }
        int count = integer(accessor, "count", true, path);
        int[] result = new int[count];
        read(accessorIndex, 1, (output, component, buffer, offset, ignored, normalized) -> {
            long value = readUnsigned(buffer, offset, type);
            if (value > Integer.MAX_VALUE) {
                throw fail(path, "index exceeds signed framework range: " + value);
            }
            result[output] = (int) value;
        });
        for (int value : result) {
            if (value < 0 || value >= vertexCount) {
                throw fail(path, "index " + value + " outside vertex count " + vertexCount);
            }
        }
        return result;
    }

    float[] colors(int accessorIndex, int vertexCount, String path) {
        Map<String, Object> accessor = definition(accessorIndex, path);
        String type = string(accessor, "type", true, path + ".type");
        int components = componentCount(type, path + ".type");
        if (components != 3 && components != 4) throw fail(path, "COLOR_0 must be VEC3 or VEC4");
        int componentType = integer(accessor, "componentType", true, path + ".componentType");
        boolean normalized = bool(accessor, "normalized", false, path + ".normalized");
        if (componentType != 5126
                && !(normalized && (componentType == 5121 || componentType == 5123))) {
            throw fail(path, "COLOR_0 must use FLOAT or normalized unsigned byte/short");
        }
        float[] raw = floats(accessorIndex, components, UNSIGNED_NORMALIZED_COMPONENTS, path);
        requireCount(raw.length / components, vertexCount, path);
        for (int index = 0; index < raw.length; index++) {
            raw[index] = Math.max(0.0f, Math.min(1.0f, raw[index]));
        }
        if (components == 4) return raw;
        float[] result = new float[raw.length / 3 * 4];
        for (int index = 0; index < raw.length / 3; index++) {
            System.arraycopy(raw, index * 3, result, index * 4, 3);
            result[index * 4 + 3] = 1.0f;
        }
        return result;
    }

    private void read(int accessorIndex, int components, AccessorConsumer consumer) {
        Map<String, Object> accessor = accessors.get(accessorIndex);
        String path = "accessors[" + accessorIndex + "]";
        int type = integer(accessor, "componentType", true, path);
        int componentBytes = componentBytes(type);
        int count = integer(accessor, "count", true, path + ".count");
        boolean normalized = bool(accessor, "normalized", false, path + ".normalized");
        int accessorOffset = integer(accessor, "byteOffset", false, path + ".byteOffset", 0);
        int viewIndex = integer(accessor, "bufferView", false, path + ".bufferView", -1);
        if (viewIndex >= 0) {
            Map<String, Object> view = buffers.view(viewIndex, path + ".bufferView");
            int bufferIndex = integer(view, "buffer", true,
                    "bufferViews[" + viewIndex + "].buffer");
            int viewOffset = integer(view, "byteOffset", false,
                    "bufferViews[" + viewIndex + "].byteOffset", 0);
            int viewLength = integer(view, "byteLength", true,
                    "bufferViews[" + viewIndex + "].byteLength");
            int elementBytes = Math.multiplyExact(components, componentBytes);
            int stride = integer(view, "byteStride", false,
                    "bufferViews[" + viewIndex + "].byteStride", elementBytes);
            if (stride < elementBytes || (view.containsKey("byteStride")
                    && (stride < 4 || stride > 252 || stride % 4 != 0))) {
                throw fail("bufferViews[" + viewIndex + "].byteStride", "invalid stride " + stride);
            }
            long last = count == 0 ? accessorOffset
                    : Math.addExact(accessorOffset, Math.addExact(
                    Math.multiplyExact((long) (count - 1), stride), elementBytes));
            if (accessorOffset < 0 || last > viewLength) {
                throw fail(path, "range exceeds bufferView[" + viewIndex + "]");
            }
            if (((long) viewOffset + accessorOffset) % componentBytes != 0) {
                throw fail(path + ".byteOffset",
                        "is not aligned to component size " + componentBytes);
            }
            ByteBuffer data = buffers.buffer(bufferIndex,
                    "bufferViews[" + viewIndex + "].buffer");
            for (int row = 0; row < count; row++) {
                for (int component = 0; component < components; component++) {
                    int offset = Math.addExact(viewOffset, Math.addExact(accessorOffset,
                            Math.addExact(Math.multiplyExact(row, stride), component * componentBytes)));
                    consumer.accept(row * components + component, component,
                            data, offset, type, normalized);
                }
            }
        } else {
            for (int row = 0; row < count; row++) {
                int outputBase = Math.multiplyExact(row, components);
                for (int component = 0; component < components; component++) {
                    consumer.accept(Math.addExact(outputBase, component), component,
                            ZERO_ACCESSOR_BUFFER, 0, type, normalized);
                }
            }
        }
        Map<String, Object> sparse = map(accessor.get("sparse"), path + ".sparse");
        if (sparse != null) {
            applySparse(accessorIndex, sparse, components, consumer, type, normalized, count);
        }
    }

    private void applySparse(int accessorIndex, Map<String, Object> sparse, int components,
                             AccessorConsumer consumer, int valueType,
                             boolean normalized, int count) {
        String sparsePath = "accessors[" + accessorIndex + "].sparse";
        int sparseCount = integer(sparse, "count", true, sparsePath + ".count");
        if (sparseCount < 0 || sparseCount > count) throw fail(sparsePath + ".count", "out of range");
        String indicesPath = sparsePath + ".indices";
        String valuesPath = sparsePath + ".values";
        Map<String, Object> indicesDefinition = object(sparse, "indices", true, indicesPath);
        Map<String, Object> valuesDefinition = object(sparse, "values", true, valuesPath);
        int indexView = integer(indicesDefinition, "bufferView", true, indicesPath + ".bufferView");
        int indexType = integer(indicesDefinition, "componentType", true,
                indicesPath + ".componentType");
        if (!Set.of(5121, 5123, 5125).contains(indexType)) {
            throw fail(indicesPath, "componentType must be unsigned integer");
        }
        ByteBuffer indexData = buffers.viewBuffer(indexView, indicesPath + ".bufferView");
        int indexOffset = integer(indicesDefinition, "byteOffset", false,
                indicesPath + ".byteOffset", 0);
        int valueView = integer(valuesDefinition, "bufferView", true, valuesPath + ".bufferView");
        ByteBuffer valueData = buffers.viewBuffer(valueView, valuesPath + ".bufferView");
        int valueOffset = integer(valuesDefinition, "byteOffset", false,
                valuesPath + ".byteOffset", 0);
        int indexBytes = componentBytes(indexType);
        int valueBytes = componentBytes(valueType);
        int indexViewOffset = integer(buffers.view(indexView, indicesPath + ".bufferView"),
                "byteOffset", false, indicesPath + ".bufferView.byteOffset", 0);
        int valueViewOffset = integer(buffers.view(valueView, valuesPath + ".bufferView"),
                "byteOffset", false, valuesPath + ".bufferView.byteOffset", 0);
        int valueElementBytes;
        try {
            valueElementBytes = Math.multiplyExact(components, valueBytes);
        } catch (ArithmeticException error) {
            throw fail(valuesPath, "element size overflow", error);
        }
        validateSparseRange(indicesPath, indexOffset, sparseCount, indexBytes,
                indexBytes, indexData.remaining(), indexViewOffset);
        validateSparseRange(valuesPath, valueOffset, sparseCount, valueElementBytes,
                valueBytes, valueData.remaining(), valueViewOffset);
        int previous = -1;
        for (int row = 0; row < sparseCount; row++) {
            int sparseIndexOffset = Math.toIntExact((long) indexOffset + (long) row * indexBytes);
            long raw = readUnsigned(indexData, sparseIndexOffset, indexType);
            if (raw > Integer.MAX_VALUE) throw fail(indicesPath, "index too large");
            int target = (int) raw;
            if (target <= previous || target >= count) {
                throw fail(indicesPath, "indices must be strictly increasing and in range");
            }
            previous = target;
            for (int component = 0; component < components; component++) {
                int offset = Math.toIntExact((long) valueOffset
                        + (long) row * valueElementBytes + (long) component * valueBytes);
                consumer.accept(target * components + component, component,
                        valueData, offset, valueType, normalized);
            }
        }
    }

    private void validateSparseRange(String path, int offset, int count, int elementBytes,
                                     int componentAlignment, int viewLength, int viewOffset) {
        if (offset < 0) throw fail(path, "byteOffset must be non-negative");
        if (((long) viewOffset + offset) % componentAlignment != 0) {
            throw fail(path, "byteOffset " + offset + " plus bufferView offset " + viewOffset
                    + " is not aligned to component size " + componentAlignment);
        }
        final long end;
        try {
            end = Math.addExact((long) offset, Math.multiplyExact((long) count, elementBytes));
        } catch (ArithmeticException error) {
            throw fail(path, "byte range overflow", error);
        }
        if (end > viewLength) {
            throw fail(path, "byte range " + offset + ".." + end
                    + " exceeds bufferView length " + viewLength);
        }
    }

    private static float readFloat(ByteBuffer buffer, int offset, int type, boolean normalized) {
        return switch (type) {
            case 5126 -> buffer.getFloat(offset);
            case 5120 -> normalized ? Math.max(-1.0f, buffer.get(offset) / 127.0f) : buffer.get(offset);
            case 5121 -> normalized ? (buffer.get(offset) & 255) / 255.0f : buffer.get(offset) & 255;
            case 5122 -> normalized ? Math.max(-1.0f, buffer.getShort(offset) / 32767.0f)
                    : buffer.getShort(offset);
            case 5123 -> normalized ? (buffer.getShort(offset) & 65535) / 65535.0f
                    : buffer.getShort(offset) & 65535;
            default -> throw new IllegalArgumentException("unsupported component type " + type);
        };
    }

    private static long readUnsigned(ByteBuffer buffer, int offset, int type) {
        return switch (type) {
            case 5121 -> buffer.get(offset) & 255L;
            case 5123 -> buffer.getShort(offset) & 65535L;
            case 5125 -> Integer.toUnsignedLong(buffer.getInt(offset));
            default -> throw new IllegalArgumentException("not an unsigned component type");
        };
    }

    private static int componentBytes(int type) {
        return switch (type) {
            case 5120, 5121 -> 1;
            case 5122, 5123 -> 2;
            case 5125, 5126 -> 4;
            default -> throw new IllegalArgumentException("unsupported component type " + type);
        };
    }

    private static int componentCount(String type, String path) {
        return switch (type) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            case "MAT4" -> 16;
            default -> throw GltfJson.failure(path, "unsupported accessor type " + type);
        };
    }

    private GltfAssetException fail(String location, String message) {
        return new GltfAssetException(source, GltfAssetException.Phase.DECODE, location, message);
    }

    private GltfAssetException fail(String location, String message, Throwable cause) {
        return new GltfAssetException(source, GltfAssetException.Phase.DECODE, location,
                null, message, cause);
    }

    @FunctionalInterface
    private interface AccessorConsumer {
        void accept(int outputIndex, int component, ByteBuffer buffer, int offset,
                    int componentType, boolean normalized);
    }
}
