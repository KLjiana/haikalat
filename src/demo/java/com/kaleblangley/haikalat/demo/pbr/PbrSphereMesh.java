package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.mesh.MeshData;

/** Demo 私有的确定性 UV sphere，不扩张 stable builtin mesh API。 */
final class PbrSphereMesh {
    private PbrSphereMesh() {
    }

    static MeshData create(int longitudeSegments, int latitudeSegments) {
        if (longitudeSegments < 3 || latitudeSegments < 2) {
            throw new IllegalArgumentException("sphere segment counts are too small");
        }
        int columns = longitudeSegments + 1;
        float[] vertices = new float[columns * (latitudeSegments + 1) * 12];
        int cursor = 0;
        for (int y = 0; y <= latitudeSegments; y++) {
            float v = y / (float) latitudeSegments;
            double phi = Math.PI * v;
            float ring = (float) Math.sin(phi);
            float py = (float) Math.cos(phi);
            for (int x = 0; x <= longitudeSegments; x++) {
                float u = x / (float) longitudeSegments;
                double theta = Math.PI * 2.0 * u;
                float px = ring * (float) Math.cos(theta);
                float pz = ring * (float) Math.sin(theta);
                vertices[cursor++] = px;
                vertices[cursor++] = py;
                vertices[cursor++] = pz;
                vertices[cursor++] = u;
                vertices[cursor++] = 1.0f - v;
                vertices[cursor++] = px;
                vertices[cursor++] = py;
                vertices[cursor++] = pz;
                vertices[cursor++] = -(float) Math.sin(theta);
                vertices[cursor++] = 0.0f;
                vertices[cursor++] = (float) Math.cos(theta);
                vertices[cursor++] = 1.0f;
            }
        }
        int[] indices = new int[longitudeSegments * latitudeSegments * 6];
        cursor = 0;
        for (int y = 0; y < latitudeSegments; y++) {
            for (int x = 0; x < longitudeSegments; x++) {
                int a = y * columns + x;
                int b = (y + 1) * columns + x;
                indices[cursor++] = a;
                indices[cursor++] = b;
                indices[cursor++] = a + 1;
                indices[cursor++] = a + 1;
                indices[cursor++] = b;
                indices[cursor++] = b + 1;
            }
        }
        VertexLayout layout = VertexLayout.interleaved(12 * Float.BYTES,
                VertexAttribute.builder().index(0).size(3).offsetBytes(0)
                        .semantic(VertexSemantic.POSITION).build(),
                VertexAttribute.builder().index(1).size(2).offsetBytes(3L * Float.BYTES)
                        .semantic(VertexSemantic.TEXCOORD_0).build(),
                VertexAttribute.builder().index(2).size(3).offsetBytes(5L * Float.BYTES)
                        .semantic(VertexSemantic.NORMAL).build(),
                VertexAttribute.builder().index(3).size(4).offsetBytes(8L * Float.BYTES)
                        .semantic(VertexSemantic.TANGENT).build());
        return MeshData.indexed("pbr-demo-sphere", vertices, indices, layout);
    }
}
