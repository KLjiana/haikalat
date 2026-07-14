package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.demo.stress.GeneratedStressPrimitive;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProceduralShaderGenerationTest {
    @Test
    void generatedCatalogContainsReusableUint8Topology() {
        assertEquals(3, GeneratedStressPrimitive.TRIANGLE.logicalVertexCount());
        assertEquals(0, GeneratedStressPrimitive.TRIANGLE.indexCount());

        assertEquals(4, GeneratedStressPrimitive.QUAD.logicalVertexCount());
        assertEquals(6, GeneratedStressPrimitive.QUAD.indexCount());
        assertEquals(3, maxUnsigned(GeneratedStressPrimitive.QUAD.indices()));

        assertEquals(8, GeneratedStressPrimitive.CUBE.logicalVertexCount());
        assertEquals(36, GeneratedStressPrimitive.CUBE.indexCount());
        assertEquals(7, maxUnsigned(GeneratedStressPrimitive.CUBE.indices()));
        assertEquals(8, Arrays.stream(toUnsigned(GeneratedStressPrimitive.CUBE.indices()))
                .distinct().count());
    }

    @Test
    void cubeTrianglesKeepOutwardCounterClockwiseWinding() {
        int[] indices = toUnsigned(GeneratedStressPrimitive.CUBE.indices());
        for (int index = 0; index < indices.length; index += 3) {
            float[] a = cubeCorner(indices[index]);
            float[] b = cubeCorner(indices[index + 1]);
            float[] c = cubeCorner(indices[index + 2]);
            float abX = b[0] - a[0];
            float abY = b[1] - a[1];
            float abZ = b[2] - a[2];
            float acX = c[0] - a[0];
            float acY = c[1] - a[1];
            float acZ = c[2] - a[2];
            float normalX = abY * acZ - abZ * acY;
            float normalY = abZ * acX - abX * acZ;
            float normalZ = abX * acY - abY * acX;
            float centerX = a[0] + b[0] + c[0];
            float centerY = a[1] + b[1] + c[1];
            float centerZ = a[2] + b[2] + c[2];
            assertTrue(normalX * centerX + normalY * centerY + normalZ * centerZ > 0.0f,
                    "Cube triangle " + index / 3 + " must face outward");
        }
    }

    @Test
    void generatedShadersAndCatalogStaySynchronized() throws IOException {
        for (GeneratedStressPrimitive primitive : GeneratedStressPrimitive.values()) {
            String flat = resource(primitive.gpuShaderResource());
            String indexed = resource(primitive.indexedShaderResource());
            String ssbo = resource(primitive.indexedSsboShaderResource());

            assertTrue(flat.contains("PRIMITIVE_VERTICES[" + primitive.gpuVertexCount() + "]"));
            assertEquals(primitive.logicalVertexCount() - 1,
                    expectedMaximumVertexId(primitive));
            assertEquals(1, occurrences(ssbo, "instances[gl_InstanceID]"));
            assertTrue(ssbo.contains("uvec4 instanceData = instances[gl_InstanceID]"));
            for (String source : new String[]{flat, indexed, ssbo}) {
                assertFalse(source.contains("uShape"));
                assertFalse(source.contains("sin("));
                assertFalse(source.contains("cos("));
            }
        }
    }

    private static int expectedMaximumVertexId(GeneratedStressPrimitive primitive) {
        return primitive.indexed() ? maxUnsigned(primitive.indices()) : primitive.logicalVertexCount() - 1;
    }

    private static int maxUnsigned(byte[] values) {
        return Arrays.stream(toUnsigned(values)).max().orElse(-1);
    }

    private static int[] toUnsigned(byte[] values) {
        int[] result = new int[values.length];
        for (int i = 0; i < values.length; i++) result[i] = Byte.toUnsignedInt(values[i]);
        return result;
    }

    private static float[] cubeCorner(int vertex) {
        return new float[]{
                (vertex & 1) == 0 ? -0.5f : 0.5f,
                (vertex & 2) == 0 ? -0.5f : 0.5f,
                (vertex & 4) == 0 ? -0.5f : 0.5f
        };
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = ProceduralShaderGenerationTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing generated shader: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
