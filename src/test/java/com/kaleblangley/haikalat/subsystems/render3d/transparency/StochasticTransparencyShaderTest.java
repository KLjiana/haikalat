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
 * Source-level contract for the stochastic-transparency shaders and the
 * CPU-side pipeline state.
 *
 * <p>The real pixel behaviour lives in {@code StochasticTransparencyPassGlTest};
 * this JVM case pins the attachment order, the uniform names consumed by
 * {@link StochasticTransparencyPass}, the acceptance formula, the additive
 * hit-count encoding, the reprojection/rejection terms and the premultiplied
 * composite blend.  A rename or swapped attachment would otherwise only fail on
 * a real GPU run.</p>
 */
class StochasticTransparencyShaderTest {
    private static final String SURFACE_VERTEX =
            "/shaders/render3d/transparency/stochastic-transparency-surface.vert";
    private static final String SURFACE_FRAGMENT =
            "/shaders/render3d/transparency/stochastic-transparency-surface.frag";
    private static final String ACCUMULATE_FRAGMENT =
            "/shaders/render3d/transparency/stochastic-transparency-accumulate.frag";
    private static final String RESOLVE_FRAGMENT =
            "/shaders/render3d/transparency/stochastic-transparency-resolve.frag";
    private static final String COMPOSITE_FRAGMENT =
            "/shaders/render3d/transparency/stochastic-transparency-composite.frag";
    private static final List<String> OWNED_SHADERS = List.of(
            SURFACE_VERTEX, SURFACE_FRAGMENT, ACCUMULATE_FRAGMENT, RESOLVE_FRAGMENT,
            COMPOSITE_FRAGMENT);

    @Test
    void everyOwnedShaderHasExactlyOneLeadingVersionDirectiveAndMain() {
        for (String path : OWNED_SHADERS) {
            String source = resource(path);
            assertTrue(source.startsWith("#version 460 core\n"),
                    "shader must own a single leading version line: " + path);
            assertEquals(1, occurrences(source, "#version"),
                    "shader must declare the version once: " + path);
            assertTrue(source.contains("void main()"), "shader must keep its main: " + path);
        }
    }

    @Test
    void surfaceVertexUsesTheSharedQuadAndForwardsTheInstanceIdentity() {
        String source = resource(SURFACE_VERTEX);
        assertTrue(source.contains("layout(location = 0) in vec2 aCorner"),
                "corner comes from the shared unit quad");
        assertTrue(source.contains("in vec3 aCenter"), "instance centre attribute");
        assertTrue(source.contains("in vec3 aHalfRight"), "instance right half-edge attribute");
        assertTrue(source.contains("in vec3 aHalfUp"), "instance up half-edge attribute");
        assertTrue(source.contains("in vec4 aColor"), "instance colour/alpha attribute");
        assertTrue(source.contains("uniform mat4 uView"));
        assertTrue(source.contains("uniform mat4 uProjection"));
        assertTrue(source.contains("vInstanceId = uint(gl_InstanceID)"),
                "the per-layer hash identity must come from the instance id");
        assertTrue(source.contains("flat out uint vInstanceId"),
                "the forward must be flat so all fragments of one surface share the id");
        assertFalse(source.contains("sampler"),
                "the surface stage must not sample a depth or history texture");
    }

    @Test
    void surfaceFragmentImplementsTheDocumentedAcceptanceAndHitEncoding() {
        String source = resource(SURFACE_FRAGMENT);
        assertTrue(source.contains("layout(location = 0) out vec4 oSampleColor"));
        for (String uniform : List.of("uAlphaThreshold", "uCoverageGamma", "uJitterMode",
                "uSeed", "uFrameIndex", "uSampleIndex")) {
            assertTrue(source.contains("uniform") && source.contains(uniform),
                    "fragment shader must declare " + uniform);
        }
        assertTrue(source.contains("if (!(alpha > uAlphaThreshold))"),
                "alpha threshold branch");
        assertTrue(source.contains("pow(alpha, uCoverageGamma)"),
                "coverage mapping must be the documented power");
        assertTrue(source.contains("stThreshold() < probability"),
                "acceptance must be the strict threshold comparison");
        assertTrue(source.contains("if (uJitterMode == 0)"),
                "hash mode branch");
        assertTrue(source.contains("if (uJitterMode == 1)"),
                "interleaved-gradient mode branch");
        assertTrue(source.contains("stBayer4(pixel)"),
                "ordered dither mode branch");
        assertTrue(source.contains("0x7feb352dU") && source.contains("0x846ca68bU"),
                "the white-noise hash must keep its documented avalanche constants");
        assertTrue(source.contains("vInstanceId * 0x9E3779B9U"),
                "overlapping layers must decorrelate their acceptance");
        assertTrue(source.contains("float(stHash32(key) >> 8) * (1.0 / 16777216.0)"),
                "the hash must map to [0, 1)");
        assertTrue(source.contains("oSampleColor = vec4(radiance, 1.0);"),
                "an accepted sample must write full opacity plus the hit flag");
        assertTrue(source.contains("any(isnan(radiance))") && source.contains("any(isinf(radiance))"),
                "non-finite radiance must be rejected or zeroed");
        assertTrue(source.contains("if (isnan(alpha) || isinf(alpha))"),
                "non-finite alpha must not be accepted");
        assertFalse(source.contains("uSceneDepth"),
                "the sample stage must not sample the shared scene depth");
        assertFalse(source.contains("sampler"),
                "the sample stage must not sample any texture");
    }

    @Test
    void resolveNormalisesSamplesAndAppliesTheDocumentedHistoryContract() {
        String source = resource(RESOLVE_FRAGMENT);
        assertTrue(source.contains("layout(location = 0) out vec4 oResolvedColor"));
        assertTrue(source.contains("layout(location = 1) out vec4 oState"));
        for (String uniform : List.of("uFrameSummation", "uSceneDepth", "uHistoryColor",
                "uHistoryState", "uExtent", "uSamplesPerFrame", "uHistoryValid",
                "uHistoryWeight", "uInverseProjection", "uInverseView",
                "uPreviousViewProjection", "uNeighborhoodRadius", "uReactiveThreshold",
                "uDepthRejectionThreshold", "uVelocityRejectionPixels")) {
            assertTrue(source.contains(uniform), "resolve shader must declare " + uniform);
        }
        assertTrue(source.contains("vec4 stCurrentEstimate(ivec2 pixel)"),
                "the per-frame estimate must be normalised in one documented helper");
        assertTrue(source.contains("sum.rgb / samples"), "colour is the sample average");
        assertTrue(source.contains("sum.a / samples"), "coverage is the accepted fraction");
        assertTrue(source.contains("uSamplesPerFrame"), "the divide must use the sample count");

        assertTrue(source.contains("uPreviousViewProjection * vec4(worldPosition, 1.0)"),
                "history must be reprojected with the previous stable view-projection");
        assertTrue(source.contains("length((previousUv - uv) * uExtent) <= uVelocityRejectionPixels"),
                "the screen-space motion budget must reject far history");
        assertTrue(source.contains("abs(state.b - currentViewDepth) > tolerance"),
                "history must be rejected outside the depth window");
        assertTrue(source.contains("abs(currentCoverage - historyCoverage) > uReactiveThreshold"),
                "the coverage shock must drive reactive rejection");

        assertTrue(source.contains("clampedCoverage = clamp(historyCoverage, minimumCoverage, maximumCoverage)"),
                "coverage must be clamped to the current neighbourhood AABB");
        assertTrue(source.contains("coverageMean - coverageSigma"),
                "coverage must also be clamped to the neighbourhood moments");
        assertTrue(source.contains("clampedAverage = clamp(clampedAverage, low, high)"),
                "the history colour must be clamped to the current colour AABB");
        assertTrue(source.contains("outCoverage = mix(currentCoverage, clampedCoverage, weight)"),
                "the accepted history must use the bounded running-average blend");
        assertTrue(source.contains("outPremultiplied = mix(current.rgb, clampedPremultiplied, weight)"),
                "the premultiplied colour must use the same blend");
        assertTrue(source.contains("oState = vec4(outCoverage, outSquaredCoverage,"),
                "the state attachment must expose coverage, its moment and the depth");
        assertTrue(source.contains("ST_MAX_HISTORY_WEIGHT"),
                "the history weight must be bounded below one");
        assertTrue(source.contains("isnan(outCoverage)") && source.contains("any(isinf(outPremultiplied))"),
                "the resolved output must reject non-finite values");
        assertTrue(source.contains("ST_MAX_NEIGHBORHOOD_RADIUS"),
                "the neighbourhood loop must stay compile-time bounded");
        assertTrue(source.contains("for (int y = -ST_MAX_NEIGHBORHOOD_RADIUS"),
                "the neighbourhood loop must be the bounded constant loop");
        assertFalse(source.contains("gl_FragDepth"),
                "the pass must not write scene depth");
    }

    @Test
    void compositeIsPremultipliedAndEmptyCoverageIsANoOp() {
        String source = resource(COMPOSITE_FRAGMENT);
        assertTrue(source.contains("uniform sampler2D uResolvedColor"));
        assertTrue(source.contains("texture(uResolvedColor, vUv)"));
        assertTrue(source.contains("if (!(coverage > 0.0))"),
                "zero coverage must produce an unchanged target");
        assertTrue(source.contains("fragColor = vec4(premultiplied, coverage)"),
                "the premultiplied colour must be paired with coverage as source alpha");
        assertTrue(source.contains("any(isnan(resolved))") && source.contains("any(isinf(resolved))"),
                "non-finite resolved values must be a no-op");
        assertTrue(source.contains("clamp(resolved.rgb, vec3(0.0), vec3(ST_MAX_COLOR))"),
                "the composite source must stay finite and non-negative");
    }

    @Test
    void accumulateAddsOneDepthTestedSampleToTheFrameSummation() {
        String source = resource(ACCUMULATE_FRAGMENT);
        assertTrue(source.contains("uniform sampler2D uSampleColor"));
        assertTrue(source.contains("texture(uSampleColor, vUv)"));
        assertTrue(source.contains("clamp(sampleColour.a, 0.0, 1.0)"),
                "the hit flag must be bounded");
        assertTrue(source.contains(
                        "fragColor = vec4(clamp(sampleColour.rgb, vec3(0.0), vec3(ST_MAX_COLOR)), hit);"),
                "the accumulated sample must pair the bounded colour with the hit count");
        assertTrue(source.contains("any(isnan(sampleColour))")
                        && source.contains("any(isinf(sampleColour))"),
                "non-finite samples must be a no-op");
    }

    @Test
    void passDeclaresTheAdditiveAndPremultipliedBlendContract() {
        assertEquals(GL11.GL_ONE, StochasticTransparencyPass.ACCUMULATION_BLEND_SOURCE);
        assertEquals(GL11.GL_ONE, StochasticTransparencyPass.ACCUMULATION_BLEND_DESTINATION);
        assertEquals(GL11.GL_ONE, StochasticTransparencyPass.COMPOSITE_BLEND_SOURCE);
        assertEquals(GL11.GL_ONE_MINUS_SRC_ALPHA,
                StochasticTransparencyPass.COMPOSITE_BLEND_DESTINATION);
        assertEquals(GL11.GL_LESS, StochasticTransparencyPass.SAMPLE_DEPTH_FUNCTION);
        assertEquals(0, StochasticTransparencyPass.FRAME_SUMMATION_TEXTURE_UNIT);
        assertEquals(1, StochasticTransparencyPass.SCENE_DEPTH_TEXTURE_UNIT);
        assertEquals(2, StochasticTransparencyPass.HISTORY_COLOR_TEXTURE_UNIT);
        assertEquals(3, StochasticTransparencyPass.HISTORY_STATE_TEXTURE_UNIT);
        assertEquals(0, StochasticTransparencyPass.RESOLVED_COLOR_TEXTURE_UNIT);
        assertEquals(0, StochasticTransparencyPass.SAMPLE_COLOR_TEXTURE_UNIT);
    }

    private static String resource(String path) {
        try (InputStream stream = StochasticTransparencyPass.class.getResourceAsStream(path)) {
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
