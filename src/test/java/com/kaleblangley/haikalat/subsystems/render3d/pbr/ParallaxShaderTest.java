package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.core.material.ParallaxOcclusionSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the parallax-occlusion shader contract: the frozen uniform names and
 * constants, the exact original-UV path when disabled or at zero height scale,
 * the fact that only the following material lookups consume the displaced UV,
 * the bounded/fail-closed march and the synchronization with the benchmark-only
 * full-scan reference.
 */
class ParallaxShaderTest {
    private static final String PBR = "/shaders/render3d/pbr/pbr-forward.frag";
    private static final String REFERENCE = "/shaders/clustered/full-scan-reference.frag";

    @Test
    void frozenUniformsAndConstantsMatchTheSettingsContract() throws IOException {
        String source = resource(PBR);
        for (String name : new String[]{"uParallaxEnabled", "uParallaxMinSteps",
                "uParallaxMaxSteps", "uParallaxHeightChannel", "uParallaxFlipHeight"}) {
            assertTrue(source.contains("uniform int " + name + ";"),
                    "missing int uniform " + name);
        }
        for (String name : new String[]{"uParallaxHeightScale", "uParallaxFadeStart",
                "uParallaxFadeEnd", "uParallaxFadeStartDistance", "uParallaxMaxDistance"}) {
            assertTrue(source.contains("uniform float " + name + ";"),
                    "missing float uniform " + name);
        }
        assertTrue(source.contains("uniform sampler2D uHeightMap;"));
        assertEquals("const int PARALLAX_MAX_STEPS = "
                        + ParallaxOcclusionSettings.MAXIMUM_STEP_COUNT + ";",
                lineStartingWith(source, "const int PARALLAX_MAX_STEPS "));
        assertEquals("const float PARALLAX_MAX_SLOPE = 8.0;",
                lineStartingWith(source, "const float PARALLAX_MAX_SLOPE "));
        assertEquals("const float PARALLAX_MIN_VIEW_Z = 1.0e-4;",
                lineStartingWith(source, "const float PARALLAX_MIN_VIEW_Z "));
        assertFalse(source.contains("uniform sampler2D uParallax"),
                "parallax must reuse the single uHeightMap role and add no other texture");
    }

    @Test
    void disabledAndZeroScaleKeepTheOriginalUvPath() throws IOException {
        String source = resource(PBR);
        assertEquals(1, occurrences(source,
                        "if (uParallaxEnabled != 0 && uParallaxHeightScale > 0.0)"),
                "exactly one positive guard selects the march");
        assertFalse(source.contains("uParallaxEnabled == 0"),
                "a negative guard would make the disabled path the special case");
        assertEquals(1, occurrences(source, "vec2 materialUv = vTexCoord;"),
                "the disabled path must initialise the UV from vTexCoord exactly once");
        String setup = section(source,
                "    // Parallax occlusion only offsets the following material lookups.",
                "    vec4 vertexColor");
        assertTrue(setup.contains("vec2 materialUv = vTexCoord;"));
        assertTrue(setup.contains("materialUv = parallaxOcclusionUv(geometricNormal, v);"));
    }

    @Test
    void positivePathOnlyChangesTheFollowingMaterialLookups() throws IOException {
        String source = resource(PBR);
        for (String call : new String[]{
                "texture(uBaseColorMap, materialUv)",
                "texture(uMetallicRoughnessMap, materialUv)",
                "texture(uNormalMap, materialUv)",
                "texture(uOcclusionMap, materialUv)",
                "texture(uEmissiveMap, materialUv)"}) {
            assertTrue(source.contains(call), "the base-layer lookup must use the POM UV: " + call);
        }
        assertEquals(1, occurrences(source, "texture(uHeightMap, uv)"),
                "the height map must be sampled only inside parallaxHeight");
        assertTrue(source.contains("float aoSample = texture(uOcclusionMap, materialUv).r;"));
        assertTrue(source.contains(
                "vec3 emissive = texture(uEmissiveMap, materialUv).rgb * uEmissiveFactor;"));
        // The clearcoat normal is an optional independent overlay: its sampling
        // is explicitly documented as staying on the geometric UV.
        assertTrue(source.contains("texture(uClearcoatNormalMap, vTexCoord)"),
                "the coat overlay must not silently change its sample coordinate");
        assertTrue(source.contains("The optional coat normal map keeps sampling"),
                "the coat UV limitation must stay documented in the shader");
    }

    @Test
    void marchIsBoundedAndFailsClosed() throws IOException {
        String source = resource(PBR);
        String block = parallaxBlock(source);
        assertTrue(block.contains("for (int i = 0; i < PARALLAX_MAX_STEPS; ++i)"),
                "the march must use the compile-time cap");
        assertTrue(block.contains("if (i >= layerCount || !(currentDepth < currentHeight)) break;"));
        assertTrue(block.contains(
                        "int minimumSteps = clamp(uParallaxMinSteps, 1, PARALLAX_MAX_STEPS);"),
                "the uniform step count must be clamped to the compile-time cap");
        assertTrue(block.contains(
                "int maximumSteps = clamp(uParallaxMaxSteps, minimumSteps, PARALLAX_MAX_STEPS);"));
        assertTrue(block.contains("float span = max(end - start, 1.0e-4);"),
                "a degenerate fade window must not divide by zero");
        assertTrue(block.contains("if (!(scale > 0.0) || !(scale <= 1.0)) return vTexCoord;"),
                "a NaN/out-of-range scale must fail closed to the original UV");
        assertTrue(block.contains("!(uParallaxFadeEnd > uParallaxFadeStart)"),
                "an inverted angular window must fail closed");
        assertTrue(block.contains("!(uParallaxMaxDistance > uParallaxFadeStartDistance)"),
                "an inverted distance window must fail closed");
        assertTrue(block.contains("if (!(viewT.z > PARALLAX_MIN_VIEW_Z)) return vTexCoord;"),
                "a view at or below the tangent plane must not divide by zero");
        assertTrue(block.contains(
                        "if (slopeLength > PARALLAX_MAX_SLOPE) slope *= PARALLAX_MAX_SLOPE / slopeLength;"),
                "the lateral slope must be capped before the UV march");
        assertTrue(block.contains("int channel = clamp(uParallaxHeightChannel, 0, 3);"));
        assertTrue(block.contains(
                "return clamp(uParallaxFlipHeight != 0 ? 1.0 - height : height, 0.0, 1.0);"));
        assertTrue(block.contains("float previousError = previousDepth - previousHeight;"));
        assertTrue(block.contains("float blend = denominator < -1.0e-6"));
        assertTrue(block.contains("return mix(previousUv, currentUv, blend);"),
                "the hit layer must be refined with one linear interpolation");
    }

    @Test
    void marchNeverWritesDepthShadowVelocityOrColor() throws IOException {
        String source = resource(PBR);
        String block = parallaxBlock(source);
        assertFalse(source.contains("gl_FragDepth"),
                "the approximation must never write fragment depth");
        assertFalse(block.contains("gl_FragDepth"));
        assertFalse(block.contains("gl_FragCoord"));
        assertFalse(block.contains("uShadowMap"));
        assertFalse(block.contains("FragColor"),
                "the march only returns a UV; it must not touch the output colour");
        assertFalse(block.contains("uVelocity") || block.contains("vVelocity"),
                "the march must not consume or write velocity");
        // Shadow lookups are computed from vWorldPosition, not from materialUv.
        assertTrue(source.contains("vec3 samplePosition = vWorldPosition + n * shadowNormalBias();"));
    }

    @Test
    void fullScanReferenceCarriesTheSameParallaxCode() throws IOException {
        String production = resource(PBR);
        String reference = resource(REFERENCE);
        assertEquals(parallaxBlock(production), parallaxBlock(reference),
                "the benchmark-only reference must not drift from the parallax shader");
        assertEquals(section(production, "void main() {", "    vec3 f0 = mix"),
                section(reference, "void main() {", "    vec3 f0 = mix"),
                "the UV selection and material sampling setup must match in both shaders");
        assertTrue(reference.contains("uniform sampler2D uHeightMap;"));
        assertTrue(reference.contains("const int PARALLAX_MAX_STEPS = 128;"));
    }

    private static String parallaxBlock(String source) {
        return section(source, "float parallaxFade(float value, float start, float end)",
                "vec2 receiverPlaneGradient(vec3 projected)");
    }

    private static String lineStartingWith(String source, String prefix) {
        for (String line : source.split("\n")) {
            if (line.startsWith(prefix)) return line.strip();
        }
        throw new AssertionError("missing shader line starting with " + prefix);
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
        try (InputStream input = ParallaxShaderTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("missing shader " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
