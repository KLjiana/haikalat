package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetByteResolver;
import com.kaleblangley.haikalat.core.assets.AssetRef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.limit;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.validVersion;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.bool;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.integer;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.objects;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.string;

/** Imports a v1 external glTF animation library into an ordinary loaded scene. */
final class GltfAnimationLibraryImporter {
    private static final String SCHEMA = "haikalat.gltf-animation-library/1";

    private final AssetByteResolver resolver;
    private final AssetRef manifest;
    private final GltfLoadOptions options;

    GltfAnimationLibraryImporter(AssetByteResolver resolver, AssetRef manifest,
                                 GltfLoadOptions options) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.options = Objects.requireNonNull(options, "options");
    }

    LoadedGltfScene load() {
        byte[] bytes;
        try {
            bytes = resolver.readBytes(manifest, options.limits().documentBytes());
        } catch (RuntimeException failure) {
            throw new GltfAssetException(manifest, GltfAssetException.Phase.READ,
                    "manifest", null, "could not read animation library manifest", failure);
        }
        Map<String, Object> root = GltfDocumentReader.parseJson(manifest, bytes);
        try {
            return decode(root);
        } catch (GltfAssetException failure) {
            throw failure;
        } catch (GltfDecodeException failure) {
            throw new GltfAssetException(manifest, GltfAssetException.Phase.DECODE,
                    failure.location(), null, failure.getMessage(), failure);
        } catch (RuntimeException failure) {
            throw new GltfAssetException(manifest, GltfAssetException.Phase.DECODE,
                    "$", null,
                    failure.getMessage() == null
                            ? failure.getClass().getSimpleName()
                            : failure.getMessage(),
                    failure);
        }
    }

    private LoadedGltfScene decode(Map<String, Object> root) {
        String schema = requiredText(root, "schema", "schema");
        if (!SCHEMA.equals(schema)) {
            throw fail("schema", "unsupported animation library schema " + schema);
        }
        String gltfVersion = requiredText(root, "gltfVersion", "gltfVersion");
        if (!validVersion(gltfVersion) || !gltfVersion.startsWith("2.")) {
            throw fail("gltfVersion",
                    "only numeric glTF 2.x animation libraries are supported, got "
                            + gltfVersion);
        }
        String nodeBinding = requiredText(root, "nodeBinding", "nodeBinding");
        if (!nodeBinding.equals("name")) {
            throw fail("nodeBinding", "unsupported node binding mode " + nodeBinding);
        }

        String modelPath = requiredText(root, "model", "model");
        AssetRef modelRef = resolve(manifest, modelPath, "model");
        LoadedGltfScene model = new GltfAssetLoader(resolver).load(modelRef, options);

        List<Map<String, Object>> definitions = objects(root, "animations");
        if (definitions.isEmpty()) {
            throw fail("animations", "animation library must contain at least one animation");
        }
        int finalAnimationCount;
        try {
            finalAnimationCount = Math.addExact(model.animations().size(),
                    definitions.size());
        } catch (ArithmeticException failure) {
            throw fail("animations", "animation count overflows", failure);
        }
        limit(manifest, "animations", finalAnimationCount,
                options.limits().animations(), "animations");

        List<LoadedGltfScene.AnimationDef> animations =
                new ArrayList<>(finalAnimationCount);
        animations.addAll(model.animations());
        Set<String> animationNames = new HashSet<>();
        for (LoadedGltfScene.AnimationDef animation : model.animations()) {
            if (!animationNames.add(animation.name())) {
                throw fail("animations", "model contains duplicate animation name "
                        + animation.name());
            }
        }

        long decodedBufferBytes = model.statistics().decodedBufferBytes();
        long externalDataUriBytes = 0L;
        long keyframes = countKeyframes(model.animations());
        List<String> warnings = new ArrayList<>();
        for (int index = 0; index < definitions.size(); index++) {
            AnimationEntry entry = decodeEntry(definitions.get(index), index);
            if (!animationNames.add(entry.name())) {
                throw fail("animations[" + index + "].name",
                        "duplicate animation name " + entry.name());
            }
            GltfExternalAnimationDecoder.Result decoded =
                    GltfExternalAnimationDecoder.decode(resolver, entry, model,
                            animations.size(), options);
            animations.add(decoded.animation());
            for (String warning : decoded.warnings()) {
                warnings.add(entry.file().path() + ": " + warning);
            }
            decodedBufferBytes = checkedAdd(decodedBufferBytes,
                    decoded.decodedBufferBytes(), "decodedBufferBytes");
            limit(manifest, "decodedBufferBytes", decodedBufferBytes,
                    options.limits().decodedBufferBytes(), "animations");
            if (entry.selfContained()) {
                externalDataUriBytes = checkedAdd(externalDataUriBytes,
                        decoded.decodedBufferBytes(), "dataUriBytes");
                limit(manifest, "dataUriBytes", externalDataUriBytes,
                        options.limits().dataUriBytes(), "animations");
            }
            keyframes = checkedAdd(keyframes,
                    countKeyframes(List.of(decoded.animation())),
                    "animationKeyframes");
            limit(manifest, "animationKeyframes", keyframes,
                    options.limits().animationKeyframes(), "animations");
        }
        long extraDecodedBufferBytes =
                decodedBufferBytes - model.statistics().decodedBufferBytes();
        return model.withExternalAnimations(animations, warnings,
                extraDecodedBufferBytes);
    }

    private AnimationEntry decodeEntry(Map<String, Object> definition, int index) {
        String path = "animations[" + index + "]";
        String name = requiredText(definition, "name", path + ".name");
        String filePath = requiredText(definition, "file", path + ".file");
        AssetRef file = resolve(manifest, filePath, path + ".file");
        if (!file.path().toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
            throw fail(path + ".file",
                    "external animation sidecar must be a JSON document");
        }
        boolean selfContained = bool(definition, "selfContained", false,
                path + ".selfContained");
        List<Map<String, Object>> bindingDtos = objects(definition, "nodeBindings");
        limit(manifest, "nodeBindings", bindingDtos.size(),
                options.limits().nodes(), path + ".nodeBindings");
        List<NodeBinding> bindings = new ArrayList<>(bindingDtos.size());
        Set<Integer> sidecarNodes = new HashSet<>();
        Set<Integer> sourceNodes = new HashSet<>();
        for (int bindingIndex = 0; bindingIndex < bindingDtos.size(); bindingIndex++) {
            Map<String, Object> binding = bindingDtos.get(bindingIndex);
            String bindingPath = path + ".nodeBindings[" + bindingIndex + "]";
            int sidecarNode = integer(binding, "sidecarNode", true,
                    bindingPath + ".sidecarNode");
            int sourceNode = integer(binding, "sourceNode", true,
                    bindingPath + ".sourceNode");
            if (sidecarNode < 0 || sourceNode < 0) {
                throw fail(bindingPath, "node indices must be non-negative");
            }
            if (!sidecarNodes.add(sidecarNode)) {
                throw fail(bindingPath + ".sidecarNode",
                        "duplicate sidecar node " + sidecarNode);
            }
            if (!sourceNodes.add(sourceNode)) {
                throw fail(bindingPath + ".sourceNode",
                        "duplicate source node " + sourceNode);
            }
            bindings.add(new NodeBinding(sidecarNode, sourceNode,
                    requiredText(binding, "name", bindingPath + ".name"),
                    bindingPath));
        }
        return new AnimationEntry(name, file, selfContained, bindings, manifest);
    }

    private AssetRef resolve(AssetRef owner, String uri, String location) {
        try {
            return resolver.resolveRelative(owner, uri);
        } catch (RuntimeException failure) {
            throw new GltfAssetException(manifest, GltfAssetException.Phase.RESOLVE,
                    location, uri, failure.getMessage(), failure);
        }
    }

    private String requiredText(Map<String, Object> owner, String key, String path) {
        String value = string(owner, key, true, path);
        if (value.isBlank()) throw fail(path, "must not be blank");
        return value;
    }

    private long countKeyframes(List<LoadedGltfScene.AnimationDef> animations) {
        long count = 0L;
        for (LoadedGltfScene.AnimationDef animation : animations) {
            for (LoadedGltfScene.AnimationChannelDef channel : animation.channels()) {
                count = checkedAdd(count, channel.timesSeconds().length,
                        "animationKeyframes");
            }
        }
        return count;
    }

    private long checkedAdd(long left, long right, String name) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException failure) {
            throw fail("animations", name + " total overflows", failure);
        }
    }

    private GltfAssetException fail(String location, String message) {
        return new GltfAssetException(manifest, GltfAssetException.Phase.DECODE,
                location, message);
    }

    private GltfAssetException fail(String location, String message, Throwable cause) {
        return new GltfAssetException(manifest, GltfAssetException.Phase.DECODE,
                location, null, message, cause);
    }

    record AnimationEntry(String name, AssetRef file, boolean selfContained,
                          List<NodeBinding> bindings, AssetRef manifest) {
        AnimationEntry {
            bindings = List.copyOf(bindings);
        }
    }

    record NodeBinding(int sidecarNode, int sourceNode, String name, String location) {
    }
}
