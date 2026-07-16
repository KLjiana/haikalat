package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.core.BlendMode;

import java.util.List;
import java.util.Objects;

/**
 * 用于资产和场景配置的非 OpenGL 材质定义。
 * runtime {@code Material} 会在之后根据这些 shader/texture 引用构建。
 */
public record MaterialDef(
        String shader,
        List<TextureBinding> textures,
        BlendMode blendMode,
        boolean depthTest,
        MaterialModel model,
        PbrMaterialProperties pbr
) {
    public MaterialDef {
        shader = Objects.requireNonNull(shader, "shader");
        textures = List.copyOf(Objects.requireNonNull(textures, "textures"));
        blendMode = Objects.requireNonNull(blendMode, "blendMode");
        model = Objects.requireNonNull(model, "model");
        if (model == MaterialModel.LEGACY && pbr != null) {
            throw new IllegalArgumentException("LEGACY material cannot contain PBR properties");
        }
        if (model == MaterialModel.METALLIC_ROUGHNESS) {
            if (pbr == null) throw new IllegalArgumentException("METALLIC_ROUGHNESS material requires PBR properties");
            if (blendMode != BlendMode.OPAQUE) {
                throw new IllegalArgumentException("METALLIC_ROUGHNESS material requires opaque blend mode");
            }
            if (!textures.isEmpty()) {
                throw new IllegalArgumentException("PBR material cannot override fixed units with generic texture bindings");
            }
        }
    }

    /** 保留 v0.11 构造契约；缺少 model 时始终为 legacy。 */
    public MaterialDef(String shader, List<TextureBinding> textures, BlendMode blendMode, boolean depthTest) {
        this(shader, textures, blendMode, depthTest, MaterialModel.LEGACY, null);
    }

    public static MaterialDef of(String shader) {
        return new MaterialDef(shader, List.of(), BlendMode.OPAQUE, true);
    }

    public static MaterialDef metallicRoughness(String shader, boolean depthTest,
                                                PbrMaterialProperties properties) {
        return new MaterialDef(shader, List.of(), BlendMode.OPAQUE, depthTest,
                MaterialModel.METALLIC_ROUGHNESS, properties);
    }

    public record TextureBinding(int unit, String samplerName, String texture, String sampler) {
        public TextureBinding {
            if (unit < 0) {
                throw new IllegalArgumentException("unit must be non-negative");
            }
            samplerName = Objects.requireNonNull(samplerName, "samplerName");
            texture = Objects.requireNonNull(texture, "texture");
        }

        public TextureBinding(int unit, String samplerName, String texture) {
            this(unit, samplerName, texture, null);
        }
    }
}
