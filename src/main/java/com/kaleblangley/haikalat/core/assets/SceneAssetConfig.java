package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Objects;

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
        Map<String, LightDef> lights,
        Map<String, GltfSceneDef> gltfScenes
) {
    public SceneAssetConfig {
        shaders = Map.copyOf(Objects.requireNonNull(shaders, "shaders"));
        textures = Map.copyOf(Objects.requireNonNull(textures, "textures"));
        materials = Map.copyOf(Objects.requireNonNull(materials, "materials"));
        models = Map.copyOf(Objects.requireNonNull(models, "models"));
        objects = Map.copyOf(Objects.requireNonNull(objects, "objects"));
        lights = Map.copyOf(Objects.requireNonNull(lights, "lights"));
        gltfScenes = Map.copyOf(Objects.requireNonNull(gltfScenes, "gltfScenes"));
        SceneReferenceValidator.validate(shaders, textures, materials, models, objects);
        for (String name : gltfScenes.keySet()) {
            if (objects.containsKey(name)) throw new GlException(
                    "gltf." + name + " conflicts with object." + name);
        }
    }

    public SceneAssetConfig(Map<String, ShaderAsset> shaders, Map<String, TextureDef> textures,
                            Map<String, MaterialDef> materials, Map<String, ModelDef> models,
                            Map<String, ObjectDef> objects, Map<String, LightDef> lights) {
        this(shaders, textures, materials, models, objects, lights, Map.of());
    }

    public static SceneAssetConfig load(ResourceLocator locator, String path) {
        String source = locator.readString(AssetRef.of(path));
        if (path.endsWith(".properties")) {
            return parseProperties(source);
        }
        return parse(source);
    }

    public static SceneAssetConfig parseProperties(String source) {
        return ScenePropertiesParser.parse(source);
    }

    public static SceneAssetConfig parse(String source) {
        return SceneTextManifestParser.parse(source);
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

    /** glTF 内部材质驱动的静态场景实例配置。 */
    public record GltfSceneDef(AssetRef path, String scene, Vector3f position,
                               Vector3f rotationRadians, float scale, boolean castShadows) {
        public GltfSceneDef {
            path = Objects.requireNonNull(path, "path");
            if (!path.extension().equals("gltf") && !path.extension().equals("glb")) {
                throw new GlException("glTF scene path must use .gltf or .glb: " + path.path());
            }
            scene = Objects.requireNonNull(scene, "scene").strip();
            if (scene.isEmpty()) throw new GlException("glTF scene selector must not be blank");
            position = new Vector3f(Objects.requireNonNull(position, "position"));
            rotationRadians = new Vector3f(Objects.requireNonNull(rotationRadians, "rotationRadians"));
            if (!position.isFinite()) throw new GlException("glTF scene position must be finite");
            if (!rotationRadians.isFinite()) throw new GlException("glTF scene rotation must be finite");
            if (!Float.isFinite(scale) || scale == 0.0f) throw new GlException("glTF scene scale must be finite and non-zero");
        }
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
