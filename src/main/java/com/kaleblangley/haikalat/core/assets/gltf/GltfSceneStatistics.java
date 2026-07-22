package com.kaleblangley.haikalat.core.assets.gltf;

/**
 * glTF CPU 解码和 runtime 去重统计。
 *
 * @param nodeCount 节点总数
 * @param reachableNodeCount 选中场景可达节点数
 * @param meshCount mesh 数量
 * @param primitiveCount primitive 数量
 * @param materialCount 材质数量
 * @param textureCount 纹理数量
 * @param imageCount 图像数量
 * @param samplerCount sampler 数量
 * @param generatedNormalVertices 生成法线的顶点数
 * @param tangentFallbackTriangles 使用切线回退的三角形数
 * @param tangentFallbackVertices 使用切线回退的顶点数
 * @param decodedBufferBytes 解码后的 buffer 字节数
 * @param encodedImageBytes 编码图像字节数
 * @param vertexBytes 顶点数据字节数
 * @param indexBytes 索引数据字节数
 */
public record GltfSceneStatistics(
        int nodeCount, int reachableNodeCount, int meshCount, int primitiveCount,
        int materialCount, int textureCount, int imageCount, int samplerCount,
        int generatedNormalVertices, int tangentFallbackTriangles, int tangentFallbackVertices,
        long decodedBufferBytes, long encodedImageBytes, long vertexBytes, long indexBytes) {
}
