package com.kaleblangley.haikalat.core.mesh;

import java.util.Set;

import static org.lwjgl.opengl.GL11.GL_FLOAT;

public final class BuiltinMeshData {
    public static final String TRIANGLE = "triangle";
    public static final String QUAD = "quad";
    public static final String TEXTURED_QUAD = "texturedQuad";
    private static final Set<String> NAMES = Set.of(TRIANGLE, QUAD, TEXTURED_QUAD);

    private static final VertexLayout POSITION_COLOR = VertexLayout.interleaved(9 * Float.BYTES,
            VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build(),
            VertexAttribute.builder().index(1).size(3).type(GL_FLOAT).offsetBytes(3L * Float.BYTES).build(),
            VertexAttribute.builder().index(2).size(3).type(GL_FLOAT).offsetBytes(6L * Float.BYTES).build());

    private static final VertexLayout POSITION_UV = VertexLayout.interleaved(8 * Float.BYTES,
            VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build(),
            VertexAttribute.builder().index(1).size(2).type(GL_FLOAT).offsetBytes(3L * Float.BYTES).build(),
            VertexAttribute.builder().index(2).size(3).type(GL_FLOAT).offsetBytes(5L * Float.BYTES).build());

    private BuiltinMeshData() {
    }

    public static VertexLayout positionColorLayout() {
        return POSITION_COLOR;
    }

    public static VertexLayout positionUvLayout() {
        return POSITION_UV;
    }

    public static Set<String> names() {
        return NAMES;
    }

    public static MeshData named(String name) {
        return switch (name) {
            case TRIANGLE -> coloredTriangle(name);
            case QUAD -> coloredQuad(name);
            case TEXTURED_QUAD -> texturedQuad(name);
            default -> throw new IllegalArgumentException("Unknown builtin mesh: " + name);
        };
    }

    public static MeshData coloredTriangle(String name) {
        return MeshData.of(name, new float[]{
                -0.5f, -0.5f, 0.0f, 1.0f, 0.3f, 0.2f, 0.0f, 0.0f, 1.0f,
                0.5f, -0.5f, 0.0f, 0.2f, 1.0f, 0.3f, 0.0f, 0.0f, 1.0f,
                0.0f, 0.5f, 0.0f, 0.2f, 0.3f, 1.0f, 0.0f, 0.0f, 1.0f
        }, POSITION_COLOR);
    }

    public static MeshData coloredQuad(String name) {
        return MeshData.of(name, new float[]{
                -0.5f, -0.5f, 0.0f, 1.0f, 0.8f, 0.2f, 0.0f, 0.0f, 1.0f,
                0.5f, -0.5f, 0.0f, 0.2f, 0.8f, 1.0f, 0.0f, 0.0f, 1.0f,
                0.5f, 0.5f, 0.0f, 0.8f, 0.2f, 1.0f, 0.0f, 0.0f, 1.0f,
                -0.5f, -0.5f, 0.0f, 1.0f, 0.8f, 0.2f, 0.0f, 0.0f, 1.0f,
                0.5f, 0.5f, 0.0f, 0.8f, 0.2f, 1.0f, 0.0f, 0.0f, 1.0f,
                -0.5f, 0.5f, 0.0f, 0.2f, 1.0f, 0.8f, 0.0f, 0.0f, 1.0f
        }, POSITION_COLOR);
    }

    public static MeshData texturedQuad(String name) {
        return MeshData.indexed(name, new float[]{
                -0.5f, -0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
                0.5f, -0.5f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f,
                0.5f, 0.5f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f,
                -0.5f, 0.5f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f
        }, new int[]{0, 1, 2, 0, 2, 3}, POSITION_UV);
    }
}
