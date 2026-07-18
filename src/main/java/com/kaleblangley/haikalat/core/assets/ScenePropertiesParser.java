package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;
import com.kaleblangley.haikalat.core.BlendMode;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/** Parses the supported {@code .properties} scene-manifest syntax. */
final class ScenePropertiesParser {
    private ScenePropertiesParser() {
    }

    static SceneAssetConfig parse(String source) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(Objects.requireNonNull(source, "source")));
        } catch (IOException e) {
            throw new GlException("Invalid scene properties", e);
        }

        Map<String, ShaderAsset> shaders = new LinkedHashMap<>();
        Map<String, SceneAssetConfig.TextureDef> textures = new LinkedHashMap<>();
        Map<String, MaterialDef> materials = new LinkedHashMap<>();
        Map<String, SceneAssetConfig.ModelDef> models = new LinkedHashMap<>();
        Map<String, SceneAssetConfig.ObjectDef> objects = new LinkedHashMap<>();
        Map<String, SceneAssetConfig.LightDef> lights = new LinkedHashMap<>();
        Map<String, SceneAssetConfig.GltfSceneDef> gltfScenes = new LinkedHashMap<>();

        for (String name : names(properties, "shader.")) {
            shaders.put(name, shaderAsset(properties, name));
        }
        for (String name : names(properties, "texture.")) {
            String prefix = "texture." + name + ".";
            textures.put(name, new SceneAssetConfig.TextureDef(
                    AssetRef.of(required(properties, prefix + "path")),
                    SceneValueParser.booleanValue(properties, prefix + "flipVertically", true),
                    SceneValueParser.colorSpace(properties.getProperty(prefix + "colorSpace", "linear"))));
        }
        for (String name : names(properties, "material.")) {
            materials.put(name, material(properties, name));
        }
        for (String name : names(properties, "model.")) {
            models.put(name, new SceneAssetConfig.ModelDef(
                    AssetRef.of(required(properties, "model." + name + ".path"))));
        }
        for (String name : names(properties, "object.")) {
            String prefix = "object." + name + ".";
            objects.put(name, new SceneAssetConfig.ObjectDef(
                    required(properties, prefix + "model"),
                    required(properties, prefix + "material"),
                    SceneValueParser.vector3(required(properties, prefix + "position"),
                            prefix + "position"),
                    SceneValueParser.vector3(required(properties, prefix + "rotation"),
                            prefix + "rotation"),
                    SceneValueParser.floatValue(properties, prefix + "scale", 1.0f),
                    SceneValueParser.booleanValue(properties, prefix + "castShadows", true)));
        }
        for (String name : names(properties, "light.")) {
            String prefix = "light." + name + ".";
            lights.put(name, new SceneAssetConfig.LightDef(
                    required(properties, prefix + "type"),
                    SceneValueParser.vector3(required(properties, prefix + "vector"),
                            prefix + "vector"),
                    SceneValueParser.vector3(required(properties, prefix + "color"),
                            prefix + "color"),
                    SceneValueParser.floatValue(properties, prefix + "intensity", 1.0f),
                    SceneValueParser.floatValue(properties, prefix + "range", 0.0f),
                    SceneValueParser.booleanValue(properties, prefix + "castShadows", false)));
        }
        for (String name : names(properties, "gltf.")) {
            String prefix = "gltf." + name + ".";
            rejectUnknownGltfKeys(properties, prefix);
            gltfScenes.put(name, new SceneAssetConfig.GltfSceneDef(
                    AssetRef.of(required(properties, prefix + "path")),
                    properties.getProperty(prefix + "scene", "default").strip(),
                    SceneValueParser.vector3(required(properties, prefix + "position"),
                            prefix + "position"),
                    SceneValueParser.vector3(required(properties, prefix + "rotation"),
                            prefix + "rotation"),
                    SceneValueParser.floatValue(properties, prefix + "scale", 1.0f),
                    SceneValueParser.booleanValue(properties, prefix + "castShadows", true)));
        }
        return new SceneAssetConfig(shaders, textures, materials, models, objects, lights, gltfScenes);
    }

    private static Set<String> names(Properties properties, String prefix) {
        Set<String> names = new TreeSet<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(prefix)) continue;
            String rest = key.substring(prefix.length());
            int dot = rest.indexOf('.');
            if (dot > 0) names.add(rest.substring(0, dot));
        }
        return names;
    }

    private static MaterialDef material(Properties properties, String name) {
        String prefix = "material." + name + ".";
        String shader = required(properties, prefix + "shader");
        BlendMode blendMode = SceneValueParser.enumValue(BlendMode.class,
                properties.getProperty(prefix + "blend", "OPAQUE"), prefix + "blend");
        boolean depthTest = SceneValueParser.booleanValue(properties, prefix + "depthTest", true);
        MaterialModel model = parseMaterialModel(properties.getProperty(prefix + "model", "legacy"));
        if (model == MaterialModel.METALLIC_ROUGHNESS) {
            rejectUnknownPbrKeys(properties, prefix);
            return new MaterialDef(shader, List.of(), blendMode, depthTest, model,
                    pbrProperties(properties, prefix));
        }
        rejectLegacyPbrKeys(properties, prefix);
        List<MaterialDef.TextureBinding> bindings = new ArrayList<>();
        for (String samplerName : textureBindingNames(properties, prefix + "texture.")) {
            String bindingPrefix = prefix + "texture." + samplerName;
            String texture = required(properties, bindingPrefix);
            int unit = SceneValueParser.intValue(properties, bindingPrefix + ".unit", 0);
            String sampler = properties.getProperty(bindingPrefix + ".sampler");
            bindings.add(new MaterialDef.TextureBinding(unit, samplerName, texture, sampler));
        }
        return new MaterialDef(shader, bindings, blendMode, depthTest);
    }

    private static MaterialModel parseMaterialModel(String value) {
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "legacy" -> MaterialModel.LEGACY;
            case "metallicroughness", "metallic_roughness", "metallic-roughness" ->
                    MaterialModel.METALLIC_ROUGHNESS;
            default -> throw new GlException("Unsupported material model: " + value);
        };
    }

    private static PbrMaterialProperties pbrProperties(Properties properties, String materialPrefix) {
        String prefix = materialPrefix + "pbr.";
        Vector4f baseColor = SceneValueParser.vector4(
                properties.getProperty(prefix + "baseColorFactor", "1,1,1,1"),
                prefix + "baseColorFactor");
        float metallic = SceneValueParser.floatValue(properties, prefix + "metallicFactor", 0.0f);
        float roughness = SceneValueParser.floatValue(properties, prefix + "roughnessFactor", 1.0f);
        float normalScale = SceneValueParser.floatValue(properties, prefix + "normalScale", 1.0f);
        float occlusion = SceneValueParser.floatValue(properties, prefix + "occlusionStrength", 1.0f);
        Vector3f emissive = SceneValueParser.vector3(
                properties.getProperty(prefix + "emissiveFactor", "0,0,0"),
                prefix + "emissiveFactor");
        EnumMap<PbrTextureRole, String> roleTextures = new EnumMap<>(PbrTextureRole.class);
        addRoleTexture(properties, prefix, "baseColorTexture", PbrTextureRole.BASE_COLOR, roleTextures);
        addRoleTexture(properties, prefix, "normalTexture", PbrTextureRole.NORMAL, roleTextures);
        addRoleTexture(properties, prefix, "metallicRoughnessTexture",
                PbrTextureRole.METALLIC_ROUGHNESS, roleTextures);
        addRoleTexture(properties, prefix, "occlusionTexture", PbrTextureRole.OCCLUSION, roleTextures);
        addRoleTexture(properties, prefix, "emissiveTexture", PbrTextureRole.EMISSIVE, roleTextures);
        try {
            return new PbrMaterialProperties(baseColor, metallic, roughness, normalScale,
                    occlusion, emissive, roleTextures);
        } catch (IllegalArgumentException error) {
            throw new GlException("Invalid PBR parameters for "
                    + materialPrefix.substring(0, materialPrefix.length() - 1)
                    + ": " + error.getMessage(), error);
        }
    }

    private static void addRoleTexture(Properties properties, String prefix, String suffix,
                                       PbrTextureRole role, Map<PbrTextureRole, String> output) {
        String value = properties.getProperty(prefix + suffix);
        if (value != null && !value.isBlank()) output.put(role, value.strip());
    }

    private static void rejectLegacyPbrKeys(Properties properties, String materialPrefix) {
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(materialPrefix + "pbr.")) {
                throw new GlException(key + " is only valid for model=metallicRoughness");
            }
        }
    }

    private static void rejectUnknownPbrKeys(Properties properties, String materialPrefix) {
        Set<String> allowed = Set.of("baseColorFactor", "metallicFactor", "roughnessFactor",
                "normalScale", "occlusionStrength", "emissiveFactor", "baseColorTexture",
                "normalTexture", "metallicRoughnessTexture", "occlusionTexture", "emissiveTexture");
        String prefix = materialPrefix + "pbr.";
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(prefix) && !allowed.contains(key.substring(prefix.length()))) {
                throw new GlException("Unknown PBR material property: " + key);
            }
            if (key.startsWith(materialPrefix + "texture.")) {
                throw new GlException("PBR material cannot use generic sampler binding: " + key);
            }
        }
    }

    private static ShaderAsset shaderAsset(Properties properties, String name) {
        String prefix = "shader." + name + ".";
        EnumMap<ShaderStage, AssetRef> stages = new EnumMap<>(ShaderStage.class);
        addStage(properties, prefix, "vertex", ShaderStage.VERTEX, stages);
        addStage(properties, prefix, "tessControl", ShaderStage.TESS_CONTROL, stages);
        addStage(properties, prefix, "tessEvaluation", ShaderStage.TESS_EVALUATION, stages);
        addStage(properties, prefix, "geometry", ShaderStage.GEOMETRY, stages);
        addStage(properties, prefix, "fragment", ShaderStage.FRAGMENT, stages);
        addStage(properties, prefix, "compute", ShaderStage.COMPUTE, stages);
        if (stages.isEmpty()) throw new GlException("Shader has no recognized stages: " + name);
        return new ShaderAsset(stages);
    }

    private static void addStage(Properties properties, String prefix, String suffix,
                                 ShaderStage stage, Map<ShaderStage, AssetRef> stages) {
        String path = properties.getProperty(prefix + suffix);
        if (path != null && !path.isBlank()) stages.put(stage, AssetRef.of(path.strip()));
    }

    private static Set<String> textureBindingNames(Properties properties, String prefix) {
        Set<String> names = new TreeSet<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(prefix)) continue;
            String rest = key.substring(prefix.length());
            int dot = rest.indexOf('.');
            String name = dot < 0 ? rest : rest.substring(0, dot);
            if (!name.isBlank()) names.add(name);
        }
        return names;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new GlException("Missing required scene property: " + key);
        }
        return value.strip();
    }

    private static void rejectUnknownGltfKeys(Properties properties, String prefix) {
        Set<String> allowed = Set.of("path", "scene", "position", "rotation", "scale", "castShadows");
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(prefix) && !allowed.contains(key.substring(prefix.length()))) {
                throw new GlException("Unknown glTF scene property: " + key);
            }
        }
    }
}
