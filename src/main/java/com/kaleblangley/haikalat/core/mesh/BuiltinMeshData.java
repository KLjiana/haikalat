package com.kaleblangley.haikalat.core.mesh;

import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;

import java.util.Set;

import static org.lwjgl.opengl.GL11.GL_FLOAT;

public final class BuiltinMeshData {
    /** Builtin vertex layouts occupy attributes 0..2; instance data starts here. */
    public static final int INSTANCE_ATTRIBUTE_BASE = 3;
    public static final String TRIANGLE = "triangle";
    public static final String QUAD = "quad";
    public static final String CUBE = "cube";
    public static final String TEXTURED_QUAD = "texturedQuad";
    private static final Set<String> NAMES = Set.of(TRIANGLE, QUAD, CUBE, TEXTURED_QUAD);

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
            case CUBE -> coloredCube(name);
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

    /** Indexed 12-triangle cube with per-face colors and normals. */
    public static MeshData coloredCube(String name) {
        float[][][] faces = {
                {{-.5f, -.5f, .5f}, {.5f, -.5f, .5f}, {.5f, .5f, .5f}, {-.5f, .5f, .5f}},
                {{.5f, -.5f, -.5f}, {-.5f, -.5f, -.5f}, {-.5f, .5f, -.5f}, {.5f, .5f, -.5f}},
                {{-.5f, -.5f, -.5f}, {-.5f, -.5f, .5f}, {-.5f, .5f, .5f}, {-.5f, .5f, -.5f}},
                {{.5f, -.5f, .5f}, {.5f, -.5f, -.5f}, {.5f, .5f, -.5f}, {.5f, .5f, .5f}},
                {{-.5f, .5f, .5f}, {.5f, .5f, .5f}, {.5f, .5f, -.5f}, {-.5f, .5f, -.5f}},
                {{-.5f, -.5f, -.5f}, {.5f, -.5f, -.5f}, {.5f, -.5f, .5f}, {-.5f, -.5f, .5f}}
        };
        float[][] normals = {
                {0, 0, 1}, {0, 0, -1}, {-1, 0, 0},
                {1, 0, 0}, {0, 1, 0}, {0, -1, 0}
        };
        float[][] colors = {
                {1f, .3f, .2f}, {.2f, .6f, 1f}, {.3f, 1f, .4f},
                {1f, .8f, .2f}, {.8f, .3f, 1f}, {.2f, 1f, 1f}
        };
        float[] vertices = new float[6 * 4 * 9];
        int[] indices = new int[6 * 6];
        int vertexOffset = 0;
        int indexOffset = 0;
        for (int face = 0; face < faces.length; face++) {
            for (float[] position : faces[face]) {
                vertices[vertexOffset++] = position[0];
                vertices[vertexOffset++] = position[1];
                vertices[vertexOffset++] = position[2];
                vertices[vertexOffset++] = colors[face][0];
                vertices[vertexOffset++] = colors[face][1];
                vertices[vertexOffset++] = colors[face][2];
                vertices[vertexOffset++] = normals[face][0];
                vertices[vertexOffset++] = normals[face][1];
                vertices[vertexOffset++] = normals[face][2];
            }
            int base = face * 4;
            indices[indexOffset++] = base;
            indices[indexOffset++] = base + 1;
            indices[indexOffset++] = base + 2;
            indices[indexOffset++] = base;
            indices[indexOffset++] = base + 2;
            indices[indexOffset++] = base + 3;
        }
        return MeshData.indexed(name, vertices, indices, POSITION_COLOR);
    }
}
