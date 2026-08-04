package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.limit;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.decimal;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.floatArray;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.objects;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.string;

/** Decodes the compact, name-bound animation JSON emitted by Haikalat tools. */
final class HaikalatAnimationClipDecoder {
    private static final String SCHEMA = "haikalat.animation-clip/1";

    private HaikalatAnimationClipDecoder() {
    }

    static boolean matches(Map<String, Object> root) {
        return SCHEMA.equals(root.get("schema"));
    }

    static GltfExternalAnimationDecoder.Result decode(
            GltfAnimationLibraryImporter.AnimationEntry entry,
            LoadedGltfScene model, int animationIndex,
            GltfLoadOptions options, Map<String, Object> root) {
        AssetRef source = entry.file();
        String documentName = requiredText(source, root, "name", "name");
        if (!documentName.equals(entry.name())) {
            throw fail(source, "name", "clip name '" + documentName
                    + "' differs from manifest name '" + entry.name() + "'");
        }
        float duration = decimal(root, "duration", Float.NaN, "$");
        if (!Float.isFinite(duration) || duration < 0.0f) {
            throw fail(source, "duration", "must be finite and non-negative");
        }
        float fps = decimal(root, "fps", 30.0f, "$");
        if (!Float.isFinite(fps) || fps <= 0.0f) {
            throw fail(source, "fps", "must be finite and positive");
        }
        validateLoop(source, string(root, "loop", false, "loop"));

        Map<String, Integer> nodes = nodeBindings(source, entry, model);
        List<Map<String, Object>> trackDtos = objects(root, "tracks");
        if (trackDtos.isEmpty()) {
            throw fail(source, "tracks", "animation clip must contain at least one track");
        }
        limit(source, "animationChannels", trackDtos.size(),
                options.limits().animationChannels(), "tracks");

        List<LoadedGltfScene.AnimationChannelDef> channels =
                new ArrayList<>(trackDtos.size());
        Set<String> targets = new HashSet<>();
        long keyframeCount = 0L;
        for (int trackIndex = 0; trackIndex < trackDtos.size(); trackIndex++) {
            Map<String, Object> track = trackDtos.get(trackIndex);
            String path = "tracks[" + trackIndex + "]";
            String nodeName = requiredText(source, track, "node", path + ".node");
            Integer nodeIndex = nodes.get(nodeName);
            if (nodeIndex == null) {
                throw fail(source, path + ".node",
                        "no model node named '" + nodeName + "'");
            }
            LoadedGltfScene.AnimationTargetPath targetPath = targetPath(source,
                    requiredText(source, track, "path", path + ".path"), path + ".path");
            if (!targets.add(nodeIndex + ":" + targetPath)) {
                throw fail(source, path,
                        "duplicate animation target " + nodeName + "/" + targetPath);
            }
            LoadedGltfScene.NodeRigDef rig = model.nodeRigs().get(nodeIndex);
            if (targetPath != LoadedGltfScene.AnimationTargetPath.WEIGHTS
                    && rig.matrixAuthored()) {
                throw fail(source, path + ".node",
                        "TRS animation cannot target a matrix-authored node");
            }
            int components = targetPath == LoadedGltfScene.AnimationTargetPath.WEIGHTS
                    ? rig.morphWeights().length : targetPath.components();
            if (components == 0) {
                throw fail(source, path + ".node",
                        "weights animation requires a node with morph targets");
            }
            LoadedGltfScene.AnimationInterpolation interpolation = interpolation(source,
                    string(track, "interpolation", false, path + ".interpolation"),
                    path + ".interpolation");
            List<Map<String, Object>> keyframes = objects(track, "keyframes");
            if (keyframes.isEmpty()) {
                throw fail(source, path + ".keyframes", "must not be empty");
            }
            keyframeCount = checkedAdd(source, keyframeCount, keyframes.size(), path);
            limit(source, "animationKeyframes", keyframeCount,
                    options.limits().animationKeyframes(), "tracks");

            float[] times = new float[keyframes.size()];
            float[] values = new float[Math.multiplyExact(keyframes.size(), components)];
            float previous = -1.0f;
            for (int keyIndex = 0; keyIndex < keyframes.size(); keyIndex++) {
                Map<String, Object> keyframe = keyframes.get(keyIndex);
                String keyPath = path + ".keyframes[" + keyIndex + "]";
                float time = decimal(keyframe, "time", Float.NaN, keyPath);
                if (!Float.isFinite(time) || time < 0.0f
                        || keyIndex > 0 && time <= previous || time > duration) {
                    throw fail(source, keyPath + ".time",
                            "must be finite, strictly increasing, and within duration");
                }
                float[] value = floatArray(keyframe.get("value"), components,
                        keyPath + ".value");
                if (targetPath == LoadedGltfScene.AnimationTargetPath.ROTATION) {
                    validateQuaternion(source, value, keyPath + ".value");
                }
                times[keyIndex] = time;
                System.arraycopy(value, 0, values, keyIndex * components, components);
                previous = time;
            }
            channels.add(new LoadedGltfScene.AnimationChannelDef(nodeIndex, targetPath,
                    components, interpolation, times, values));
        }

        List<LoadedGltfScene.AnimationMarkerDef> markers =
                decodeEvents(source, root, duration, fps);
        LoadedGltfScene.AnimationDef animation = new LoadedGltfScene.AnimationDef(
                animationIndex, entry.name(), channels, duration, markers);
        return new GltfExternalAnimationDecoder.Result(animation, List.of(), 0L);
    }

    private static Map<String, Integer> nodeBindings(
            AssetRef source, GltfAnimationLibraryImporter.AnimationEntry entry,
            LoadedGltfScene model) {
        if (model.nodeRigs().size() != model.nodes().size()) {
            throw fail(source, "model.nodes", "model node and rig tables are not aligned");
        }
        Map<String, Integer> modelNodes = new HashMap<>();
        Set<String> ambiguousNames = new HashSet<>();
        for (LoadedGltfScene.Node node : model.nodes()) {
            if (node.name().isBlank()) continue;
            Integer previous = modelNodes.putIfAbsent(node.name(), node.index());
            if (previous != null) ambiguousNames.add(node.name());
        }
        if (entry.bindings().isEmpty()) {
            ambiguousNames.forEach(modelNodes::remove);
            return Map.copyOf(modelNodes);
        }

        Map<String, Integer> result = new HashMap<>();
        for (GltfAnimationLibraryImporter.NodeBinding binding : entry.bindings()) {
            if (binding.sourceNode() >= model.nodes().size()) {
                throw fail(entry.manifest(), binding.location() + ".sourceNode",
                        "index " + binding.sourceNode() + " outside model node table");
            }
            String modelName = model.nodes().get(binding.sourceNode()).name();
            if (!binding.name().equals(modelName)) {
                throw fail(entry.manifest(), binding.location() + ".name",
                        "binding name '" + binding.name()
                                + "' differs from model node name '" + modelName + "'");
            }
            if (result.putIfAbsent(binding.name(), binding.sourceNode()) != null) {
                throw fail(entry.manifest(), binding.location() + ".name",
                        "duplicate binding name '" + binding.name() + "'");
            }
        }
        return Map.copyOf(result);
    }

    private static List<LoadedGltfScene.AnimationMarkerDef> decodeEvents(
            AssetRef source, Map<String, Object> root, float duration, float fps) {
        List<Map<String, Object>> events = objects(root, "events");
        if (events.isEmpty()) return List.of();
        List<LoadedGltfScene.AnimationMarkerDef> markers = new ArrayList<>(events.size());
        for (int index = 0; index < events.size(); index++) {
            Map<String, Object> event = events.get(index);
            String path = "events[" + index + "]";
            String name = requiredText(source, event, "name", path + ".name");
            float time = decimal(event, "timeSeconds", Float.NaN, path);
            if (Float.isNaN(time)) time = decimal(event, "time", Float.NaN, path);
            if (Float.isNaN(time)) {
                float frame = decimal(event, "frame", Float.NaN, path);
                if (!Float.isNaN(frame)) time = frame / fps;
            }
            if (!Float.isFinite(time) || time < 0.0f || time > duration) {
                throw fail(source, path, "event time must be within animation duration");
            }
            markers.add(new LoadedGltfScene.AnimationMarkerDef(time, name,
                    string(event, "priority", false, path + ".priority")));
        }
        return List.copyOf(markers);
    }

    private static LoadedGltfScene.AnimationTargetPath targetPath(
            AssetRef source, String value, String path) {
        try {
            return LoadedGltfScene.AnimationTargetPath.valueOf(
                    value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw fail(source, path, "unsupported animation target path " + value);
        }
    }

    private static LoadedGltfScene.AnimationInterpolation interpolation(
            AssetRef source, String value, String path) {
        String resolved = value == null ? "linear" : value.toLowerCase(Locale.ROOT);
        return switch (resolved) {
            case "step" -> LoadedGltfScene.AnimationInterpolation.STEP;
            case "linear" -> LoadedGltfScene.AnimationInterpolation.LINEAR;
            default -> throw fail(source, path, "unsupported interpolation " + resolved);
        };
    }

    private static void validateLoop(AssetRef source, String value) {
        if (value == null) return;
        if (!value.equals("loop") && !value.equals("once") && !value.equals("hold")) {
            throw fail(source, "loop", "unsupported loop mode " + value);
        }
    }

    private static void validateQuaternion(AssetRef source, float[] value, String path) {
        float lengthSquared = value[0] * value[0] + value[1] * value[1]
                + value[2] * value[2] + value[3] * value[3];
        if (!Float.isFinite(lengthSquared) || lengthSquared <= 1.0e-12f) {
            throw fail(source, path, "rotation quaternion must be non-zero");
        }
    }

    private static String requiredText(AssetRef source, Map<String, Object> owner,
                                       String key, String path) {
        String value = string(owner, key, true, path);
        if (value.isBlank()) throw fail(source, path, "must not be blank");
        return value;
    }

    private static long checkedAdd(AssetRef source, long left, long right, String path) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException failure) {
            throw new GltfAssetException(source, GltfAssetException.Phase.DECODE,
                    path, null, "animation keyframe total overflows", failure);
        }
    }

    private static GltfAssetException fail(AssetRef source, String location,
                                           String message) {
        return new GltfAssetException(source, GltfAssetException.Phase.DECODE,
                location, message);
    }
}
