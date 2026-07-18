package com.kaleblangley.haikalat.core.assets.gltf;

/** glTF CPU 解码和 runtime 去重统计。 */
public record GltfSceneStatistics(
        int nodeCount, int reachableNodeCount, int meshCount, int primitiveCount,
        int materialCount, int textureCount, int imageCount, int samplerCount,
        int generatedNormalVertices, int tangentFallbackTriangles, int tangentFallbackVertices,
        long decodedBufferBytes, long encodedImageBytes, long vertexBytes, long indexBytes) {
}
