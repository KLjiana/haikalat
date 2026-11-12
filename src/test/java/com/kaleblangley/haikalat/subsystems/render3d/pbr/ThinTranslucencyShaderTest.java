package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the #8 API/shader contract across every PBR-forward material factory.
 *
 * <p>The worker GL run failed because {@code createWithBindings(...)} built the
 * material without uploading {@code uThicknessFactor}, {@code uTransmissionFactor}
 * and {@code uBacklightFactor}; the uniforms kept their GLSL default zero, so
 * the transmission term returned zero for every thin material created through
 * the glTF-style path.  These checks require the uploads in both shared
 * builders ({@code create} and {@code baseBuilder}) and require the shader term
 * to remain additive, bounded and exactly zero when either control is off.</p>
 */
class ThinTranslucencyShaderTest {
    private static final String PBR = "/shaders/render3d/pbr/pbr-forward.frag";
    private static final String CLUSTER_REFERENCE =
            "/shaders/clustered/full-scan-reference.frag";
    private static final String[] UNIFORMS = {
            "uThicknessFactor", "uTransmissionFactor", "uBacklightFactor"};
    private static final Pattern TOP_LEVEL_LOCAL = Pattern.compile(
            "^    (?:const\\s+)?(?:vec[234]|mat[234]|float|int|uint|bool|ivec[234]|uvec[234])"
                    + "\\s+(\\w+)\\s*=", Pattern.MULTILINE);

    @Test
    void forwardShaderDeclaresAndAddsTheBoundedTransmissionTerm() throws IOException {
        String source = resource(PBR);
        for (String name : UNIFORMS) {
            assertTrue(source.contains("uniform float " + name + ";"),
                    "missing forward uniform " + name);
            assertTrue(countIdentifier(source, name) >= 2,
                    name + " is declared but never referenced and may be optimized out");
        }
        String term = section(source, "vec3 thinSurfaceTransmission(vec3 n",
                "float rangeInverseSquareAttenuation");
        assertTrue(term.contains("if (uTransmissionFactor <= 0.0 || uBacklightFactor <= 0.0)"
                        + " return vec3(0.0);"),
                "either zero control must return exactly zero before any expensive math");
        assertTrue(term.contains("clamp(dot(-n, l), 0.0, 1.0)"),
                "the term must use the back-facing cosine");
        assertTrue(term.contains("clamp(dot(v, -l), 0.0, 1.0)"),
                "the forward lobe must use the view/ray cosine");
        assertTrue(term.contains("pow(forward, mix(1.0, 8.0, thickness))"),
                "thickness must tighten the forward lobe from exponent 1 to 8");
        assertTrue(term.contains("exp(-2.0 * thickness)"),
                "thickness must absorb the transmitted energy");
        assertTrue(term.contains(
                        "(uTransmissionFactor * uBacklightFactor * back * lobe * absorption)"),
                "the artistic transmission weight must be the documented product");
        assertEquals(2, occurrences(source,
                        "base += thinSurfaceTransmission(n, v, l, radiance, baseColor);"),
                "directional and local evaluators must add the same term once each");
        assertTermIsAdditiveAfterSubsurface(source);
    }

    @Test
    void clusteredReferenceShaderCarriesTheSameThinContract() throws IOException {
        String source = resource(CLUSTER_REFERENCE);
        for (String name : UNIFORMS) {
            assertTrue(source.contains("uniform float " + name + ";"),
                    "missing clustered-reference uniform " + name);
            assertTrue(countIdentifier(source, name) >= 2,
                    name + " is declared but not used in the clustered reference shader");
        }
        assertTrue(source.contains("vec3 thinSurfaceTransmission(vec3 n"),
                "the light-table reference shader must mirror the production surface term");
        assertEquals(2, occurrences(source,
                        "base += thinSurfaceTransmission(n, v, l, radiance, baseColor);"));
        assertTermIsAdditiveAfterSubsurface(source);
    }

    @Test
    void everyPbrForwardMaterialFactoryUploadsTheThinUniforms() throws IOException {
        String source = Files.readString(Path.of("src", "main", "java", "com",
                "kaleblangley", "haikalat", "subsystems", "render3d", "pbr",
                "PbrMaterials.java"));
        String create = section(source, "public static Material create(ShaderProgram shader",
                "    /** 使用每个纹理角色自己的 glTF sampler 创建 PBR material。 */");
        String baseBuilder = section(source, "private static Material.Builder baseBuilder(",
                "    /**\n     * Uploads the default disabled probe contract.");
        for (String name : UNIFORMS) {
            String property = name.substring(1);
            property = Character.toLowerCase(property.charAt(0)) + property.substring(1);
            String upload = "setFloat(\"" + name + "\", properties."
                    + property + "())";
            assertTrue(create.contains(upload),
                    "legacy create(...) must upload " + name + " (" + upload + ")");
            assertTrue(baseBuilder.contains(upload),
                    "baseBuilder(...) must upload " + name + " for every createWithBindings path");
        }
        assertTrue(source.contains("Material.Builder builder = baseBuilder(shader, properties,"
                        + " clearcoat, anisotropic, parallax,"),
                "the public createWithBindings overload chain must route through baseBuilder");
        assertTrue(source.contains(
                        "return createWithBindings(shader, properties, supplied, fallbacks, cullMode,"),
                "specialized PBR factories must reuse the shared createWithBindings path");
        for (String name : UNIFORMS) {
            assertEquals(2, occurrences(source, "\"" + name + "\""),
                    name + " must be uploaded by exactly the two shared PBR-forward builders; "
                            + "a third path or a missing path is a contract change");
        }
    }

    private static void assertTermIsAdditiveAfterSubsurface(String source) {
        String directional = section(source, "vec3 directionalRadiance(int index",
                "vec3 localRadiance(int index");
        String local = section(source, "vec3 localRadiance(int index", "int resolveCluster()");
        for (String evaluator : new String[]{directional, local}) {
            int transmission = evaluator.indexOf(
                    "base += thinSurfaceTransmission(n, v, l, radiance, baseColor);");
            assertTrue(transmission > 0, "missing additive thin term");
            int subsurface = evaluator.indexOf(
                    "base += subsurfaceTransmission(n, l, radiance);");
            assertTrue(subsurface > 0,
                    "missing subsurface transmission term; the ordering check would be vacuous");
            assertTrue(transmission > subsurface,
                    "the thin term must stay additive next to the unchanged reflection terms");
            assertTrue(evaluator.indexOf("return visibility * base;") > transmission,
                    "visibility must scale the whole additive result");
        }
    }

    @Test
    void evaluatorFunctionsDeclareEachLocalOnce() throws IOException {
        // The first integration attempt duplicated `vec3 radiance` in the local
        // evaluator during conflict resolution; GLSL rejects it with C1038.
        // No GL context is available in the JVM suite, so guard the lexical
        // property a compiler enforces here: one top-level declaration per name
        // in each evaluator.  Nested block locals are indented eight spaces and
        // are intentionally out of scope; shadowing them is legal.
        assertUniqueTopLevelLocals(resource(PBR), "pbr-forward.frag");
        assertUniqueTopLevelLocals(resource(CLUSTER_REFERENCE), "full-scan-reference.frag");
    }

    private static void assertUniqueTopLevelLocals(String source, String label) {
        String[][] evaluators = {
                {"directional", section(source, "vec3 directionalRadiance(int index",
                        "vec3 localRadiance(int index")},
                {"local", section(source, "vec3 localRadiance(int index",
                        "int resolveCluster()")}};
        for (String[] evaluator : evaluators) {
            java.util.Set<String> names = new java.util.HashSet<>();
            Matcher declaration = TOP_LEVEL_LOCAL.matcher(evaluator[1]);
            while (declaration.find()) {
                String name = declaration.group(1);
                assertTrue(names.add(name), label + ": the " + evaluator[0]
                        + " evaluator declares local '" + name + "' more than once; "
                        + "GLSL rejects duplicate declarations (C1038)");
            }
        }
    }

    private static String section(String source, String startMarker, String endMarker) {
        String normalized = source.replace("\r\n", "\n");
        int start = normalized.indexOf(startMarker);
        assertTrue(start >= 0, "missing section start: " + startMarker);
        int end = normalized.indexOf(endMarker, start + startMarker.length());
        assertTrue(end > start, "missing section end: " + endMarker);
        return normalized.substring(start, end);
    }

    private static int countIdentifier(String source, String name) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(source);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = ThinTranslucencyShaderTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("missing shader " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
