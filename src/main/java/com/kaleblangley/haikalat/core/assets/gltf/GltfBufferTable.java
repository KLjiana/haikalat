package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.index;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.integer;

/** 已解析 buffer/bufferView 的只读范围表。 */
final class GltfBufferTable {
    private final AssetRef source;
    private final List<byte[]> buffers;
    private final List<Map<String, Object>> views;

    GltfBufferTable(AssetRef source, List<byte[]> buffers,
                    List<Map<String, Object>> views) {
        this.source = source;
        this.buffers = buffers;
        this.views = views;
    }

    void validate() {
        for (int viewIndex = 0; viewIndex < views.size(); viewIndex++) {
            Map<String, Object> view = views.get(viewIndex);
            String path = "bufferViews[" + viewIndex + "]";
            int bufferIndex = integer(view, "buffer", true, path + ".buffer");
            index(bufferIndex, buffers.size(), path + ".buffer");
            int offset = integer(view, "byteOffset", false, path + ".byteOffset", 0);
            int length = integer(view, "byteLength", true, path + ".byteLength");
            if (offset < 0 || length < 0 || (long) offset + length > buffers.get(bufferIndex).length) {
                throw fail(path, "range " + offset + ".." + ((long) offset + length)
                        + " exceeds buffer length " + buffers.get(bufferIndex).length);
            }
            if (view.containsKey("byteStride")) {
                int stride = integer(view, "byteStride", true, path + ".byteStride");
                if (stride < 4 || stride > 252 || stride % 4 != 0) {
                    throw fail(path + ".byteStride", "must be a 4-byte multiple in 4..252");
                }
            }
        }
    }

    int viewCount() {
        return views.size();
    }

    Map<String, Object> view(int viewIndex, String path) {
        index(viewIndex, views.size(), path);
        return views.get(viewIndex);
    }

    ByteBuffer viewBuffer(int viewIndex, String path) {
        Map<String, Object> view = view(viewIndex, path);
        int bufferIndex = integer(view, "buffer", true, path + ".buffer");
        index(bufferIndex, buffers.size(), path + ".buffer");
        int offset = integer(view, "byteOffset", false, path + ".byteOffset", 0);
        int length = integer(view, "byteLength", true, path + ".byteLength");
        if (offset < 0 || length < 0 || (long) offset + length > buffers.get(bufferIndex).length) {
            throw fail(path, "bufferView range exceeds buffer");
        }
        return ByteBuffer.wrap(buffers.get(bufferIndex), offset, length)
                .slice().order(ByteOrder.LITTLE_ENDIAN).asReadOnlyBuffer()
                .order(ByteOrder.LITTLE_ENDIAN);
    }

    byte[] viewBytes(int viewIndex, String path) {
        ByteBuffer buffer = viewBuffer(viewIndex, path);
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    ByteBuffer buffer(int bufferIndex, String path) {
        index(bufferIndex, buffers.size(), path);
        return ByteBuffer.wrap(buffers.get(bufferIndex)).order(ByteOrder.LITTLE_ENDIAN)
                .asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    private GltfAssetException fail(String location, String message) {
        return new GltfAssetException(source, GltfAssetException.Phase.DECODE, location, message);
    }
}
