package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.AssetByteResolver;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.*;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.*;

/** glTF 2.0 scene、skin 与 animation 的纯 JVM 加载入口。 */
public final class GltfAssetLoader {
    private final AssetByteResolver locator;

    public GltfAssetLoader(ResourceLocator locator) {
        this((AssetByteResolver) locator);
    }

    public GltfAssetLoader(AssetByteResolver locator) {
        this.locator = Objects.requireNonNull(locator, "locator");
    }

    public LoadedGltfScene load(AssetRef ref) {
        return load(ref, GltfLoadOptions.defaults());
    }

    public LoadedGltfScene load(AssetRef ref, GltfLoadOptions options) {
        return load(ref, options, GltfAnimationMetadata.empty());
    }

    public LoadedGltfScene loadWithSidecar(AssetRef ref) {
        return loadWithSidecar(ref, GltfLoadOptions.defaults());
    }

    /** Loads {@code <asset>.animation.json} or {@code <asset>.markers.json} when present. */
    public LoadedGltfScene loadWithSidecar(AssetRef ref, GltfLoadOptions options) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(options, "options");
        String path = ref.path();
        int dot = path.lastIndexOf('.');
        String base = dot < 0 ? path : path.substring(0, dot);
        AssetRef sidecar = AssetRef.of(base + ".animation.json");
        if (!locator.exists(sidecar)) {
            sidecar = AssetRef.of(base + ".markers.json");
        }
        if (!locator.exists(sidecar)) return load(ref, options);
        String sidecarJson = new String(locator.readBytes(sidecar,
                options.limits().documentBytes()), java.nio.charset.StandardCharsets.UTF_8);
        return load(ref, options, GltfAnimationMetadata.fromJson(sidecar, sidecarJson));
    }

    /**
     * Loads an unpacked {@code haikalat.gltf-animation-library/1} manifest and merges
     * its external glTF animation sidecars into the referenced model.
     */
    public LoadedGltfScene loadAnimationLibrary(AssetRef manifest) {
        return loadAnimationLibrary(manifest, GltfLoadOptions.defaults());
    }

    /**
     * Loads an unpacked {@code haikalat.gltf-animation-library/1} manifest and merges
     * its external glTF animation sidecars into the referenced model.
     */
    public LoadedGltfScene loadAnimationLibrary(AssetRef manifest, GltfLoadOptions options) {
        return new GltfAnimationLibraryImporter(locator,
                Objects.requireNonNull(manifest, "manifest"),
                Objects.requireNonNull(options, "options")).load();
    }

    /**
     * Opens a ZIP animation library without extracting it to the filesystem.
     * The archive must contain exactly one {@code animation-library.json}.
     */
    public static LoadedGltfScene loadAnimationLibrary(Path archive) {
        return loadAnimationLibrary(archive, GltfLoadOptions.defaults());
    }

    /**
     * Opens a ZIP animation library without extracting it to the filesystem.
     * The archive must contain exactly one {@code animation-library.json}.
     */
    public static LoadedGltfScene loadAnimationLibrary(Path archive, GltfLoadOptions options) {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(options, "options");
        AssetRef source = AssetRef.of(archive.toAbsolutePath().normalize().toString());
        try (GltfAnimationArchiveResolver resolver =
                     GltfAnimationArchiveResolver.open(archive)) {
            return new GltfAnimationLibraryImporter(resolver, resolver.manifest(), options)
                    .load();
        } catch (GltfAssetException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new GltfAssetException(source, GltfAssetException.Phase.READ,
                    "archive", null,
                    failure.getMessage() == null
                            ? "could not open animation library archive"
                            : failure.getMessage(),
                    failure);
        } catch (RuntimeException failure) {
            throw new GltfAssetException(source, GltfAssetException.Phase.READ,
                    "archive", null,
                    failure.getMessage() == null
                            ? "could not open animation library archive"
                            : failure.getMessage(),
                    failure);
        }
    }

    public LoadedGltfScene load(AssetRef ref, GltfLoadOptions options,
                                GltfAnimationMetadata metadata) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(metadata, "metadata");
        if (!ref.extension().equals("gltf") && !ref.extension().equals("glb")) {
            throw error(ref, GltfAssetException.Phase.READ, "source", "expected .gltf or .glb asset");
        }
        byte[] document;
        try {
            document = locator.readBytes(ref, options.limits().documentBytes());
        } catch (RuntimeException failure) {
            throw new GltfAssetException(ref, GltfAssetException.Phase.READ, "source", null,
                    "could not read document", failure);
        }
        limit(ref, "documentBytes", document.length, options.limits().documentBytes(), "source");
        GltfDocumentReader.Document parsed = GltfDocumentReader.read(ref, document,
                ref.extension().equals("glb"));
        Map<String, Object> root = GltfDocumentReader.parseJson(ref, parsed.json());
        try {
            return new Decoder(ref, root, parsed.bin(), parsed.warnings(), options, metadata).decode();
        } catch (GltfAssetException failure) {
            throw failure;
        } catch (GltfDecodeException failure) {
            throw new GltfAssetException(ref, GltfAssetException.Phase.DECODE,
                    failure.location(), null, failure.getMessage(), failure);
        } catch (RuntimeException failure) {
            throw new GltfAssetException(ref, GltfAssetException.Phase.DECODE, "$", null,
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(), failure);
        }
    }

    private final class Decoder {
        private final AssetRef source;
        private final Map<String, Object> root;
        private final byte[] glbBin;
        private final List<String> warnings;
        private final GltfLoadOptions options;
        private final GltfAnimationMetadata metadata;
        private final GltfUriResolver uriResolver;
        private List<byte[]> buffers;
        private List<Map<String, Object>> views;
        private List<Map<String, Object>> accessors;
        private GltfBufferTable bufferTable;
        private GltfAccessorDecoder accessorDecoder;
        private int normalFallbacks;
        private int tangentFallbackTriangles;
        private int tangentFallbackVertices;
        private long decodedBufferBytes;
        private long encodedImageBytes;
        private long vertexBytes;
        private long indexBytes;

        Decoder(AssetRef source, Map<String, Object> root, byte[] glbBin,
                List<String> warnings, GltfLoadOptions options,
                GltfAnimationMetadata metadata) {
            this.source = source;
            this.root = root;
            this.glbBin = glbBin;
            this.warnings = new ArrayList<>(warnings);
            this.options = options;
            this.metadata = metadata;
            this.uriResolver = new GltfUriResolver(locator, source, glbBin, options.limits());
        }

        LoadedGltfScene decode() {
            warnings.addAll(GltfDocumentValidator.validate(source, root,
                    options.strictExtensions()));
            List<Map<String, Object>> nodeDtos = objects(root, "nodes");
            List<Map<String, Object>> meshDtos = objects(root, "meshes");
            List<Map<String, Object>> materialDtos = objects(root, "materials");
            List<Map<String, Object>> textureDtos = objects(root, "textures");
            List<Map<String, Object>> imageDtos = objects(root, "images");
            List<Map<String, Object>> samplerDtos = objects(root, "samplers");
            List<Map<String, Object>> skinDtos = objects(root, "skins");
            List<Map<String, Object>> animationDtos = objects(root, "animations");
            accessors = objects(root, "accessors");
            views = objects(root, "bufferViews");
            checkCounts(nodeDtos, meshDtos, materialDtos, textureDtos, imageDtos, samplerDtos,
                    skinDtos, animationDtos);
            GltfUriResolver.BufferResolution resolvedBuffers = uriResolver.resolveBuffers(root);
            buffers = resolvedBuffers.buffers();
            decodedBufferBytes = resolvedBuffers.decodedBytes();
            bufferTable = new GltfBufferTable(source, buffers, views);
            bufferTable.validate();
            accessorDecoder = new GltfAccessorDecoder(source, accessors, bufferTable);
            GltfMaterialDecoder materialDecoder = new GltfMaterialDecoder(source,
                    options.limits(), uriResolver, bufferTable, options.strictExtensions());
            GltfMaterialDecoder.Result materialResult = materialDecoder.decode(samplerDtos,
                    textureDtos, imageDtos, materialDtos);
            List<LoadedGltfScene.SamplerDef> samplers = materialResult.samplers();
            List<LoadedGltfScene.TextureDef> textures = materialResult.textures();
            List<LoadedGltfScene.ImageDef> images = materialResult.images();
            List<LoadedGltfScene.MaterialDef> materials = materialResult.materials();
            encodedImageBytes = materialResult.encodedImageBytes();
            int defaultMaterialIndex = materials.size();
            List<LoadedGltfScene.MaterialDef> allMaterials = new ArrayList<>(materials);
            allMaterials.add(materialDecoder.defaultMaterial(defaultMaterialIndex));
            GltfMeshCanonicalizer.Result meshResult = new GltfMeshCanonicalizer(source,
                    options.limits(), accessorDecoder)
                    .decode(meshDtos, allMaterials, defaultMaterialIndex);
            List<LoadedGltfScene.Primitive> primitives = meshResult.primitives();
            normalFallbacks = meshResult.normalFallbacks();
            tangentFallbackTriangles = meshResult.tangentFallbackTriangles();
            tangentFallbackVertices = meshResult.tangentFallbackVertices();
            vertexBytes = meshResult.vertexBytes();
            indexBytes = meshResult.indexBytes();
            List<LoadedGltfScene.SkinDef> skins = new GltfSkinDecoder(source,
                    options.limits(), accessorDecoder).decode(skinDtos, nodeDtos.size());
            GltfNodeDecoder.Result nodeResult = new GltfNodeDecoder(source, root, options)
                    .decode(nodeDtos, meshDtos.size(), skins.size(),
                            meshResult.meshMorphTargetCounts(),
                            meshResult.meshMorphDefaultWeights());
            List<LoadedGltfScene.Node> nodes = nodeResult.nodes();
            validateSkinning(nodes, nodeResult.nodeRigs(), primitives,
                    meshResult.primitiveSkinning(), skins);
            List<LoadedGltfScene.AnimationDef> animations = new GltfAnimationDecoder(source,
                    options.limits(), accessorDecoder).decode(animationDtos, nodeResult.nodeRigs(),
                    metadata);
            int reachable = (int) nodes.stream().filter(LoadedGltfScene.Node::reachable).count();
            int animationChannels = animations.stream()
                    .mapToInt(animation -> animation.channels().size()).sum();
            GltfSceneStatistics stats = new GltfSceneStatistics(nodeDtos.size(), reachable, meshDtos.size(),
                    primitives.size(), allMaterials.size(), textures.size(), images.size(), samplers.size(),
                    skins.size(), animations.size(), animationChannels,
                    normalFallbacks, tangentFallbackTriangles, tangentFallbackVertices,
                    decodedBufferBytes, encodedImageBytes, vertexBytes, indexBytes,
                    meshResult.primitiveMorphTargets().values().stream()
                            .mapToInt(value -> value.targets().size()).sum(),
                    meshResult.morphDeltaBytes());
            return new LoadedGltfScene(source, nodeResult.sceneIndex(), nodeResult.sceneName(),
                    nodeResult.roots(), nodes,
                    primitives, nodeResult.nodeRigs(), meshResult.primitiveSkinning(),
                    meshResult.primitiveMorphTargets(),
                    skins, animations, allMaterials, textures, images, samplers, warnings, stats);
        }

        private void validateSkinning(List<LoadedGltfScene.Node> nodes,
                                      List<LoadedGltfScene.NodeRigDef> nodeRigs,
                                      List<LoadedGltfScene.Primitive> primitives,
                                      Map<Integer, LoadedGltfScene.PrimitiveSkinning> skinning,
                                      List<LoadedGltfScene.SkinDef> skins) {
            for (LoadedGltfScene.NodeRigDef node : nodeRigs) {
                if (node.skinIndex() < 0) continue;
                LoadedGltfScene.SkinDef skin = skins.get(node.skinIndex());
                int meshIndex = nodes.get(node.nodeIndex()).meshIndex();
                if (meshIndex < 0) continue;
                for (LoadedGltfScene.Primitive primitive : primitives) {
                    if (primitive.meshIndex() != meshIndex) continue;
                    LoadedGltfScene.PrimitiveSkinning contract = skinning.get(primitive.index());
                    if (contract != null && contract.maxJointIndex() >= skin.joints().size()) {
                        throw fail("nodes[" + node.nodeIndex() + "].mesh",
                                "JOINTS_0 index " + contract.maxJointIndex()
                                        + " exceeds skin[" + node.skinIndex()
                                        + "] joint count " + skin.joints().size());
                    }
                }
            }
        }

        private void checkCounts(List<?> nodes, List<?> meshes, List<?> materials, List<?> textures,
                                 List<?> images, List<?> samplers, List<?> skins,
                                 List<?> animations) {
            GltfAssetLimits l = options.limits();
            limit(source, "nodes", nodes.size(), l.nodes(), "nodes");
            limit(source, "meshes", meshes.size(), l.meshes(), "meshes");
            limit(source, "materials", materials.size(), l.materials(), "materials");
            limit(source, "textures", textures.size(), l.textures(), "textures");
            limit(source, "images", images.size(), l.images(), "images");
            limit(source, "samplers", samplers.size(), l.samplers(), "samplers");
            limit(source, "accessors", accessors.size(), l.accessors(), "accessors");
            limit(source, "skins", skins.size(), l.skins(), "skins");
            limit(source, "animations", animations.size(), l.animations(), "animations");
        }

        private GltfAssetException fail(String location, String message) { return error(source, GltfAssetException.Phase.DECODE, location, message); }
        private GltfAssetException fail(String location, String message, Throwable cause) {
            return new GltfAssetException(source, GltfAssetException.Phase.DECODE, location, null, message, cause);
        }
    }

    private static GltfAssetException error(AssetRef source,GltfAssetException.Phase phase,String location,String message){return new GltfAssetException(source,phase,location,message);}
    private static GltfAssetException error(AssetRef source,GltfAssetException.Phase phase,String location,String message,Throwable cause){return new GltfAssetException(source,phase,location,null,message,cause);}


}
