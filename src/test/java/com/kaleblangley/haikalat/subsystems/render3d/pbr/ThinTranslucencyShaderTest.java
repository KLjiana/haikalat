package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private static final String[] SHADER_PATHS = {PBR, CLUSTER_REFERENCE};
    private static final String[] TRANSMISSION_SHADOW_IDENTIFIERS = {
            "THIN_MAX_THICKNESS_METRES", "thinTransmissionWorldThickness",
            "directionalCascadeDepthTolerance", "directionalShadowFactorTransmissive",
            "gDirectionalShadowDepthTolerance", "thinVisibility"};
    // GLSL 4.60 reserved keywords that could silently shadow a built-in if a new
    // helper reused one. The check keeps added names out of this set.
    private static final Set<String> GLSL_RESERVED = Set.of(
            "common", "partition", "active", "asm", "class", "union", "enum", "typedef",
            "template", "this", "resource", "goto", "inline", "noinline", "public", "static",
            "extern", "external", "interface", "long", "short", "double", "half", "fixed",
            "unsigned", "superp", "input", "output", "hvec2", "hvec3", "hvec4", "fvec2",
            "fvec3", "fvec4", "sampler3DRect", "filter", "sizeof", "cast", "namespace",
            "using", "row_major", "attribute", "varying");
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
        // The local-light evaluator keeps the direct additive term; the
        // directional evaluator captures the term so only its shadow share can
        // be re-evaluated with the bounded thickness tolerance.
        assertEquals(1, occurrences(source,
                        "base += thinSurfaceTransmission(n, v, l, radiance, baseColor);"),
                "the local-light evaluator must add the unchanged term once");
        assertEquals(1, occurrences(source,
                        "vec3 thin = thinSurfaceTransmission(n, v, l, radiance, baseColor);"),
                "the directional evaluator must capture the term for the split");
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
        assertEquals(1, occurrences(source,
                "base += thinSurfaceTransmission(n, v, l, radiance, baseColor);"));
        assertEquals(1, occurrences(source,
                "vec3 thin = thinSurfaceTransmission(n, v, l, radiance, baseColor);"));
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
        int directionalSubsurface = directional.indexOf(
                "base += subsurfaceTransmission(n, l, radiance);");
        assertTrue(directionalSubsurface > 0,
                "missing directional subsurface transmission term; ordering check would be vacuous");
        int directionalCapture = directional.indexOf(
                "vec3 thin = thinSurfaceTransmission(n, v, l, radiance, baseColor);");
        assertTrue(directionalCapture > directionalSubsurface,
                "the thin term must be evaluated next to the unchanged reflection terms");
        assertTrue(directional.indexOf("base += thin;") > directionalCapture,
                "the captured thin term must still enter the additive result");
        assertTrue(directional.indexOf("return visibility * base;") > directionalCapture,
                "the legacy branch must scale the whole additive result by visibility");

        int localSubsurface = local.indexOf("base += subsurfaceTransmission(n, l, radiance);");
        assertTrue(localSubsurface > 0,
                "missing local subsurface transmission term; ordering check would be vacuous");
        int localTransmission = local.indexOf(
                "base += thinSurfaceTransmission(n, v, l, radiance, baseColor);");
        assertTrue(localTransmission > localSubsurface,
                "the local thin term must stay additive next to the unchanged reflection terms");
        assertTrue(local.indexOf("return visibility * base;") > localTransmission,
                "visibility must scale the whole local additive result");
    }

    @Test
    void transmissionShadowToleranceIsBoundedToTheBacklightLookupOnly() throws IOException {
        for (String path : SHADER_PATHS) {
            String source = resource(path);
            String label = path.substring(path.lastIndexOf('/') + 1);
            assertTrue(source.contains("const float THIN_MAX_THICKNESS_METRES = 0.05;"),
                    label + ": the world-thickness bound must be the documented 5 cm");

            String thickness = section(source,
                    "float thinTransmissionWorldThickness() {",
                    "float directionalCascadeDepthTolerance(int cascade");
            assertTrue(thickness.contains("if (isnan(uThicknessFactor) || isinf(uThicknessFactor))"
                            + " return 0.0;"),
                    label + ": a non-finite thickness must fail closed before the clamp");
            assertTrue(thickness.contains(
                            "return clamp(uThicknessFactor, 0.0, 1.0) * THIN_MAX_THICKNESS_METRES;"),
                    label + ": the slab is the clamped control times the compile-time bound");

            String tolerance = section(source,
                    "float directionalCascadeDepthTolerance(int cascade",
                    "float directionalShadowFactorTransmissive(vec3 n, vec3 l)");
            assertTrue(tolerance.contains("directionalCascadeMatrix(cascade)"),
                    label + ": the tolerance must use the selected cascade matrix");
            assertTrue(tolerance.contains("vec4(vWorldPosition, 1.0)"),
                    label + ": the tolerance must project the receiver");
            assertTrue(tolerance.contains(
                            "vec4(vWorldPosition + l * worldThickness, 1.0)"),
                    label + ": the tolerance must project the slab toward the light");
            assertTrue(tolerance.contains(
                            "float difference = abs(receiver.z / receiver.w - slab.z / slab.w);"),
                    label + ": the tolerance is the normalized window-depth extent");
            assertTrue(tolerance.contains("return clamp(difference * 0.5, 0.0, 0.02);"),
                    label + ": the tolerance must be clamped to [0, 0.02] normalized depth");
            assertTrue(tolerance.contains("isnan(difference) || isinf(difference)"),
                    label + ": a non-finite projection must fail closed");

            String transmissive = section(source,
                    "float directionalShadowFactorTransmissive(vec3 n, vec3 l)",
                    "float directionalContactShadow(vec3 n, vec3 l)");
            assertTrue(transmissive.contains(
                            "if (uHasDirectionalShadow == 0 || uDirectionalCascadeCount <= 0)"
                                    + " return 0.0;"),
                    label + ": no directional shadow/cascade must return a fully lit factor");
            assertTrue(transmissive.contains(
                            "directionalCascadeDepthTolerance("),
                    label + ": the transmissive factor must derive the tolerance");
            assertTrue(transmissive.contains("gDirectionalShadowDepthTolerance = tolerance;")
                            && transmissive.contains("gDirectionalShadowDepthTolerance = 0.0;"),
                    label + ": the extra bias may only be raised around one shadow lookup");
            assertTrue(transmissive.contains("float factor = directionalShadowFactor(n, l);"),
                    label + ": the same PCF/PCSS selection must produce the transmissive factor");
            // The exact lookup keeps its call sites; only the comparison bias
            // gains the tolerance when the transmissive wrapper raises it.
            assertEquals(2, occurrences(source, "+ gDirectionalShadowDepthTolerance"),
                    label + ": exactly the PCF and PCSS comparison references take the extra bias");
            String pcf = section(source, "float sampleDirectionalCascade(int cascade",
                    "float sampleDirectionalCascadePcss(int cascade");
            String pcss = section(source, "float sampleDirectionalCascadePcss(int cascade",
                    "float sampleDirectionalShadow(int cascade");
            assertEquals(1, occurrences(pcf,
                            "for (int x = -2; x <= 2; ++x) for (int y = -2; y <= 2; ++y)"),
                    label + ": the legacy 5x5 PCF loop bound must stay identical");
            assertEquals(1, occurrences(pcss, "for (int tap = 0; tap < PCSS_MAX_BLOCKER_TAPS; ++tap)"),
                    label + ": the PCSS blocker loop bound must stay identical");
            assertEquals(1, occurrences(pcss, "for (int tap = 0; tap < PCSS_MAX_FILTER_TAPS; ++tap)"),
                    label + ": the PCSS filter loop bound must stay identical");

            String directional = section(source, "vec3 directionalRadiance(int index",
                    "vec3 localRadiance(int index");
            String local = section(source, "vec3 localRadiance(int index", "int resolveCluster()");
            assertEquals(1, occurrences(directional,
                            "directionalShadowFactorTransmissive(n, l)"),
                    label + ": only the directional evaluator may re-evaluate the backlight shadow");
            assertEquals(0, occurrences(local, "directionalShadowFactorTransmissive"),
                    label + ": local point/spot shadows must not take the directional tolerance");
            assertTrue(directional.contains(
                            "if (all(equal(thin, vec3(0.0))) || index != uDirectionalShadowFrameLightIndex"),
                    label + ": zero/no-slab/non-shadow-light must take the exact legacy branch");
            assertTrue(directional.contains(
                            "return visibility * (base - thin) + thinVisibility * thin;"),
                    label + ": only the thin share may be recombined with thinVisibility");
            assertTrue(directional.contains("thinVisibility = 1.0 - directionalShadowFactorTransmissive"),
                    label + ": the thin share uses the transmissive visibility");
            assertTrue(directional.contains("visibility = 1.0 - directionalShadowFactor(n, l);"),
                    label + ": the direct term must keep the exact visibility");
            // Five uses: declaration, PCF bias, PCSS bias, raise, reset.
            assertEquals(5, countIdentifier(source, "gDirectionalShadowDepthTolerance"),
                    label + ": the tolerance global must have exactly one writer pair");
        }
    }

    @Test
    void transmissionShadowHelpersUseNoReservedGlslKeywords() throws IOException {
        for (String path : SHADER_PATHS) {
            String source = resource(path);
            String label = path.substring(path.lastIndexOf('/') + 1);
            for (String name : TRANSMISSION_SHADOW_IDENTIFIERS) {
                assertFalse(GLSL_RESERVED.contains(name),
                        label + ": '" + name + "' is a reserved GLSL keyword");
                assertTrue(name.startsWith("THIN_") || Character.isLowerCase(name.charAt(0)),
                        label + ": '" + name + "' does not follow the shader naming style");
                assertTrue(source.contains(name),
                        label + ": missing transmission-shadow identifier " + name);
            }
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
