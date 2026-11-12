package com.kaleblangley.haikalat.subsystems.postprocess.ssgi;

import com.kaleblangley.haikalat.subsystems.postprocess.SsgiSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the shader-side contracts that carry #39: the bounded hemisphere march,
 * the depth/normal/roughness gates, the clamped offscreen ambient fallback, the
 * one-bounce compositing weight and the shared albedo producer.
 *
 * <p>Pixel correctness needs a GL context and is covered by the real-GL test;
 * this class pins the numeric table, the uniform/reference agreement and the
 * fail-closed disabled path before the GPU queue.</p>
 */
class SsgiShaderTest {
    private static final String SSGI = "/shaders/postprocess/ssgi.frag";
    private static final String SURFACE = "/shaders/render3d/surface/scene-surface.frag";
    private static final String RESOLVE = "/shaders/postprocess/scene-surface-resolve.frag";

    @Test
    void fixedBudgetsAndConstantsMatchTheSettingsContract() throws IOException {
        String source = resource(SSGI);
        assertTrue(source.contains("#version 460 core"));
        assertTrue(source.contains("#define SSGI_MAX_SAMPLES "
                + SsgiSettings.MAXIMUM_SAMPLES));
        assertTrue(source.contains("#define SSGI_MAX_STEPS " + SsgiSettings.MAXIMUM_STEPS));
        assertTrue(source.contains("#define SSGI_GOLDEN_RATIO 0.61803398875"));
        assertTrue(source.contains("#define SSGI_DEPTH_BIAS 0.02"));
        assertTrue(source.contains("#define SSGI_MIN_HIT_DISTANCE_METRES 0.05"));
        assertTrue(source.contains("#define SSGI_BACKFACE_EPSILON 0.05"));
        assertTrue(source.contains("#define SSGI_PI 3.14159265359"));
        assertEquals(SsgiIndirectReference.GOLDEN_RATIO, 0.61803398875);
        assertEquals(SsgiIndirectReference.DEPTH_BIAS, 0.02);
        assertEquals(SsgiIndirectReference.MINIMUM_HIT_DISTANCE, 0.05);
        assertEquals(SsgiIndirectReference.BACKFACE_EPSILON, 0.05);
    }

    @Test
    void everyDeclaredSsgIUniformIsReferencedByTheShader() throws IOException {
        String source = resource(SSGI);
        for (String uniform : new String[]{
                "uniform sampler2D uColor;",
                "uniform sampler2D uDepth;",
                "uniform sampler2D uNormal;",
                "uniform sampler2D uRoughness;",
                "uniform sampler2D uAlbedo;",
                "uniform samplerCube uIrradiance;",
                "uniform vec2 uViewportSize;",
                "uniform mat4 uInverseProjection;",
                "uniform mat4 uProjection;",
                "uniform mat4 uView;",
                "uniform int uSampleCount;",
                "uniform int uStepCount;",
                "uniform float uMaxDistance;",
                "uniform float uThickness;",
                "uniform float uStrength;",
                "uniform float uMinimumSampleRoughness;",
                "uniform float uMaximumOffscreenFraction;",
                "uniform float uEdgeFadePixels;",
                "uniform vec3 uAmbientFallback;",
                "uniform int uEnvironmentAvailable;",
                "uniform float uEnvironmentIntensity;",
                "uniform float uEnvironmentRotation;"}) {
            assertTrue(source.contains(uniform), "missing SSGI uniform " + uniform);
        }
        Matcher declaration = Pattern.compile("uniform\\s+\\w+\\s+(u\\w+)").matcher(source);
        Set<String> declared = new LinkedHashSet<>();
        while (declaration.find()) {
            String name = declaration.group(1);
            declared.add(name);
            assertTrue(countIdentifier(source, name) >= 2,
                    "uniform " + name + " is declared but never referenced and may be "
                            + "optimized out");
        }
        assertEquals(Set.of("uColor", "uDepth", "uNormal", "uRoughness", "uAlbedo",
                        "uIrradiance", "uViewportSize", "uInverseProjection", "uProjection",
                        "uView", "uSampleCount", "uStepCount",
                        "uMaxDistance", "uThickness", "uStrength", "uMinimumSampleRoughness",
                        "uMaximumOffscreenFraction", "uEdgeFadePixels", "uAmbientFallback",
                        "uEnvironmentAvailable", "uEnvironmentIntensity",
                        "uEnvironmentRotation"),
                declared,
                "the shader uniform set must match the pass upload set");
    }

    @Test
    void theMarchIsHardBoundedByBothBudgets() throws IOException {
        String source = resource(SSGI);
        assertTrue(source.contains(
                "for (int sampleIndex = 0; sampleIndex < SSGI_MAX_SAMPLES; sampleIndex++) {"));
        assertTrue(source.contains("if (sampleIndex >= sampleCount) break;"));
        assertTrue(source.contains(
                "for (int stepIndex = 0; stepIndex < SSGI_MAX_STEPS; stepIndex++) {"));
        assertTrue(source.contains("if (stepIndex >= stepCount) break;"));
        assertFalse(source.contains("while ("),
                "the march must not contain an unbounded loop");
        assertTrue(source.contains("int sampleCount = clamp(uSampleCount, 1, SSGI_MAX_SAMPLES);"));
        assertTrue(source.contains("int stepCount = clamp(uStepCount, 1, SSGI_MAX_STEPS);"));
        assertTrue(source.contains("float maximumDistance = max(uMaxDistance, 1.0e-4);"));
        assertTrue(source.contains("float thickness = clamp(uThickness, 1.0e-4, maximumDistance);"));
        assertTrue(source.contains("float rotation = interleavedGradientNoise(gl_FragCoord.xy);"),
                "the fixed sequence must be rotated per pixel without history");
    }

    /**
     * GLSL 4.60 reserves {@code sample} as an interpolation qualifier keyword and
     * several other names as qualifiers/functions. A local variable with one of
     * those names fails real shader compilation even though the JVM source test
     * cannot see it (the GPU round found {@code int sample = 0} in #39 round 1).
     * This guard rejects a declaration-shaped use of the reserved subset.
     */
    @Test
    void reservedGlslKeywordsAreNotUsedAsVariableNames() throws IOException {
        String source = stripComments(resource(SSGI));
        String template = "\\b(?:int|uint|float|double|bool|vec[234]|ivec[234]|uvec[234]"
                + "|bvec[234]|mat[234]|sampler\\w*)\\s+%s\\b";
        for (String keyword : new String[]{
                "sample", "step", "distance", "mix", "clamp", "dot", "cross", "normalize",
                "texture", "length", "reflect", "refract", "smoothstep", "input", "output",
                "filter", "layout", "uniform", "buffer", "shared", "coherent", "volatile",
                "restrict", "readonly", "writeonly", "patch", "subroutine", "centroid",
                "flat", "smooth", "noperspective", "const", "attribute", "varying",
                "atomic_uint"}) {
            Pattern declaration = Pattern.compile(String.format(template,
                    Pattern.quote(keyword)));
            assertFalse(declaration.matcher(source).find(),
                    "ssgi.frag declares a variable named '" + keyword
                            + "', which is a reserved GLSL keyword/qualifier/function in 4.60");
        }
    }

    @Test
    void skyBackgroundAndRejectedSamplesContributeNothing() throws IOException {
        String source = resource(SSGI);
        assertTrue(source.contains("if (sceneDepth >= 1.0) break;"),
                "in-raster sky must not be added: the forward IBL ambient already covers it");
        assertTrue(source.contains("if (separation <= bias) continue;"));
        assertTrue(source.contains("if (separation > thickness) break;"),
                "a ray passing behind thick geometry must not sample it");
        assertTrue(source.contains("if (facing <= SSGI_BACKFACE_EPSILON) break;"));
        assertTrue(source.contains("float roughWeight = sampleRoughnessWeight("),
                "hit roughness must gate specular-looking samples");
        assertTrue(source.contains("float edge = borderFade(sampleUv);"));
        assertTrue(source.contains("if (samplePixel == centerPixel) continue;"),
                "the receiver's own pixel must never be sampled as a neighbour");
        assertTrue(source.contains("if (marchDistance < SSGI_MIN_HIT_DISTANCE_METRES) continue;"),
                "a too-close self-hit must be skipped, not accepted");
    }

    @Test
    void offscreenFallbackIsClampedAndTheCompositeAddsOneBounce() throws IOException {
        String source = resource(SSGI);
        assertTrue(source.contains("if (offscreen) offscreenSamples++;"));
        assertTrue(source.contains("float offscreenFraction = float(offscreenSamples) "
                + "/ float(sampleCount);"));
        assertTrue(source.contains("float offscreenBudget = min(offscreenFraction, "
                + "max(uMaximumOffscreenFraction, 0.0));"));
        assertTrue(source.contains("gathered += ambientRadiance * offscreenBudget * SSGI_PI;"));
        assertEquals(2, occurrences(source, "ambientRadiance"),
                "the ambient approximation may only be the offscreen fallback, never a second "
                        + "additive ambient term");
        assertTrue(source.contains("vec3 added = albedo * max(gathered, vec3(0.0)) "
                + "* clamp(uStrength, 0.0, 1.0) / SSGI_PI;"));
        assertTrue(source.contains("vec3 result = base + added;"));
        assertFalse(source.contains("base -"),
                "the pass must never subtract the existing direct, IBL or AO terms");
        assertFalse(source.contains("texture(uIrradiance, rotateEnvironment(normal)).rgb\n"
                        + "                +"),
                "the environment must not be added directly on top of the ambient baseline");
        assertTrue(source.contains("vec3 ambientIrradiance;"),
                "the ambient baseline is still derived for the fallback comparison");
        assertTrue(source.contains("FragColor = vec4(base, 1.0);"),
                "background/albedo-zero/NaN pixels must fall back to the exact base colour");
    }

    @Test
    void sharedSurfaceProducesAndResolvesTheAlbedoChannel() throws IOException {
        String surface = resource(SURFACE);
        assertTrue(surface.contains(
                "layout(location = SCENE_SLOT_ALBEDO) out vec4 outAlbedo;"));
        assertTrue(surface.contains("uniform vec4 uAlbedoFactor;"));
        assertTrue(surface.contains(
                "outAlbedo = vec4(clamp(uAlbedoFactor.rgb, 0.0, 1.0), 1.0);"),
                "the shared surface must write linear albedo for the GI receiver weight");

        String resolve = resource(RESOLVE);
        assertTrue(resolve.contains(
                "layout(location = SCENE_SLOT_ALBEDO) out vec4 outAlbedo;"));
        assertTrue(resolve.contains("uniform sampler2DMS uAlbedo;"));
        assertTrue(resolve.contains("outAlbedo = texelFetch(uAlbedo, pixel, best);"),
                "MSAA resolve must carry the nearest sample's albedo");
        assertTrue(resolve.contains("outAlbedo = vec4(0.0);"));
    }

    @Test
    void disabledRequestIsRejectedBeforeAnyGlObjectIsCreated() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new SsgiPass(SsgiSettings.disabled()));
        assertTrue(failure.getMessage().contains("active SSGI"));
    }

    @Test
    void passUniformNamesMatchTheShaderText() throws IOException {
        String shader = resource(SSGI);
        Matcher upload = Pattern.compile("setUniform(?:Int|Float|Vec2|Vec3|Mat4)\\(program, "
                + "\"(u[A-Za-z0-9_]*)\"").matcher(javaSource());
        Set<String> uploaded = new LinkedHashSet<>();
        while (upload.find()) {
            assertTrue(uploaded.add(upload.group(1)),
                    "duplicate SSGI upload " + upload.group(1));
        }
        for (String name : uploaded) {
            assertTrue(countIdentifier(shader, name) >= 2,
                    name + " is uploaded by SsgiPass but is not declared and used by ssgi.frag");
        }
        assertTrue(uploaded.containsAll(Set.of("uColor", "uDepth", "uNormal", "uRoughness",
                "uAlbedo", "uIrradiance", "uEnvironmentAvailable", "uSampleCount",
                "uStepCount", "uMaximumOffscreenFraction")),
                "the pass must upload every input the shader consumes: " + uploaded);
    }

    private static String javaSource() throws IOException {
        return java.nio.file.Files.readString(java.nio.file.Path.of("src", "main", "java",
                "com", "kaleblangley", "haikalat", "subsystems", "postprocess", "ssgi",
                "SsgiPass.java"));
    }

    private static int countIdentifier(String source, String name) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(source);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
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

    private static String stripComments(String source) {
        return source.replaceAll("//[^\\n]*", "");
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = SsgiShaderTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("missing shader " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
