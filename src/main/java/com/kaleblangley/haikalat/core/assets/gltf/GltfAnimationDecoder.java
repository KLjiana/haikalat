package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.index;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.limit;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.integer;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.object;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.objects;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.string;

/** animation sampler/channel decoding stage, including CUBICSPLINE payloads. */
final class GltfAnimationDecoder {
    private final AssetRef source;
    private final GltfAssetLimits limits;
    private final GltfAccessorDecoder accessors;

    GltfAnimationDecoder(AssetRef source, GltfAssetLimits limits,
                         GltfAccessorDecoder accessors) {
        this.source = source;
        this.limits = limits;
        this.accessors = accessors;
    }

    List<LoadedGltfScene.AnimationDef> decode(
            List<Map<String, Object>> definitions,
            List<LoadedGltfScene.NodeRigDef> nodes) {
        limit(source, "animations", definitions.size(), limits.animations(), "animations");
        List<LoadedGltfScene.AnimationDef> result = new ArrayList<>(definitions.size());
        long totalKeyframes = 0L;
        for (int animationIndex = 0; animationIndex < definitions.size(); animationIndex++) {
            Map<String, Object> definition = definitions.get(animationIndex);
            String path = "animations[" + animationIndex + "]";
            List<Map<String, Object>> samplers = objects(definition, "samplers");
            List<Map<String, Object>> channels = objects(definition, "channels");
            if (samplers.isEmpty() || channels.isEmpty()) {
                throw fail(path, "animation must contain samplers and channels");
            }
            limit(source, "animationChannels", channels.size(),
                    limits.animationChannels(), path + ".channels");
            Set<String> targets = new HashSet<>();
            List<LoadedGltfScene.AnimationChannelDef> decoded = new ArrayList<>(channels.size());
            float duration = 0.0f;
            for (int channelIndex = 0; channelIndex < channels.size(); channelIndex++) {
                Map<String, Object> channel = channels.get(channelIndex);
                String channelPath = path + ".channels[" + channelIndex + "]";
                int samplerIndex = integer(channel, "sampler", true, channelPath + ".sampler");
                index(samplerIndex, samplers.size(), channelPath + ".sampler");
                Map<String, Object> target = object(channel, "target", true,
                        channelPath + ".target");
                int nodeIndex = integer(target, "node", true, channelPath + ".target.node");
                index(nodeIndex, nodes.size(), channelPath + ".target.node");
                LoadedGltfScene.AnimationTargetPath targetPath = targetPath(
                        string(target, "path", true, channelPath + ".target.path"),
                        channelPath + ".target.path");
                int componentCount = targetPath == LoadedGltfScene.AnimationTargetPath.WEIGHTS
                        ? nodes.get(nodeIndex).morphWeights().length : targetPath.components();
                if (componentCount == 0) {
                    throw fail(channelPath + ".target.node",
                            "weights animation requires a node with morph targets");
                }
                if (targetPath != LoadedGltfScene.AnimationTargetPath.WEIGHTS
                        && nodes.get(nodeIndex).matrixAuthored()) {
                    throw fail(channelPath + ".target.node",
                            "TRS animation cannot target a matrix-authored node");
                }
                if (!targets.add(nodeIndex + ":" + targetPath)) {
                    throw fail(channelPath + ".target",
                            "duplicate animation target path for node " + nodeIndex);
                }

                Map<String, Object> sampler = samplers.get(samplerIndex);
                String samplerPath = path + ".samplers[" + samplerIndex + "]";
                LoadedGltfScene.AnimationInterpolation interpolation = interpolation(
                        string(sampler, "interpolation", false,
                                samplerPath + ".interpolation"), samplerPath + ".interpolation");
                int input = integer(sampler, "input", true, samplerPath + ".input");
                float[] times = accessors.floats(input, 1,
                        GltfAccessorDecoder.NO_NORMALIZED_COMPONENTS, samplerPath + ".input");
                validateTimes(times, samplerPath + ".input");
                int output = integer(sampler, "output", true, samplerPath + ".output");
                int accessorComponents = targetPath == LoadedGltfScene.AnimationTargetPath.WEIGHTS
                        ? 1 : componentCount;
                float[] values = accessors.floats(output, accessorComponents,
                        GltfAccessorDecoder.NO_NORMALIZED_COMPONENTS, samplerPath + ".output");
                int factor = interpolation == LoadedGltfScene.AnimationInterpolation.CUBIC_SPLINE
                        ? 3 : 1;
                int expected = Math.multiplyExact(
                        Math.multiplyExact(times.length, factor), componentCount);
                if (values.length != expected) {
                    throw fail(samplerPath + ".output", "output count must be " + factor
                            + " value tuple(s) per input keyframe");
                }
                totalKeyframes = Math.addExact(totalKeyframes, times.length);
                limit(source, "animationKeyframes", totalKeyframes,
                        limits.animationKeyframes(), "animations");
                duration = Math.max(duration, times[times.length - 1]);
                decoded.add(new LoadedGltfScene.AnimationChannelDef(nodeIndex, targetPath,
                        componentCount, interpolation, times, values));
            }
            result.add(new LoadedGltfScene.AnimationDef(animationIndex,
                    Objects.toString(definition.get("name"), ""), decoded, duration));
        }
        return List.copyOf(result);
    }

    private void validateTimes(float[] times, String path) {
        if (times.length == 0) throw fail(path, "animation input must not be empty");
        float previous = -1.0f;
        for (int index = 0; index < times.length; index++) {
            float value = times[index];
            if (!Float.isFinite(value) || value < 0.0f
                    || index > 0 && value <= previous) {
                throw fail(path, "times must be finite, non-negative, and strictly increasing");
            }
            previous = value;
        }
    }

    private LoadedGltfScene.AnimationTargetPath targetPath(String value, String path) {
        try {
            return LoadedGltfScene.AnimationTargetPath.valueOf(
                    value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw fail(path, "unsupported animation target path " + value);
        }
    }

    private LoadedGltfScene.AnimationInterpolation interpolation(String value, String path) {
        String resolved = value == null ? "LINEAR" : value;
        return switch (resolved) {
            case "STEP" -> LoadedGltfScene.AnimationInterpolation.STEP;
            case "LINEAR" -> LoadedGltfScene.AnimationInterpolation.LINEAR;
            case "CUBICSPLINE" -> LoadedGltfScene.AnimationInterpolation.CUBIC_SPLINE;
            default -> throw fail(path, "unsupported interpolation " + resolved);
        };
    }

    private GltfAssetException fail(String location, String message) {
        return new GltfAssetException(source, GltfAssetException.Phase.DECODE,
                location, message);
    }
}
