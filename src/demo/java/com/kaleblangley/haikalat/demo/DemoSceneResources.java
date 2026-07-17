package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.LoadedModel;
import com.kaleblangley.haikalat.core.assets.MaterialDef;
import com.kaleblangley.haikalat.core.assets.MaterialModel;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;
import com.kaleblangley.haikalat.core.assets.ModelAssetManager;
import com.kaleblangley.haikalat.core.assets.ObjModelLoader;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.core.assets.SceneAssetConfig;
import com.kaleblangley.haikalat.core.assets.ShaderAsset;
import com.kaleblangley.haikalat.core.assets.TextureAssetCache;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import com.kaleblangley.haikalat.core.mesh.TangentGenerator;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.Objects;
import java.util.function.Consumer;

/** 持有并管理由 Demo manifest 组装出的 OpenGL 资源。 */
final class DemoSceneResources implements AutoCloseable {
    private final Map<String, ShaderProgram> shaders = new LinkedHashMap<>();
    private final Map<String, Material> materials = new LinkedHashMap<>();
    private final Map<MeshVariant, List<Mesh>> meshes = new LinkedHashMap<>();
    private final TextureAssetCache textureCache;
    private PbrFallbackTextures pbrFallbacks;
    private boolean closed;

    private DemoSceneResources(SceneAssetConfig config) {
        textureCache = new TextureAssetCache((path, flipVertically, colorSpace) ->
                Texture2D.fromResource(DemoSceneResources.class, path.path(), flipVertically, colorSpace));
    }

    static DemoSceneResources load(ResourceLocator locator, SceneAssetConfig config) {
        return load(locator, config, ignored -> {
        });
    }

    static DemoSceneResources load(ResourceLocator locator, SceneAssetConfig config,
                                   Consumer<DemoSceneResources> allocationObserver) {
        Objects.requireNonNull(locator, "locator");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(allocationObserver, "allocationObserver");
        DemoSceneResources resources = new DemoSceneResources(config);
        allocationObserver.accept(resources);
        try {
            resources.loadShaders(config);
            resources.loadMaterials(config);
            resources.loadMeshes(locator, config);
            return resources;
        } catch (RuntimeException failure) {
            try {
                resources.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    ShaderProgram shader(String name) {
        ShaderProgram shader = shaders.get(name);
        if (shader == null) {
            throw new IllegalArgumentException("Shader not loaded: " + name);
        }
        return shader;
    }

    Material material(String name) {
        Material material = materials.get(name);
        if (material == null) {
            throw new IllegalArgumentException("Material not loaded: " + name);
        }
        return material;
    }

    List<Mesh> meshes(String modelReference, MaterialModel materialModel) {
        List<Mesh> result = meshes.get(new MeshVariant(modelReference, materialModel));
        if (result == null) {
            throw new IllegalArgumentException("Model variant not loaded: " + modelReference
                    + " [" + materialModel + "]");
        }
        return result;
    }

    List<Mesh> meshes(SceneAssetConfig.ObjectDef object, SceneAssetConfig config) {
        MaterialModel model = config.materials().get(object.material()).model();
        return meshes(object.model(), model);
    }

    boolean isClosed() {
        return closed;
    }

    private void loadShaders(SceneAssetConfig config) {
        for (Map.Entry<String, ShaderAsset> entry : config.shaders().entrySet()) {
            ShaderAsset asset = entry.getValue();
            ShaderProgram.Builder builder = ShaderProgram.builder();
            for (Map.Entry<ShaderStage, AssetRef> stage : asset.stages().entrySet()) {
                builder.resource(DemoSceneResources.class, stage.getKey(), stage.getValue().path());
            }
            shaders.put(entry.getKey(), builder.link());
        }
    }

    private void loadMaterials(SceneAssetConfig config) {
        for (Map.Entry<String, MaterialDef> entry : config.materials().entrySet()) {
            String name = entry.getKey();
            MaterialDef def = entry.getValue();
            if (def.model() == MaterialModel.METALLIC_ROUGHNESS) {
                if (pbrFallbacks == null) pbrFallbacks = new PbrFallbackTextures();
                EnumMap<PbrTextureRole, Texture2D> supplied = new EnumMap<>(PbrTextureRole.class);
                for (Map.Entry<PbrTextureRole, String> role : def.pbr().textures().entrySet()) {
                    SceneAssetConfig.TextureDef texture = config.textures().get(role.getValue());
                    supplied.put(role.getKey(), textureCache.get(texture.path(),
                            texture.flipVertically(), texture.colorSpace()));
                }
                materials.put(name, PbrMaterials.create(shader(def.shader()), def.pbr(), supplied,
                        pbrFallbacks));
                continue;
            }
            Material.Builder builder = Material.builder(shader(def.shader()))
                    .model(def.model())
                    .blendMode(def.blendMode())
                    .depthTest(def.depthTest())
                    .setInt("uUseTexture", def.textures().isEmpty() ? 0 : 1);
            for (MaterialDef.TextureBinding binding : def.textures()) {
                if (binding.sampler() != null) {
                    throw new IllegalStateException("Sampler definitions are not wired yet: " + binding.sampler());
                }
                SceneAssetConfig.TextureDef texture = config.textures().get(binding.texture());
                builder.texture(binding.unit(), binding.samplerName(), textureCache.get(
                        texture.path(), texture.flipVertically(), texture.colorSpace()));
            }
            if (!def.textures().isEmpty()) {
                builder.setVec3("uTint", new Vector3f(1.0f));
            }
            materials.put(name, builder.build());
        }
    }

    private void loadMeshes(ResourceLocator locator, SceneAssetConfig config) {
        ModelAssetManager legacyAssets = new ModelAssetManager()
                .register("obj", new ObjModelLoader(locator));
        for (SceneAssetConfig.ObjectDef object : config.objects().values()) {
            MaterialModel materialModel = config.materials().get(object.material()).model();
            MeshVariant variant = new MeshVariant(object.model(), materialModel);
            meshes.computeIfAbsent(variant, ignored -> {
                if (object.builtinMesh()) {
                    MeshData data = BuiltinMeshData.named(object.builtinMeshName());
                    if (materialModel == MaterialModel.METALLIC_ROUGHNESS) {
                        data = TangentGenerator.generate(data).mesh();
                    }
                    return List.of(Mesh.from(data));
                }
                SceneAssetConfig.ModelDef model = config.models().get(object.model());
                LoadedModel loaded = materialModel == MaterialModel.METALLIC_ROUGHNESS
                        && model.path().extension().equals("obj")
                        ? new ObjModelLoader(locator).load(model.path(), ObjModelLoader.Options.PBR)
                        : legacyAssets.load(model.path());
                return uploadMeshes(loaded);
            });
        }
    }

    private static List<Mesh> uploadMeshes(LoadedModel loaded) {
        List<Mesh> uploaded = new ArrayList<>(loaded.meshes().size());
        try {
            for (MeshData meshData : loaded.meshes()) {
                uploaded.add(Mesh.from(meshData));
            }
            return List.copyOf(uploaded);
        } catch (RuntimeException failure) {
            RuntimeException closeFailure = closeReverse(uploaded, null);
            if (closeFailure != null) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        RuntimeException failure = null;
        List<List<Mesh>> meshLists = new ArrayList<>(meshes.values());
        for (int i = meshLists.size() - 1; i >= 0; i--) {
            failure = closeReverse(meshLists.get(i), failure);
        }
        meshes.clear();
        failure = closeReverse(new ArrayList<>(materials.values()), failure);
        materials.clear();
        try {
            textureCache.close();
        } catch (RuntimeException closeFailure) {
            failure = accumulate(failure, closeFailure);
        }
        try {
            if (pbrFallbacks != null) pbrFallbacks.close();
        } catch (RuntimeException closeFailure) {
            failure = accumulate(failure, closeFailure);
        }
        pbrFallbacks = null;
        failure = closeReverse(new ArrayList<>(shaders.values()), failure);
        shaders.clear();
        closed = true;
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException closeReverse(List<? extends AutoCloseable> resources,
                                                 RuntimeException failure) {
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (Exception closeFailure) {
                failure = accumulate(failure,
                        new IllegalStateException("Failed to close demo scene resource", closeFailure));
            }
        }
        return failure;
    }

    private static RuntimeException accumulate(RuntimeException failure, RuntimeException next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    /** 同一模型引用可同时拥有 legacy 与 canonical PBR 两份独立 GPU layout。 */
    private record MeshVariant(String modelReference, MaterialModel materialModel) {
        private MeshVariant {
            Objects.requireNonNull(modelReference, "modelReference");
            Objects.requireNonNull(materialModel, "materialModel");
        }
    }
}
