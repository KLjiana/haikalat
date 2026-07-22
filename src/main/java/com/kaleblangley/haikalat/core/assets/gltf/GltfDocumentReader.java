package com.kaleblangley.haikalat.core.assets.gltf;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.kaleblangley.haikalat.core.assets.AssetRef;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 供 {@link GltfAssetLoader} 使用的包内 GLB 容器与 JSON 文档读取器。 */
final class GltfDocumentReader {
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int JSON_CHUNK = 0x4E4F534A;
    private static final int BIN_CHUNK = 0x004E4942;
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private GltfDocumentReader() {
    }

    static Document read(AssetRef source, byte[] bytes, boolean glb) {
        return glb ? readGlb(source, bytes) : new Document(bytes, null, List.of());
    }

    private static Document readGlb(AssetRef source, byte[] bytes) {
        if (bytes.length < 20) throw parseError(source, "byte 0", "GLB is truncated");
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (input.getInt() != GLB_MAGIC) throw parseError(source, "byte 0", "invalid GLB magic");
        int version = input.getInt();
        if (version != 2) throw parseError(source, "byte 4", "unsupported GLB version " + version);
        long declared = Integer.toUnsignedLong(input.getInt());
        if (declared != bytes.length) throw parseError(source, "byte 8",
                "declared GLB length " + declared + " differs from actual " + bytes.length);
        byte[] json = null;
        byte[] bin = null;
        List<String> warnings = new ArrayList<>();
        int chunk = 0;
        while (input.hasRemaining()) {
            int offset = input.position();
            if (input.remaining() < 8) throw parseError(source, "chunk[" + chunk + "] byte " + offset,
                    "truncated chunk header");
            long length = Integer.toUnsignedLong(input.getInt());
            int type = input.getInt();
            if ((length & 3L) != 0L || length > input.remaining()) {
                throw parseError(source, "chunk[" + chunk + "] byte " + offset,
                        "invalid or out-of-range aligned chunk length " + length);
            }
            byte[] payload = new byte[Math.toIntExact(length)];
            input.get(payload);
            if (chunk == 0 && type != JSON_CHUNK) throw parseError(source, "chunk[0]",
                    "first GLB chunk must be JSON");
            if (type == JSON_CHUNK) {
                if (json != null) throw parseError(source, "chunk[" + chunk + "]", "duplicate JSON chunk");
                json = trimJsonPadding(source, payload, chunk);
            } else if (type == BIN_CHUNK) {
                if (bin != null) throw parseError(source, "chunk[" + chunk + "]", "duplicate BIN chunk");
                bin = payload;
            } else {
                warnings.add("ignored unknown GLB chunk type 0x" + Integer.toHexString(type));
            }
            chunk++;
        }
        if (json == null) throw parseError(source, "chunk[0]", "missing JSON chunk");
        return new Document(json, bin, List.copyOf(warnings));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseJson(AssetRef source, byte[] bytes) {
        try (JsonParser parser = JSON.createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw parseError(source, "$", "root must be an object");
            }
            Object value = readValue(parser);
            if (parser.nextToken() != null) throw parseError(source, "$", "trailing JSON content");
            return (Map<String, Object>) value;
        } catch (GltfAssetException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new GltfAssetException(source, GltfAssetException.Phase.PARSE, "$", null,
                    "invalid JSON: " + failure.getMessage(), failure);
        }
    }

    private static byte[] trimJsonPadding(AssetRef source, byte[] bytes, int chunk) {
        int end = bytes.length;
        while (end > 0 && (bytes[end - 1] == 0 || bytes[end - 1] == 0x20)) end--;
        for (int i = end; i < bytes.length; i++) {
            if (bytes[i] != 0 && bytes[i] != 0x20) {
                throw parseError(source, "chunk[" + chunk + "] byte " + i, "invalid JSON padding");
            }
        }
        return java.util.Arrays.copyOf(bytes, end);
    }

    private static Object readValue(JsonParser parser) throws IOException {
        return switch (parser.currentToken()) {
            case START_OBJECT -> {
                Map<String, Object> object = new LinkedHashMap<>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String field = parser.currentName();
                    parser.nextToken();
                    object.put(field, readValue(parser));
                }
                yield object;
            }
            case START_ARRAY -> {
                List<Object> array = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) array.add(readValue(parser));
                yield List.copyOf(array);
            }
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT -> parser.getLongValue();
            case VALUE_NUMBER_FLOAT -> parser.getDoubleValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw new IOException("unexpected token " + parser.currentToken());
        };
    }

    private static GltfAssetException parseError(AssetRef source, String location, String message) {
        return new GltfAssetException(source, GltfAssetException.Phase.PARSE, location, message);
    }

    record Document(byte[] json, byte[] bin, List<String> warnings) {
    }
}
