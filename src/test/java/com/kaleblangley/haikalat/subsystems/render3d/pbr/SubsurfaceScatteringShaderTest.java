package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.sss.SubsurfaceScatteringPass;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                "uniform int uSubsurfaceScreenSpace;",
                "uniform int uSubsurfaceDiffuseSignal;"}) {
            assertTrue(source.contains(uniform), "missing forward uniform " + uniform);
        }
        assertEverySubsurfaceUniformReferenced(source, "pbr-forward.frag");
        assertFalse(source.contains("uSubsurfaceDiffusionRadius"),
                "the radius is consumed by the mask/pass, not the forward shader");
        assertTrue(source.contains("uSubsurfaceScreenSpace"),
                "the forward shader needs the material screen-space toggle to decide "
                        + "whether it may write the diffuse-share signal");
        assertFalse(source.contains("uSubsurfaceMaxDistance"),
                "the gather distance is consumed by the mask/pass");
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
    void forwardWritesTheBoundedDiffuseShareIntoAlphaOnlyWhenAllocated()
            throws IOException {
        String source = resource(PBR);
        String main = section(source, "void main() {", "#ifdef HAIKALAT_VOLUME_FOG");
        assertTrue(main.contains(
                        "float outputAlpha = uAlphaMode == 2 ? baseSample.a : 1.0;"),
                "the legacy opaque alpha must be the default before the signal gate");
        assertTrue(main.contains(
                        "if (uSubsurfaceEnabled != 0 && uSubsurfaceScreenSpace != 0\n"
                                + "            && uSubsurfaceDiffuseSignal != 0"
                                + " && uAlphaMode != 2) {"),
                "the signal needs the material, screen-space and frame gates together");
        assertTrue(main.contains("vec3 nonDiffuse = max(gSubsurfaceNonDiffuseRadiance"
                        + " + specularIndirect"),
                "the diffuse share must subtract the actual specular + emissive terms");
        assertTrue(main.contains(
                        "float diffuseLuminance = max(totalLuminance - luminance3(nonDiffuse), 0.0);"));
        assertTrue(main.contains(
                        "clamp(diffuseLuminance / totalLuminance, 0.0, 1.0) : 1.0"));
        assertTrue(main.contains("FragColor = vec4(surfaceRadiance, outputAlpha);"));
        assertEquals(2, occurrences(source,
                        "gSubsurfaceNonDiffuseRadiance += visibility * directSpecular;"),
                "both the directional and local light evaluators must accumulate the "
                        + "actual specular contribution exactly once");
        assertEquals(2, occurrences(source,
                        "gDirectSpecularRadiance = specular * radiance * nDotL;"),
                "the isotropic and anisotropic BRDFs must both publish the specular lobe");
        assertTrue(source.contains(
                        "specularIndirect = uEnableSpecularIbl != 0 ? specularIbl : vec3(0.0);"),
                "the specular IBL copy must be gated by the same enable switch");
        assertTrue(source.contains("specularIndirect *= ao * gtao * uEnvironmentIntensity;"),
                "the specular copy must receive the same occlusion scaling as indirect");
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
        assertEverySubsurfaceUniformReferenced(source, "scene-surface.frag");
        assertTrue(source.contains("const vec3 SUBSURFACE_SKIN_PENETRATION = vec3(1.0, 0.55, 0.30);"));
        assertTrue(source.contains("texture(uOcclusionMap, vTexCoord).r"));
        assertTrue(source.contains("(1.0 - clamp(base.a, 0.0, 1.0)) * thickness"));
        assertTrue(source.contains("exp(-thickness / max(profileScale * penetration,"));
        assertTrue(source.contains("smoothstep(bound * 0.5, bound,"),
                "the material mask must fade with camera distance");
        assertTrue(source.contains("uSubsurfaceIntensity <= "
                        + SubsurfaceScatteringSettings.MAXIMUM_INTENSITY),
                "the mask must accept every valid material intensity");
        assertTrue(source.contains("clamp(uSubsurfaceIntensity, 0.0, 1.0)"),
                "the mask coverage must saturate at 1 instead of rejecting intensity > 1");
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
        assertTrue(source.contains("float diffuseShare(vec4 color)"),
                "the shader must consume the alpha diffuse-share contract");
        assertTrue(source.contains("centerColor.rgb * centerShare"),
                "the centre diffuse share must be a separate value");
        assertTrue(source.contains("sampleColor.rgb * diffuseShare(sampleColor)"),
                "only the tap's diffuse share may enter the gather");
        assertTrue(source.contains("* clamp(centerShare, 0.0, 1.0);"),
                "a fully specular centre must receive no scattering");
        assertTrue(source.contains(
                        "FragColor = vec4(centerColor.rgb - centerDiffuse + scattered, centerColor.a);"),
                "the centre specular part must be re-added untouched");
        assertTrue(source.contains("FragColor = vec4(centerColor.rgb, centerColor.a);"),
                "non-scattering pixels must be copied exactly");
        assertFalse(source.contains("SUBSURFACE_HIGHLIGHT_PROTECTION"),
                "the legacy luminance band must be gone; alpha is the material signal");
        assertFalse(source.contains("highlightProtection("),
                "diffusion must be gated by the diffuse share, not a luminance window");

        for (double[] tap : SubsurfaceScatteringReference.POISSON_TAPS) {
            String literal = String.format(Locale.ROOT, "vec2(%.8f, %.8f)",
                    tap[0], tap[1]);
            assertTrue(source.contains(literal),
                    "shader tap table drifted from the reference: " + literal);
        }
    }

    @Test
    void pbrMaterialsUploadsOnlySubsurfaceUniformsReferencedByTheForwardShader()
            throws IOException {
        String javaSource = Files.readString(Path.of("src", "main", "java", "com",
                "kaleblangley", "haikalat", "subsystems", "render3d", "pbr",
                "PbrMaterials.java"));
        Matcher upload = Pattern.compile(
                "set(?:Float|Int|Vec3)\\(\"(uSubsurface[A-Za-z0-9_]*)\"").matcher(javaSource);
        Set<String> uploaded = new LinkedHashSet<>();
        while (upload.find()) {
            assertTrue(uploaded.add(upload.group(1)),
                    "duplicate subsurface upload " + upload.group(1));
        }
        assertEquals(Set.of("uSubsurfaceEnabled", "uSubsurfaceScatterColor",
                        "uSubsurfaceIntensity", "uSubsurfaceProfile",
                        "uSubsurfaceProfileScale", "uSubsurfaceThicknessSource",
                        "uSubsurfaceThickness", "uSubsurfaceScreenSpace"),
                uploaded,
                "the forward material upload set must match the uniforms the forward shader "
                        + "actually consumes");
        String forward = resource(PBR);
        for (String name : uploaded) {
            assertTrue(countIdentifier(forward, name) >= 2,
                    name + " is uploaded by PbrMaterials but is not declared and used by "
                            + "pbr-forward.frag; GLSL may optimize it out");
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

    /**
     * Every declared subsurface uniform must be referenced outside its own
     * declaration. GLSL drops unused uniforms from the linked program, and a
     * later {@code uniformLocation} upload then fails at draw time; this guard
     * catches that class of contract drift before the GPU queue.
     */
    private static void assertEverySubsurfaceUniformReferenced(String source, String label) {
        Matcher declaration = Pattern.compile(
                "uniform\\s+\\w+\\s+(uSubsurface\\w+)").matcher(source);
        int declared = 0;
        while (declaration.find()) {
            declared++;
            String name = declaration.group(1);
            assertTrue(countIdentifier(source, name) >= 2,
                    label + ": uniform " + name
                            + " is declared but never referenced and may be optimized out");
        }
        assertTrue(declared > 0, label + " must declare at least one subsurface uniform");
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

    private static String resource(String path) throws IOException {
        try (InputStream input = SubsurfaceScatteringShaderTest.class
                .getResourceAsStream(path)) {
            if (input == null) throw new IOException("missing shader " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
