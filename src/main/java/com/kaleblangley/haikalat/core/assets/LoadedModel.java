package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.core.mesh.MeshData;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;

import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_FLOAT;

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

    public enum VertexFormat {
        POSITION(VertexLayout.interleaved(3 * Float.BYTES,
                VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build())),
        POSITION_UV(VertexLayout.interleaved(5 * Float.BYTES,
                VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build(),
                VertexAttribute.builder().index(1).size(2).type(GL_FLOAT).offsetBytes(3L * Float.BYTES).build())),
        POSITION_NORMAL(VertexLayout.interleaved(6 * Float.BYTES,
                VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build(),
                VertexAttribute.builder().index(1).size(3).type(GL_FLOAT).offsetBytes(3L * Float.BYTES).build())),
        POSITION_NORMAL_UV(VertexLayout.interleaved(8 * Float.BYTES,
                VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build(),
                VertexAttribute.builder().index(1).size(3).type(GL_FLOAT).offsetBytes(3L * Float.BYTES).build(),
                VertexAttribute.builder().index(2).size(2).type(GL_FLOAT).offsetBytes(6L * Float.BYTES).build()));

        private final VertexLayout layout;

        VertexFormat(VertexLayout layout) {
            this.layout = layout;
        }

        public VertexLayout layout() {
            return layout;
        }

        public MeshData meshData(String name, float[] vertices, int[] indices) {
            return MeshData.indexed(name, vertices, indices, layout);
        }
    }
}
