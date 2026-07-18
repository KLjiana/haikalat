package com.kaleblangley.haikalat.subsystems.render3d.gltf;

import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.core.CullMode;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAssetException;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAlphaMode;
import com.kaleblangley.haikalat.core.assets.gltf.GltfSceneStatistics;
import com.kaleblangley.haikalat.core.assets.gltf.LoadedGltfScene;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrTextureBinding;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 已上传并显式拥有 glTF GPU 资源的静态场景资产。 */
public final class GltfSceneAsset implements AutoCloseable {
    private final LoadedGltfScene source;
    private final GltfRuntimeLibrary library;
    private final List<Mesh> meshes;
    private final Map<MaterialKey, Material> materials;
    private final List<Texture2D> textures;
    private final List<Sampler> samplers;
    private boolean closed;

    private GltfSceneAsset(LoadedGltfScene source, GltfRuntimeLibrary library,
                           List<Mesh> meshes, Map<MaterialKey, Material> materials,
                           List<Texture2D> textures, List<Sampler> samplers) {
        this.source = source;
        this.library = library;
        this.meshes = List.copyOf(meshes);
        this.materials = Map.copyOf(materials);
        this.textures = List.copyOf(textures);
        this.samplers = List.copyOf(samplers);
    }

    public static GltfSceneAsset upload(LoadedGltfScene source, GltfRuntimeLibrary library) {
        return upload(source, library, UploadFault.NONE);
    }

    static GltfSceneAsset upload(LoadedGltfScene source, GltfRuntimeLibrary library,
                                 UploadFault fault) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(library, "library");
        Objects.requireNonNull(fault, "fault");
        library.retainAsset();
        List<Texture2D> ownedTextures = new ArrayList<>();
        List<Sampler> ownedSamplers = new ArrayList<>();
        List<Mesh> ownedMeshes = new ArrayList<>();
        Map<MaterialKey, Material> ownedMaterials = new LinkedHashMap<>();
        try {
            Map<SamplerDescriptor, Sampler> samplerCache = createSamplers(source,
                    ownedSamplers, fault);
            Map<LoadedGltfScene.ImageVariantKey, Texture2D> textureCache = createTextures(
                    source, ownedTextures, fault);
            for (LoadedGltfScene.Primitive primitive : source.primitives()) {
                MaterialKey key = new MaterialKey(primitive.materialIndex(), primitive.hasVertexColor());
                if (!ownedMaterials.containsKey(key)) {
                    ownedMaterials.put(key, createMaterial(source, primitive, library,
                            samplerCache, textureCache));
                    fault.check(UploadStage.MATERIAL, primitive.materialIndex());
                }
            }
            for (LoadedGltfScene.Primitive primitive : source.primitives()) {
                ownedMeshes.add(Mesh.from(primitive.mesh()));
                fault.check(UploadStage.MESH, primitive.index());
            }
            return new GltfSceneAsset(source, library, ownedMeshes, ownedMaterials,
                    ownedTextures, ownedSamplers);
        } catch (RuntimeException failure) {
            RuntimeException primary = closeOwned(ownedMeshes, ownedMaterials.values(),
                    ownedSamplers, ownedTextures, failure);
            try {
                library.releaseAsset();
            } catch (RuntimeException cleanupFailure) {
                primary.addSuppressed(cleanupFailure);
            }
            throw new GltfAssetException(source.source(), GltfAssetException.Phase.UPLOAD, "$", null,
                    "GPU resource upload failed", primary);
        }
    }

    public List<SceneObject> instantiate(Matrix4fc rootTransform, boolean castShadows) {
        ensureOpen();
        Objects.requireNonNull(rootTransform, "rootTransform");
        if (!Float.isFinite(new Matrix4f(rootTransform).determinant3x3())
                || Math.abs(new Matrix4f(rootTransform).determinant3x3()) < 1.0e-12f) {
            throw new GltfAssetException(source.source(), GltfAssetException.Phase.INSTANTIATE,
                    "rootTransform", "root transform must be finite and non-singular");
        }
        List<SceneObject> result = new ArrayList<>();
        for (LoadedGltfScene.Node node : source.nodes()) {
            if (!node.reachable() || node.meshIndex() < 0) continue;
            Matrix4f model = new Matrix4f(rootTransform).mul(node.worldTransform());
            if (!Float.isFinite(model.determinant3x3()) || Math.abs(model.determinant3x3()) < 1.0e-12f) {
                throw new GltfAssetException(source.source(), GltfAssetException.Phase.INSTANTIATE,
                        "nodes[" + node.index() + "]", "node transform is singular");
            }
            for (LoadedGltfScene.Primitive primitive : source.primitives()) {
                if (primitive.meshIndex() != node.meshIndex()) continue;
                LoadedGltfScene.MaterialDef materialDef = source.materials()
                        .get(primitive.materialIndex());
                if (castShadows && materialDef.alphaMode() == GltfAlphaMode.MASK) {
                    throw new GltfAssetException(source.source(), GltfAssetException.Phase.INSTANTIATE,
                            "nodes[" + node.index() + "].mesh[" + primitive.meshIndex()
                                    + "].primitives[" + primitive.primitiveIndex() + "]",
                            "MASK materials require castShadows=false until masked shadow depth is supported");
                }
                Mesh mesh = meshes.get(primitive.index());
                Material material = materials.get(new MaterialKey(primitive.materialIndex(),
                        primitive.hasVertexColor()));
                result.add(new SceneObject(mesh, material, (out, frame) -> {
                    ensureOpen();
                    out.set(model);
                }, castShadows));
            }
        }
        return List.copyOf(result);
    }

    public GltfSceneStatistics statistics() { return source.statistics(); }
    public boolean isClosed() { return closed; }
    public int uniqueMeshCount() { return meshes.size(); }
    public int uniqueTextureCount() { return textures.size(); }
    public int uniqueSamplerCount() { return samplers.size() + 1; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = closeOwned(meshes, materials.values(), samplers, textures, null);
        try { library.releaseAsset(); } catch (RuntimeException error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }

    private static Map<SamplerDescriptor, Sampler> createSamplers(LoadedGltfScene source,
                                                                   List<Sampler> owned,
                                                                   UploadFault fault) {
        Map<SamplerDescriptor, Sampler> result = new HashMap<>();
        for (LoadedGltfScene.SamplerDef def : source.samplers()) {
            SamplerDescriptor descriptor = new SamplerDescriptor(def.minFilter(), def.magFilter(),
                    def.wrapS(), def.wrapT());
            result.computeIfAbsent(descriptor, key -> {
                Sampler sampler = Sampler.create(new Sampler.Descriptor(key.min(), key.mag(),
                        key.wrapS(), key.wrapT(), key.wrapT()));
                owned.add(sampler);
                fault.check(UploadStage.SAMPLER, def.index());
                return sampler;
            });
        }
        return result;
    }

    private static Map<LoadedGltfScene.ImageVariantKey, Texture2D> createTextures(
            LoadedGltfScene source, List<Texture2D> owned, UploadFault fault) {
        Map<LoadedGltfScene.ImageVariantKey, Texture2D> result = new HashMap<>();
        for (LoadedGltfScene.MaterialDef material : source.materials()) {
            for (Map.Entry<PbrTextureRole, Integer> entry : material.textureIndices().entrySet()) {
                LoadedGltfScene.TextureDef texture = source.textures().get(entry.getValue());
                LoadedGltfScene.ImageVariantKey key = new LoadedGltfScene.ImageVariantKey(
                        texture.imageIndex(), entry.getKey().requiredColorSpace());
                result.computeIfAbsent(key, ignored -> {
                    Texture2D uploaded = Texture2D.fromEncoded(
                            source.images().get(key.imageIndex()).encoded(), false, key.colorSpace());
                    owned.add(uploaded);
                    fault.check(UploadStage.TEXTURE, key.imageIndex());
                    return uploaded;
                });
            }
        }
        return result;
    }

    private static Material createMaterial(LoadedGltfScene source,
                                           LoadedGltfScene.Primitive primitive,
                                           GltfRuntimeLibrary library,
                                           Map<SamplerDescriptor, Sampler> samplerCache,
                                           Map<LoadedGltfScene.ImageVariantKey, Texture2D> textureCache) {
        LoadedGltfScene.MaterialDef def = source.materials().get(primitive.materialIndex());
        EnumMap<PbrTextureRole, PbrTextureBinding> bindings = new EnumMap<>(PbrTextureRole.class);
        for (Map.Entry<PbrTextureRole, Integer> entry : def.textureIndices().entrySet()) {
            LoadedGltfScene.TextureDef texture = source.textures().get(entry.getValue());
            Texture2D image = textureCache.get(new LoadedGltfScene.ImageVariantKey(
                    texture.imageIndex(), entry.getKey().requiredColorSpace()));
            Sampler sampler = library.defaultSampler();
            if (texture.samplerIndex() >= 0) {
                LoadedGltfScene.SamplerDef samplerDef = source.samplers().get(texture.samplerIndex());
                sampler = samplerCache.get(new SamplerDescriptor(samplerDef.minFilter(),
                        samplerDef.magFilter(), samplerDef.wrapS(), samplerDef.wrapT()));
            }
            bindings.put(entry.getKey(), new PbrTextureBinding(image, sampler));
        }
        return PbrMaterials.createWithBindings(library.shader(), def.properties(), bindings,
                library.fallbacks(), def.doubleSided() ? CullMode.NONE : CullMode.BACK,
                def.doubleSided(), primitive.hasVertexColor(),
                def.alphaMode() == GltfAlphaMode.MASK ? def.alphaCutoff() : 0.0f);
    }

    private static RuntimeException closeOwned(Iterable<Mesh> meshes,
                                               Iterable<Material> materials,
                                               Iterable<Sampler> samplers,
                                               Iterable<Texture2D> textures,
                                               RuntimeException primary) {
        RuntimeException failure = primary;
        List<AutoCloseable> resources = new ArrayList<>();
        appendReverse(resources, meshes);
        appendReverse(resources, materials);
        appendReverse(resources, samplers);
        appendReverse(resources, textures);
        for (AutoCloseable resource : resources) {
            try { resource.close(); }
            catch (Exception error) {
                RuntimeException runtime = error instanceof RuntimeException r ? r : new RuntimeException(error);
                if (failure == null) failure = runtime; else failure.addSuppressed(runtime);
            }
        }
        return failure;
    }

    private static void appendReverse(List<AutoCloseable> output,
                                      Iterable<? extends AutoCloseable> input) {
        List<AutoCloseable> resources = new ArrayList<>();
        input.forEach(resources::add);
        for (int i = resources.size() - 1; i >= 0; i--) {
            output.add(resources.get(i));
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("glTF scene asset is closed");
    }

    private record MaterialKey(int materialIndex, boolean vertexColor) {}
    private record SamplerDescriptor(int min, int mag, int wrapS, int wrapT) {}

    enum UploadStage { SAMPLER, TEXTURE, MATERIAL, MESH }

    @FunctionalInterface
    interface UploadFault {
        UploadFault NONE = (stage, index) -> {};
        void check(UploadStage stage, int index);
    }
}
