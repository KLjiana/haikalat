package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayDeque;

import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.finite;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.index;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.floatArray;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.integer;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.integers;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.objects;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.vec3;

/** selected scene、hierarchy 与静态 world transform 解码阶段。 */
final class GltfNodeDecoder {
    private final AssetRef source;
    private final Map<String, Object> root;
    private final GltfLoadOptions options;

    GltfNodeDecoder(AssetRef source, Map<String, Object> root, GltfLoadOptions options) {
        this.source = source;
        this.root = root;
        this.options = options;
    }

    Result decode(List<Map<String, Object>> nodeDefinitions, int meshCount, int skinCount) {
        SceneChoice scene = selectScene();
        List<LoadedGltfScene.Node> nodes = decodeNodes(
                nodeDefinitions, meshCount, skinCount, scene.roots());
        return new Result(scene.index(), scene.name(), scene.roots(), nodes, decodedRigs);
    }

    private SceneChoice selectScene() {
        List<Map<String, Object>> scenes = objects(root, "scenes");
        if (scenes.isEmpty()) throw fail("scenes", "asset has no scenes");
        int selected;
        if (options.scene() instanceof SceneSelection.ByIndex byIndex) {
            selected = byIndex.index();
        } else if (options.scene() instanceof SceneSelection.ByName byName) {
            List<Integer> matches = new ArrayList<>();
            for (int index = 0; index < scenes.size(); index++) {
                if (byName.name().equals(scenes.get(index).get("name"))) matches.add(index);
            }
            if (matches.size() != 1) {
                throw fail("scenes", "scene name '" + byName.name()
                        + "' matched " + matches.size() + " scenes");
            }
            selected = matches.getFirst();
        } else {
            selected = integer(root, "scene", false, "scene", 0);
        }
        index(selected, scenes.size(), "scene");
        Map<String, Object> scene = scenes.get(selected);
        return new SceneChoice(selected, Objects.toString(scene.get("name"), ""),
                integers(scene.get("nodes"), "scenes[" + selected + "].nodes"));
    }

    private List<LoadedGltfScene.Node> decodeNodes(List<Map<String, Object>> definitions,
                                                   int meshCount, int skinCount,
                                                   List<Integer> roots) {
        int count = definitions.size();
        for (int rootIndex : roots) index(rootIndex, count, "scene.nodes");
        Matrix4f[] locals = new Matrix4f[count];
        LocalTransform[] localTransforms = new LocalTransform[count];
        List<List<Integer>> children = new ArrayList<>(count);
        int[] declaredParents = new int[count];
        java.util.Arrays.fill(declaredParents, -1);
        for (int index = 0; index < count; index++) {
            Map<String, Object> definition = definitions.get(index);
            String path = "nodes[" + index + "]";
            if (definition.containsKey("weights")) {
                throw fail(path + ".weights", "morph weights are not supported");
            }
            int mesh = integer(definition, "mesh", false, path + ".mesh", -1);
            if (mesh >= 0) index(mesh, meshCount, path + ".mesh");
            int skin = integer(definition, "skin", false, path + ".skin", -1);
            if (skin >= 0) index(skin, skinCount, path + ".skin");
            List<Integer> childList = integers(definition.get("children"), path + ".children");
            if (new HashSet<>(childList).size() != childList.size()) {
                throw fail(path + ".children", "duplicate child");
            }
            for (int child : childList) {
                index(child, count, path + ".children");
                if (declaredParents[child] >= 0 && declaredParents[child] != index) {
                    throw fail("nodes[" + child + "]", "node has multiple parents");
                }
                declaredParents[child] = index;
            }
            children.add(childList);
            localTransforms[index] = localTransform(definition, index);
            locals[index] = localTransforms[index].matrix();
        }
        validateHierarchy(children, declaredParents);
        Matrix4f[] worlds = new Matrix4f[count];
        byte[] visiting = new byte[count];
        int[] selectedParents = new int[count];
        java.util.Arrays.fill(selectedParents, -2);
        for (int rootIndex : roots) {
            if (declaredParents[rootIndex] >= 0) {
                throw fail("scene.nodes", "root node " + rootIndex + " has a declared parent");
            }
            if (selectedParents[rootIndex] != -2) {
                throw fail("scene.nodes", "duplicate root node " + rootIndex);
            }
            selectedParents[rootIndex] = -1;
            expandNode(rootIndex, new Matrix4f(), 0, locals, children,
                    worlds, visiting, selectedParents);
        }
        List<LoadedGltfScene.Node> result = new ArrayList<>(count);
        List<LoadedGltfScene.NodeRigDef> rigs = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Map<String, Object> definition = definitions.get(index);
            int mesh = integer(definition, "mesh", false, "nodes[" + index + "].mesh", -1);
            int skin = integer(definition, "skin", false, "nodes[" + index + "].skin", -1);
            boolean reachable = worlds[index] != null;
            Matrix4f world = reachable ? worlds[index] : new Matrix4f(locals[index]);
            result.add(new LoadedGltfScene.Node(index,
                    Objects.toString(definition.get("name"), ""), mesh, children.get(index),
                    locals[index], world, reachable, world.determinant3x3() < 0.0f));
            LocalTransform local = localTransforms[index];
            rigs.add(new LoadedGltfScene.NodeRigDef(index, declaredParents[index], skin,
                    local.translation(), local.rotation(), local.scale(), local.matrixAuthored()));
        }
        decodedRigs = List.copyOf(rigs);
        return List.copyOf(result);
    }

    private List<LoadedGltfScene.NodeRigDef> decodedRigs = List.of();

    private void validateHierarchy(List<List<Integer>> children, int[] parents) {
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int[] depths = new int[parents.length];
        for (int node = 0; node < parents.length; node++) {
            if (parents[node] < 0) queue.addLast(node);
        }
        int visited = 0;
        while (!queue.isEmpty()) {
            int node = queue.removeFirst();
            visited++;
            if (depths[node] > options.limits().hierarchyDepth()) {
                throw fail("nodes[" + node + "]", "hierarchy depth exceeds limit");
            }
            for (int child : children.get(node)) {
                depths[child] = depths[node] + 1;
                queue.addLast(child);
            }
        }
        if (visited != parents.length) {
            throw fail("nodes", "node hierarchy contains a cycle");
        }
    }

    private void expandNode(int node, Matrix4f parent, int depth,
                            Matrix4f[] locals, List<List<Integer>> children,
                            Matrix4f[] worlds, byte[] visiting, int[] parents) {
        if (depth > options.limits().hierarchyDepth()) {
            throw fail("nodes[" + node + "]", "hierarchy depth exceeds limit");
        }
        if (visiting[node] == 1) throw fail("nodes[" + node + "]", "node hierarchy cycle");
        if (visiting[node] == 2) return;
        visiting[node] = 1;
        worlds[node] = new Matrix4f(parent).mul(locals[node]);
        for (int child : children.get(node)) {
            if (parents[child] != -2 && parents[child] != node) {
                throw fail("nodes[" + child + "]", "node has multiple parents in selected scene");
            }
            parents[child] = node;
            expandNode(child, worlds[node], depth + 1, locals, children,
                    worlds, visiting, parents);
        }
        visiting[node] = 2;
    }

    private LocalTransform localTransform(Map<String, Object> definition, int index) {
        String path = "nodes[" + index + "]";
        if (definition.containsKey("matrix") && (definition.containsKey("translation")
                || definition.containsKey("rotation") || definition.containsKey("scale"))) {
            throw fail(path, "matrix cannot be combined with TRS");
        }
        if (definition.containsKey("matrix")) {
            Matrix4f matrix = new Matrix4f().set(
                    floatArray(definition.get("matrix"), 16, path + ".matrix"));
            if (!finite(matrix)) throw fail(path + ".matrix", "transform is non-finite");
            return new LocalTransform(matrix, new Vector3f(), new Quaternionf(),
                    new Vector3f(1.0f), true);
        }
        Vector3f translation = vec3(definition.get("translation"), new Vector3f(),
                path + ".translation");
        Vector3f scale = vec3(definition.get("scale"), new Vector3f(1.0f), path + ".scale");
        float[] values = definition.containsKey("rotation")
                ? floatArray(definition.get("rotation"), 4, path + ".rotation")
                : new float[]{0.0f, 0.0f, 0.0f, 1.0f};
        Quaternionf rotation = new Quaternionf(values[0], values[1], values[2], values[3]);
        if (!Float.isFinite(rotation.lengthSquared()) || rotation.lengthSquared() <= 1.0e-12f) {
            throw fail(path + ".rotation", "quaternion is zero or non-finite");
        }
        rotation.normalize();
        Matrix4f result = new Matrix4f().translationRotateScale(translation, rotation, scale);
        if (!finite(result)) throw fail(path, "transform is non-finite");
        return new LocalTransform(result, translation, rotation, scale, false);
    }

    private GltfAssetException fail(String location, String message) {
        return new GltfAssetException(source, GltfAssetException.Phase.DECODE, location, message);
    }

    record Result(int sceneIndex, String sceneName, List<Integer> roots,
                  List<LoadedGltfScene.Node> nodes,
                  List<LoadedGltfScene.NodeRigDef> nodeRigs) {
    }

    private record SceneChoice(int index, String name, List<Integer> roots) {
    }

    private record LocalTransform(Matrix4f matrix, Vector3f translation,
                                  Quaternionf rotation, Vector3f scale,
                                  boolean matrixAuthored) {
    }
}
