package com.kaleblangley.haikalat.core.assets;

import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Map;
import java.util.Objects;

/**
 * 不持有 GL 对象的不可变 metallic-roughness 参数。
 *
 * @param baseColorFactor 基础颜色因子
 * @param metallicFactor 金属度因子
 * @param roughnessFactor 粗糙度因子
 * @param normalScale 法线贴图缩放
 * @param occlusionStrength 环境遮蔽强度
 * @param emissiveFactor 自发光因子
 * @param textures 各 PBR 角色对应的纹理资产名称
 * @param thicknessFactor 薄表面归一化厚度 [0,1]，0 表示最薄
 * @param transmissionFactor 背光透射强度 [0,1]，0 关闭
 * @param backlightFactor 背光艺术增益 [0,1]，0 关闭
 */
public record PbrMaterialProperties(
        Vector4f baseColorFactor,
        float metallicFactor,
        float roughnessFactor,
        float normalScale,
        float occlusionStrength,
        Vector3f emissiveFactor,
        Map<PbrTextureRole, String> textures,
        float thicknessFactor,
        float transmissionFactor,
        float backlightFactor
) {
    public PbrMaterialProperties {
        baseColorFactor = new Vector4f(Objects.requireNonNull(baseColorFactor, "baseColorFactor"));
        emissiveFactor = new Vector3f(Objects.requireNonNull(emissiveFactor, "emissiveFactor"));
        textures = Map.copyOf(Objects.requireNonNull(textures, "textures"));
        requireUnitRange(baseColorFactor.x, "baseColorFactor.x");
        requireUnitRange(baseColorFactor.y, "baseColorFactor.y");
        requireUnitRange(baseColorFactor.z, "baseColorFactor.z");
        requireUnitRange(baseColorFactor.w, "baseColorFactor.w");
        requireUnitRange(metallicFactor, "metallicFactor");
        requireUnitRange(roughnessFactor, "roughnessFactor");
        requireFiniteNonNegative(normalScale, "normalScale");
        requireUnitRange(occlusionStrength, "occlusionStrength");
        requireFiniteNonNegative(emissiveFactor.x, "emissiveFactor.x");
        requireFiniteNonNegative(emissiveFactor.y, "emissiveFactor.y");
        requireFiniteNonNegative(emissiveFactor.z, "emissiveFactor.z");
        requireUnitRange(thicknessFactor, "thicknessFactor");
        requireUnitRange(transmissionFactor, "transmissionFactor");
        requireUnitRange(backlightFactor, "backlightFactor");
        for (Map.Entry<PbrTextureRole, String> entry : textures.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "texture role");
            String value = Objects.requireNonNull(entry.getValue(), "texture name").strip();
            if (value.isEmpty()) throw new IllegalArgumentException("PBR texture name must not be blank");
        }
    }

    /** 保留 v0.12 七参数构造契约；薄表面控制默认关闭且不改变原 PBR 输出。 */
    public PbrMaterialProperties(
            Vector4f baseColorFactor,
            float metallicFactor,
            float roughnessFactor,
            float normalScale,
            float occlusionStrength,
            Vector3f emissiveFactor,
            Map<PbrTextureRole, String> textures
    ) {
        this(baseColorFactor, metallicFactor, roughnessFactor, normalScale, occlusionStrength,
                emissiveFactor, textures, 0.0f, 0.0f, 0.0f);
    }

    public static PbrMaterialProperties defaults() {
        return new PbrMaterialProperties(new Vector4f(1.0f), 0.0f, 1.0f,
                1.0f, 1.0f, new Vector3f(), Map.of(), 0.0f, 0.0f, 0.0f);
    }

    @Override
    public Vector4f baseColorFactor() {
        return new Vector4f(baseColorFactor);
    }

    @Override
    public Vector3f emissiveFactor() {
        return new Vector3f(emissiveFactor);
    }

    private static void requireUnitRange(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be finite and in [0, 1]");
        }
    }

    private static void requireFiniteNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
