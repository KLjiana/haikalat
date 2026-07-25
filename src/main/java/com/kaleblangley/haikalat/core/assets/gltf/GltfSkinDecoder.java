package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.index;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.limit;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.integer;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.integers;

/** skin joints and inverse-bind-matrix decoding stage. */
final class GltfSkinDecoder {
    private final AssetRef source;
    private final GltfAssetLimits limits;
    private final GltfAccessorDecoder accessors;

    GltfSkinDecoder(AssetRef source, GltfAssetLimits limits,
                    GltfAccessorDecoder accessors) {
        this.source = source;
        this.limits = limits;
        this.accessors = accessors;
    }

    List<LoadedGltfScene.SkinDef> decode(List<Map<String, Object>> definitions,
                                        int nodeCount) {
        limit(source, "skins", definitions.size(), limits.skins(), "skins");
        List<LoadedGltfScene.SkinDef> result = new ArrayList<>(definitions.size());
        for (int skinIndex = 0; skinIndex < definitions.size(); skinIndex++) {
            Map<String, Object> definition = definitions.get(skinIndex);
            String path = "skins[" + skinIndex + "]";
            List<Integer> joints = integers(definition.get("joints"), path + ".joints");
            if (joints.isEmpty()) throw fail(path + ".joints", "skin must contain joints");
            limit(source, "jointsPerSkin", joints.size(), limits.jointsPerSkin(), path);
            if (new HashSet<>(joints).size() != joints.size()) {
                throw fail(path + ".joints", "skin contains duplicate joints");
            }
            for (int joint : joints) index(joint, nodeCount, path + ".joints");
            int skeletonRoot = integer(definition, "skeleton", false,
                    path + ".skeleton", -1);
            if (skeletonRoot >= 0) index(skeletonRoot, nodeCount, path + ".skeleton");

            List<Matrix4fc> inverseBindMatrices = new ArrayList<>(joints.size());
            if (definition.containsKey("inverseBindMatrices")) {
                int accessor = integer(definition, "inverseBindMatrices", true,
                        path + ".inverseBindMatrices");
                float[] values = accessors.floats(accessor, 16,
                        GltfAccessorDecoder.NO_NORMALIZED_COMPONENTS,
                        path + ".inverseBindMatrices");
                if (values.length != Math.multiplyExact(joints.size(), 16)) {
                    throw fail(path + ".inverseBindMatrices",
                            "matrix count must match skin joint count");
                }
                for (int joint = 0; joint < joints.size(); joint++) {
                    inverseBindMatrices.add(new Matrix4f().set(values, joint * 16));
                }
            } else {
                for (int joint = 0; joint < joints.size(); joint++) {
                    inverseBindMatrices.add(new Matrix4f());
                }
            }
            result.add(new LoadedGltfScene.SkinDef(skinIndex,
                    Objects.toString(definition.get("name"), ""), skeletonRoot,
                    joints, inverseBindMatrices));
        }
        return List.copyOf(result);
    }

    private GltfAssetException fail(String location, String message) {
        return new GltfAssetException(source, GltfAssetException.Phase.DECODE,
                location, message);
    }
}
