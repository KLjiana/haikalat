package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.EnumMap;

/**
 * 资产 manifest 与小型 Demo 场景 manifest。
 *
 * <p>稳定的资产字段包括 {@code shader.*}、{@code texture.*}、{@code model.*} 和
 * {@code material.*}，用于描述命名资产与绑定关系，但不持有 OpenGL 对象。</p>
 *
 * <p>Demo 场景便捷字段包括 {@code object.*} 和 {@code light.*}，刻意限制为简单的
 * object/material/light 绑定与静态变换。动画 updater、procedural geometry、复杂内建网格变体、
 * 脚本和编辑器数据继续保留在 Java 代码中。</p>
 */
public record SceneAssetConfig(
        Map<String, ShaderAsset> shaders,
        Map<String, TextureDef> textures,
        Map<String, MaterialDef> materials,
        Map<String, ModelDef> models,
        Map<String, ObjectDef> objects,
        Map<String, LightDef> lights
) {
    public SceneAssetConfig {
        shaders = Map.copyOf(Objects.requireNonNull(shaders, "shaders"));
        textures = Map.copyOf(Objects.requireNonNull(textures, "textures"));
        materials = Map.copyOf(Objects.requireNonNull(materials, "materials"));
        models = Map.copyOf(Objects.requireNonNull(models, "models"));
        objects = Map.copyOf(Objects.requireNonNull(objects, "objects"));
        lights = Map.copyOf(Objects.requireNonNull(lights, "lights"));
        validateReferences(shaders, textures, materials, models, objects);
    }

    public static SceneAssetConfig load(ResourceLocator locator, String path) {
        String source = locator.readString(AssetRef.of(path));
        if (path.endsWith(".properties")) {
            return parseProperties(source);
        }
        return parse(source);
    }

    public static SceneAssetConfig parseProperties(String source) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(Objects.requireNonNull(source, "source")));
        } catch (IOException e) {
            throw new GlException("Invalid scene properties", e);
        }

        Map<String, ShaderAsset> shaders = new LinkedHashMap<>();
        Map<String, TextureDef> textures = new LinkedHashMap<>();
        Map<String, MaterialDef> materials = new LinkedHashMap<>();
        Map<String, ModelDef> models = new LinkedHashMap<>();
        Map<String, ObjectDef> objects = new LinkedHashMap<>();
        Map<String, LightDef> lights = new LinkedHashMap<>();

        for (String name : names(properties, "shader.")) {
            shaders.put(name, shaderAsset(properties, name));
        }
        for (String name : names(properties, "texture.")) {
            textures.put(name, new TextureDef(AssetRef.of(required(properties, "texture." + name + ".path")),
                    Boolean.parseBoolean(properties.getProperty("texture." + name + ".flipVertically", "true")),
                    parseColorSpace(properties.getProperty("texture." + name + ".colorSpace", "linear"))));
        }
        for (String name : names(properties, "material.")) {
            materials.put(name, material(properties, name));
        }
        for (String name : names(properties, "model.")) {
            models.put(name, new ModelDef(AssetRef.of(required(properties, "model." + name + ".path"))));
        }
        for (String name : names(properties, "object.")) {
            objects.put(name, new ObjectDef(
                    required(properties, "object." + name + ".model"),
                    required(properties, "object." + name + ".material"),
                    vector(properties, "object." + name + ".position"),
                    vector(properties, "object." + name + ".rotation"),
                    Float.parseFloat(properties.getProperty("object." + name + ".scale", "1")),
                    Boolean.parseBoolean(properties.getProperty("object." + name + ".castShadows", "true"))));
        }
        for (String name : names(properties, "light.")) {
            lights.put(name, new LightDef(
                    required(properties, "light." + name + ".type"),
                    vector(properties, "light." + name + ".vector"),
                    vector(properties, "light." + name + ".color"),
                    Float.parseFloat(properties.getProperty("light." + name + ".intensity", "1")),
                    Float.parseFloat(properties.getProperty("light." + name + ".range", "0")),
                    Boolean.parseBoolean(properties.getProperty("light." + name + ".castShadows", "false"))));
        }

        return new SceneAssetConfig(shaders, textures, materials, models, objects, lights);
    }

    public static SceneAssetConfig parse(String source) {
        Map<String, ShaderAsset> shaders = new LinkedHashMap<>();
        Map<String, TextureDef> textures = new LinkedHashMap<>();
        Map<String, MaterialDef> materials = new LinkedHashMap<>();
        Map<String, ModelDef> models = new LinkedHashMap<>();
        Map<String, ObjectDef> objects = new LinkedHashMap<>();
        Map<String, LightDef> lights = new LinkedHashMap<>();

        String[] lines = Objects.requireNonNull(source, "source").split("\\R");
        for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
            String line = stripComment(lines[lineNumber]).strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            try {
                switch (parts[0]) {
                    case "shader" -> shaders.put(parts[1], ShaderAsset.of(parts[2], parts[3]));
                    case "texture" -> textures.put(parts[1], new TextureDef(AssetRef.of(parts[2]),
                            parts.length < 4 || Boolean.parseBoolean(parts[3]),
                            parts.length < 5 ? TextureColorSpace.LINEAR : parseColorSpace(parts[4])));
                    case "material" -> materials.put(parts[1], parseMaterial(parts));
                    case "model" -> models.put(parts[1], new ModelDef(AssetRef.of(parts[2])));
                    case "object" -> objects.put(parts[1], parseObject(parts));
                    case "light" -> lights.put(parts[1], parseLight(parts));
                    default -> throw new GlException("Unknown scene config directive: " + parts[0]);
                }
            } catch (RuntimeException e) {
                throw new GlException("Invalid scene config at line " + (lineNumber + 1) + ": " + lines[lineNumber], e);
            }
        }
        return new SceneAssetConfig(shaders, textures, materials, models, objects, lights);
    }

    private static MaterialDef parseMaterial(String[] parts) {
        requireLength(parts, 3);
        BlendMode blendMode = parts.length >= 4 ? BlendMode.valueOf(parts[3].toUpperCase()) : BlendMode.OPAQUE;
        boolean depthTest = parts.length < 5 || Boolean.parseBoolean(parts[4]);
        return new MaterialDef(parts[2], List.of(), blendMode, depthTest);
    }

    private static ObjectDef parseObject(String[] parts) {
        requireLength(parts, 12);
        return new ObjectDef(
                parts[2],
                parts[3],
                new Vector3f(Float.parseFloat(parts[4]), Float.parseFloat(parts[5]), Float.parseFloat(parts[6])),
                new Vector3f(Float.parseFloat(parts[7]), Float.parseFloat(parts[8]), Float.parseFloat(parts[9])),
                Float.parseFloat(parts[10]),
                Boolean.parseBoolean(parts[11]));
    }

    private static LightDef parseLight(String[] parts) {
        requireLength(parts, 12);
        return new LightDef(
                parts[2],
                new Vector3f(Float.parseFloat(parts[3]), Float.parseFloat(parts[4]), Float.parseFloat(parts[5])),
                new Vector3f(Float.parseFloat(parts[6]), Float.parseFloat(parts[7]), Float.parseFloat(parts[8])),
                Float.parseFloat(parts[9]),
                Float.parseFloat(parts[10]),
                Boolean.parseBoolean(parts[11]));
    }

    private static void requireLength(String[] parts, int length) {
        if (parts.length < length) {
            throw new GlException("Expected at least " + length + " tokens");
        }
    }

    private static TextureColorSpace parseColorSpace(String value) {
        try {
            return TextureColorSpace.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new GlException("Unsupported texture color space: " + value
                    + ". Supported values: linear, srgb", error);
        }
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static Set<String> names(Properties properties, String prefix) {
        Set<String> names = new TreeSet<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String rest = key.substring(prefix.length());
            int dot = rest.indexOf('.');
            if (dot > 0) {
                names.add(rest.substring(0, dot));
            }
        }
        return names;
    }

    private static MaterialDef material(Properties properties, String name) {
        String prefix = "material." + name + ".";
        String shader = required(properties, prefix + "shader");
        BlendMode blendMode = BlendMode.valueOf(properties.getProperty(prefix + "blend", "OPAQUE").toUpperCase());
        boolean depthTest = Boolean.parseBoolean(properties.getProperty(prefix + "depthTest", "true"));
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
            int unit = Integer.parseInt(properties.getProperty(bindingPrefix + ".unit", "0"));
            String sampler = properties.getProperty(bindingPrefix + ".sampler");
            bindings.add(new MaterialDef.TextureBinding(unit, samplerName, texture, sampler));
        }
        return new MaterialDef(shader, bindings, blendMode, depthTest);
    }

    private static MaterialModel parseMaterialModel(String value) {
        return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "legacy" -> MaterialModel.LEGACY;
            case "metallicroughness", "metallic_roughness", "metallic-roughness" ->
                    MaterialModel.METALLIC_ROUGHNESS;
            default -> throw new GlException("Unsupported material model: " + value);
        };
    }

    private static PbrMaterialProperties pbrProperties(Properties properties, String materialPrefix) {
        String prefix = materialPrefix + "pbr.";
        Vector4f baseColor = vector4(properties.getProperty(prefix + "baseColorFactor", "1,1,1,1"),
                prefix + "baseColorFactor");
        float metallic = floatValue(properties, prefix + "metallicFactor", 0.0f);
        float roughness = floatValue(properties, prefix + "roughnessFactor", 1.0f);
        float normalScale = floatValue(properties, prefix + "normalScale", 1.0f);
        float occlusion = floatValue(properties, prefix + "occlusionStrength", 1.0f);
        Vector3f emissive = vector3(properties.getProperty(prefix + "emissiveFactor", "0,0,0"),
                prefix + "emissiveFactor");
        EnumMap<PbrTextureRole, String> roleTextures = new EnumMap<>(PbrTextureRole.class);
        addRoleTexture(properties, prefix, "baseColorTexture", PbrTextureRole.BASE_COLOR, roleTextures);
        addRoleTexture(properties, prefix, "normalTexture", PbrTextureRole.NORMAL, roleTextures);
        addRoleTexture(properties, prefix, "metallicRoughnessTexture", PbrTextureRole.METALLIC_ROUGHNESS,
                roleTextures);
        addRoleTexture(properties, prefix, "occlusionTexture", PbrTextureRole.OCCLUSION, roleTextures);
        addRoleTexture(properties, prefix, "emissiveTexture", PbrTextureRole.EMISSIVE, roleTextures);
        try {
            return new PbrMaterialProperties(baseColor, metallic, roughness, normalScale,
                    occlusion, emissive, roleTextures);
        } catch (IllegalArgumentException error) {
            throw new GlException("Invalid PBR parameters for " + materialPrefix.substring(0,
                    materialPrefix.length() - 1) + ": " + error.getMessage(), error);
        }
    }

    private static void addRoleTexture(Properties properties, String prefix, String suffix,
                                       PbrTextureRole role, Map<PbrTextureRole, String> output) {
        String value = properties.getProperty(prefix + suffix);
        if (value != null && !value.isBlank()) output.put(role, value.strip());
    }

    private static float floatValue(Properties properties, String key, float fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Float.parseFloat(value.strip());
    }

    private static Vector4f vector4(String value, String key) {
        String[] parts = value.split("\\s*,\\s*");
        if (parts.length != 4) throw new GlException("Expected vector property with 4 components: " + key);
        return new Vector4f(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]),
                Float.parseFloat(parts[2]), Float.parseFloat(parts[3]));
    }

    private static Vector3f vector3(String value, String key) {
        String[] parts = value.split("\\s*,\\s*");
        if (parts.length != 3) throw new GlException("Expected vector property with 3 components: " + key);
        return new Vector3f(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]),
                Float.parseFloat(parts[2]));
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
            if (!key.startsWith(prefix)) {
                continue;
            }
            String rest = key.substring(prefix.length());
            int dot = rest.indexOf('.');
            String name = dot < 0 ? rest : rest.substring(0, dot);
            if (!name.isBlank()) {
                names.add(name);
            }
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

    private static Vector3f vector(Properties properties, String key) {
        String[] parts = required(properties, key).split("\\s*,\\s*");
        if (parts.length != 3) {
            throw new GlException("Expected vector property with 3 components: " + key);
        }
        return new Vector3f(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
    }

    private static void validateReferences(Map<String, ShaderAsset> shaders,
                                           Map<String, TextureDef> textures,
                                           Map<String, MaterialDef> materials,
                                           Map<String, ModelDef> models,
                                           Map<String, ObjectDef> objects) {
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
            if (material.model() == MaterialModel.METALLIC_ROUGHNESS) {
                if (!"pbrForward".equals(material.shader())) {
                    throw new GlException("material." + materialName
                            + " metallic-roughness contract requires shader=pbrForward, got "
                            + material.shader());
                }
                for (Map.Entry<PbrTextureRole, String> role : material.pbr().textures().entrySet()) {
                    TextureDef texture = textures.get(role.getValue());
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
        for (Map.Entry<String, ObjectDef> entry : objects.entrySet()) {
            String objectName = entry.getKey();
            ObjectDef object = entry.getValue();
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

    public record TextureDef(AssetRef path, boolean flipVertically, TextureColorSpace colorSpace) {
        public TextureDef {
            path = Objects.requireNonNull(path, "path");
            colorSpace = Objects.requireNonNull(colorSpace, "colorSpace");
        }

        public TextureDef(AssetRef path, boolean flipVertically) {
            this(path, flipVertically, TextureColorSpace.LINEAR);
        }
    }

    public record ModelDef(AssetRef path) {
    }

    public record ObjectDef(
            String model,
            String material,
            Vector3f position,
            Vector3f rotationRadians,
            float scale,
            boolean castShadows
    ) {
        private static final String BUILTIN_PREFIX = "builtin:";

        public ObjectDef {
            model = Objects.requireNonNull(model, "model");
            material = Objects.requireNonNull(material, "material");
            if (model.startsWith(BUILTIN_PREFIX)) {
                String builtinName = model.substring(BUILTIN_PREFIX.length());
                if (!BuiltinMeshData.names().contains(builtinName)) {
                    throw new GlException("Unknown builtin mesh: " + model + ". Supported: " + BuiltinMeshData.names());
                }
            }
            position = new Vector3f(Objects.requireNonNull(position, "position"));
            rotationRadians = new Vector3f(Objects.requireNonNull(rotationRadians, "rotationRadians"));
        }

        public boolean builtinMesh() {
            return model.startsWith(BUILTIN_PREFIX);
        }

        public String builtinMeshName() {
            if (!builtinMesh()) {
                throw new IllegalStateException("Object does not reference a builtin mesh: " + model);
            }
            return model.substring(BUILTIN_PREFIX.length());
        }
    }

    public record LightDef(
            String type,
            Vector3f positionOrDirection,
            Vector3f color,
            float intensity,
            float range,
            boolean castShadows
    ) {
        public LightDef {
            positionOrDirection = new Vector3f(Objects.requireNonNull(positionOrDirection, "positionOrDirection"));
            color = new Vector3f(Objects.requireNonNull(color, "color"));
        }
    }
}
