package com.kaleblangley.haikalat.core.assets.gltf;

/**
 * glTF 输入容量上限；所有值均在分配或解码前检查。
 *
 * @param documentBytes 文档最大字节数
 * @param decodedBufferBytes 解码后 buffer 最大总字节数
 * @param imageBytes 单个编码图像最大字节数
 * @param nodes 最大节点数
 * @param meshes 最大 mesh 数
 * @param primitives 最大 primitive 数
 * @param materials 最大材质数
 * @param textures 最大纹理数
 * @param images 最大图像数
 * @param samplers 最大 sampler 数
 * @param accessors 最大 accessor 数
 * @param skins 最大 skin 数
 * @param jointsPerSkin 单个 skin 最大 joint 数
 * @param animations 最大 animation 数
 * @param animationChannels 单个 animation 最大 channel 数
 * @param animationKeyframes 所有 animation channel 的最大累计关键帧数
 * @param primitiveVertices 单个 primitive 最大顶点数
 * @param primitiveIndices 单个 primitive 最大索引数
 * @param hierarchyDepth 最大节点层级深度
 * @param dataUriBytes data URI 解码后的最大字节数
 * @param morphTargetsPerPrimitive 单个 primitive 最大 Morph Target 数
 * @param morphDeltaBytes 所有 Morph delta 解码后的最大累计字节数
 */
public record GltfAssetLimits(
        long documentBytes, long decodedBufferBytes, long imageBytes,
        int nodes, int meshes, int primitives, int materials,
        int textures, int images, int samplers, int accessors,
        int skins, int jointsPerSkin, int animations, int animationChannels,
        int animationKeyframes,
        int primitiveVertices, int primitiveIndices, int hierarchyDepth,
        long dataUriBytes, int morphTargetsPerPrimitive, long morphDeltaBytes) {
    public GltfAssetLimits {
        if (documentBytes <= 0 || decodedBufferBytes <= 0 || imageBytes <= 0
                || nodes <= 0 || meshes <= 0 || primitives <= 0 || materials <= 0
                || textures <= 0 || images <= 0 || samplers <= 0 || accessors <= 0
                || skins <= 0 || jointsPerSkin <= 0 || animations <= 0
                || animationChannels <= 0 || animationKeyframes <= 0
                || primitiveVertices <= 0 || primitiveIndices <= 0 || hierarchyDepth <= 0
                || dataUriBytes <= 0 || morphTargetsPerPrimitive <= 0
                || morphDeltaBytes <= 0) {
            throw new IllegalArgumentException("all glTF asset limits must be positive");
        }
    }

    public GltfAssetLimits(
            long documentBytes, long decodedBufferBytes, long imageBytes,
            int nodes, int meshes, int primitives, int materials,
            int textures, int images, int samplers, int accessors,
            int skins, int jointsPerSkin, int animations, int animationChannels,
            int animationKeyframes, int primitiveVertices, int primitiveIndices,
            int hierarchyDepth, long dataUriBytes) {
        this(documentBytes, decodedBufferBytes, imageBytes, nodes, meshes, primitives,
                materials, textures, images, samplers, accessors, skins, jointsPerSkin,
                animations, animationChannels, animationKeyframes, primitiveVertices,
                primitiveIndices, hierarchyDepth, dataUriBytes, 8,
                256L * 1024L * 1024L);
    }

    public static GltfAssetLimits defaults() {
        long mib = 1024L * 1024L;
        return new GltfAssetLimits(64L * mib, 512L * mib, 64L * mib,
                100_000, 50_000, 100_000, 16_384,
                16_384, 16_384, 16_384, 200_000,
                16_384, 4_096, 16_384, 65_536, 10_000_000,
                16_777_216, 50_000_000, 256, 256L * mib,
                8, 256L * mib);
    }

    public GltfAssetLimits withMorphLimits(int targetsPerPrimitive, long deltaBytes) {
        return new GltfAssetLimits(documentBytes, decodedBufferBytes, imageBytes,
                nodes, meshes, primitives, materials, textures, images, samplers,
                accessors, skins, jointsPerSkin, animations, animationChannels,
                animationKeyframes, primitiveVertices, primitiveIndices, hierarchyDepth,
                dataUriBytes, targetsPerPrimitive, deltaBytes);
    }
}
