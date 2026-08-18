package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.core.assets.MaterialModel;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.CullMode;
import com.kaleblangley.haikalat.core.BlendMode;

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
                .setInt("uEnableNormalMap", 1)
                .setInt("uHasVertexColor", 0)
                .setInt("uDoubleSided", 0)
                .setFloat("uAlphaCutoff", 0.0f);
        builder.setInt("uAlphaMode", 0);
        bind(builder, 0, "uBaseColorMap", PbrTextureRole.BASE_COLOR, supplied, fallbacks);
        bind(builder, 1, "uNormalMap", PbrTextureRole.NORMAL, supplied, fallbacks);
        bind(builder, 2, "uMetallicRoughnessMap", PbrTextureRole.METALLIC_ROUGHNESS, supplied, fallbacks);
        bind(builder, 3, "uOcclusionMap", PbrTextureRole.OCCLUSION, supplied, fallbacks);
        bind(builder, 4, "uEmissiveMap", PbrTextureRole.EMISSIVE, supplied, fallbacks);
        return builder.build();
    }

    /** 使用每个纹理角色自己的 glTF sampler 创建 PBR material。 */
    public static Material createWithBindings(ShaderProgram shader, PbrMaterialProperties properties,
                                               Map<PbrTextureRole, PbrTextureBinding> supplied,
                                               PbrFallbackTextures fallbacks) {
        return createWithBindings(shader, properties, supplied, fallbacks, CullMode.NONE, false, false);
    }

    /** 创建带 glTF raster/vertex-color 合同的 PBR material。 */
    public static Material createWithBindings(ShaderProgram shader, PbrMaterialProperties properties,
                                               Map<PbrTextureRole, PbrTextureBinding> supplied,
                                               PbrFallbackTextures fallbacks, CullMode cullMode,
                                               boolean doubleSided, boolean hasVertexColor) {
        return createWithBindings(shader, properties, supplied, fallbacks, cullMode,
                doubleSided, hasVertexColor, 0.0f);
    }

    /** 创建带 glTF MASK cutoff 的 PBR material；零表示 OPAQUE。 */
    public static Material createWithBindings(ShaderProgram shader, PbrMaterialProperties properties,
                                               Map<PbrTextureRole, PbrTextureBinding> supplied,
                                               PbrFallbackTextures fallbacks, CullMode cullMode,
                                               boolean doubleSided, boolean hasVertexColor,
                                               float alphaCutoff) {
        return createWithBindings(shader, properties, supplied, fallbacks, cullMode,
                doubleSided, hasVertexColor, alphaCutoff, BlendMode.OPAQUE);
    }

    /** Creates a glTF PBR material with an explicit raster alpha policy. */
    public static Material createWithBindings(ShaderProgram shader, PbrMaterialProperties properties,
                                               Map<PbrTextureRole, PbrTextureBinding> supplied,
                                               PbrFallbackTextures fallbacks, CullMode cullMode,
                                               boolean doubleSided, boolean hasVertexColor,
                                               float alphaCutoff, BlendMode blendMode) {
        if (!Float.isFinite(alphaCutoff) || alphaCutoff < 0.0f || alphaCutoff > 1.0f) {
            throw new IllegalArgumentException("alphaCutoff must be finite and in [0, 1]");
        }
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(properties, "properties");
        supplied = Map.copyOf(Objects.requireNonNull(supplied, "supplied"));
        Objects.requireNonNull(fallbacks, "fallbacks");
        Material.Builder builder = baseBuilder(shader, properties)
                .cullMode(cullMode)
                .blendMode(Objects.requireNonNull(blendMode, "blendMode"))
                .setInt("uDoubleSided", doubleSided ? 1 : 0)
                .setInt("uHasVertexColor", hasVertexColor ? 1 : 0)
                .setFloat("uAlphaCutoff", alphaCutoff)
                .setInt("uAlphaMode", blendMode == BlendMode.ALPHA ? 2
                        : alphaCutoff > 0.0f ? 1 : 0);
        bindWithSampler(builder, 0, "uBaseColorMap", PbrTextureRole.BASE_COLOR, supplied, fallbacks);
        bindWithSampler(builder, 1, "uNormalMap", PbrTextureRole.NORMAL, supplied, fallbacks);
        bindWithSampler(builder, 2, "uMetallicRoughnessMap", PbrTextureRole.METALLIC_ROUGHNESS, supplied, fallbacks);
        bindWithSampler(builder, 3, "uOcclusionMap", PbrTextureRole.OCCLUSION, supplied, fallbacks);
        bindWithSampler(builder, 4, "uEmissiveMap", PbrTextureRole.EMISSIVE, supplied, fallbacks);
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

    private static Material.Builder baseBuilder(ShaderProgram shader, PbrMaterialProperties properties) {
        return Material.builder(shader)
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
                .setInt("uEnableNormalMap", 1)
                .setInt("uHasVertexColor", 0)
                .setInt("uDoubleSided", 0)
                .setFloat("uAlphaCutoff", 0.0f);
    }

    private static void bindWithSampler(Material.Builder builder, int unit, String samplerName,
                                        PbrTextureRole role,
                                        Map<PbrTextureRole, PbrTextureBinding> supplied,
                                        PbrFallbackTextures fallbacks) {
        PbrTextureBinding binding = supplied.get(role);
        Texture2D texture = binding == null ? fallbacks.texture(role) : binding.texture();
        Sampler sampler = binding == null ? fallbacks.sampler() : binding.sampler();
        if (texture.colorSpace() != role.requiredColorSpace()) {
            throw new IllegalArgumentException(role + " requires " + role.requiredColorSpace()
                    + " texture, got " + texture.colorSpace());
        }
        builder.texture(unit, samplerName, texture, sampler);
    }
}
