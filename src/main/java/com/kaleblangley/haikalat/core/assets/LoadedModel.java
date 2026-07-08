package com.kaleblangley.haikalat.core.assets;

import java.util.List;
import java.util.Objects;

public record LoadedModel(List<MeshData> meshes) {
    public LoadedModel {
        meshes = List.copyOf(Objects.requireNonNull(meshes, "meshes"));
        if (meshes.isEmpty()) {
            throw new IllegalArgumentException("meshes must not be empty");
        }
    }

    public MeshData firstMesh() {
        return meshes.get(0);
    }

    public record MeshData(String name, float[] vertices, int[] indices, VertexFormat format) {
        public MeshData {
            name = Objects.requireNonNull(name, "name");
            vertices = Objects.requireNonNull(vertices, "vertices").clone();
            indices = Objects.requireNonNull(indices, "indices").clone();
            format = Objects.requireNonNull(format, "format");
        }

        @Override
        public float[] vertices() {
            return vertices.clone();
        }

        @Override
        public int[] indices() {
            return indices.clone();
        }
    }

    public enum VertexFormat {
        POSITION,
        POSITION_UV,
        POSITION_NORMAL,
        POSITION_NORMAL_UV
    }
}
