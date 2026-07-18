package com.kaleblangley.haikalat.core.assets.gltf;

/** glTF 输入容量上限；所有值均在分配或解码前检查。 */
public record GltfAssetLimits(
        long documentBytes, long decodedBufferBytes, long imageBytes,
        int nodes, int meshes, int primitives, int materials,
        int textures, int images, int samplers, int accessors,
        int primitiveVertices, int primitiveIndices, int hierarchyDepth,
        long dataUriBytes) {
    public GltfAssetLimits {
        if (documentBytes <= 0 || decodedBufferBytes <= 0 || imageBytes <= 0
                || nodes <= 0 || meshes <= 0 || primitives <= 0 || materials <= 0
                || textures <= 0 || images <= 0 || samplers <= 0 || accessors <= 0
                || primitiveVertices <= 0 || primitiveIndices <= 0 || hierarchyDepth <= 0
                || dataUriBytes <= 0) {
            throw new IllegalArgumentException("all glTF asset limits must be positive");
        }
    }

    public static GltfAssetLimits defaults() {
        long mib = 1024L * 1024L;
        return new GltfAssetLimits(64L * mib, 512L * mib, 64L * mib,
                100_000, 50_000, 100_000, 16_384,
                16_384, 16_384, 16_384, 200_000,
                16_777_216, 50_000_000, 256, 256L * mib);
    }
}
