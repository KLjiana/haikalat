package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.limit;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.integer;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.objects;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.string;

/** 解析受 root 约束的 external/data URI，并统一执行容量限制。 */
final class GltfUriResolver {
    private final ResourceLocator locator;
    private final AssetRef source;
    private final byte[] glbBin;
    private final GltfAssetLimits limits;
    private long decodedDataUriBytes;

    GltfUriResolver(ResourceLocator locator, AssetRef source, byte[] glbBin,
                    GltfAssetLimits limits) {
        this.locator = locator;
        this.source = source;
        this.glbBin = glbBin;
        this.limits = limits;
    }

    BufferResolution resolveBuffers(Map<String, Object> root) {
        List<Map<String, Object>> definitions = objects(root, "buffers");
        List<byte[]> result = new ArrayList<>(definitions.size());
        Map<String, byte[]> uriCache = new HashMap<>();
        long total = 0L;
        for (int index = 0; index < definitions.size(); index++) {
            Map<String, Object> definition = definitions.get(index);
            String path = "buffers[" + index + "]";
            int declared = integer(definition, "byteLength", true, path + ".byteLength");
            if (declared < 0) throw decode(path + ".byteLength", "must not be negative");
            long nextTotal;
            try {
                nextTotal = Math.addExact(total, declared);
            } catch (ArithmeticException error) {
                throw decode(path + ".byteLength", "decoded buffer byte total overflows", error);
            }
            limit(source, "decodedBufferBytes", nextTotal, limits.decodedBufferBytes(), "buffers");
            String uri = string(definition, "uri", false, path + ".uri");
            byte[] bytes;
            if (uri == null) {
                if (index != 0 || glbBin == null) {
                    throw decode(path, "buffer without URI requires GLB BIN chunk");
                }
                bytes = glbBin;
            } else {
                int bufferIndex = index;
                bytes = uriCache.computeIfAbsent(uri,
                        key -> resolve(key, false, "buffers[" + bufferIndex + "].uri"));
            }
            if (declared > bytes.length) {
                throw decode(path + ".byteLength",
                        "declared " + declared + " exceeds payload " + bytes.length);
            }
            total = nextTotal;
            result.add(java.util.Arrays.copyOf(bytes, declared));
        }
        return new BufferResolution(List.copyOf(result), total);
    }

    byte[] resolve(String uri, boolean image, String path) {
        if (uri.startsWith("data:")) return decodeDataUri(uri, image, path);
        final AssetRef dependent;
        try {
            dependent = locator.resolveRelative(source, uri);
        } catch (RuntimeException error) {
            throw new GltfAssetException(source, GltfAssetException.Phase.RESOLVE, path, uri,
                    error.getMessage(), error);
        }
        long byteLimit = image ? limits.imageBytes() : limits.decodedBufferBytes();
        try {
            return locator.readBytes(dependent, byteLimit);
        } catch (RuntimeException error) {
            throw new GltfAssetException(source, GltfAssetException.Phase.RESOLVE, path, uri,
                    "could not read dependent resource", error);
        }
    }

    private byte[] decodeDataUri(String uri, boolean image, String path) {
        int comma = uri.indexOf(',');
        if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) {
            throw decode(path, "only base64 data URIs are supported");
        }
        String mime = uri.substring(5, comma - 7).toLowerCase(Locale.ROOT);
        Set<String> allowed = image ? Set.of("image/png", "image/jpeg")
                : Set.of("application/octet-stream", "application/gltf-buffer");
        if (!allowed.contains(mime)) throw decode(path, "unsupported data URI mime " + mime);
        long encodedLength = uri.length() - comma - 1L;
        int padding = uri.endsWith("==") ? 2 : uri.endsWith("=") ? 1 : 0;
        long estimate;
        try {
            estimate = Math.max(0L, Math.multiplyExact(encodedLength, 6L) / 8L - padding);
            limit(source, "dataUriBytes", Math.addExact(decodedDataUriBytes, estimate),
                    limits.dataUriBytes(), path);
        } catch (ArithmeticException error) {
            throw decode(path, "data URI size overflows", error);
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(uri.substring(comma + 1));
            decodedDataUriBytes = Math.addExact(decodedDataUriBytes, decoded.length);
            limit(source, "dataUriBytes", decodedDataUriBytes, limits.dataUriBytes(), path);
            return decoded;
        } catch (IllegalArgumentException error) {
            throw decode(path, "invalid base64 payload", error);
        }
    }

    private GltfAssetException decode(String location, String message) {
        return new GltfAssetException(source, GltfAssetException.Phase.DECODE, location, message);
    }

    private GltfAssetException decode(String location, String message, Throwable cause) {
        return new GltfAssetException(source, GltfAssetException.Phase.DECODE, location,
                null, message, cause);
    }

    record BufferResolution(List<byte[]> buffers, long decodedBytes) {
    }
}
