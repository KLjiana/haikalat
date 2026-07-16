package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.assets.MaterialModel;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;
import com.kaleblangley.haikalat.core.material.Material;

import java.util.Map;
import java.util.Objects;

/** 将不可变 PBR 定义装配为通用 runtime Material 的便利入口。 */
public final class PbrMaterials {
    private PbrMaterials() {
    }

    public static Material create(ShaderProgram shader, PbrMaterialProperties properties,
                                  Map<PbrTextureRole, Texture2D> supplied,
                                  PbrFallbackTextures fallbacks) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(properties, "properties");
        supplied = Map.copyOf(Objects.requireNonNull(supplied, "supplied"));
        Objects.requireNonNull(fallbacks, "fallbacks");
        Material.Builder builder = Material.builder(shader)
                .model(MaterialModel.METALLIC_ROUGHNESS)
                .setVec4("uBaseColorFactor", properties.baseColorFactor())
                .setFloat("uMetallicFactor", properties.metallicFactor())
                .setFloat("uRoughnessFactor", properties.roughnessFactor())
                .setFloat("uNormalScale", properties.normalScale())
                .setFloat("uOcclusionStrength", properties.occlusionStrength())
                .setVec3("uEmissiveFactor", properties.emissiveFactor())
                .setInt("uEnableDirect", 1)
                .setInt("uEnableDiffuseIbl", 1)
                .setInt("uEnableSpecularIbl", 1)
                .setInt("uEnableNormalMap", 1);
        bind(builder, 0, "uBaseColorMap", PbrTextureRole.BASE_COLOR, supplied, fallbacks);
        bind(builder, 1, "uNormalMap", PbrTextureRole.NORMAL, supplied, fallbacks);
        bind(builder, 2, "uMetallicRoughnessMap", PbrTextureRole.METALLIC_ROUGHNESS, supplied, fallbacks);
        bind(builder, 3, "uOcclusionMap", PbrTextureRole.OCCLUSION, supplied, fallbacks);
        bind(builder, 4, "uEmissiveMap", PbrTextureRole.EMISSIVE, supplied, fallbacks);
        return builder.build();
    }

    private static void bind(Material.Builder builder, int unit, String samplerName,
                             PbrTextureRole role, Map<PbrTextureRole, Texture2D> supplied,
                             PbrFallbackTextures fallbacks) {
        Texture2D texture = supplied.getOrDefault(role, fallbacks.texture(role));
        if (texture.colorSpace() != role.requiredColorSpace()) {
            throw new IllegalArgumentException(role + " requires " + role.requiredColorSpace()
                    + " texture, got " + texture.colorSpace());
        }
        builder.texture(unit, samplerName, texture, fallbacks.sampler());
    }
}
