package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.sss.SubsurfaceScatteringPass;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the three shader contracts that carry #32/#33: the PBR forward
 * transmission term, the shared surface-mask producer and the bounded
 * screen-space Poisson gather. The test also compares the fixed tap table with
 * the independent double reference and checks that a disabled pass object is
 * rejected before any GL resource is created.
 */
class SubsurfaceScatteringShaderTest {
    private static final String PBR = "/shaders/render3d/pbr/pbr-forward.frag";
    private static final String SURFACE = "/shaders/render3d/surface/scene-surface.frag";
    private static final String DIFFUSION = "/shaders/postprocess/subsurface-scattering.frag";

    @Test
    void forwardUniformsAndConstantsMatchTheSettingsContract() throws IOException {
        String source = resource(PBR);
        for (String uniform : new String[]{
                "uniform int uSubsurfaceEnabled;",
                "uniform vec3 uSubsurfaceScatterColor;",
                "uniform float uSubsurfaceIntensity;",
                "uniform int uSubsurfaceProfile;",
                "uniform float uSubsurfaceProfileScale;",
                "uniform int uSubsurfaceThicknessSource;",
                "uniform float uSubsurfaceThickness;",
                "uniform float uSubsurfaceDiffusionRadius;",
                "uniform int uSubsurfaceScreenSpace;",
                "uniform float uSubsurfaceMaxDistance;"}) {
            assertTrue(source.contains(uniform), "missing forward uniform " + uniform);
        }
        assertTrue(source.contains("const float SUBSURFACE_MIN_PROFILE_SCALE = "
                + SubsurfaceScatteringSettings.MINIMUM_PROFILE_SCALE + ";"));
        assertTrue(source.contains("const float SUBSURFACE_MAX_PROFILE_SCALE = "
                + SubsurfaceScatteringSettings.MAXIMUM_PROFILE_SCALE + ";"));
        assertTrue(source.contains("const float SUBSURFACE_MAX_THICKNESS = "
                + SubsurfaceScatteringSettings.MAXIMUM_THICKNESS + ";"));
        assertTrue(source.contains("const float SUBSURFACE_MAX_INTENSITY = "
                + SubsurfaceScatteringSettings.MAXIMUM_INTENSITY + ";"));
        assertTrue(source.contains("const vec3 SUBSURFACE_SKIN_PENETRATION = vec3(1.0, 0.55, 0.30);"));
        assertFalse(source.contains("sampler2D uSubsurfaceProfileMap"),
                "the first version has an analytic profile, not a per-pixel profile texture");
    }

    @Test
    void transmissionIsAddedWithoutScalingTheExistingDirectTerms() throws IOException {
        String source = resource(PBR);
        String helpers = section(source, "vec3 subsurfacePenetration() {",
                "int directionalCascadeIndex() {");
        assertTrue(helpers.contains("float subsurfaceThickness(vec4 baseSample, vec2 materialUv)"));
        assertTrue(helpers.contains("texture(uOcclusionMap, materialUv).r"),
                "the occlusion proxy must sample the same material UV");
        assertTrue(helpers.contains("(1.0 - alpha) * thickness"),
                "the base-alpha proxy must invert the alpha");
        assertTrue(helpers.contains("vec3 subsurfaceTransmission(vec3 n, vec3 l, vec3 radiance)"));
        assertTrue(helpers.contains("clamp(dot(-n, l), 0.0, 1.0)"),
                "the transmission term must use the back-facing cosine");
        assertTrue(helpers.contains("subsurfaceAttenuation(gSubsurfaceThickness)"),
                "the per-light term must use the thickness resolved once per fragment");
        assertEquals(2, occurrences(source, "base += subsurfaceTransmission(n, l, radiance);"),
                "the directional and local light evaluators must add the same term once each");
        String directional = section(source, "vec3 directionalRadiance(int index",
                "vec3 localRadiance(int index");
        String local = section(source, "vec3 localRadiance(int index", "int resolveCluster()");
        assertTrue(directional.indexOf("base += subsurfaceTransmission")
                        > directional.indexOf("base = base * transmitted"),
                "the scatter term is additive, not a replacement of the direct result");
        assertTrue(local.indexOf("base += subsurfaceTransmission")
                        > local.indexOf("base = base * transmitted"));
        assertTrue(directional.contains("return visibility * base;"));
        assertTrue(local.contains("return visibility * base;"));
        assertFalse(directional.contains("directBrdf(...) * subsurface"),
                "the existing specular must never be multiplied by the scatter weight");
        assertTrue(source.contains(
                "gSubsurfaceThickness = 0.0;\n    if (uSubsurfaceEnabled != 0) {\n"
                        + "        gSubsurfaceThickness = subsurfaceThickness(baseSample, materialUv);"),
                "the disabled path must leave the thickness zero and sample no thickness map");
    }

    @Test
    void surfaceShaderWritesTheBoundedRgbaMaskConsumedByThePass() throws IOException {
        String source = resource(SURFACE);
        assertTrue(source.contains(
                "layout(location = SCENE_SLOT_SUBSURFACE) out vec4 outSubsurface;"));
        for (String uniform : new String[]{
                "uniform int uSubsurfaceEnabled;",
                "uniform float uSubsurfaceIntensity;",
                "uniform int uSubsurfaceProfile;",
                "uniform float uSubsurfaceProfileScale;",
                "uniform int uSubsurfaceThicknessSource;",
                "uniform float uSubsurfaceThickness;",
                "uniform float uSubsurfaceDiffusionRadius;",
                "uniform int uSubsurfaceScreenSpace;",
                "uniform float uSubsurfaceMaxDistance;",
                "uniform sampler2D uOcclusionMap;"}) {
            assertTrue(source.contains(uniform), "missing mask uniform " + uniform);
        }
        assertTrue(source.contains("const vec3 SUBSURFACE_SKIN_PENETRATION = vec3(1.0, 0.55, 0.30);"));
        assertTrue(source.contains("texture(uOcclusionMap, vTexCoord).r"));
        assertTrue(source.contains("(1.0 - clamp(base.a, 0.0, 1.0)) * thickness"));
        assertTrue(source.contains("exp(-thickness / max(profileScale * penetration,"));
        assertTrue(source.contains("smoothstep(bound * 0.5, bound,"),
                "the material mask must fade with camera distance");
        assertTrue(source.contains(
                "outSubsurface = vec4(coverage, effectiveRadius, thickness,"),
                "the mask layout is coverage/radius/thickness/participation");
    }

    @Test
    void diffusionShaderUsesTheFixedBoundedKernel() throws IOException {
        String source = resource(DIFFUSION);
        assertTrue(source.contains("const int SUBSURFACE_TAP_COUNT = 16;"));
        assertTrue(source.contains("const float SUBSURFACE_MAX_RADIUS_PIXELS = 64.0;"));
        assertTrue(source.contains("const float SUBSURFACE_DEPTH_TOLERANCE = 0.03;"));
        assertTrue(source.contains("const float SUBSURFACE_NORMAL_TOLERANCE = 0.85;"));
        assertTrue(source.contains("const float SUBSURFACE_HIGHLIGHT_PROTECTION = 0.85;"));
        assertTrue(source.contains("const float SUBSURFACE_EDGE_FADE_PIXELS = 8.0;"));
        assertTrue(source.contains("const vec2 SUBSURFACE_TAPS[SUBSURFACE_TAP_COUNT]"));
        assertTrue(source.contains("if (sampleUv.x <= 0.0 || sampleUv.x >= 1.0"),
                "offscreen taps must be rejected instead of wrapped");
        assertTrue(source.contains("if (sampleMask.r <= 0.0 || sampleMask.a <= 0.5) continue;"),
                "only participating subsurface pixels may contribute");
        assertTrue(source.contains("dot(sampleNormal, centerNormal) < SUBSURFACE_NORMAL_TOLERANCE"),
                "a normal boundary must reject the tap");
        assertTrue(source.contains("abs(dot(delta, centerNormal)) > tolerance"),
                "a depth layer boundary must reject the tap");
        assertTrue(source.contains("highlightProtection(sampleColor)"),
                "bright specular neighbours must contribute less");
        assertTrue(source.contains("(1.0 - centerHighlight)"),
                "a centre highlight must not be replaced by its blurred copy");
        assertTrue(source.contains("FragColor = vec4(centerColor.rgb, centerColor.a);"),
                "non-scattering pixels must be copied exactly");

        for (double[] tap : SubsurfaceScatteringReference.POISSON_TAPS) {
            String literal = String.format(Locale.ROOT, "vec2(%.8f, %.8f)",
                    tap[0], tap[1]);
            assertTrue(source.contains(literal),
                    "shader tap table drifted from the reference: " + literal);
        }
    }

    @Test
    void disabledPassIsRejectedBeforeAnyGlObjectIsCreated() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new SubsurfaceScatteringPass(SubsurfaceScatteringSettings.disabled()));
        assertTrue(failure.getMessage().contains("active screen-space subsurface"));
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
        try (InputStream input = SubsurfaceScatteringShaderTest.class
                .getResourceAsStream(path)) {
            if (input == null) throw new IOException("missing shader " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
