package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.LoadedModel;
import com.kaleblangley.haikalat.core.assets.MaterialDef;
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
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Owns the GL resources assembled from the demo manifest. */
final class DemoSceneResources implements AutoCloseable {
    private final Map<String, ShaderProgram> shaders = new LinkedHashMap<>();
    private final Map<String, Material> materials = new LinkedHashMap<>();
    private final Map<String, List<Mesh>> meshes = new LinkedHashMap<>();
    private final TextureAssetCache textureCache;
    private boolean closed;

    private DemoSceneResources(SceneAssetConfig config) {
        textureCache = new TextureAssetCache(ref -> loadTexture(ref, config));
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

    List<Mesh> meshes(String modelReference) {
        List<Mesh> result = meshes.get(modelReference);
        if (result == null) {
            throw new IllegalArgumentException("Model not loaded: " + modelReference);
        }
        return result;
    }

    boolean isClosed() {
        return closed;
    }

    private void loadShaders(SceneAssetConfig config) {
        for (Map.Entry<String, ShaderAsset> entry : config.shaders().entrySet()) {
            ShaderAsset asset = entry.getValue();
            shaders.put(entry.getKey(), ShaderProgram.fromResource(DemoSceneResources.class,
                    asset.vertexShader().path(), asset.fragmentShader().path()));
        }
    }

    private void loadMaterials(SceneAssetConfig config) {
        for (Map.Entry<String, MaterialDef> entry : config.materials().entrySet()) {
            String name = entry.getKey();
            MaterialDef def = entry.getValue();
            Material.Builder builder = Material.builder(shader(def.shader()))
                    .blendMode(def.blendMode())
                    .depthTest(def.depthTest())
                    .setInt("uUseTexture", def.textures().isEmpty() ? 0 : 1);
            for (MaterialDef.TextureBinding binding : def.textures()) {
                if (binding.sampler() != null) {
                    throw new IllegalStateException("Sampler definitions are not wired yet: " + binding.sampler());
                }
                SceneAssetConfig.TextureDef texture = config.textures().get(binding.texture());
                builder.texture(binding.unit(), binding.samplerName(), textureCache.get(texture.path().path()));
            }
            if (!def.textures().isEmpty()) {
                builder.setVec3("uTint", new Vector3f(1.0f));
            }
            materials.put(name, builder.build());
        }
    }

    private void loadMeshes(ResourceLocator locator, SceneAssetConfig config) {
        for (SceneAssetConfig.ObjectDef object : config.objects().values()) {
            if (object.builtinMesh()) {
                String reference = object.model();
                meshes.computeIfAbsent(reference, ignored -> List.of(
                        Mesh.from(BuiltinMeshData.named(object.builtinMeshName()))));
            }
        }

        ModelAssetManager modelAssets = new ModelAssetManager()
                .register("obj", new ObjModelLoader(locator));
        for (Map.Entry<String, SceneAssetConfig.ModelDef> entry : config.models().entrySet()) {
            LoadedModel loaded = modelAssets.load(entry.getValue().path());
            meshes.put(entry.getKey(), uploadMeshes(loaded));
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

    private static Texture2D loadTexture(AssetRef ref, SceneAssetConfig config) {
        boolean flip = config.textures().values().stream()
                .filter(texture -> texture.path().equals(ref))
                .findFirst()
                .map(SceneAssetConfig.TextureDef::flipVertically)
                .orElse(true);
        return Texture2D.fromResource(DemoSceneResources.class, ref.path(), flip);
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
}
