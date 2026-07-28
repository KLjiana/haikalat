package com.kaleblangley.haikalat.subsystems.render3d.gltf;

import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.core.CullMode;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAssetException;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAlphaMode;
import com.kaleblangley.haikalat.core.assets.gltf.GltfImageData;
import com.kaleblangley.haikalat.core.assets.gltf.GltfSceneStatistics;
import com.kaleblangley.haikalat.core.assets.gltf.LoadedGltfScene;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.Bounds3f;
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
    private final Map<Integer, GltfMorphTargetBuffer> morphBuffers;
    private final Map<MaterialKey, Material> materials;
    private final List<Texture2D> textures;
    private final List<Sampler> samplers;
    private boolean closed;
    private int activeInstances;

    private GltfSceneAsset(LoadedGltfScene source, GltfRuntimeLibrary library,
                           List<Mesh> meshes, Map<MaterialKey, Material> materials,
                           List<Texture2D> textures, List<Sampler> samplers,
                           Map<Integer, GltfMorphTargetBuffer> morphBuffers) {
        this.source = source;
        this.library = library;
        this.meshes = List.copyOf(meshes);
        this.materials = Map.copyOf(materials);
        this.textures = List.copyOf(textures);
        this.samplers = List.copyOf(samplers);
        this.morphBuffers = Map.copyOf(morphBuffers);
    }

    public static GltfSceneAsset upload(LoadedGltfScene source, GltfRuntimeLibrary library) {
        return upload(source, library, UploadFault.NONE);
    }

    static GltfSceneAsset upload(LoadedGltfScene source, GltfRuntimeLibrary library,
                                 UploadFault fault) {
        try (UploadSession session = beginUpload(source, library, fault)) {
            while (!session.isComplete()) session.advance(Integer.MAX_VALUE);
            return session.finish();
        }
    }

    /** Begins a staged GL-thread upload while preserving the synchronous API above. */
    public static UploadSession beginUpload(LoadedGltfScene source,
                                            GltfRuntimeLibrary library) {
        return beginUpload(source, library, UploadFault.NONE);
    }

    /** Begins a staged upload using pixels decoded before the GL hand-off. */
    public static UploadSession beginUpload(LoadedGltfScene source,
                                            GltfRuntimeLibrary library,
                                            Map<Integer, GltfImageData> decodedImages) {
        return beginUpload(source, library, decodedImages, UploadFault.NONE);
    }

    static UploadSession beginUpload(LoadedGltfScene source, GltfRuntimeLibrary library,
                                     UploadFault fault) {
        return new UploadSession(source, library, Map.of(), fault);
    }

    static UploadSession beginUpload(LoadedGltfScene source, GltfRuntimeLibrary library,
                                     Map<Integer, GltfImageData> decodedImages,
                                     UploadFault fault) {
        return new UploadSession(source, library, decodedImages, fault);
    }

    /**
     * GL-thread upload state machine. Each successful step creates at most one
     * sampler, texture, material, mesh or morph buffer.
     */
    public static final class UploadSession implements AutoCloseable {
        private final LoadedGltfScene source;
        private final GltfRuntimeLibrary library;
        private final Map<Integer, GltfImageData> decodedImages;
        private final UploadFault fault;
        private final List<Texture2D> ownedTextures = new ArrayList<>();
        private final List<Sampler> ownedSamplers = new ArrayList<>();
        private final List<Mesh> ownedMeshes = new ArrayList<>();
        private final Map<MaterialKey, Material> ownedMaterials = new LinkedHashMap<>();
        private final Map<Integer, GltfMorphTargetBuffer> ownedMorphBuffers =
                new LinkedHashMap<>();
        private final Map<SamplerDescriptor, Sampler> samplerCache = new LinkedHashMap<>();
        private final Map<LoadedGltfScene.ImageVariantKey, Texture2D> textureCache =
                new LinkedHashMap<>();
        private final List<LoadedGltfScene.ImageVariantKey> textureOrder;
        private final List<LoadedGltfScene.Primitive> materialOrder;
        private UploadPhase phase = UploadPhase.SAMPLER;
        private int cursor;
        private boolean transferred;
        private boolean closed;

        private UploadSession(LoadedGltfScene source, GltfRuntimeLibrary library,
                              Map<Integer, GltfImageData> decodedImages,
                              UploadFault fault) {
            this.source = Objects.requireNonNull(source, "source");
            this.library = Objects.requireNonNull(library, "library");
            this.decodedImages = Map.copyOf(Objects.requireNonNull(decodedImages,
                    "decodedImages"));
            this.fault = Objects.requireNonNull(fault, "fault");
            textureOrder = collectTextureOrder(source);
            materialOrder = collectMaterialOrder(source);
            library.retainAsset();
        }

        public UploadPhase phase() {
            return phase;
        }

        public boolean isComplete() {
            return phase == UploadPhase.COMPLETE;
        }

        /** Conservative byte estimate for the next resource-creation step. */
        public long estimatedNextBytes() {
            ensureActive();
            return switch (phase) {
                case SAMPLER, MATERIAL, MORPH, COMPLETE -> 0L;
                case TEXTURE -> {
                    if (cursor >= textureOrder.size()) yield 0L;
                    LoadedGltfScene.ImageVariantKey key = textureOrder.get(cursor);
                    GltfImageData decoded = decodedImages.get(key.imageIndex());
                    yield decoded == null
                            ? source.images().get(key.imageIndex()).encoded().length
                            : (long) decoded.width() * decoded.height() * 4L;
                }
                case MESH -> {
                    if (cursor >= source.primitives().size()) yield 0L;
                    LoadedGltfScene.Primitive primitive = source.primitives().get(cursor);
                    yield (long) primitive.mesh().vertexCount()
                            * primitive.mesh().layout().strideBytes()
                            + (long) primitive.mesh().indexCount() * Integer.BYTES;
                }
            };
        }

        /**
         * Advances at most {@code maxSteps} resource-creation steps.
         *
         * @return actual resource steps completed
         */
        public int advance(int maxSteps) {
            ensureActive();
            if (maxSteps <= 0) throw new IllegalArgumentException("maxSteps must be positive");
            int completed = 0;
            try {
                while (completed < maxSteps && phase != UploadPhase.COMPLETE) {
                    switch (phase) {
                        case SAMPLER -> {
                            if (cursor >= source.samplers().size()) {
                                next(UploadPhase.TEXTURE);
                                continue;
                            }
                            LoadedGltfScene.SamplerDef def = source.samplers().get(cursor++);
                            SamplerDescriptor descriptor = new SamplerDescriptor(def.minFilter(),
                                    def.magFilter(), def.wrapS(), def.wrapT());
                            if (!samplerCache.containsKey(descriptor)) {
                                Sampler sampler = Sampler.create(new Sampler.Descriptor(
                                        descriptor.min(), descriptor.mag(), descriptor.wrapS(),
                                        descriptor.wrapT(), descriptor.wrapT()));
                                ownedSamplers.add(sampler);
                                samplerCache.put(descriptor, sampler);
                                fault.check(UploadStage.SAMPLER, def.index());
                                completed++;
                            }
                        }
                        case TEXTURE -> {
                            if (cursor >= textureOrder.size()) {
                                next(UploadPhase.MATERIAL);
                                continue;
                            }
                            LoadedGltfScene.ImageVariantKey key = textureOrder.get(cursor++);
                            GltfImageData decoded = decodedImages.get(key.imageIndex());
                            Texture2D uploaded = decoded == null
                                    ? Texture2D.fromEncoded(
                                    source.images().get(key.imageIndex()).encoded(),
                                    false, key.colorSpace())
                                    : Texture2D.fromRgba8(decoded.width(), decoded.height(),
                                    decoded.rgba8(), key.colorSpace());
                            ownedTextures.add(uploaded);
                            textureCache.put(key, uploaded);
                            fault.check(UploadStage.TEXTURE, key.imageIndex());
                            completed++;
                        }
                        case MATERIAL -> {
                            if (cursor >= materialOrder.size()) {
                                next(UploadPhase.MESH);
                                continue;
                            }
                            LoadedGltfScene.Primitive primitive = materialOrder.get(cursor++);
                            MaterialKey key = new MaterialKey(primitive.materialIndex(),
                                    primitive.hasVertexColor());
                            Material material = createMaterial(source, primitive, library,
                                    samplerCache, textureCache);
                            ownedMaterials.put(key, material);
                            fault.check(UploadStage.MATERIAL, primitive.materialIndex());
                            completed++;
                        }
                        case MESH -> {
                            if (cursor >= source.primitives().size()) {
                                next(UploadPhase.MORPH);
                                continue;
                            }
                            LoadedGltfScene.Primitive primitive =
                                    source.primitives().get(cursor++);
                            Bounds3f bounds = source.primitiveSkinning(primitive.index()).isPresent()
                                    ? Bounds3f.unbounded()
                                    : source.primitiveMorphTargets(primitive.index())
                                    .map(LoadedGltfScene.MorphTargetSetDef::conservativeBounds)
                                    .orElseGet(() -> primitive.mesh().localBounds());
                            ownedMeshes.add(Mesh.from(primitive.mesh(), bounds));
                            fault.check(UploadStage.MESH, primitive.index());
                            completed++;
                        }
                        case MORPH -> {
                            if (cursor >= source.primitives().size()) {
                                next(UploadPhase.COMPLETE);
                                continue;
                            }
                            LoadedGltfScene.Primitive primitive =
                                    source.primitives().get(cursor++);
                            var definition = source.primitiveMorphTargets(primitive.index());
                            if (definition.isPresent()) {
                                GltfMorphTargetBuffer buffer =
                                        new GltfMorphTargetBuffer(definition.orElseThrow());
                                ownedMorphBuffers.put(primitive.index(), buffer);
                                fault.check(UploadStage.MORPH, primitive.index());
                                completed++;
                            }
                        }
                        case COMPLETE -> { }
                    }
                }
                return completed;
            } catch (RuntimeException failure) {
                RuntimeException cleanup = closePartial(failure);
                throw new GltfAssetException(source.source(), GltfAssetException.Phase.UPLOAD,
                        "$", null, "GPU resource upload failed", cleanup);
            }
        }

        public GltfSceneAsset finish() {
            ensureActive();
            if (!isComplete()) {
                throw new IllegalStateException("upload session is not complete: " + phase);
            }
            GltfSceneAsset asset = new GltfSceneAsset(source, library, ownedMeshes,
                    ownedMaterials, ownedTextures, ownedSamplers, ownedMorphBuffers);
            transferred = true;
            closed = true;
            return asset;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (!transferred) {
                RuntimeException failure = closePartial(null);
                if (failure != null) throw failure;
            }
        }

        private void next(UploadPhase next) {
            phase = next;
            cursor = 0;
        }

        private RuntimeException closePartial(RuntimeException primary) {
            if (closed && transferred) return primary;
            closed = true;
            RuntimeException failure = closeOwned(ownedMeshes, ownedMaterials.values(),
                    ownedSamplers, ownedTextures, ownedMorphBuffers.values(), primary);
            try {
                library.releaseAsset();
            } catch (RuntimeException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
            return failure;
        }

        private void ensureActive() {
            if (closed) throw new IllegalStateException("upload session is closed");
        }

        private static List<LoadedGltfScene.ImageVariantKey> collectTextureOrder(
                LoadedGltfScene source) {
            LinkedHashMap<LoadedGltfScene.ImageVariantKey, Boolean> order =
                    new LinkedHashMap<>();
            for (LoadedGltfScene.MaterialDef material : source.materials()) {
                for (Map.Entry<PbrTextureRole, Integer> entry
                        : material.textureIndices().entrySet()) {
                    LoadedGltfScene.TextureDef texture =
                            source.textures().get(entry.getValue());
                    order.putIfAbsent(new LoadedGltfScene.ImageVariantKey(
                            texture.imageIndex(), entry.getKey().requiredColorSpace()), true);
                }
            }
            return List.copyOf(order.keySet());
        }

        private static List<LoadedGltfScene.Primitive> collectMaterialOrder(
                LoadedGltfScene source) {
            LinkedHashMap<MaterialKey, LoadedGltfScene.Primitive> order =
                    new LinkedHashMap<>();
            for (LoadedGltfScene.Primitive primitive : source.primitives()) {
                order.putIfAbsent(new MaterialKey(primitive.materialIndex(),
                        primitive.hasVertexColor()), primitive);
            }
            return List.copyOf(order.values());
        }
    }

    public enum UploadPhase {
        SAMPLER,
        TEXTURE,
        MATERIAL,
        MESH,
        MORPH,
        COMPLETE
    }

    /*
     * Legacy direct implementation retained below only as helper source for
     * single-resource construction. The public synchronous entry now drains
     * UploadSession above.
     */
    private static GltfSceneAsset uploadDirect(LoadedGltfScene source,
                                               GltfRuntimeLibrary library,
                                               UploadFault fault) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(library, "library");
        Objects.requireNonNull(fault, "fault");
        library.retainAsset();
        List<Texture2D> ownedTextures = new ArrayList<>();
        List<Sampler> ownedSamplers = new ArrayList<>();
        List<Mesh> ownedMeshes = new ArrayList<>();
        Map<MaterialKey, Material> ownedMaterials = new LinkedHashMap<>();
        Map<Integer, GltfMorphTargetBuffer> ownedMorphBuffers = new LinkedHashMap<>();
        try (CloseStack rollback = new CloseStack()) {
            rollback.own(new LibraryLease(library));
            Map<SamplerDescriptor, Sampler> samplerCache = createSamplers(source,
                    ownedSamplers, rollback, fault);
            Map<LoadedGltfScene.ImageVariantKey, Texture2D> textureCache = createTextures(
                    source, ownedTextures, rollback, fault);
            for (LoadedGltfScene.Primitive primitive : source.primitives()) {
                MaterialKey key = new MaterialKey(primitive.materialIndex(), primitive.hasVertexColor());
                if (!ownedMaterials.containsKey(key)) {
                    ownedMaterials.put(key, rollback.own(createMaterial(source, primitive, library,
                            samplerCache, textureCache)));
                    fault.check(UploadStage.MATERIAL, primitive.materialIndex());
                }
            }
            for (LoadedGltfScene.Primitive primitive : source.primitives()) {
                Bounds3f bounds = source.primitiveSkinning(primitive.index()).isPresent()
                        ? Bounds3f.unbounded()
                        : source.primitiveMorphTargets(primitive.index())
                        .map(LoadedGltfScene.MorphTargetSetDef::conservativeBounds)
                        .orElseGet(() -> primitive.mesh().localBounds());
                ownedMeshes.add(rollback.own(Mesh.from(primitive.mesh(), bounds)));
                fault.check(UploadStage.MESH, primitive.index());
            }
            for (LoadedGltfScene.Primitive primitive : source.primitives()) {
                source.primitiveMorphTargets(primitive.index()).ifPresent(definition -> {
                    GltfMorphTargetBuffer buffer =
                            rollback.own(new GltfMorphTargetBuffer(definition));
                    ownedMorphBuffers.put(primitive.index(), buffer);
                    fault.check(UploadStage.MORPH, primitive.index());
                });
            }
            GltfSceneAsset asset = new GltfSceneAsset(source, library, ownedMeshes, ownedMaterials,
                    ownedTextures, ownedSamplers, ownedMorphBuffers);
            rollback.releaseOwnership();
            return asset;
        } catch (RuntimeException failure) {
            throw new GltfAssetException(source.source(), GltfAssetException.Phase.UPLOAD, "$", null,
                    "GPU resource upload failed", failure);
        }
    }

    public List<SceneObject> instantiate(Matrix4fc rootTransform, boolean castShadows) {
        ensureOpen();
        Objects.requireNonNull(rootTransform, "rootTransform");
        if (!morphBuffers.isEmpty()) {
            throw new GltfAssetException(source.source(),
                    GltfAssetException.Phase.INSTANTIATE, "meshes",
                    "morph-target assets require instantiateAnimated so weights remain "
                            + "instance-owned");
        }
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
                result.add(SceneObject.fixed(mesh, material, model, castShadows));
            }
        }
        return List.copyOf(result);
    }

    public GltfSceneInstance instantiateAnimated(Matrix4fc rootTransform,
                                                 boolean castShadows) {
        ensureOpen();
        return GltfSceneInstance.create(this, rootTransform, castShadows);
    }

    public GltfSceneStatistics statistics() { return source.statistics(); }
    public boolean isClosed() { return closed; }
    public int uniqueMeshCount() { return meshes.size(); }
    public int uniqueTextureCount() { return textures.size(); }
    public int uniqueSamplerCount() { return samplers.size() + 1; }
    public int morphTargetBufferCount() { return morphBuffers.size(); }
    public long morphTargetGpuBytes() {
        long bytes = 0L;
        for (GltfMorphTargetBuffer buffer : morphBuffers.values()) {
            bytes = Math.addExact(bytes, buffer.byteSize());
        }
        return bytes;
    }
    /** @return 实际上传且可由多个 scene instance 共享的 material 数量 */
    public int uniqueMaterialCount() { return materials.size(); }

    /** @return mesh/index 与已上传 RGBA 纹理的保守 GPU storage 估值 */
    public long estimatedGpuBytes() {
        long bytes = Math.addExact(source.statistics().vertexBytes(), source.statistics().indexBytes());
        for (Texture2D texture : textures) {
            bytes = Math.addExact(bytes, Math.multiplyExact(
                    Math.multiplyExact((long) texture.width(), texture.height()), 4L));
        }
        for (GltfMorphTargetBuffer buffer : morphBuffers.values()) {
            bytes = Math.addExact(bytes, buffer.byteSize());
        }
        return bytes;
    }

    @Override
    public void close() {
        if (closed) return;
        if (activeInstances != 0) {
            throw new IllegalStateException("cannot close glTF scene asset while "
                    + activeInstances + " animated instances are active");
        }
        closed = true;
        RuntimeException failure = closeOwned(meshes, materials.values(), samplers, textures,
                morphBuffers.values(), null);
        try { library.releaseAsset(); } catch (RuntimeException error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }

    private static Map<SamplerDescriptor, Sampler> createSamplers(LoadedGltfScene source,
                                                                   List<Sampler> owned, CloseStack rollback,
                                                                   UploadFault fault) {
        Map<SamplerDescriptor, Sampler> result = new HashMap<>();
        for (LoadedGltfScene.SamplerDef def : source.samplers()) {
            SamplerDescriptor descriptor = new SamplerDescriptor(def.minFilter(), def.magFilter(),
                    def.wrapS(), def.wrapT());
            result.computeIfAbsent(descriptor, key -> {
                Sampler sampler = Sampler.create(new Sampler.Descriptor(key.min(), key.mag(),
                        key.wrapS(), key.wrapT(), key.wrapT()));
                owned.add(rollback.own(sampler));
                fault.check(UploadStage.SAMPLER, def.index());
                return sampler;
            });
        }
        return result;
    }

    private static Map<LoadedGltfScene.ImageVariantKey, Texture2D> createTextures(
            LoadedGltfScene source, List<Texture2D> owned, CloseStack rollback, UploadFault fault) {
        Map<LoadedGltfScene.ImageVariantKey, Texture2D> result = new HashMap<>();
        for (LoadedGltfScene.MaterialDef material : source.materials()) {
            for (Map.Entry<PbrTextureRole, Integer> entry : material.textureIndices().entrySet()) {
                LoadedGltfScene.TextureDef texture = source.textures().get(entry.getValue());
                LoadedGltfScene.ImageVariantKey key = new LoadedGltfScene.ImageVariantKey(
                        texture.imageIndex(), entry.getKey().requiredColorSpace());
                result.computeIfAbsent(key, ignored -> {
                    Texture2D uploaded = Texture2D.fromEncoded(
                            source.images().get(key.imageIndex()).encoded(), false, key.colorSpace());
                    owned.add(rollback.own(uploaded));
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
                                               Iterable<GltfMorphTargetBuffer> morphBuffers,
                                               RuntimeException primary) {
        CloseStack closeStack = new CloseStack();
        morphBuffers.forEach(closeStack::own);
        textures.forEach(closeStack::own);
        samplers.forEach(closeStack::own);
        materials.forEach(closeStack::own);
        meshes.forEach(closeStack::own);
        try {
            closeStack.close();
        } catch (RuntimeException cleanupFailure) {
            if (primary == null) return cleanupFailure;
            primary.addSuppressed(cleanupFailure);
        }
        return primary;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("glTF scene asset is closed");
    }

    LoadedGltfScene sourceData() {
        ensureOpen();
        return source;
    }

    Mesh mesh(int primitiveIndex) {
        ensureOpen();
        return meshes.get(primitiveIndex);
    }

    Material material(LoadedGltfScene.Primitive primitive) {
        ensureOpen();
        return materials.get(new MaterialKey(primitive.materialIndex(),
                primitive.hasVertexColor()));
    }

    GltfMorphTargetBuffer morphBuffer(int primitiveIndex) {
        ensureOpen();
        return morphBuffers.get(primitiveIndex);
    }

    void retainInstance() {
        ensureOpen();
        activeInstances = Math.incrementExact(activeInstances);
    }

    void releaseInstance() {
        if (activeInstances <= 0) {
            throw new IllegalStateException("glTF scene instance count underflow");
        }
        activeInstances--;
    }

    private record MaterialKey(int materialIndex, boolean vertexColor) {}
    private record SamplerDescriptor(int min, int mag, int wrapS, int wrapT) {}
    private record LibraryLease(GltfRuntimeLibrary library) implements AutoCloseable {
        @Override public void close() { library.releaseAsset(); }
    }

    enum UploadStage { SAMPLER, TEXTURE, MATERIAL, MESH, MORPH }

    @FunctionalInterface
    interface UploadFault {
        UploadFault NONE = (stage, index) -> {};
        void check(UploadStage stage, int index);
    }
}
