package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.core.material.AnisotropicSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the anisotropic PBR shader contract: the frozen uniform names, the
 * zero-strength exact isotropic path, the Burley D/V evaluator, the bounded
 * tangent-frame fallback and the explicitly approximate IBL lookup. It also
 * prevents the benchmark-only full-scan reference from drifting from the
 * production shader.
 */
class AnisotropicShaderTest {
    private static final String PBR = "/shaders/render3d/pbr/pbr-forward.frag";
    private static final String REFERENCE = "/shaders/clustered/full-scan-reference.frag";

    @Test
    void frozenUniformsAndConstantMatchTheSettingsContract() throws IOException {
        String source = resource(PBR);
        assertTrue(source.contains("uniform int uAnisotropicEnabled;"));
        assertTrue(source.contains("uniform float uAnisotropicStrength;"));
        assertTrue(source.contains("uniform float uAnisotropicRotation;"));
        assertEquals("const float ANISOTROPIC_MIN_ALPHA = "
                        + AnisotropicSettings.MINIMUM_ALPHA + ";",
                constant(source, "ANISOTROPIC_MIN_ALPHA"));
        assertFalse(source.contains("uniform sampler2D uAnisotropic"),
                "anisotropy must not add a texture or GPU owner");
        assertFalse(source.contains("uniform samplerCube uAnisotropic"));
    }

    @Test
    void zeroStrengthKeepsTheOriginalIsotropicBranches() throws IOException {
        String source = resource(PBR);
        assertEquals(1, occurrences(source,
                        "if (uAnisotropicEnabled != 0 && uAnisotropicStrength > 0.0)"),
                "exactly one positive lazy-setup guard selects the anisotropic frame");
        assertFalse(source.contains("uAnisotropicEnabled == 0"),
                "a negative guard would make the disabled path the special case");
        String setup = section(source,
                "    // Lazy anisotropic setup: disabled or zero strength keeps the original",
                "    vec3 direct = vec3(0.0);");
        assertTrue(setup.contains("float anisotropicStrength = 0.0;"));
        assertTrue(setup.contains("vec3 anisotropicTangent = vec3(0.0);"));
        assertTrue(setup.contains("vec3 anisotropicBitangent = vec3(0.0);"));
        assertTrue(setup.contains(
                "if (!anisotropicTangentFrame(n, anisotropicTangent, anisotropicBitangent)) {"));
        assertTrue(setup.contains("anisotropicStrength = 0.0;"),
                "a failed fallback must degrade to the exact isotropic path");

        assertEquals(3, occurrences(source, "anisotropicStrength > 0.0"),
                "only the two light evaluators and the IBL lookup may branch");
        assertEquals(2, occurrences(source,
                "            : directBrdf(n, v, l, radiance, baseColor, metallic, roughness, f0);"),
                "both direct evaluators must keep the original isotropic call as the fallback");
        assertTrue(source.contains(
                "vec3 reflection = rotateEnvironment(reflect(-v, n));\n"
                        + "    float prefilterRoughness = roughness;"),
                "the original reflection must be computed before the bounded IBL branch");
        assertTrue(source.contains(
                "textureLod(uPrefilteredMap, reflection,\n"
                        + "            prefilterRoughness * uPrefilterMaxLod).rgb;"),
                "zero strength must keep the original mip expression");
    }

    @Test
    void nonFiniteRotationIsRejectedBeforeTheFrameMath() throws IOException {
        String source = resource(PBR);
        String setup = section(source,
                "    // Lazy anisotropic setup: disabled or zero strength keeps the original",
                "    vec3 direct = vec3(0.0);");
        String guard = "if (isnan(uAnisotropicRotation) || isinf(uAnisotropicRotation)) {";
        assertTrue(setup.contains(guard),
                "the setup must explicitly reject NaN/Inf rotation before cos/sin");
        assertTrue(setup.contains("} else if (!anisotropicTangentFrame("),
                "the finite-rotation frame failure must stay the second line of defense");
        assertTrue(setup.indexOf(guard) < setup.indexOf("anisotropicTangentFrame("),
                "the explicit finite guard must run before the frame/tangent math");
        assertTrue(setup.contains("anisotropicStrength = 0.0;"),
                "a non-finite rotation must restore the isotropic path");
        assertTrue(setup.contains("anisotropicTangent = vec3(0.0);")
                        && setup.contains("anisotropicBitangent = vec3(0.0);"),
                "frame failure must clear the NaN outs so a speculative ternary arm "
                        + "cannot contaminate the isotropic result");
    }

    @Test
    void tangentFrameHandlesMirroredAndDegenerateInputs() throws IOException {
        String helpers = helperBlock(resource(PBR));
        assertTrue(helpers.contains("vec3 fallbackAnisotropicTangent(vec3 n)"));
        assertTrue(helpers.contains(
                "vec3 reference = abs(n.z) < 0.999 ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0);"),
                "the fallback must use the same non-parallel reference axis as TangentGenerator");
        assertTrue(helpers.contains("cross(reference, n);"));
        assertTrue(helpers.contains("if (!(dot(candidate, candidate) > 1.0e-8)) {"),
                "NaN comparisons must route missing/degenerate tangents to the fallback");
        assertTrue(helpers.contains("vTangentHandedness"),
                "mirrored UV/negative determinant handling must consume the vertex handedness");
        assertTrue(helpers.contains("handedness < 0.0 ? -1.0 : 1.0"),
                "a non-finite handedness must stay bounded");
        assertTrue(helpers.contains("vec3 projected = candidate - n * dot(n, candidate);"),
                "the vertex tangent must be re-projected onto the shaded normal");
        assertTrue(helpers.contains("float c = cos(uAnisotropicRotation);"));
        assertTrue(helpers.contains("float s = sin(uAnisotropicRotation);"));
        assertTrue(helpers.contains(
                        "vec3 rotatedTangent = tangent * c + bitangent * s;"),
                "rotation must stay inside the tangent plane");
        assertTrue(helpers.contains(
                "if (!(tangentLengthSquared > 1.0e-8) || !(bitangentLengthSquared > 1.0e-8)) return false;"),
                "non-finite rotations must fail closed to the isotropic path");
    }

    @Test
    void burleyDistributionAndVisibilityAreTheOnlySpecularChanges() throws IOException {
        String helpers = helperBlock(resource(PBR));
        assertTrue(helpers.contains(
                "float distributionGgxAnisotropic(vec3 n, vec3 h, vec3 t, vec3 b, float at, float ab)"));
        assertTrue(helpers.contains(
                "vec3 scaled = vec3(ab * tDotH, at * bDotH, a2 * nDotH);"));
        assertTrue(helpers.contains("return a2 * weight * weight / PI;"));
        assertTrue(helpers.contains(
                "float visibilitySmithAnisotropic(vec3 n, vec3 v, vec3 l, vec3 t, vec3 b,"));
        assertTrue(helpers.contains(
                "return 0.5 / max(lambdaV + lambdaL, 1.0e-5);"));
        String direct = section(resource(PBR), "vec3 directBrdfAnisotropic(",
                "/**\n * Bounded anisotropic IBL lookup direction.");
        assertTrue(direct.contains(
                "float at = max(alpha * (1.0 + strength), ANISOTROPIC_MIN_ALPHA);"));
        assertTrue(direct.contains(
                "float ab = max(alpha * (1.0 - strength), ANISOTROPIC_MIN_ALPHA);"),
                "the full-strength minor axis must hit the shared alpha floor");
        assertTrue(direct.contains("vec3 specular = d * visibility * f;"),
                "the Burley visibility already contains the 1/(4 NoV NoL) denominator");
        assertFalse(direct.contains("geometrySmith("),
                "the anisotropic path must not mix the isotropic Schlick-GGX term");
    }

    @Test
    void iblBranchIsBoundedAndExplicitlyApproximate() throws IOException {
        String source = resource(PBR);
        String ibl = section(source, "    vec3 reflection = rotateEnvironment(reflect(-v, n));",
                "    vec2 brdf = texture(uBrdfLut, vec2(nDotV, roughness)).rg;");
        assertTrue(ibl.contains(
                "// Bounded approximation, not an exact anisotropic IBL: the existing"));
        assertTrue(ibl.contains("reflection = rotateEnvironment(anisotropicReflectionDirection("));
        assertTrue(ibl.contains("prefilterRoughness = clamp(roughness * (1.0 + 0.5 * anisotropicStrength),"));
        assertTrue(ibl.contains("textureLod(uPrefilteredMap, reflection,"));
        assertTrue(ibl.contains("prefilterRoughness * uPrefilterMaxLod).rgb;"));
        String lookup = section(source, "vec3 anisotropicReflectionDirection(",
                "/**\n * Tangent-space clearcoat normal.");
        assertTrue(lookup.contains("vec3 local = vec3(dot(reflection, tangent), "
                + "dot(reflection, bitangent),"));
        assertTrue(lookup.contains("local.x *= 1.0 + 0.5 * strength;"));
        assertTrue(lookup.contains("local.y *= 1.0 - 0.35 * strength;"));
        assertTrue(lookup.contains("warped * inversesqrt(lengthSquared) : reflection"),
                "the stretched lookup must be normalized with a bounded fallback");
    }

    @Test
    void fullScanReferenceCarriesTheSameAnisotropicCode() throws IOException {
        String production = resource(PBR);
        String reference = resource(REFERENCE);
        assertEquals(helperBlock(production), helperBlock(reference),
                "the benchmark-only reference must not drift from the anisotropic BRDF");
        assertEquals(section(production,
                        "    vec3 reflection = rotateEnvironment(reflect(-v, n));",
                        "    vec2 brdf = texture(uBrdfLut, vec2(nDotV, roughness)).rg;"),
                section(reference,
                        "    vec3 reflection = rotateEnvironment(reflect(-v, n));",
                        "    vec2 brdf = texture(uBrdfLut, vec2(nDotV, roughness)).rg;"),
                "the bounded IBL branch must match in both shaders");
    }

    private static String helperBlock(String source) {
        return section(source, "vec3 fallbackAnisotropicTangent(vec3 n) {",
                "/**\n * Tangent-space clearcoat normal.");
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
        try (InputStream input = AnisotropicShaderTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("missing shader " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
