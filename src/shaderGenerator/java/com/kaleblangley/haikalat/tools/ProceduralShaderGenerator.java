package com.kaleblangley.haikalat.tools;

import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.MeshData;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Build-time generator for flat and indexed procedural stress geometry. */
public final class ProceduralShaderGenerator {
    private static final byte[] NO_INDICES = {};
    private static final byte[] QUAD_INDICES = {0, 1, 3, 0, 3, 2};
    private static final byte[] CUBE_INDICES = {
            // Cache-optimized triangle order. Each triplet keeps the original outward winding.
            0, 6, 2, 6, 3, 2, 1, 0, 2, 1, 2, 3,
            0, 4, 6, 0, 1, 5, 5, 1, 3, 0, 5, 4,
            6, 7, 3, 5, 3, 7, 4, 5, 7, 4, 7, 6
    };
    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("TRIANGLE", BuiltinMeshData.TRIANGLE, false, false,
                    3, NO_INDICES, trianglePosition()),
            new Definition("QUAD", BuiltinMeshData.QUAD, false, false,
                    4, QUAD_INDICES, quadPosition()),
            new Definition("CUBE", BuiltinMeshData.CUBE, true, true,
                    8, CUBE_INDICES, cubePosition()));

    private ProceduralShaderGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected generated Java and resource output directories");
        }
        Path javaRoot = Path.of(args[0]);
        Path resourceRoot = Path.of(args[1]);
        String flatTemplate = readTemplate("/stress_procedural.vert.template");
        String indexedTemplate = readTemplate("/stress_indexed.vert.template");
        String ssboTemplate = readTemplate("/stress_indexed_ssbo.vert.template");
        List<Generated> generated = new ArrayList<>();
        for (Definition definition : DEFINITIONS) {
            generated.add(generateShaders(resourceRoot, flatTemplate, indexedTemplate,
                    ssboTemplate, definition));
        }
        generateCatalog(javaRoot, generated);
    }

    private static Generated generateShaders(Path root, String flatTemplate,
                                              String indexedTemplate, String ssboTemplate,
                                              Definition definition) throws IOException {
        MeshData mesh = BuiltinMeshData.named(definition.builtinName());
        List<float[]> expanded = expandedPositions(mesh);
        String stem = definition.builtinName();
        String flatPath = writeShader(root, stem + ".vert", flatTemplate
                .replace("@VERTEX_COUNT@", Integer.toString(expanded.size()))
                .replace("@POSITIONS@", formatPositions(expanded))
                .replace("@ROTATE_LOCAL@", rotation(definition.rotate()))
                .replace("@DEPTH@", depth(definition.depth())));
        String indexedPath = writeShader(root, stem + "_indexed.vert", indexedTemplate
                .replace("@LOCAL_POSITION@", definition.localPosition())
                .replace("@ROTATE_LOCAL@", rotation(definition.rotate()))
                .replace("@DEPTH@", depth(definition.depth())));
        String ssboPath = writeShader(root, stem + "_indexed_ssbo.vert", ssboTemplate
                .replace("@LOCAL_POSITION@", definition.localPosition())
                .replace("@ROTATE_LOCAL@", packedRotation(definition.rotate())));
        return new Generated(definition, flatPath, indexedPath, ssboPath,
                expanded.size(), expanded.size() / 3);
    }

    private static String writeShader(Path root, String fileName, String source) throws IOException {
        Path output = root.resolve("demo/stress/generated").resolve(fileName);
        Files.createDirectories(output.getParent());
        Files.writeString(output, source, StandardCharsets.UTF_8);
        return "/demo/stress/generated/" + fileName;
    }

    private static List<float[]> expandedPositions(MeshData mesh) {
        float[] vertices = mesh.vertices();
        int stride = mesh.layout().strideBytes() / Float.BYTES;
        int[] indices = mesh.indices();
        int count = indices.length == 0 ? mesh.vertexCount() : indices.length;
        List<float[]> positions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int vertex = indices.length == 0 ? i : indices[i];
            int offset = vertex * stride;
            positions.add(new float[]{vertices[offset], vertices[offset + 1], vertices[offset + 2]});
        }
        return positions;
    }

    private static String formatPositions(List<float[]> positions) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < positions.size(); i++) {
            float[] p = positions.get(i);
            result.append("    vec3(")
                    .append(glsl(p[0])).append(", ")
                    .append(glsl(p[1])).append(", ")
                    .append(glsl(p[2])).append(')');
            result.append(i + 1 == positions.size() ? '\n' : ",\n");
        }
        return result.toString().stripTrailing();
    }

    private static void generateCatalog(Path root, List<Generated> generated) throws IOException {
        StringBuilder constants = new StringBuilder();
        for (int i = 0; i < generated.size(); i++) {
            Generated item = generated.get(i);
            Definition definition = item.definition();
            constants.append("    ").append(definition.enumName())
                    .append("(\"").append(definition.builtinName()).append("\", \"")
                    .append(item.gpuResource()).append("\", \"")
                    .append(item.indexedResource()).append("\", \"")
                    .append(item.ssboResource()).append("\", ")
                    .append(item.gpuVertexCount()).append(", ")
                    .append(definition.logicalVertexCount()).append(", new byte[]{")
                    .append(formatBytes(definition.indices())).append("}, ")
                    .append(item.triangleCount()).append(", ")
                    .append(definition.depth()).append(')')
                    .append(i + 1 == generated.size() ? ";\n" : ",\n");
        }
        String source = """
                package com.kaleblangley.haikalat.demo.stress;

                import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;

                /** Generated by ProceduralShaderGenerator. Do not edit by hand. */
                public enum GeneratedStressPrimitive {
                %s
                    private final String builtinName;
                    private final String gpuShaderResource;
                    private final String indexedShaderResource;
                    private final String indexedSsboShaderResource;
                    private final int gpuVertexCount;
                    private final int logicalVertexCount;
                    private final byte[] indices;
                    private final int trianglesPerInstance;
                    private final boolean depthLayers;

                    GeneratedStressPrimitive(String builtinName, String gpuShaderResource,
                                             String indexedShaderResource,
                                             String indexedSsboShaderResource,
                                             int gpuVertexCount, int logicalVertexCount,
                                             byte[] indices, int trianglesPerInstance,
                                             boolean depthLayers) {
                        this.builtinName = builtinName;
                        this.gpuShaderResource = gpuShaderResource;
                        this.indexedShaderResource = indexedShaderResource;
                        this.indexedSsboShaderResource = indexedSsboShaderResource;
                        this.gpuVertexCount = gpuVertexCount;
                        this.logicalVertexCount = logicalVertexCount;
                        this.indices = indices;
                        this.trianglesPerInstance = trianglesPerInstance;
                        this.depthLayers = depthLayers;
                    }

                    public String builtinName() { return builtinName; }
                    public String gpuShaderResource() { return gpuShaderResource; }
                    public String indexedShaderResource() { return indexedShaderResource; }
                    public String indexedSsboShaderResource() { return indexedSsboShaderResource; }
                    public int gpuVertexCount() { return gpuVertexCount; }
                    public int logicalVertexCount() { return logicalVertexCount; }
                    public byte[] indices() { return indices.clone(); }
                    public int indexCount() { return indices.length; }
                    public int indexType() { return GL_UNSIGNED_BYTE; }
                    public boolean indexed() { return indices.length != 0; }
                    public int trianglesPerInstance() { return trianglesPerInstance; }
                    public boolean depthLayers() { return depthLayers; }
                }
                """.formatted(constants);
        Path output = root.resolve("com/kaleblangley/haikalat/demo/stress/GeneratedStressPrimitive.java");
        Files.createDirectories(output.getParent());
        Files.writeString(output, source, StandardCharsets.UTF_8);
    }

    private static String readTemplate(String resource) throws IOException {
        try (InputStream input = ProceduralShaderGenerator.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing stress shader template " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String formatBytes(byte[] values) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) result.append(", ");
            result.append(Byte.toUnsignedInt(values[i]));
        }
        return result.toString();
    }

    private static String rotation(boolean enabled) {
        return enabled
                ? "    local.xy = vec2(c * local.x - s * local.y, s * local.x + c * local.y);"
                : "    // This specialized 2D primitive has no local rotation.";
    }

    private static String packedRotation(boolean enabled) {
        return enabled
                ? """
                      vec2 baseRotation = unpackSnorm2x16(instanceData.z);
                      float c = baseRotation.x * uTimeRotation.x - baseRotation.y * uTimeRotation.y;
                      float s = baseRotation.y * uTimeRotation.x + baseRotation.x * uTimeRotation.y;
                      local.xy = vec2(c * local.x - s * local.y, s * local.x + c * local.y);"""
                : "    // This specialized 2D primitive has no local rotation.";
    }

    private static String depth(boolean enabled) {
        return enabled ? "float((instance & 15) - 8) * 0.008" : "0.0";
    }

    private static String trianglePosition() {
        return """
                    const vec2 TRIANGLE_CORNERS[3] = vec2[3](
                        vec2(-0.5, -0.5), vec2(0.5, -0.5), vec2(0.0, 0.5));
                    vec3 local = vec3(TRIANGLE_CORNERS[gl_VertexID], 0.0) * instanceScale;""";
    }

    private static String quadPosition() {
        return """
                    int vertex = gl_VertexID;
                    vec3 local = vec3(
                        (vertex & 1) == 0 ? -0.5 : 0.5,
                        (vertex & 2) == 0 ? -0.5 : 0.5,
                        0.0) * instanceScale;""";
    }

    private static String cubePosition() {
        return """
                    int vertex = gl_VertexID;
                    vec3 local = vec3(
                        (vertex & 1) == 0 ? -0.5 : 0.5,
                        (vertex & 2) == 0 ? -0.5 : 0.5,
                        (vertex & 4) == 0 ? -0.5 : 0.5) * instanceScale;""";
    }

    private static String glsl(float value) {
        String text = String.format(Locale.ROOT, "%.9f", value);
        return text.equals("-0.000000000") ? "0.000000000" : text;
    }

    private record Definition(String enumName, String builtinName, boolean depth, boolean rotate,
                              int logicalVertexCount, byte[] indices, String localPosition) {
    }

    private record Generated(Definition definition, String gpuResource, String indexedResource,
                             String ssboResource, int gpuVertexCount, int triangleCount) {
    }
}
