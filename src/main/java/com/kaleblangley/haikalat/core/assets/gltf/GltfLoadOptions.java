package com.kaleblangley.haikalat.core.assets.gltf;

import java.util.Objects;

/** 不含 OpenGL 状态的 glTF 加载选项。 */
public record GltfLoadOptions(SceneSelection scene, boolean strictExtensions, GltfAssetLimits limits) {
    public GltfLoadOptions {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(limits, "limits");
    }

    public static GltfLoadOptions defaults() {
        return new GltfLoadOptions(new SceneSelection.Default(), true, GltfAssetLimits.defaults());
    }
}
