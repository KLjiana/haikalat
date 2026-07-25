package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.*;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.*;

/** glTF 2.0 scene、skin 与 animation 的纯 JVM 加载入口。 */
public final class GltfAssetLoader {
    private final ResourceLocator locator;

    public GltfAssetLoader(ResourceLocator locator) {
        this.locator = Objects.requireNonNull(locator, "locator");
    }

    public LoadedGltfScene load(AssetRef ref) {
        return load(ref, GltfLoadOptions.defaults());
    }

    public LoadedGltfScene load(AssetRef ref, GltfLoadOptions options) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(options, "options");
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
            return new Decoder(ref, root, parsed.bin(), parsed.warnings(), options).decode();
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
                List<String> warnings, GltfLoadOptions options) {
            this.source = source;
            this.root = root;
            this.glbBin = glbBin;
            this.warnings = new ArrayList<>(warnings);
            this.options = options;
            this.uriResolver = new GltfUriResolver(locator, source, glbBin, options.limits());
        }

        LoadedGltfScene decode() {
            validateAsset();
            validateExtensions();
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
                    options.limits(), uriResolver, bufferTable);
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
                    .decode(nodeDtos, meshDtos.size(), skins.size());
            List<LoadedGltfScene.Node> nodes = nodeResult.nodes();
            validateSkinning(nodes, nodeResult.nodeRigs(), primitives,
                    meshResult.primitiveSkinning(), skins);
            List<LoadedGltfScene.AnimationDef> animations = new GltfAnimationDecoder(source,
                    options.limits(), accessorDecoder).decode(animationDtos, nodeResult.nodeRigs());
            int reachable = (int) nodes.stream().filter(LoadedGltfScene.Node::reachable).count();
            int animationChannels = animations.stream()
                    .mapToInt(animation -> animation.channels().size()).sum();
            GltfSceneStatistics stats = new GltfSceneStatistics(nodeDtos.size(), reachable, meshDtos.size(),
                    primitives.size(), allMaterials.size(), textures.size(), images.size(), samplers.size(),
                    skins.size(), animations.size(), animationChannels,
                    normalFallbacks, tangentFallbackTriangles, tangentFallbackVertices,
                    decodedBufferBytes, encodedImageBytes, vertexBytes, indexBytes);
            return new LoadedGltfScene(source, nodeResult.sceneIndex(), nodeResult.sceneName(),
                    nodeResult.roots(), nodes,
                    primitives, nodeResult.nodeRigs(), meshResult.primitiveSkinning(),
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

        private void validateAsset() {
            Map<String, Object> asset = object(root, "asset", true, "asset");
            String version = string(asset, "version", true, "asset.version");
            if (!validVersion(version) || !version.startsWith("2.")) {
                throw fail("asset.version", "only numeric glTF 2.x versions are supported, got " + version);
            }
            String min = string(asset, "minVersion", false, "asset.minVersion");
            if (min != null && (!validVersion(min) || compareVersion(min, "2.0") > 0)) {
                throw fail("asset.minVersion", "requires unsupported glTF " + min);
            }
        }

        private void validateExtensions() {
            Set<String> required = new HashSet<>(strings(root.get("extensionsRequired"), "extensionsRequired"));
            if (!required.isEmpty()) throw fail("extensionsRequired", "unsupported required extensions " + required);
            List<String> used = strings(root.get("extensionsUsed"), "extensionsUsed");
            if (!used.isEmpty()) warnings.add("ignored optional extensions: " + used);
            List<String> payloads = new ArrayList<>();
            collectExtensionPayloads(root, "$", payloads);
            if (!payloads.isEmpty()) {
                if (options.strictExtensions()) {
                    String first = payloads.getFirst();
                    int separator = first.indexOf(':');
                    throw fail(first.substring(0, separator),
                            "unsupported extension payload " + first.substring(separator + 1));
                }
                warnings.add("ignored extension payloads by explicit lenient policy: " + payloads);
            }
        }

        private void collectExtensionPayloads(Object value, String path, List<String> output) {
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String key = Objects.toString(entry.getKey());
                    String childPath = path + "." + key;
                    if (key.equals("extensions")) {
                        Map<String, Object> extensions = map(entry.getValue(), childPath);
                        if (extensions != null) {
                            for (String extension : extensions.keySet()) {
                                output.add(childPath + ":" + extension);
                            }
                        }
                    } else {
                        collectExtensionPayloads(entry.getValue(), childPath, output);
                    }
                }
            } else if (value instanceof List<?> list) {
                for (int index = 0; index < list.size(); index++) {
                    collectExtensionPayloads(list.get(index), path + "[" + index + "]", output);
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
