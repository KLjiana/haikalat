package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;

/** metallic-roughness 工作流的固定纹理角色。 */
public enum PbrTextureRole {
    BASE_COLOR(TextureColorSpace.SRGB),
    NORMAL(TextureColorSpace.LINEAR),
    METALLIC_ROUGHNESS(TextureColorSpace.LINEAR),
    OCCLUSION(TextureColorSpace.LINEAR),
    EMISSIVE(TextureColorSpace.SRGB),
    /**
     * Height data for parallax occlusion mapping. It is scalar linear data, not
     * a colour; the sampled channel and the optional inversion are material
     * settings, not texture-role properties. The shared missing-map fallback is
     * RGBA {@code (0,0,0,0)}, so every channel is a flat surface unless the
     * material flips the height.
     */
    HEIGHT(TextureColorSpace.LINEAR);

    private final TextureColorSpace requiredColorSpace;

    PbrTextureRole(TextureColorSpace requiredColorSpace) {
        this.requiredColorSpace = requiredColorSpace;
    }

    public TextureColorSpace requiredColorSpace() {
        return requiredColorSpace;
    }
}
