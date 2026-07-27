package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.decimal;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.object;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.objects;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.string;

/**
 * Optional animation metadata loaded from a glTF animation {@code extras} object or a
 * {@code .animation.json} sidecar.
 */
public final class GltfAnimationMetadata {
    private final Map<String, List<LoadedGltfScene.AnimationMarkerDef>> markers;

    public GltfAnimationMetadata(
            Map<String, ? extends List<LoadedGltfScene.AnimationMarkerDef>> markers) {
        Objects.requireNonNull(markers, "markers");
        LinkedHashMap<String, List<LoadedGltfScene.AnimationMarkerDef>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends List<LoadedGltfScene.AnimationMarkerDef>> entry
                : markers.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "animation name");
            if (name.isBlank()) throw new IllegalArgumentException("animation name must not be blank");
            copy.put(name, List.copyOf(entry.getValue()));
        }
        this.markers = Map.copyOf(copy);
    }

    public static GltfAnimationMetadata empty() {
        return new GltfAnimationMetadata(Map.of());
    }

    public List<LoadedGltfScene.AnimationMarkerDef> markers(String animationName) {
        return markers.getOrDefault(animationName, List.of());
    }

    public Map<String, List<LoadedGltfScene.AnimationMarkerDef>> markersByAnimation() {
        return markers;
    }

    /**
     * Parses either {@code {"animations":{"attack":{"markers":[...]}}}} or
     * {@code {"animations":[{"name":"attack","markers":[...]}]}}.
     */
    public static GltfAnimationMetadata fromJson(AssetRef source, String json) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(json, "json");
        Map<String, Object> root = GltfDocumentReader.parseJson(source,
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, List<LoadedGltfScene.AnimationMarkerDef>> result = new LinkedHashMap<>();
        Object animationValue = root.get("animations");
        Map<String, Object> animationMap = animationValue instanceof Map<?, ?>
                ? object(root, "animations", false, "$.animations") : null;
        if (animationMap != null) {
            for (Map.Entry<String, Object> entry : animationMap.entrySet()) {
                Map<String, Object> definition = GltfJson.map(entry.getValue(),
                        "$.animations." + entry.getKey());
                result.put(entry.getKey(), parseMarkers(definition, "$.animations."
                        + entry.getKey()));
            }
        } else if (animationValue instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                Map<String, Object> definition = GltfJson.map(list.get(index),
                        "$.animations[" + index + "]");
                String name = string(definition, "name", true,
                        "$.animations[" + index + "].name");
                result.put(name, parseMarkers(definition, "$.animations[" + index + "]"));
            }
        } else {
            for (Map<String, Object> definition : objects(root, "animationMarkers")) {
                String name = string(definition, "animation", true,
                        "$.animationMarkers.animation");
                result.put(name, parseMarkers(definition, "$.animationMarkers." + name));
            }
        }
        return new GltfAnimationMetadata(result);
    }

    private static List<LoadedGltfScene.AnimationMarkerDef> parseMarkers(
            Map<String, Object> definition, String path) {
        List<Map<String, Object>> values = objects(definition, "markers");
        List<LoadedGltfScene.AnimationMarkerDef> result = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            Map<String, Object> marker = values.get(index);
            String markerPath = path + ".markers[" + index + "]";
            String name = string(marker, "name", true, markerPath + ".name");
            float time = decimal(marker, "timeSeconds", Float.NaN,
                    markerPath + ".timeSeconds");
            if (Float.isNaN(time)) {
                time = decimal(marker, "time", Float.NaN, markerPath + ".time");
            }
            if (!Float.isFinite(time) || time < 0.0f) {
                throw GltfJson.failure(markerPath, "sidecar marker requires a non-negative timeSeconds");
            }
            String priority = string(marker, "priority", false, markerPath + ".priority");
            result.add(new LoadedGltfScene.AnimationMarkerDef(time, name, priority));
        }
        return List.copyOf(result);
    }
}
