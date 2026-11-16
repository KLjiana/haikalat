package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.core.material.ClearcoatSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the PBR clearcoat shader contract: the disabled path keeps the original
 * single-layer evaluation, the coat has its own roughness and F0, direct and
 * IBL use the same convex energy split, and the coat normal input follows the
 * declared tangent space.
 */
class ClearcoatShaderTest {
    private static final String PBR = "/shaders/render3d/pbr/pbr-forward.frag";
    private static final String REFERENCE = "/shaders/clustered/full-scan-reference.frag";

    @Test
    void clearcoatUniformsAndConstantsMatchTheSettingsContract() throws IOException {
        String source = resource(PBR);
        assertTrue(source.contains("uniform int uClearcoatEnabled;"));
        assertTrue(source.contains("uniform float uClearcoatRoughness;"));
        assertTrue(source.contains("uniform float uClearcoatNormalStrength;"));
        assertTrue(source.contains("uniform sampler2D uClearcoatNormalMap;"));
        assertEquals("const float CLEARCOAT_F0 = " + ClearcoatSettings.F0 + ";",
                constant(source, "CLEARCOAT_F0"));
        assertEquals("const float CLEARCOAT_MIN_ROUGHNESS = "
                        + ClearcoatSettings.MINIMUM_ROUGHNESS + ";",
                constant(source, "CLEARCOAT_MIN_ROUGHNESS"));
    }

    @Test
    void disabledPathOnlyTouchesTheBaseLayerInsidePositiveGuards() throws IOException {
        String source = resource(PBR);
        assertEquals(4, occurrences(source, "if (uClearcoatEnabled != 0)"),
                "exactly the two light evaluators, the main setup and the IBL split may branch");
        assertFalse(source.contains("uClearcoatEnabled == 0"),
                "a negative guard would make the disabled path the special case");
        String setup = section(source,
                "    // Lazy clearcoat setup: disabled keeps the original single-layer values.",
                "    if (uClusterDebugMode != 0) {");
        assertTrue(setup.contains("vec3 clearcoatNormalValue = n;"),
                "disabled must keep the shaded base normal as the coat normal");
        assertTrue(setup.contains("float clearcoatRoughness = 0.0;"));
        String direct = section(source, "vec3 directionalRadiance(int index",
                "vec3 localRadiance(int index");
        String local = section(source, "vec3 localRadiance(int index", "int resolveCluster()");
        for (String radiance : new String[]{direct, local}) {
            assertTrue(radiance.contains("if (uClearcoatEnabled != 0) {"));
            assertTrue(radiance.contains("float transmitted = 1.0 - clearcoatFresnel("));
            assertTrue(radiance.contains("base = base * transmitted"));
        }
    }

    @Test
    void coatUsesItsOwnRoughnessAndTheFixedInterfaceFresnel() throws IOException {
        String source = resource(PBR);
        String helpers = clearcoatHelpers(source);
        assertTrue(helpers.contains("vec3 clearcoatNormal(vec3 geometricNormal)"));
        assertTrue(helpers.contains("sampled.xy *= uClearcoatNormalStrength;"),
                "the strength must scale only the tangent-space XY slope");
        assertTrue(helpers.contains("mat3(t, b, geometricNormal) * sampled"),
                "the coat TBN must use the declared base tangent-space convention");
        assertTrue(helpers.contains("float clearcoatFresnel(float cosTheta)"));
        assertTrue(helpers.contains("float clearcoatFresnelRoughness(float cosTheta, float roughness)"));
        assertTrue(helpers.contains("vec3 clearcoatDirectBrdf(vec3 cn, vec3 v, vec3 l, float roughness)"));
        assertFalse(helpers.contains("uRoughnessFactor"),
                "clearcoat roughness must stay independent of the base roughness factor");
        assertFalse(helpers.contains("uNormalScale"),
                "clearcoat normal input must not reuse the base normal scale");
        assertFalse(helpers.contains("0.04"),
                "the interface F0 must come from CLEARCOAT_F0, not a duplicated literal");
        assertTrue(source.contains("clearcoatNormalValue = clearcoatNormal(geometricNormal);"),
                "the coat normal must be driven by the geometric normal, not the base normal map");
        assertTrue(source.contains(
                        "clearcoatRoughness = clamp(uClearcoatRoughness, CLEARCOAT_MIN_ROUGHNESS, 1.0);"),
                "coat roughness must use the shared safe GGX floor");
    }

    @Test
    void directAndIblUseTheSameConvexEnergySplit() throws IOException {
        String source = resource(PBR);
        String direct = section(source, "vec3 directionalRadiance(int index",
                "vec3 localRadiance(int index");
        // The S6 diffuse-share signal needs the coat term as a named contribution:
        // the base colour keeps the same convex split, while the parallel
        // `directSpecular` copy receives the identical transmission and coat term.
        assertTrue(direct.contains(
                "vec3 coatSpecular = clearcoatDirectBrdf(clearcoatNormalValue, v, l,\n"
                        + "                clearcoatRoughness) * radiance;\n"
                        + "        base = base * transmitted + coatSpecular;\n"
                        + "        directSpecular = directSpecular * transmitted + coatSpecular;"),
                "the direct path must add the coat term exactly once and mirror it into "
                        + "the specular side channel");
        String ibl = section(source,
                "        // Same convex interface split as the direct path:",
                "    float gtao = 1.0;");
        assertTrue(ibl.contains("float cnDotV = max(dot(clearcoatNormalValue, v), 0.0);"));
        assertTrue(ibl.contains(
                "float clearcoatF = clearcoatFresnelRoughness(cnDotV, clearcoatRoughness);"));
        assertTrue(ibl.contains("textureLod(uPrefilteredMap, coatReflection,"));
        assertTrue(ibl.contains("clearcoatRoughness * uPrefilterMaxLod).rgb;"),
                "the coat must sample the existing prefiltered environment at its own roughness");
        assertTrue(ibl.contains("texture(uBrdfLut, vec2(cnDotV, clearcoatRoughness)).rg;"));
        assertTrue(ibl.contains("indirect *= 1.0 - clearcoatF;"),
                "the base indirect term must lose exactly the coat Fresnel");
        assertTrue(ibl.contains("if (uEnableSpecularIbl != 0) {"),
                "the coat IBL contribution must follow the frame specular-IBL gate");
        assertFalse(ibl.contains("uIrradianceMap"),
                "the coat is a specular layer and must not add a second diffuse environment term");
    }

    @Test
    void fullScanReferenceCarriesTheSameClearcoatMath() throws IOException {
        String production = resource(PBR);
        String reference = resource(REFERENCE);
        assertEquals(clearcoatHelpers(production), clearcoatHelpers(reference),
                "the benchmark-only reference must not drift from the clearcoat BRDF");
        assertEquals(section(production,
                        "        // Same convex interface split as the direct path:",
                        "    float gtao = 1.0;"),
                section(reference,
                        "        // Same convex interface split as the direct path:",
                        "    float gtao = 1.0;"),
                "direct and IBL energy splits must match in both shaders");
    }

    private static String clearcoatHelpers(String source) {
        return section(source, "vec3 clearcoatNormal(vec3 geometricNormal)",
                "vec2 receiverPlaneGradient(vec3 projected)");
    }

    private static String constant(String source, String name) {
        for (String line : source.split("\n")) {
            if (line.startsWith("const float " + name + " ")) return line.strip();
        }
        throw new AssertionError("missing shader constant " + name);
    }

    private static String section(String source, String startMarker, String endMarker) {
        String normalized = source.replace("\r\n", "\n");
        int start = normalized.indexOf(startMarker);
        assertTrue(start >= 0, "missing section start: " + startMarker);
        int end = normalized.indexOf(endMarker, start + startMarker.length());
        assertTrue(end > start, "missing section end: " + endMarker);
        return normalized.substring(start, end);
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
        try (InputStream input = ClearcoatShaderTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("missing shader " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
