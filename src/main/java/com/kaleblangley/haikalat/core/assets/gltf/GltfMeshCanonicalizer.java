package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import com.kaleblangley.haikalat.core.mesh.Bounds3f;
import com.kaleblangley.haikalat.core.mesh.NormalGenerator;
import com.kaleblangley.haikalat.core.mesh.TangentGenerator;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;

import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.index;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.limit;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.requireCount;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.floatArray;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.floats;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.integer;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.object;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.objects;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/** primitive 解码、normal/tangent fallback 与 canonical vertex layout 组装阶段。 */
final class GltfMeshCanonicalizer {
    private static final float MAX_ABSOLUTE_MORPH_WEIGHT = 8.0f;
    private final AssetRef source;
    private final GltfAssetLimits limits;
    private final GltfAccessorDecoder accessors;

    GltfMeshCanonicalizer(AssetRef source, GltfAssetLimits limits,
                          GltfAccessorDecoder accessors) {
        this.source = source;
        this.limits = limits;
        this.accessors = accessors;
    }

    Result decode(List<Map<String, Object>> meshes,
                  List<LoadedGltfScene.MaterialDef> materials,
                  int defaultMaterial) {
        List<LoadedGltfScene.Primitive> result = new ArrayList<>();
        int normalFallbacks = 0;
        int tangentFallbackTriangles = 0;
        int tangentFallbackVertices = 0;
        long vertexBytes = 0L;
        long indexBytes = 0L;
        Map<Integer, LoadedGltfScene.PrimitiveSkinning> primitiveSkinning =
                new LinkedHashMap<>();
        Map<Integer, LoadedGltfScene.MorphTargetSetDef> primitiveMorphTargets =
                new LinkedHashMap<>();
        Map<Integer, Integer> meshMorphTargetCounts = new LinkedHashMap<>();
        Map<Integer, float[]> meshMorphDefaultWeights = new LinkedHashMap<>();
        long morphDeltaBytes = 0L;
        for (int meshIndex = 0; meshIndex < meshes.size(); meshIndex++) {
            Map<String, Object> mesh = meshes.get(meshIndex);
            List<Map<String, Object>> definitions = objects(mesh, "primitives");
            float[] authoredMeshWeights = mesh.containsKey("weights")
                    ? floats(mesh.get("weights"), "meshes[" + meshIndex + "].weights")
                    : null;
            int meshTargetCount = -1;
            limit(source, "primitives", result.size() + definitions.size(),
                    limits.primitives(), "meshes");
            for (int primitiveIndex = 0; primitiveIndex < definitions.size(); primitiveIndex++) {
                Map<String, Object> definition = definitions.get(primitiveIndex);
                String path = "meshes[" + meshIndex + "].primitives[" + primitiveIndex + "]";
                if (integer(definition, "mode", false, path + ".mode", 4) != 4) {
                    throw fail(path + ".mode", "only TRIANGLES mode 4 is supported");
                }
                if (definition.containsKey("extensions")) {
                    throw fail(path, "primitive extensions are not supported");
                }
                Map<String, Object> attributes = object(definition, "attributes", true,
                        path + ".attributes");
                int positionAccessor = integer(attributes, "POSITION", true,
                        path + ".attributes.POSITION");
                float[] positions = accessors.floats(positionAccessor, 3,
                        GltfAccessorDecoder.NO_NORMALIZED_COMPONENTS,
                        path + ".attributes.POSITION");
                validatePositionBounds(accessors.definition(positionAccessor,
                        path + ".attributes.POSITION"), positions, path + ".attributes.POSITION");
                int vertexCount = positions.length / 3;
                limit(source, "primitiveVertices", vertexCount, limits.primitiveVertices(), path);
                int[] indices = definition.containsKey("indices")
                        ? accessors.indices(integer(definition, "indices", true, path + ".indices"),
                        vertexCount, path + ".indices") : new int[0];
                int elementCount = indices.length == 0 ? vertexCount : indices.length;
                if (elementCount % 3 != 0) {
                    throw fail(path, "triangle element count must be divisible by 3");
                }
                limit(source, "primitiveIndices", elementCount, limits.primitiveIndices(), path);

                List<Map<String, Object>> targetDefinitions = objects(definition, "targets");
                limit(source, "morphTargetsPerPrimitive", targetDefinitions.size(),
                        limits.morphTargetsPerPrimitive(), path + ".targets");
                if (!targetDefinitions.isEmpty()) {
                    if (meshTargetCount < 0) meshTargetCount = targetDefinitions.size();
                    if (meshTargetCount != targetDefinitions.size()) {
                        throw fail(path + ".targets",
                                "all morph primitives in a mesh must have the same target count");
                    }
                } else if (authoredMeshWeights != null) {
                    throw fail(path + ".targets", "mesh weights require morph targets");
                }

                float[] normals;
                if (attributes.containsKey("NORMAL")) {
                    normals = accessors.floats(integer(attributes, "NORMAL", true, path), 3,
                            GltfAccessorDecoder.SIGNED_NORMALIZED_COMPONENTS,
                            path + ".attributes.NORMAL");
                    normalizeVectors(normals, 3, path + ".attributes.NORMAL");
                } else {
                    NormalGenerator.Result generated = NormalGenerator.generate(positions, indices);
                    normals = generated.normals();
                    normalFallbacks += generated.fallbackVertexCount();
                }
                requireCount(normals.length / 3, vertexCount, path + ".attributes.NORMAL");
                float[] uv = attributes.containsKey("TEXCOORD_0")
                        ? accessors.floats(integer(attributes, "TEXCOORD_0", true, path), 2,
                        GltfAccessorDecoder.UNSIGNED_NORMALIZED_COMPONENTS,
                        path + ".attributes.TEXCOORD_0") : new float[vertexCount * 2];
                requireCount(uv.length / 2, vertexCount, path + ".attributes.TEXCOORD_0");
                int material = integer(definition, "material", false,
                        path + ".material", defaultMaterial);
                index(material, materials.size(), path + ".material");
                if (!materials.get(material).textureIndices().isEmpty()
                        && !attributes.containsKey("TEXCOORD_0")) {
                    throw fail(path + ".attributes.TEXCOORD_0",
                            "textured material requires TEXCOORD_0");
                }
                float[] colors = attributes.containsKey("COLOR_0")
                        ? accessors.colors(integer(attributes, "COLOR_0", true, path),
                        vertexCount, path + ".attributes.COLOR_0") : null;
                boolean hasJoints = attributes.containsKey("JOINTS_0");
                boolean hasWeights = attributes.containsKey("WEIGHTS_0");
                if (hasJoints != hasWeights) {
                    throw fail(path + ".attributes",
                            "JOINTS_0 and WEIGHTS_0 must be provided together");
                }
                int[] joints = null;
                float[] weights = null;
                int maxJointIndex = -1;
                if (hasJoints) {
                    joints = accessors.unsignedVector(integer(attributes, "JOINTS_0", true, path),
                            4, path + ".attributes.JOINTS_0");
                    requireCount(joints.length / 4, vertexCount,
                            path + ".attributes.JOINTS_0");
                    weights = accessors.floats(integer(attributes, "WEIGHTS_0", true, path), 4,
                            GltfAccessorDecoder.UNSIGNED_NORMALIZED_COMPONENTS,
                            path + ".attributes.WEIGHTS_0");
                    requireCount(weights.length / 4, vertexCount,
                            path + ".attributes.WEIGHTS_0");
                    maxJointIndex = normalizeWeightsAndFindMaxJoint(joints, weights, path);
                }
                String meshName = source.path() + "/mesh[" + meshIndex + "]:"
                        + Objects.toString(mesh.get("name"), "")
                        + "/primitive[" + primitiveIndex + "]";
                MeshData canonical;
                if (attributes.containsKey("TANGENT")) {
                    float[] tangents = accessors.floats(integer(attributes, "TANGENT", true, path), 4,
                            GltfAccessorDecoder.SIGNED_NORMALIZED_COMPONENTS,
                            path + ".attributes.TANGENT");
                    requireCount(tangents.length / 4, vertexCount, path + ".attributes.TANGENT");
                    validateTangents(tangents, path + ".attributes.TANGENT");
                    canonical = canonicalWithTangent(meshName, positions, uv, normals,
                            tangents, colors, indices);
                } else {
                    MeshData base = new MeshData(meshName, interleaveBase(positions, uv, normals),
                            indices, baseLayout(), GL_TRIANGLES);
                    TangentGenerator.Result generated = TangentGenerator.generate(base);
                    tangentFallbackTriangles += generated.fallbackTriangleCount();
                    tangentFallbackVertices += generated.fallbackVertexCount();
                    canonical = colors == null ? generated.mesh() : appendColors(generated.mesh(), colors);
                }
                int canonicalIndex = result.size();
                if (hasJoints) {
                    canonical = appendSkinning(canonical, joints, weights);
                    primitiveSkinning.put(canonicalIndex,
                            new LoadedGltfScene.PrimitiveSkinning(canonicalIndex, maxJointIndex));
                }
                vertexBytes += (long) canonical.vertices().length * Float.BYTES;
                indexBytes += (long) canonical.indices().length * Integer.BYTES;
                result.add(new LoadedGltfScene.Primitive(result.size(), meshIndex, primitiveIndex,
                        meshName, canonical, material, colors != null));
                if (!targetDefinitions.isEmpty()) {
                    float[] defaultWeights = authoredMeshWeights == null
                            ? new float[targetDefinitions.size()] : authoredMeshWeights.clone();
                    if (defaultWeights.length != targetDefinitions.size()) {
                        throw fail("meshes[" + meshIndex + "].weights",
                                "weight count must match morph target count");
                    }
                    validateMorphWeights(defaultWeights,
                            "meshes[" + meshIndex + "].weights");
                    LoadedGltfScene.MorphTargetSetDef morphSet = decodeMorphTargets(
                            canonicalIndex, positions, vertexCount, targetDefinitions,
                            defaultWeights, path);
                    morphDeltaBytes = Math.addExact(morphDeltaBytes, morphSet.deltaBytes());
                    limit(source, "morphDeltaBytes", morphDeltaBytes,
                            limits.morphDeltaBytes(), "meshes");
                    primitiveMorphTargets.put(canonicalIndex, morphSet);
                }
            }
            if (meshTargetCount >= 0) {
                meshMorphTargetCounts.put(meshIndex, meshTargetCount);
                meshMorphDefaultWeights.put(meshIndex, authoredMeshWeights == null
                        ? new float[meshTargetCount] : authoredMeshWeights.clone());
            }
        }
        return new Result(List.copyOf(result), normalFallbacks, tangentFallbackTriangles,
                tangentFallbackVertices, vertexBytes, indexBytes,
                Map.copyOf(primitiveSkinning), Map.copyOf(primitiveMorphTargets),
                Map.copyOf(meshMorphTargetCounts), copyWeightMap(meshMorphDefaultWeights),
                morphDeltaBytes);
    }

    private LoadedGltfScene.MorphTargetSetDef decodeMorphTargets(
            int canonicalIndex, float[] positions, int vertexCount,
            List<Map<String, Object>> definitions, float[] defaultWeights, String path) {
        List<LoadedGltfScene.MorphTargetDef> targets = new ArrayList<>(definitions.size());
        long bytes = 0L;
        for (int targetIndex = 0; targetIndex < definitions.size(); targetIndex++) {
            Map<String, Object> definition = definitions.get(targetIndex);
            String targetPath = path + ".targets[" + targetIndex + "]";
            for (String semantic : definition.keySet()) {
                if (!semantic.equals("POSITION") && !semantic.equals("NORMAL")
                        && !semantic.equals("TANGENT")) {
                    throw fail(targetPath + "." + semantic,
                            "unsupported morph target semantic " + semantic);
                }
            }
            float[] position = morphDeltas(definition, "POSITION", vertexCount, targetPath);
            float[] normal = morphDeltas(definition, "NORMAL", vertexCount, targetPath);
            float[] tangent = morphDeltas(definition, "TANGENT", vertexCount, targetPath);
            bytes = Math.addExact(bytes, Math.multiplyExact(
                    (long) position.length + normal.length + tangent.length, Float.BYTES));
            targets.add(new LoadedGltfScene.MorphTargetDef(position, normal, tangent));
        }
        return new LoadedGltfScene.MorphTargetSetDef(canonicalIndex, vertexCount, targets,
                defaultWeights, conservativeMorphBounds(positions, targets), bytes);
    }

    private float[] morphDeltas(Map<String, Object> definition, String semantic,
                                int vertexCount, String path) {
        if (!definition.containsKey(semantic)) return new float[0];
        float[] values = accessors.floats(integer(definition, semantic, true,
                        path + "." + semantic), 3,
                GltfAccessorDecoder.NO_NORMALIZED_COMPONENTS, path + "." + semantic);
        requireCount(values.length / 3, vertexCount, path + "." + semantic);
        return values;
    }

    private static Bounds3f conservativeMorphBounds(
            float[] positions, List<LoadedGltfScene.MorphTargetDef> targets) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int vertex = 0; vertex < positions.length / 3; vertex++) {
            int offset = vertex * 3;
            float dx = 0.0f;
            float dy = 0.0f;
            float dz = 0.0f;
            for (LoadedGltfScene.MorphTargetDef target : targets) {
                float[] delta = target.positionDeltas();
                if (delta.length == 0) continue;
                dx += Math.abs(delta[offset]) * MAX_ABSOLUTE_MORPH_WEIGHT;
                dy += Math.abs(delta[offset + 1]) * MAX_ABSOLUTE_MORPH_WEIGHT;
                dz += Math.abs(delta[offset + 2]) * MAX_ABSOLUTE_MORPH_WEIGHT;
            }
            minX = Math.min(minX, positions[offset] - dx);
            minY = Math.min(minY, positions[offset + 1] - dy);
            minZ = Math.min(minZ, positions[offset + 2] - dz);
            maxX = Math.max(maxX, positions[offset] + dx);
            maxY = Math.max(maxY, positions[offset + 1] + dy);
            maxZ = Math.max(maxZ, positions[offset + 2] + dz);
        }
        return Bounds3f.of(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private void validateMorphWeights(float[] values, String path) {
        for (float value : values) {
            if (!Float.isFinite(value)
                    || Math.abs(value) > MAX_ABSOLUTE_MORPH_WEIGHT) {
                throw fail(path, "morph weights must be finite and in ["
                        + -MAX_ABSOLUTE_MORPH_WEIGHT + ", "
                        + MAX_ABSOLUTE_MORPH_WEIGHT + "]");
            }
        }
    }

    private static Map<Integer, float[]> copyWeightMap(Map<Integer, float[]> source) {
        Map<Integer, float[]> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, value.clone()));
        return Map.copyOf(result);
    }

    private int normalizeWeightsAndFindMaxJoint(int[] joints, float[] weights, String path) {
        int maximum = 0;
        for (int vertex = 0; vertex < joints.length / 4; vertex++) {
            float sum = 0.0f;
            for (int component = 0; component < 4; component++) {
                int offset = vertex * 4 + component;
                maximum = Math.max(maximum, joints[offset]);
                float weight = weights[offset];
                if (!Float.isFinite(weight) || weight < 0.0f) {
                    throw fail(path + ".attributes.WEIGHTS_0",
                            "weights must be finite and non-negative");
                }
                sum += weight;
            }
            if (!Float.isFinite(sum) || sum <= 1.0e-8f) {
                throw fail(path + ".attributes.WEIGHTS_0",
                        "each vertex must have a positive weight sum");
            }
            for (int component = 0; component < 4; component++) {
                weights[vertex * 4 + component] /= sum;
            }
        }
        return maximum;
    }

    private void validatePositionBounds(Map<String, Object> accessor,
                                        float[] values, String path) {
        float[] minimum = accessor.containsKey("min")
                ? floatArray(accessor.get("min"), 3, path + ".min") : null;
        float[] maximum = accessor.containsKey("max")
                ? floatArray(accessor.get("max"), 3, path + ".max") : null;
        if ((minimum == null) != (maximum == null)) {
            throw fail(path, "POSITION min and max must be provided together");
        }
        if (minimum == null) return;
        for (int component = 0; component < 3; component++) {
            if (minimum[component] > maximum[component]) {
                throw fail(path, "POSITION min exceeds max");
            }
        }
        for (int index = 0; index < values.length; index++) {
            int component = index % 3;
            if (values[index] < minimum[component] || values[index] > maximum[component]) {
                throw fail(path, "POSITION bounds do not enclose decoded vertex data");
            }
        }
    }

    private void validateTangents(float[] tangents, String path) {
        for (int index = 0; index < tangents.length; index += 4) {
            float lengthSquared = tangents[index] * tangents[index]
                    + tangents[index + 1] * tangents[index + 1]
                    + tangents[index + 2] * tangents[index + 2];
            if (!Float.isFinite(lengthSquared) || lengthSquared <= 1.0e-12f) {
                throw fail(path, "tangent xyz must be finite and non-zero");
            }
            float handedness = tangents[index + 3];
            if (!Float.isFinite(handedness)
                    || Math.abs(Math.abs(handedness) - 1.0f) > 1.0e-3f) {
                throw fail(path, "tangent w must be -1 or +1");
            }
        }
    }

    private static MeshData canonicalWithTangent(String name, float[] positions,
                                                  float[] uv, float[] normals,
                                                  float[] tangents, float[] colors,
                                                  int[] indices) {
        int count = positions.length / 3;
        int stride = colors == null ? 12 : 16;
        float[] values = new float[count * stride];
        for (int index = 0; index < count; index++) {
            int output = index * stride;
            System.arraycopy(positions, index * 3, values, output, 3);
            System.arraycopy(uv, index * 2, values, output + 3, 2);
            Vector3f normal = new Vector3f(normals[index * 3], normals[index * 3 + 1],
                    normals[index * 3 + 2]).normalize();
            Vector3f tangent = new Vector3f(tangents[index * 4], tangents[index * 4 + 1],
                    tangents[index * 4 + 2]);
            tangent.sub(new Vector3f(normal).mul(normal.dot(tangent)));
            if (!Float.isFinite(tangent.lengthSquared()) || tangent.lengthSquared() <= 1.0e-12f) {
                tangent.set(1.0f, 0.0f, 0.0f);
            } else {
                tangent.normalize();
            }
            values[output + 5] = normal.x;
            values[output + 6] = normal.y;
            values[output + 7] = normal.z;
            values[output + 8] = tangent.x;
            values[output + 9] = tangent.y;
            values[output + 10] = tangent.z;
            values[output + 11] = tangents[index * 4 + 3] < 0.0f ? -1.0f : 1.0f;
            if (colors != null) System.arraycopy(colors, index * 4, values, output + 12, 4);
        }
        return new MeshData(name, values, indices,
                colors == null ? TangentGenerator.pbrLayout() : colorLayout(), GL_TRIANGLES);
    }

    private static MeshData appendColors(MeshData source, float[] colors) {
        float[] old = source.vertices();
        int count = source.vertexCount();
        float[] values = new float[count * 16];
        for (int index = 0; index < count; index++) {
            System.arraycopy(old, index * 12, values, index * 16, 12);
            System.arraycopy(colors, index * 4, values, index * 16 + 12, 4);
        }
        return new MeshData(source.name(), values, source.indices(),
                colorLayout(), source.primitiveMode());
    }

    private static MeshData appendSkinning(MeshData source, int[] joints, float[] weights) {
        float[] old = source.vertices();
        int count = source.vertexCount();
        int oldStride = source.layout().strideBytes() / Float.BYTES;
        int newStride = oldStride + 8;
        float[] values = new float[count * newStride];
        for (int vertex = 0; vertex < count; vertex++) {
            int output = vertex * newStride;
            System.arraycopy(old, vertex * oldStride, values, output, oldStride);
            for (int component = 0; component < 4; component++) {
                values[output + oldStride + component] = joints[vertex * 4 + component];
                values[output + oldStride + 4 + component] = weights[vertex * 4 + component];
            }
        }
        List<VertexAttribute> attributes = new ArrayList<>(source.layout().attributes());
        attributes.add(attribute(5, 4, oldStride, VertexSemantic.JOINTS_0));
        attributes.add(attribute(6, 4, oldStride + 4, VertexSemantic.WEIGHTS_0));
        return new MeshData(source.name(), values, source.indices(),
                VertexLayout.interleaved(newStride * Float.BYTES,
                        attributes.toArray(VertexAttribute[]::new)), source.primitiveMode());
    }

    private static VertexLayout baseLayout() {
        return VertexLayout.interleaved(8 * Float.BYTES,
                attribute(0, 3, 0, VertexSemantic.POSITION),
                attribute(1, 2, 3, VertexSemantic.TEXCOORD_0),
                attribute(2, 3, 5, VertexSemantic.NORMAL));
    }

    private static VertexLayout colorLayout() {
        return VertexLayout.interleaved(16 * Float.BYTES,
                attribute(0, 3, 0, VertexSemantic.POSITION),
                attribute(1, 2, 3, VertexSemantic.TEXCOORD_0),
                attribute(2, 3, 5, VertexSemantic.NORMAL),
                attribute(3, 4, 8, VertexSemantic.TANGENT),
                attribute(4, 4, 12, VertexSemantic.COLOR_0));
    }

    private static VertexAttribute attribute(int index, int size, int offset,
                                             VertexSemantic semantic) {
        return VertexAttribute.builder().index(index).size(size).type(GL_FLOAT)
                .offsetBytes((long) offset * Float.BYTES).semantic(semantic).build();
    }

    private static float[] interleaveBase(float[] positions, float[] uv, float[] normals) {
        int count = positions.length / 3;
        float[] result = new float[count * 8];
        for (int index = 0; index < count; index++) {
            System.arraycopy(positions, index * 3, result, index * 8, 3);
            System.arraycopy(uv, index * 2, result, index * 8 + 3, 2);
            System.arraycopy(normals, index * 3, result, index * 8 + 5, 3);
        }
        return result;
    }

    private static void normalizeVectors(float[] values, int components, String path) {
        for (int index = 0; index < values.length; index += components) {
            float length = 0.0f;
            for (int component = 0; component < 3; component++) {
                length += values[index + component] * values[index + component];
            }
            if (!Float.isFinite(length) || length <= 1.0e-12f) {
                throw GltfJson.failure(path, "contains zero/non-finite vector");
            }
            float inverse = (float) (1.0 / Math.sqrt(length));
            for (int component = 0; component < 3; component++) {
                values[index + component] *= inverse;
            }
        }
    }

    private GltfAssetException fail(String location, String message) {
        return new GltfAssetException(source, GltfAssetException.Phase.DECODE, location, message);
    }

    record Result(List<LoadedGltfScene.Primitive> primitives,
                  int normalFallbacks,
                  int tangentFallbackTriangles,
                  int tangentFallbackVertices,
                  long vertexBytes,
                  long indexBytes,
                  Map<Integer, LoadedGltfScene.PrimitiveSkinning> primitiveSkinning,
                  Map<Integer, LoadedGltfScene.MorphTargetSetDef> primitiveMorphTargets,
                  Map<Integer, Integer> meshMorphTargetCounts,
                  Map<Integer, float[]> meshMorphDefaultWeights,
                  long morphDeltaBytes) {
    }
}
