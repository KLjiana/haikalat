package com.kaleblangley.haikalat.gl.mesh;

public record DrawSortKey(int shaderProgramId, int vertexArrayId, int textureId, int materialId) implements Comparable<DrawSortKey> {
    @Override
    public int compareTo(DrawSortKey other) {
        int result = Integer.compare(shaderProgramId, other.shaderProgramId);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(vertexArrayId, other.vertexArrayId);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(textureId, other.textureId);
        if (result != 0) {
            return result;
        }
        return Integer.compare(materialId, other.materialId);
    }
}
