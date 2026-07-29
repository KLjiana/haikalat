package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetByteResolver;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.limit;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.objects;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.string;

/** Decodes animation-only glTF JSON and remaps its node table onto a model. */
final class GltfExternalAnimationDecoder {
    private GltfExternalAnimationDecoder() {
    }

    static Result decode(AssetByteResolver resolver,
                         GltfAnimationLibraryImporter.AnimationEntry entry,
                         LoadedGltfScene model, int animationIndex,
                         GltfLoadOptions options) {
        AssetRef source = entry.file();
        byte[] bytes;
        try {
            bytes = resolver.readBytes(source, options.limits().documentBytes());
        } catch (RuntimeException failure) {
            throw new GltfAssetException(source, GltfAssetException.Phase.READ,
                    "source", null, "could not read external animation document", failure);
        }
        Map<String, Object> root = GltfDocumentReader.parseJson(source, bytes);
        try {
            return decodeRoot(resolver, entry, model, animationIndex, options, root);
        } catch (GltfAssetException failure) {
            throw failure;
        } catch (GltfDecodeException failure) {
            throw new GltfAssetException(source, GltfAssetException.Phase.DECODE,
                    failure.location(), null, failure.getMessage(), failure);
        } catch (RuntimeException failure) {
            throw new GltfAssetException(source, GltfAssetException.Phase.DECODE,
                    "$", null,
                    failure.getMessage() == null
                            ? failure.getClass().getSimpleName()
                            : failure.getMessage(),
                    failure);
        }
    }

    private static Result decodeRoot(
            AssetByteResolver resolver,
            GltfAnimationLibraryImporter.AnimationEntry entry,
            LoadedGltfScene model, int animationIndex, GltfLoadOptions options,
            Map<String, Object> root) {
        AssetRef source = entry.file();
        List<String> warnings = GltfDocumentValidator.validate(source, root,
                options.strictExtensions());
        List<Map<String, Object>> nodes = objects(root, "nodes");
        List<Map<String, Object>> accessors = objects(root, "accessors");
        List<Map<String, Object>> views = objects(root, "bufferViews");
        List<Map<String, Object>> animationDtos = objects(root, "animations");
        if (nodes.isEmpty()) {
            throw fail(source, "nodes",
                    "external animation document must contain a node table");
        }
        if (animationDtos.size() != 1) {
            throw fail(source, "animations",
                    "each library sidecar must contain exactly one animation, found "
                            + animationDtos.size());
        }
        limit(source, "nodes", nodes.size(), options.limits().nodes(), "nodes");
        limit(source, "accessors", accessors.size(), options.limits().accessors(),
                "accessors");
        limit(source, "animations", animationDtos.size(),
                options.limits().animations(), "animations");
        if (entry.selfContained()) validateSelfContained(source, root);

        GltfUriResolver.BufferResolution resolved =
                new GltfUriResolver(resolver, source, null, options.limits())
                        .resolveBuffers(root);
        GltfBufferTable buffers = new GltfBufferTable(source,
                resolved.buffers(), views);
        buffers.validate();
        GltfAccessorDecoder accessorDecoder =
                new GltfAccessorDecoder(source, accessors, buffers);

        int[] sourceNodes = validateBindings(source, entry, nodes, model);
        List<LoadedGltfScene.NodeRigDef> sidecarRigs =
                sidecarRigs(sourceNodes, model);
        LoadedGltfScene.AnimationDef decoded =
                new GltfAnimationDecoder(source, options.limits(), accessorDecoder)
                        .decode(animationDtos, sidecarRigs).getFirst();

        List<LoadedGltfScene.AnimationChannelDef> remapped =
                new ArrayList<>(decoded.channels().size());
        for (int channelIndex = 0;
             channelIndex < decoded.channels().size(); channelIndex++) {
            LoadedGltfScene.AnimationChannelDef channel =
                    decoded.channels().get(channelIndex);
            int target = sourceNodes[channel.nodeIndex()];
            if (target < 0) {
                throw fail(source,
                        "animations[0].channels[" + channelIndex + "].target.node",
                        "animation target node " + channel.nodeIndex()
                                + " has no manifest node binding");
            }
            remapped.add(new LoadedGltfScene.AnimationChannelDef(target,
                    channel.path(), channel.componentCount(), channel.interpolation(),
                    channel.timesSeconds(), channel.values()));
        }
        return new Result(new LoadedGltfScene.AnimationDef(animationIndex,
                entry.name(), remapped, decoded.durationSeconds(), decoded.markers()),
                warnings, resolved.decodedBytes());
    }

    private static int[] validateBindings(
            AssetRef source,
            GltfAnimationLibraryImporter.AnimationEntry entry,
            List<Map<String, Object>> sidecarNodes, LoadedGltfScene model) {
        if (model.nodeRigs().size() != model.nodes().size()) {
            throw fail(source, "model.nodes",
                    "model node and rig tables are not aligned");
        }
        int[] sourceNodes = new int[sidecarNodes.size()];
        Arrays.fill(sourceNodes, -1);
        Set<Integer> mappedSources = new HashSet<>();
        for (GltfAnimationLibraryImporter.NodeBinding binding : entry.bindings()) {
            if (binding.sidecarNode() >= sidecarNodes.size()) {
                throw fail(entry.manifest(), binding.location() + ".sidecarNode",
                        "index " + binding.sidecarNode() + " outside 0.."
                                + (sidecarNodes.size() - 1));
            }
            if (binding.sourceNode() >= model.nodes().size()) {
                throw fail(entry.manifest(), binding.location() + ".sourceNode",
                        "index " + binding.sourceNode() + " outside 0.."
                                + (model.nodes().size() - 1));
            }
            if (sourceNodes[binding.sidecarNode()] >= 0) {
                throw fail(entry.manifest(), binding.location() + ".sidecarNode",
                        "duplicate sidecar node " + binding.sidecarNode());
            }
            if (!mappedSources.add(binding.sourceNode())) {
                throw fail(entry.manifest(), binding.location() + ".sourceNode",
                        "duplicate source node " + binding.sourceNode());
            }
            String sidecarName = Objects.toString(
                    sidecarNodes.get(binding.sidecarNode()).get("name"), "");
            String modelName = model.nodes().get(binding.sourceNode()).name();
            if (!binding.name().equals(sidecarName)) {
                throw fail(entry.manifest(), binding.location() + ".name",
                        "binding name '" + binding.name()
                                + "' differs from sidecar node name '" + sidecarName + "'");
            }
            if (!binding.name().equals(modelName)) {
                throw fail(entry.manifest(), binding.location() + ".name",
                        "binding name '" + binding.name()
                                + "' differs from model node name '" + modelName + "'");
            }
            sourceNodes[binding.sidecarNode()] = binding.sourceNode();
        }
        return sourceNodes;
    }

    private static List<LoadedGltfScene.NodeRigDef> sidecarRigs(
            int[] sourceNodes, LoadedGltfScene model) {
        List<LoadedGltfScene.NodeRigDef> result =
                new ArrayList<>(sourceNodes.length);
        for (int sidecarNode = 0;
             sidecarNode < sourceNodes.length; sidecarNode++) {
            int sourceNode = sourceNodes[sidecarNode];
            if (sourceNode < 0) {
                result.add(new LoadedGltfScene.NodeRigDef(sidecarNode, -1, -1,
                        new Vector3f(), new Quaternionf(),
                        new Vector3f(1.0f), false, new float[0]));
                continue;
            }
            LoadedGltfScene.NodeRigDef modelRig = model.nodeRigs().get(sourceNode);
            result.add(new LoadedGltfScene.NodeRigDef(sidecarNode, -1, -1,
                    modelRig.translation(), modelRig.rotation(), modelRig.scale(),
                    modelRig.matrixAuthored(), modelRig.morphWeights()));
        }
        return List.copyOf(result);
    }

    private static void validateSelfContained(AssetRef source,
                                              Map<String, Object> root) {
        List<Map<String, Object>> buffers = objects(root, "buffers");
        for (int index = 0; index < buffers.size(); index++) {
            String path = "buffers[" + index + "].uri";
            String uri = string(buffers.get(index), "uri", true, path);
            if (!uri.startsWith("data:")) {
                throw fail(source, path,
                        "selfContained animation sidecars must embed every buffer");
            }
        }
    }

    private static GltfAssetException fail(AssetRef source, String location,
                                           String message) {
        return new GltfAssetException(source, GltfAssetException.Phase.DECODE,
                location, message);
    }

    record Result(LoadedGltfScene.AnimationDef animation,
                  List<String> warnings, long decodedBufferBytes) {
        Result {
            warnings = List.copyOf(warnings);
        }
    }
}
