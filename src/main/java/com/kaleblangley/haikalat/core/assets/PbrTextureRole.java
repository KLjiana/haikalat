package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;

/** metallic-roughness 工作流的五种固定纹理角色。 */
public enum PbrTextureRole {
    BASE_COLOR(TextureColorSpace.SRGB),
    NORMAL(TextureColorSpace.LINEAR),
    METALLIC_ROUGHNESS(TextureColorSpace.LINEAR),
    OCCLUSION(TextureColorSpace.LINEAR),
    EMISSIVE(TextureColorSpace.SRGB);

    private final TextureColorSpace requiredColorSpace;

    PbrTextureRole(TextureColorSpace requiredColorSpace) {
        this.requiredColorSpace = requiredColorSpace;
    }

    public TextureColorSpace requiredColorSpace() {
        return requiredColorSpace;
    }
}
