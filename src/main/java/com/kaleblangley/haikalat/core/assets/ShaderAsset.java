package com.kaleblangley.haikalat.core.assets;

import java.util.Objects;

public record ShaderAsset(AssetRef vertexShader, AssetRef fragmentShader) {
    public ShaderAsset {
        vertexShader = Objects.requireNonNull(vertexShader, "vertexShader");
        fragmentShader = Objects.requireNonNull(fragmentShader, "fragmentShader");
    }

    public static ShaderAsset of(String vertexShader, String fragmentShader) {
        return new ShaderAsset(AssetRef.of(vertexShader), AssetRef.of(fragmentShader));
    }
}
