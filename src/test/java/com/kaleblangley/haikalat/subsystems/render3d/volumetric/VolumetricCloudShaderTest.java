package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM packaging and formula contract for the volumetric-cloud shader stages.
 *
 * <p>Compiling a program needs a real GL context; this test proves the four
 * resources are on the runtime classpath, pins their accepted SHA-256 (also in
 * {@code docs/licenses/assets.tsv}) and locks the contracts a reviewer can
 * check without a GPU: the shared #10 cloud-shadow mask functions are the same
 * source text in {@code pbr-forward.frag} and {@code cloud-march.frag}, the
 * bounded step/light-march and energy formulas are present, the reconstruction
 * writes two targets with clamp-before-blend and the composite is exactly
 * {@code S + T * scene}.</p>
 */
class VolumetricCloudShaderTest {
    /** SHA-256 of the accepted {@code cloud.vert}, matching assets.tsv. */
    private static final String VERTEX_SHA256 =
            "33f1e751b5b5556feae1644344965c9be832646370573b5f5eadb4ce4a805363";
    /** SHA-256 of the accepted {@code cloud-march.frag}, matching assets.tsv. */
    private static final String MARCH_SHA256 =
            "863addf706e5a2e51f9d14cbb3f13638d305445a21fdefabaa64a17701aee70c";
    /** SHA-256 of the accepted {@code cloud-reconstruct.frag}, matching assets.tsv. */
    private static final String RECONSTRUCT_SHA256 =
            "4393a495e7208923f50ba9ad8628ee58fba536d8e797841d9c4d7b011cb5c85d";
    /** SHA-256 of the accepted {@code cloud-composite.frag}, matching assets.tsv. */
    private static final String COMPOSITE_SHA256 =
            "1a254418bf2ebf95447fa341fc6520610d4afc9dcb734d894d11606d81fd81b3";
    private static final String PBR_FORWARD = "/shaders/render3d/pbr/pbr-forward.frag";

    @Test
    void stagesArePackagedAndPinned() {
        String vertex = VolumetricCloudPass.vertexSource();
        String march = VolumetricCloudPass.marchSource();
        String reconstruct = VolumetricCloudPass.reconstructSource();
        String composite = VolumetricCloudPass.compositeSource();
        for (String source : List.of(vertex, march, reconstruct, composite)) {
            assertTrue(source.startsWith("#version 460 core"), "every stage targets GL 4.6");
            assertBalanced(source);
        }
        assertEquals(VERTEX_SHA256, sha256(vertex));
        assertEquals(MARCH_SHA256, sha256(march));
        assertEquals(RECONSTRUCT_SHA256, sha256(reconstruct));
        assertEquals(COMPOSITE_SHA256, sha256(composite));
        assertTrue(vertex.contains("layout (location = 0) in vec2 aPosition;"));
        assertTrue(vertex.contains("gl_Position = vec4(aPosition, 0.0, 1.0);"));
    }

    @Test
    void sharedCloudShadowMaskTextIsIdenticalToThePbrShader() {
        String pbr = VolumetricCloudPass.resource(PBR_FORWARD);
        String march = VolumetricCloudPass.marchSource();
        for (String function : List.of("cloudShadowHash", "cloudShadowNoise",
                "cloudShadowDensity")) {
            assertEquals(normalize(function(pbr, function)), normalize(function(march, function)),
                    "the #10 mask function " + function + " must be the same source text");
        }
        for (String constant : List.of("#define CLOUD_SHADOW_OCTAVES 3",
                "vec2(0.8, 0.6) * (uCloudShadowTime * max(uCloudShadowSpeed, 0.0))",
                "float threshold = mix(0.78, 0.22, coverage);",
                "float edge = mix(0.004, 0.28, clamp(uCloudShadowSoftness, 0.0, 1.0));")) {
            assertTrue(pbr.contains(constant), "pbr-forward.frag lost shared mask text " + constant);
            assertTrue(march.contains(constant),
                    "cloud-march.frag must reuse the #10 mask text " + constant);
        }
        assertTrue(march.contains("float cloudLayerMask(vec2 worldXZ)"),
                "the layer has exactly one authored coverage field");
        assertTrue(march.contains("cloudShadowDensity(projected * scale)"),
                "the layer evaluates the shared octave density");
    }

    @Test
    void marchDeclaresEveryUniformThePassUploadsAndStaysBounded() {
        String source = VolumetricCloudPass.marchSource();
        for (String uniform : List.of("uniform sampler2D uSceneDepth", "uniform vec2 uFullExtent",
                "uniform vec2 uLowExtent", "uniform mat4 uInverseProjection",
                "uniform mat4 uInverseView", "uniform float uLayerBase",
                "uniform float uLayerTop", "uniform vec3 uLightDirection",
                "uniform vec3 uLightColor", "uniform float uDensity",
                "uniform float uAbsorption", "uniform float uScattering",
                "uniform float uAnisotropy", "uniform float uStepSizeMetres",
                "uniform int uMaximumSteps", "uniform int uLightSteps",
                "uniform float uMaximumLightDistance", "uniform float uCloudShadowCoverage",
                "uniform float uCloudShadowSoftness", "uniform float uCloudShadowScale",
                "uniform float uCloudShadowSpeed", "uniform float uCloudShadowTime")) {
            assertTrue(source.contains(uniform + ";"), "missing march uniform " + uniform);
        }
        assertEquals(1, occurrences(source, "texelFetch(uSceneDepth"),
                "exactly one opaque-depth fetch per low-resolution fragment");
        assertTrue(source.contains(
                "float stepLength = max(span / float(uMaximumSteps), uStepSizeMetres);"));
        assertTrue(source.contains(
                "int steps = int(clamp(ceil(span / stepLength), 1.0, float(uMaximumSteps)));"),
                "the view march must clamp the step budget");
        assertTrue(source.contains("for (int step = 0; step < uMaximumSteps; step++) {"));
        assertTrue(source.contains("if (step >= steps) {"), "the budget must break the loop");
        assertTrue(source.contains("for (int step = 0; step < uLightSteps; step++) {"),
                "the light march must be bounded by uLightSteps");
        assertTrue(source.contains("float span = (uLayerTop - samplePosition.y) / toLight.y;"));
        assertTrue(source.contains("span = clamp(span, 0.0, uMaximumLightDistance);"));
        assertTrue(source.contains(
                "float slabTransmittance = exp(-min(sigma * delta, CLOUD_MAX_OPTICAL_DEPTH));"));
        assertTrue(source.contains("(1.0 - slabTransmittance)"),
                "single scatter must be energy-consistent with the slab transmittance");
        assertTrue(source.contains("scatter += transmittance * uLightColor * weight;"),
                "front-to-back accumulation");
        assertTrue(source.contains("outCloud = vec4(max(scatter, vec3(0.0)), clamp(transmittance"),
                "premultiplied scatter and transmittance are the only outputs");
        assertEquals(1, occurrences(source, "layout (location = 0) out vec4 outCloud;"));
        assertFalse(source.contains("layout (location = 1)"),
                "the march stage writes one target");
        assertFalse(source.contains("uSceneColor") || source.contains("uColorSource"),
                "the march must not sample the scene colour it is composited over");
        assertFalse(source.contains("uVolumePrefix") || source.contains("uVolumeSource"),
                "the cloud pass must not integrate the volumetric-fog medium");
        assertFalse(source.contains("sampler2DMS"),
                "the pass consumes resolved single-sample inputs");
    }

    @Test
    void reconstructionWritesTwoTargetsAndRejectsBeforeClamping() {
        String source = VolumetricCloudPass.reconstructSource();
        assertTrue(source.contains("layout (location = 0) out vec4 outCloud;"));
        assertTrue(source.contains("layout (location = 1) out vec4 outState;"));
        for (String uniform : List.of("uniform sampler2D uCloud",
                "uniform sampler2D uSceneDepth", "uniform sampler2D uHistoryCloud",
                "uniform sampler2D uHistoryState", "uniform mat4 uView",
                "uniform mat4 uInverseView", "uniform mat4 uInverseProjection",
                "uniform mat4 uPreviousView", "uniform mat4 uPreviousViewProjection",
                "uniform vec2 uFullExtent", "uniform vec2 uLowExtent",
                "uniform float uLayerBase", "uniform float uLayerTop",
                "uniform float uDepthToleranceMetres", "uniform float uHistoryBlend",
                "uniform float uExposure", "uniform int uHistoryValid")) {
            assertTrue(source.contains(uniform + ";"), "missing reconstruction uniform " + uniform);
        }
        assertTrue(source.contains(
                "float weight = bilinear * cloudDepthSimilarity(centerDepth, tapDepth);"),
                "spatial taps are depth-weighted, not a plain bilinear blur");
        assertTrue(source.contains("if (uHistoryValid != 0) {"),
                "a camera cut or exposure change must bypass the history entirely");
        assertTrue(source.contains("state.a >= 0.5 && abs(state.r - expectedDepth) <= tolerance"),
                "disocclusion rejection compares the expected previous anchor depth");
        assertTrue(source.contains("previousUv = clip.xy / clip.w * 0.5 + 0.5;"));
        assertTrue(source.contains("vec4 minimumCloud = vec4(1.0e30);"));
        assertTrue(source.contains("vec4 history = clamp(texture(uHistoryCloud, previousUv),"),
                "history is clamped to the current low-resolution range");
        assertTrue(source.contains("resolved = mix(spatial, history, historyWeight);"),
                "clamp happens before the blend");
        assertTrue(source.contains("outState = vec4(anchorDepth, uExposure, appliedHistoryWeight, 1.0);"));
        assertEquals(1, occurrences(source, "layout (location = 0) out vec4 outCloud;"));
        assertFalse(source.contains("imageStore"));
        assertFalse(source.contains("uColorSource"),
                "reconstruction does not composite; the composite stage does");
    }

    @Test
    void compositeIsExactlyScatterPlusTransmittanceTimesTheScene() {
        String source = VolumetricCloudPass.compositeSource();
        assertTrue(source.contains("uniform sampler2D uCloud;"));
        assertTrue(source.contains("uniform sampler2D uColorSource;"));
        assertTrue(source.contains("vec4 cloud = texelFetch(uCloud, pixel, 0);"));
        assertTrue(source.contains("vec4 source = texelFetch(uColorSource, pixel, 0);"));
        assertTrue(source.contains(
                "outColor = vec4(max(cloud.rgb, vec3(0.0)) + clamp(cloud.a, 0.0, 1.0) * source.rgb,"),
                "the documented S + T * scene composite");
        assertFalse(source.contains("uCloudShadow"),
                "the composite does not re-integrate the cloud medium");
    }

    private static void assertBalanced(String source) {
        int depth = 0;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                assertTrue(depth >= 0, "unbalanced closing brace in shader");
            }
        }
        assertEquals(0, depth, "unbalanced braces in shader");
    }

    private static String function(String source, String name) {
        int start = source.indexOf("float " + name + "(");
        assertTrue(start >= 0, "missing shader function " + name);
        int open = source.indexOf('{', start);
        assertTrue(open > start, "missing function body for " + name);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, index + 1);
                }
            }
        }
        throw new AssertionError("unterminated function body for " + name);
    }

    private static String normalize(String source) {
        return source.replaceAll("\\s+", " ").strip();
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int index = source.indexOf(needle);
        while (index >= 0) {
            count++;
            index = source.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String sha256(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java platform", impossible);
        }
    }
}
