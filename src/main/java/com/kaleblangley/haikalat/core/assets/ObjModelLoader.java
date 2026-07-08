package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.backend.GlException;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ObjModelLoader implements ModelAssetLoader {
    private final ResourceLocator locator;

    public ObjModelLoader(ResourceLocator locator) {
        this.locator = Objects.requireNonNull(locator, "locator");
    }

    @Override
    public LoadedModel load(AssetRef ref) {
        if (!"obj".equals(ref.extension())) {
            throw new GlException("ObjModelLoader supports .obj assets only: " + ref.path());
        }
        return parse(locator.readString(ref), ref.path());
    }

    public static LoadedModel parse(String source, String name) {
        List<Vector3f> positions = new ArrayList<>();
        List<Vector3f> normals = new ArrayList<>();
        List<Vector2f> texCoords = new ArrayList<>();
        List<Float> vertices = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        Map<String, Integer> vertexMap = new LinkedHashMap<>();

        String[] lines = Objects.requireNonNull(source, "source").split("\\R");
        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            switch (parts[0]) {
                case "v" -> positions.add(new Vector3f(
                        parseFloat(parts, 1, line),
                        parseFloat(parts, 2, line),
                        parseFloat(parts, 3, line)));
                case "vn" -> normals.add(new Vector3f(
                        parseFloat(parts, 1, line),
                        parseFloat(parts, 2, line),
                        parseFloat(parts, 3, line)).normalize());
                case "vt" -> texCoords.add(new Vector2f(
                        parseFloat(parts, 1, line),
                        parseFloat(parts, 2, line)));
                case "f" -> appendFace(parts, positions, normals, texCoords, vertices, indices, vertexMap);
                default -> {
                }
            }
        }

        if (indices.isEmpty()) {
            throw new GlException("OBJ contains no faces: " + name);
        }

        float[] vertexArray = new float[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            vertexArray[i] = vertices.get(i);
        }
        int[] indexArray = indices.stream().mapToInt(Integer::intValue).toArray();
        return new LoadedModel(List.of(new LoadedModel.MeshData(
                name,
                vertexArray,
                indexArray,
                LoadedModel.VertexFormat.POSITION_NORMAL_UV)));
    }

    private static void appendFace(String[] parts, List<Vector3f> positions, List<Vector3f> normals,
                                   List<Vector2f> texCoords, List<Float> vertices, List<Integer> indices,
                                   Map<String, Integer> vertexMap) {
        if (parts.length < 4) {
            throw new GlException("OBJ face must have at least three vertices");
        }
        int first = vertexIndex(parts[1], positions, normals, texCoords, vertices, vertexMap);
        for (int i = 2; i < parts.length - 1; i++) {
            indices.add(first);
            indices.add(vertexIndex(parts[i], positions, normals, texCoords, vertices, vertexMap));
            indices.add(vertexIndex(parts[i + 1], positions, normals, texCoords, vertices, vertexMap));
        }
    }

    private static int vertexIndex(String token, List<Vector3f> positions, List<Vector3f> normals,
                                   List<Vector2f> texCoords, List<Float> vertices,
                                   Map<String, Integer> vertexMap) {
        Integer existing = vertexMap.get(token);
        if (existing != null) {
            return existing;
        }
        String[] refs = token.split("/", -1);
        Vector3f position = positions.get(resolveIndex(refs[0], positions.size()));
        Vector2f uv = refs.length > 1 && !refs[1].isEmpty()
                ? texCoords.get(resolveIndex(refs[1], texCoords.size()))
                : new Vector2f();
        Vector3f normal = refs.length > 2 && !refs[2].isEmpty()
                ? normals.get(resolveIndex(refs[2], normals.size()))
                : new Vector3f(0.0f, 0.0f, 1.0f);

        int index = vertexMap.size();
        vertexMap.put(token, index);
        vertices.add(position.x);
        vertices.add(position.y);
        vertices.add(position.z);
        vertices.add(normal.x);
        vertices.add(normal.y);
        vertices.add(normal.z);
        vertices.add(uv.x);
        vertices.add(uv.y);
        return index;
    }

    private static int resolveIndex(String token, int size) {
        int index = Integer.parseInt(token);
        return index > 0 ? index - 1 : size + index;
    }

    private static float parseFloat(String[] parts, int index, String line) {
        if (parts.length <= index) {
            throw new GlException("Malformed OBJ line: " + line);
        }
        return Float.parseFloat(parts[index]);
    }
}
