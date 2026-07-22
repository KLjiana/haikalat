package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.backend.GlException;

import java.util.Map;

/** 校验两种受支持场景配置语法共用的资源引用。 */
final class SceneReferenceValidator {
    private SceneReferenceValidator() {
    }

    static void validate(Map<String, ShaderAsset> shaders,
                         Map<String, SceneAssetConfig.TextureDef> textures,
                         Map<String, MaterialDef> materials,
                         Map<String, SceneAssetConfig.ModelDef> models,
                         Map<String, SceneAssetConfig.ObjectDef> objects) {
        for (Map.Entry<String, MaterialDef> entry : materials.entrySet()) {
            String materialName = entry.getKey();
            MaterialDef material = entry.getValue();
            if (!shaders.containsKey(material.shader())) {
                throw new GlException("material." + materialName
                        + " references missing shader: " + material.shader());
            }
            for (MaterialDef.TextureBinding binding : material.textures()) {
                if (!textures.containsKey(binding.texture())) {
                    throw new GlException("material." + materialName + ".texture."
                            + binding.samplerName() + " references missing texture: " + binding.texture());
                }
            }
            validatePbrTextureReferences(materialName, material, textures);
        }
        for (Map.Entry<String, SceneAssetConfig.ObjectDef> entry : objects.entrySet()) {
            String objectName = entry.getKey();
            SceneAssetConfig.ObjectDef object = entry.getValue();
            if (!materials.containsKey(object.material())) {
                throw new GlException("object." + objectName
                        + " references missing material: " + object.material());
            }
            if (!object.builtinMesh() && !models.containsKey(object.model())) {
                throw new GlException("object." + objectName
                        + " references missing model: " + object.model());
            }
        }
    }

    private static void validatePbrTextureReferences(String materialName, MaterialDef material,
                                                     Map<String, SceneAssetConfig.TextureDef> textures) {
        if (material.model() != MaterialModel.METALLIC_ROUGHNESS) {
            return;
        }
        if (!"pbrForward".equals(material.shader())) {
            throw new GlException("material." + materialName
                    + " metallic-roughness contract requires shader=pbrForward, got "
                    + material.shader());
        }
        for (Map.Entry<PbrTextureRole, String> role : material.pbr().textures().entrySet()) {
            SceneAssetConfig.TextureDef texture = textures.get(role.getValue());
            if (texture == null) {
                throw new GlException("material." + materialName + ".pbr." + role.getKey()
                        + " references missing texture: " + role.getValue());
            }
            if (texture.colorSpace() != role.getKey().requiredColorSpace()) {
                throw new GlException("material." + materialName + " texture role " + role.getKey()
                        + " references " + role.getValue() + " with color space "
                        + texture.colorSpace() + "; required " + role.getKey().requiredColorSpace());
            }
        }
    }
}
