package com.kaleblangley.haikalat.subsystems.render3d.hair;

import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.mesh.Bounds3f;
import com.kaleblangley.haikalat.core.mesh.MeshData;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_FLOAT;

/**
 * Pure-JVM ribbon expansion of a deterministic strand set.
 *
 * <p>Every strand contributes {@code segments * 6} non-indexed triangle
 * vertices in one contiguous range, so the pass can record one draw per strand
 * for back-to-front alpha ordering without re-uploading geometry. A vertex
 * stores the centreline position, its tangent, the normalized position along
 * the strand, the ribbon corner sign and a per-strand random value. The
 * screen-space width expansion is evaluated in the vertex shader from those
 * values; this class never generates a camera-facing card.</p>
 *
 * <p>The returned layout is stable and shared by every LOD level:</p>
 *
 * <table>
 *   <caption>vertex layout</caption>
 *   <tr><th>location</th><th>components</th><th>meaning</th></tr>
 *   <tr><td>0</td><td>3</td><td>centreline position (local)</td></tr>
 *   <tr><td>1</td><td>3</td><td>strand tangent (local, normalized)</td></tr>
 *   <tr><td>2</td><td>1</td><td>normalized parameter along the strand</td></tr>
 *   <tr><td>3</td><td>1</td><td>ribbon corner sign (-1 / +1)</td></tr>
 *   <tr><td>4</td><td>1</td><td>per-strand random value in [0, 1)</td></tr>
 * </table>
 */
final class HairStrandGeometry {
    /** Floats per ribbon vertex. */
    static final int VERTEX_FLOATS = 9;
    /** Vertices emitted per strand segment (two triangles). */
    static final int VERTICES_PER_SEGMENT = 6;
    /** Bytes per ribbon vertex. */
    static final int VERTEX_STRIDE_BYTES = VERTEX_FLOATS * Float.BYTES;
    /** Stable layout shared by every level. */
    static final VertexLayout LAYOUT = VertexLayout.interleaved(
            VERTEX_STRIDE_BYTES,
            VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0L)
                    .semantic(VertexSemantic.POSITION).build(),
            VertexAttribute.builder().index(1).size(3).type(GL_FLOAT)
                    .offsetBytes(3L * Float.BYTES).semantic(VertexSemantic.TANGENT).build(),
            VertexAttribute.builder().index(2).size(1).type(GL_FLOAT)
                    .offsetBytes(6L * Float.BYTES).semantic(VertexSemantic.CUSTOM).build(),
            VertexAttribute.builder().index(3).size(1).type(GL_FLOAT)
                    .offsetBytes(7L * Float.BYTES).semantic(VertexSemantic.CUSTOM).build(),
            VertexAttribute.builder().index(4).size(1).type(GL_FLOAT)
                    .offsetBytes(8L * Float.BYTES).semantic(VertexSemantic.CUSTOM).build());

    private HairStrandGeometry() {
    }

    /**
     * Builds one level's complete strand mesh.
     *
     * @param settings validated strand settings
     * @param lod      level to generate
     * @return immutable mesh data plus the per-strand draw metadata
     */
    static StrandMesh build(HairStrandSettings settings, HairStrandSettings.Lod lod) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(lod, "lod");
        int strandCount = settings.strandCountFor(lod);
        int segments = settings.segmentsFor(lod);
        int verticesPerStrand = segments * VERTICES_PER_SEGMENT;
        int totalVertices = strandCount * verticesPerStrand;
        float[] vertices = new float[totalVertices * VERTEX_FLOATS];
        float[] strandCenters = new float[strandCount * 3];
        int cursor = 0;
        for (int strand = 0; strand < strandCount; strand++) {
            float[] points = HairStrandGenerator.controlPoints(settings, strand, strandCount,
                    segments);
            float random = HairStrandGenerator.randomValue(settings, strand);
            int root = strand * verticesPerStrand * VERTEX_FLOATS;
            cursor = root;
            for (int segment = 0; segment < segments; segment++) {
                int current = segment * 3;
                int next = (segment + 1) * 3;
                float[] tangentCurrent = tangentAt(points, segment, segments);
                float[] tangentNext = tangentAt(points, segment + 1, segments);
                float parameterCurrent = segment / (float) segments;
                float parameterNext = (segment + 1) / (float) segments;
                // Two triangles per segment: (current-, current+, next-) and
                // (next-, current+, next+). Duplicating the shared next- vertex
                // keeps each strand a contiguous non-indexed draw range.
                cursor = vertex(vertices, cursor, points, current, tangentCurrent,
                        parameterCurrent, -1.0f, random);
                cursor = vertex(vertices, cursor, points, current, tangentCurrent,
                        parameterCurrent, 1.0f, random);
                cursor = vertex(vertices, cursor, points, next, tangentNext,
                        parameterNext, -1.0f, random);
                cursor = vertex(vertices, cursor, points, next, tangentNext,
                        parameterNext, -1.0f, random);
                cursor = vertex(vertices, cursor, points, current, tangentCurrent,
                        parameterCurrent, 1.0f, random);
                cursor = vertex(vertices, cursor, points, next, tangentNext,
                        parameterNext, 1.0f, random);
            }
            int center = strand * 3;
            strandCenters[center] = (points[0] + points[points.length - 3]) * 0.5f;
            strandCenters[center + 1] = (points[1] + points[points.length - 2]) * 0.5f;
            strandCenters[center + 2] = (points[2] + points[points.length - 1]) * 0.5f;
        }
        MeshData data = MeshData.of("hair-strands-" + lod.name().toLowerCase(java.util.Locale.ROOT),
                vertices, LAYOUT);
        Bounds3f bounds = data.localBounds();
        return new StrandMesh(data, strandCount, segments, verticesPerStrand, strandCenters,
                bounds);
    }

    private static int vertex(float[] vertices, int cursor, float[] points, int pointOffset,
                              float[] tangent, float parameter, float corner, float random) {
        vertices[cursor++] = points[pointOffset];
        vertices[cursor++] = points[pointOffset + 1];
        vertices[cursor++] = points[pointOffset + 2];
        vertices[cursor++] = tangent[0];
        vertices[cursor++] = tangent[1];
        vertices[cursor++] = tangent[2];
        vertices[cursor++] = parameter;
        vertices[cursor++] = corner;
        vertices[cursor++] = random;
        return cursor;
    }

    private static float[] tangentAt(float[] points, int point, int segments) {
        int previous = Math.max(0, point - 1);
        int next = Math.min(segments, point + 1);
        int previousOffset = previous * 3;
        int nextOffset = next * 3;
        float x = points[nextOffset] - points[previousOffset];
        float y = points[nextOffset + 1] - points[previousOffset + 1];
        float z = points[nextOffset + 2] - points[previousOffset + 2];
        float lengthSquared = x * x + y * y + z * z;
        if (!(lengthSquared > 1.0e-16f)) {
            return new float[]{0.0f, 1.0f, 0.0f};
        }
        float inverse = 1.0f / (float) Math.sqrt(lengthSquared);
        return new float[]{x * inverse, y * inverse, z * inverse};
    }

    /**
     * One generated LOD mesh and the metadata the pass needs for sorting and
     * LOD selection.
     *
     * @param meshData        immutable CPU mesh data; the pass uploads it once
     * @param strandCount     number of strands in this level
     * @param segmentCount    segments per strand
     * @param verticesPerStrand contiguous non-indexed vertex count per strand
     * @param strandCenters   local-space midpoint per strand (xyz triples)
     * @param bounds          finite local bounds of the centreline points
     */
    record StrandMesh(MeshData meshData, int strandCount, int segmentCount,
                      int verticesPerStrand, float[] strandCenters, Bounds3f bounds) {
        StrandMesh {
            Objects.requireNonNull(meshData, "meshData");
            Objects.requireNonNull(strandCenters, "strandCenters");
            Objects.requireNonNull(bounds, "bounds");
            if (strandCount < 1 || segmentCount < 1) {
                throw new IllegalArgumentException("strand and segment counts must be positive");
            }
            if (!bounds.isFinite()) {
                throw new IllegalArgumentException("generated strand bounds must be finite");
            }
            if (strandCenters.length != strandCount * 3) {
                throw new IllegalArgumentException("strandCenters must contain one xyz per strand");
            }
        }
    }
}
