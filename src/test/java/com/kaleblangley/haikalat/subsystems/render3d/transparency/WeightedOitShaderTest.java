package com.kaleblangley.haikalat.subsystems.render3d.transparency;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level contract for the weighted-OIT shaders and the CPU-side blend
 * state.
 *
 * <p>The real pixel behaviour lives in {@code WeightedOitPassGlTest}; this JVM
 * case pins the attachment order, the uniform names consumed by
 * {@link WeightedOitPass}, the revealage/weight write contract that the fixed
 * function blend depends on, and the blend factors. A rename or a swapped
 * attachment would otherwise only fail on a real GPU run.</p>
 */
class WeightedOitShaderTest {
    private static final List<String> SHADERS = List.of(
            "/shaders/render3d/transparency/weighted-oit-surface.vert",
            "/shaders/render3d/transparency/weighted-oit-surface.frag",
            "/shaders/render3d/transparency/weighted-oit-resolve.frag");

    @Test
    void everyShaderHasExactlyOneLeadingVersionDirective() {
        for (String path : SHADERS) {
            String source = resource(path);
            assertTrue(source.startsWith("#version 460 core\n"),
                    "shader must own a single leading version line: " + path);
            assertEquals(1, occurrences(source, "#version"),
                    "shader must declare the version once: " + path);
            assertTrue(source.contains("void main()"), "shader must keep its main: " + path);
        }
    }

    @Test
    void surfaceVertexWritesTheViewDepthUsedByTheWeight() {
        String source = resource(SHADERS.get(0));
        assertTrue(source.contains("layout(location = 0) in vec2 aCorner"),
                "corner comes from the shared unit quad");
        assertTrue(source.contains("in vec3 aCenter"), "instance centre attribute");
        assertTrue(source.contains("in vec3 aHalfRight"), "instance right half-edge attribute");
        assertTrue(source.contains("in vec3 aHalfUp"), "instance up half-edge attribute");
        assertTrue(source.contains("in vec4 aColor"), "instance colour/alpha attribute");
        assertTrue(source.contains("uniform mat4 uView"));
        assertTrue(source.contains("uniform mat4 uProjection"));
        assertTrue(source.contains("vViewDepth = -viewPosition.z"),
                "view depth must be derived from the transformed centre, not gl_FragCoord.z");
        assertTrue(source.contains("gl_Position = uProjection * viewPosition"));
        assertFalse(source.contains("sampler"), "surface stage must not sample any depth texture");
    }

    @Test
    void surfaceFragmentWritesTheDocumentedAccumulationAndRevealage() {
        String source = resource(SHADERS.get(1));
        assertTrue(source.contains("layout(location = 0) out vec4 oAccumulation"),
                "attachment 0 must be the RGBA accumulation");
        assertTrue(source.contains("layout(location = 1) out float oWeightSum"),
                "attachment 1 must be the R16F weight sum");
        for (String uniform : List.of("uDepthWeight", "uDepthScale", "uReferenceDepth",
                "uMinimumWeight", "uMaximumWeight", "uAlphaThreshold")) {
            assertTrue(source.contains("uniform") && source.contains(uniform),
                    "fragment shader must declare " + uniform);
        }
        assertTrue(source.contains("if (uDepthWeight == 0)"), "depth weighting needs its off branch");
        assertTrue(source.contains("return 1.0;"),
                "depth weighting disabled must be exactly one");
        assertTrue(source.contains("1.0 + normalized * normalized"),
                "weight must be the documented rational falloff");
        assertTrue(source.contains("clamp(weight, uMinimumWeight, uMaximumWeight)"),
                "weight must be bounded");
        assertTrue(source.contains("if (!(alpha > uAlphaThreshold))"), "alpha threshold branch");
        assertTrue(source.contains("discard"), "alpha threshold must drop the fragment");
        assertTrue(source.contains("OIT_MAX_LAYER_CONTRIBUTION"),
                "surface stage must cap one layer's contribution");
        assertTrue(source.contains("OIT_MAX_LAYER_WEIGHT"),
                "surface stage must cap one layer's weight");
        assertTrue(source.contains("const float OIT_MAX_LAYER_CONTRIBUTION = 3.0e4;"),
                "the documented per-layer contribution cap must stay 3.0e4");
        assertTrue(source.contains("const float OIT_MAX_LAYER_WEIGHT = 3.0e4;"),
                "the documented per-layer weight cap must stay 3.0e4");
        assertTrue(source.contains("isnan(weight)") && source.contains("isinf(weight)"),
                "non-finite view depth/weight must fall back to a finite weight");
        assertTrue(source.contains("min(contribution, vec3(OIT_MAX_LAYER_CONTRIBUTION))"),
                "contribution cap must be a hard per-channel clamp");
        assertTrue(source.contains("vec4(contribution, clamp(alpha, 0.0, 1.0))"),
                "attachment 0 must sum the capped weighted colour and store alpha in its a channel");
        assertTrue(source.contains("oWeightSum = min(layerWeight, OIT_MAX_LAYER_WEIGHT)"),
                "attachment 1 must sum the capped alpha * weight");
        assertFalse(source.contains("uSceneDepth"),
                "the pass must not sample the shared scene depth");
    }

    @Test
    void resolveNormalisesAndWritesCoverage() {
        String source = resource(SHADERS.get(2));
        assertTrue(source.contains("uniform sampler2D uAccumulation"));
        assertTrue(source.contains("uniform sampler2D uWeight"));
        assertTrue(source.contains("uniform float uWeightEpsilon"));
        assertTrue(source.contains("texture(uAccumulation, vUv)"));
        assertTrue(source.contains("texture(uWeight, vUv).r"));
        assertTrue(source.contains("float coverage = 1.0 - revealage;"),
                "coverage is one minus the accumulated transmittance");
        assertTrue(source.contains("accumulation.rgb / weightSum"),
                "resolved colour is the weight-normalised average");
        assertTrue(source.contains(
                        "vec4(min(average, vec3(OIT_MAX_RESOLVED_COLOR)), coverage)"),
                "the target is composited with bounded colour and coverage as alpha");
        assertTrue(source.contains("OIT_MAX_ACCUMULATION")
                        && source.contains("OIT_MAX_WEIGHT_SUM"),
                "resolve must bound the accumulated attachments");
        assertTrue(source.contains("const float OIT_MAX_ACCUMULATION = 6.5e4;")
                        && source.contains("const float OIT_MAX_WEIGHT_SUM = 6.5e4;"),
                "the documented accumulation caps must stay 6.5e4");
        assertTrue(source.contains("const float OIT_MAX_RESOLVED_COLOR = 6.5504e4;"),
                "the resolved colour must stay below the half-float maximum");
        assertTrue(source.contains("OIT_MAX_RESOLVED_COLOR"),
                "resolved colour must be clamped below the half-float maximum");
        assertTrue(source.contains("isnan(accumulation)") && source.contains("isinf(accumulation)"),
                "non-finite accumulation must be detected before division");
        assertTrue(source.contains("isnan(weightSum)") && source.contains("isinf(weightSum)"),
                "non-finite weight sum must be detected before division");
        assertTrue(source.contains("min(average, vec3(OIT_MAX_RESOLVED_COLOR))"),
                "the finite fallback must stay bounded");
        assertTrue(source.contains("fragColor = vec4(0.0, 0.0, 0.0, 0.0);"),
                "invalid accumulation must fall back to an unchanged target");
    }

    @Test
    void passDeclaresTheWeightedBlendContract() {
        assertEquals(GL11.GL_ONE, WeightedOitPass.ACCUMULATION_BLEND_SOURCE_RGB);
        assertEquals(GL11.GL_ONE, WeightedOitPass.ACCUMULATION_BLEND_DESTINATION_RGB);
        assertEquals(GL11.GL_ZERO, WeightedOitPass.REVEALAGE_BLEND_SOURCE_ALPHA);
        assertEquals(GL11.GL_ONE_MINUS_SRC_ALPHA,
                WeightedOitPass.REVEALAGE_BLEND_DESTINATION_ALPHA);
        assertEquals(GL11.GL_SRC_ALPHA, WeightedOitPass.RESOLVE_BLEND_SOURCE);
        assertEquals(GL11.GL_ONE_MINUS_SRC_ALPHA, WeightedOitPass.RESOLVE_BLEND_DESTINATION);
        assertEquals(0, WeightedOitPass.ACCUMULATION_TEXTURE_UNIT);
        assertEquals(1, WeightedOitPass.WEIGHT_TEXTURE_UNIT);
    }

    private static String resource(String path) {
        try (InputStream stream = WeightedOitPass.class.getResourceAsStream(path)) {
            if (stream == null) throw new AssertionError("missing shader resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("cannot read shader resource " + path, failure);
        }
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
