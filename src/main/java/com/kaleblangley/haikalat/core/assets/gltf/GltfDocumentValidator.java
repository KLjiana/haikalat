package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.compareVersion;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.validVersion;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.map;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.object;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.string;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.strings;

/** Shared glTF asset-version and extension policy for scene and sidecar documents. */
final class GltfDocumentValidator {
    private GltfDocumentValidator() {
    }

    static List<String> validate(AssetRef source, Map<String, Object> root,
                                 boolean strictExtensions) {
        validateAsset(source, root);
        Set<String> required = new HashSet<>(
                strings(root.get("extensionsRequired"), "extensionsRequired"));
        if (!required.isEmpty()) {
            throw fail(source, "extensionsRequired",
                    "unsupported required extensions " + required);
        }
        List<String> warnings = new ArrayList<>();
        List<String> used = strings(root.get("extensionsUsed"), "extensionsUsed");
        if (!used.isEmpty()) warnings.add("ignored optional extensions: " + used);
        List<String> payloads = new ArrayList<>();
        collectExtensionPayloads(root, "$", payloads);
        if (!payloads.isEmpty()) {
            if (strictExtensions) {
                String first = payloads.getFirst();
                int separator = first.indexOf(':');
                throw fail(source, first.substring(0, separator),
                        "unsupported extension payload "
                                + first.substring(separator + 1));
            }
            warnings.add("ignored extension payloads by explicit lenient policy: "
                    + payloads);
        }
        return List.copyOf(warnings);
    }

    private static void validateAsset(AssetRef source,
                                      Map<String, Object> root) {
        Map<String, Object> asset = object(root, "asset", true, "asset");
        String version = string(asset, "version", true, "asset.version");
        if (!validVersion(version) || !version.startsWith("2.")) {
            throw fail(source, "asset.version",
                    "only numeric glTF 2.x versions are supported, got " + version);
        }
        String minimum = string(asset, "minVersion", false, "asset.minVersion");
        if (minimum != null
                && (!validVersion(minimum) || compareVersion(minimum, "2.0") > 0)) {
            throw fail(source, "asset.minVersion",
                    "requires unsupported glTF " + minimum);
        }
    }

    private static void collectExtensionPayloads(Object value, String path,
                                                 List<String> output) {
        if (value instanceof Map<?, ?> object) {
            for (Map.Entry<?, ?> entry : object.entrySet()) {
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
                collectExtensionPayloads(list.get(index),
                        path + "[" + index + "]", output);
            }
        }
    }

    private static GltfAssetException fail(AssetRef source, String location,
                                           String message) {
        return new GltfAssetException(source, GltfAssetException.Phase.DECODE,
                location, message);
    }
}
