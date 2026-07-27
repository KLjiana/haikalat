package com.kaleblangley.haikalat.core.assets.gltf;

import java.util.Objects;

/**
 * 不含 OpenGL 状态的 glTF 加载选项。
 *
 * @param scene 场景选择方式
 * @param strictExtensions 是否严格拒绝不受支持的可选扩展 payload；必需扩展始终拒绝
 * @param limits 资产容量限制
 */
public record GltfLoadOptions(SceneSelection scene, boolean strictExtensions, GltfAssetLimits limits) {
    public GltfLoadOptions {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(limits, "limits");
    }

    public static GltfLoadOptions defaults() {
        return new GltfLoadOptions(new SceneSelection.Default(), true, GltfAssetLimits.defaults());
    }
}
