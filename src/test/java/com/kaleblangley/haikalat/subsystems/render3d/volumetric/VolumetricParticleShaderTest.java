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
 * JVM packaging and formula contract for the volumetric-particle shader pair.
 *
 * <p>Compiling the program needs a real GL context; this test proves the
 * resources are on the runtime classpath, pin the accepted SHA-256 of both
 * stages (also recorded in {@code docs/licenses/assets.tsv}) and lock the
 * single-medium/energy contract a reviewer can observe without a GPU: the
 * fragment stage samples exactly one depth texture, never samples scene colour
 * or any volumetric-fog volume, declares every uniform the pass uploads and
 * keeps the documented density, step, phase and single-scatter formulas.</p>
 */
class VolumetricParticleShaderTest {
    /** SHA-256 of the accepted {@code particle.vert}, matching assets.tsv. */
    private static final String VERTEX_SHA256 =
            "33f1e751b5b5556feae1644344965c9be832646370573b5f5eadb4ce4a805363";
    /** SHA-256 of the accepted {@code particle.frag}, matching assets.tsv. */
    private static final String FRAGMENT_SHA256 =
            "b345c526f24b03798d11d27001740ff254c5ff0b9c13a1784ae65d111988451c";

    @Test
    void shaderStagesArePackagedAndPinned() {
        String vertex = VolumetricParticlePass.vertexSource();
        String fragment = VolumetricParticlePass.fragmentSource();
        assertTrue(vertex.startsWith("#version 460 core"));
        assertTrue(fragment.startsWith("#version 460 core"));
        assertEquals(VERTEX_SHA256, sha256(vertex));
        assertEquals(FRAGMENT_SHA256, sha256(fragment));
        assertTrue(vertex.contains("layout (location = 0) in vec2 aPosition;"),
                "the vertex stage consumes the screen-quad position only");
        assertFalse(vertex.contains("layout (location = 1)"),
                "the pass does not require the screen-quad UV attribute");
        assertTrue(vertex.contains("gl_Position = vec4(aPosition, 0.0, 1.0);"));
    }

    @Test
    void fragmentStageDeclaresEveryUniformThePassUploads() {
        String source = VolumetricParticlePass.fragmentSource();
        for (String uniform : List.of("uniform sampler2D uSceneDepth", "uniform vec2 uViewportSize",
                "uniform mat4 uInverseProjection", "uniform mat4 uInverseView",
                "uniform vec3 uBoundsCenter", "uniform vec3 uBoundsHalfExtent",
                "uniform vec3 uLightDirection", "uniform vec3 uLightColor",
                "uniform float uDensity", "uniform float uAbsorption",
                "uniform float uScattering", "uniform float uAnisotropy",
                "uniform float uOpticalDepthScale", "uniform float uStepSizeMetres",
                "uniform int uMaximumSteps", "uniform int uOccluding")) {
            assertTrue(source.contains(uniform + ";"), "missing shader uniform " + uniform);
        }
        assertEquals(1, occurrences(source, "texelFetch(uSceneDepth"),
                "exactly one opaque-depth fetch per fragment");
        assertTrue(source.contains("if (!(rawDepth < 1.0))"),
                "a cleared depth must be treated as no occluder");
        assertFalse(source.contains("sampler2DMS"),
                "the compute/fragment path consumes a resolved single-sample depth texture");
    }

    @Test
    void fragmentStageKeepsTheSingleMediumAndNoDoubleCompositionContract() {
        String source = VolumetricParticlePass.fragmentSource();
        assertFalse(source.contains("uSceneColor"),
                "the pass must not sample the colour it blends over");
        assertFalse(source.contains("uVolumePrefix") || source.contains("uVolumeSource"),
                "the pass must not integrate the volumetric-fog medium");
        assertFalse(source.contains("volume-common.glsl") || source.contains("volumetric/volume"),
                "the particle shader must not include the fog implementation");
        assertFalse(source.contains("imageStore"),
                "the pass writes through its framebuffer, never an unrelated image");
        assertTrue(source.contains("int uOccluding;"),
                "the blend policy is an explicit uniform");
        assertTrue(source.contains("uOpticalDepthScale"),
                "fogWeight is applied to the particle optical depth exactly once");
        assertEquals(1, occurrences(source, "opticalDepth *= uOpticalDepthScale;"),
                "the blend weight must be applied exactly once");
    }

    @Test
    void fragmentStageKeepsTheDocumentedDensityStepPhaseAndScatterFormulas() {
        String source = VolumetricParticlePass.fragmentSource();
        assertTrue(source.contains("float particleDensityWeight(vec3 worldPosition)"));
        assertTrue(source.contains("float falloff = 1.0 - radiusSquared;"));
        assertTrue(source.contains("return falloff * falloff;"));
        assertTrue(source.contains("float particlePhase(float anisotropy, float cosine)"));
        assertTrue(source.contains(
                "float denominator = max(0.01, 1.0 + anisotropy * anisotropy - 2.0 * anisotropy * cosine);"),
                "the phase denominator floor must match the volumetric fog");
        assertTrue(source.contains("float stepLength = max(span / float(uMaximumSteps), uStepSizeMetres);"));
        assertTrue(source.contains(
                "int steps = int(clamp(ceil(span / stepLength), 1.0, float(uMaximumSteps)));"),
                "the step budget must clamp the actual march length");
        assertTrue(source.contains("float transmittance = exp(-min(opticalDepth, 88.0));"),
                "transmittance must stay finite and bounded");
        assertTrue(source.contains(
                "float albedo = extinction > 0.0 ? (uDensity * uScattering) / extinction : 0.0;"));
        assertTrue(source.contains(
                "vec3 scattering = uLightColor * (phase * albedo * (1.0 - transmittance));"),
                "the in-scatter must be energy-consistent with the transmittance");
        assertTrue(source.contains("FragColor = vec4(max(scattering, vec3(0.0)), uOccluding != 0 ? coverage : 0.0);"));
        assertTrue(source.contains("float cosine = clamp(dot(uLightDirection, -rayDirection), -1.0, 1.0);"),
                "the phase cosine follows the fog's light-travel/toward-camera convention");
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
