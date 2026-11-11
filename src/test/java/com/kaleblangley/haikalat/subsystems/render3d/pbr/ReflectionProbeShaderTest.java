package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the local reflection-probe shader contract: a default-off count gate
 * around the exact original global IBL sample, a bounded two-slot
 * local-correction lookup, the CPU-supplied weights and the prefiltered mip
 * mapping. The benchmark-only full-scan oracle must carry the same helpers.
 */
class ReflectionProbeShaderTest {
    private static final String PBR = "/shaders/render3d/pbr/pbr-forward.frag";
    private static final String REFERENCE = "/shaders/clustered/full-scan-reference.frag";

    @Test
    void frozenUniformsCoverTwoBoundedSlots() throws IOException {
        String source = resource(PBR);
        assertTrue(source.contains("uniform int uReflectionProbeCount;"));
        assertTrue(source.contains("uniform float uReflectionProbeGlobalWeight;"));
        for (int slot = 0; slot < 2; slot++) {
            String prefix = "uReflectionProbe" + slot;
            assertTrue(source.contains("uniform samplerCube " + prefix + ";"));
            assertTrue(source.contains("uniform vec3 " + prefix + "Position;"));
            assertTrue(source.contains("uniform vec3 " + prefix + "Extents;"));
            assertTrue(source.contains("uniform int " + prefix + "Shape;"));
            assertTrue(source.contains("uniform float " + prefix + "Weight;"));
            assertTrue(source.contains("uniform float " + prefix + "Intensity;"));
            assertTrue(source.contains("uniform float " + prefix + "Rotation;"));
            assertTrue(source.contains("uniform float " + prefix + "MaxLod;"));
        }
        assertFalse(source.contains("uniform samplerCube uReflectionProbe2"),
                "the shader must stay bounded to two probes");
    }

    @Test
    void countZeroKeepsTheOriginalGlobalIblSample() throws IOException {
        String source = resource(PBR);
        String globalSample = "    vec3 prefiltered = textureLod(uPrefilteredMap, reflection,\n"
                + "            prefilterRoughness * uPrefilterMaxLod).rgb;";
        assertTrue(source.contains(globalSample),
                "the exact original global prefiltered sample must remain");
        assertEquals(1, occurrences(source, "if (uReflectionProbeCount > 0)"),
                "one positive count gate must wrap every local-probe instruction");
        String gate = section(source, "    if (uReflectionProbeCount > 0) {",
                "    vec2 brdf = texture(uBrdfLut");
        assertTrue(source.indexOf(globalSample) < source.indexOf(gate),
                "the global sample must be computed before the probe gate");
        assertTrue(gate.contains("float weight0 = clamp(uReflectionProbe0Weight, 0.0, 1.0);"));
        assertTrue(gate.contains("weight0 * reflectionProbeRadiance("));
        assertTrue(gate.contains("if (uReflectionProbeCount > 1) {"));
        assertTrue(gate.contains("float weight1 = clamp(uReflectionProbe1Weight, 0.0, 1.0);"));
        assertTrue(gate.contains("prefiltered = (prefiltered * globalWeight + probeRadiance) / weightSum;"));
    }

    @Test
    void localCorrectionIsBoundedBoxOrSphereProjectionWithAFallback() throws IOException {
        String source = resource(PBR);
        String direction = section(source, "vec3 reflectionProbeSampleDirection(",
                "/**\n * One probe's prefiltered specular radiance.");
        assertTrue(direction.contains("if (shape == 1) {"), "sphere branch missing");
        assertTrue(direction.contains("float discriminant = b * b - c;"));
        assertTrue(direction.contains("float nearDistance = -b - root;"));
        assertTrue(direction.contains("float farDistance = -b + root;"));
        assertTrue(direction.contains("vec3 inverse = 1.0 / safeDirection;"));
        assertTrue(direction.contains("vec3 nearVector = min(first, second);"));
        assertTrue(direction.contains("vec3 farVector = max(first, second);"));
        assertTrue(direction.contains("if (farDistance >= 0.0 && nearDistance <= farDistance) {"));
        assertTrue(direction.contains("return reflection;"),
                "a miss must fall back to the uncorrected reflection");
        assertFalse(direction.contains("for ("), "local correction must be bounded, not iterative");
        assertFalse(direction.contains("texture("), "local correction is pure geometry");
    }

    @Test
    void probeRadianceUsesCpuWeightsAndTheSharedRoughnessToMipContract() throws IOException {
        String source = resource(PBR);
        String radiance = section(source, "vec3 reflectionProbeRadiance(",
                "float distributionGgx(");
        assertTrue(radiance.contains("rotateEnvironmentBy(direction, rotation)"));
        assertTrue(radiance.contains("float maxLod = max(maximumLod, 0.0);"));
        assertTrue(radiance.contains("float lod = clamp(roughness * maxLod, 0.0, maxLod);"));
        assertTrue(radiance.contains("textureLod(probe, direction, lod).rgb * max(intensity, 0.0)"));
        String gate = section(source, "    if (uReflectionProbeCount > 0) {",
                "    vec2 brdf = texture(uBrdfLut");
        assertTrue(gate.contains("weight0 * reflectionProbeRadiance("));
        assertTrue(gate.contains("if (uReflectionProbeCount > 1) {"));
        assertTrue(gate.contains("weight1 * reflectionProbeRadiance("));
        assertFalse(gate.contains("texture("), "probe sampling must use explicit textureLod");
    }

    @Test
    void probeUnitsDoNotAliasExistingFrameOrVolumeBindings() {
        java.util.Set<Integer> occupied = java.util.Set.of(
                com.kaleblangley.haikalat.subsystems.render3d.lighting.FrameLightBinder
                        .SHADOW_TEXTURE_UNIT,
                com.kaleblangley.haikalat.subsystems.render3d.lighting.FrameLightBinder
                        .POINT_SHADOW_TEXTURE_UNIT,
                com.kaleblangley.haikalat.subsystems.render3d.lighting.FrameLightBinder
                        .SPOT_SHADOW_TEXTURE_UNIT,
                com.kaleblangley.haikalat.subsystems.render3d.lighting.FrameLightBinder
                        .GTAO_TEXTURE_UNIT,
                PbrMaterialBinder.IRRADIANCE_UNIT,
                PbrMaterialBinder.PREFILTERED_SPECULAR_UNIT,
                PbrMaterialBinder.BRDF_LUT_UNIT);
        assertFalse(occupied.contains(PbrMaterials.REFLECTION_PROBE_UNIT_0));
        assertFalse(occupied.contains(PbrMaterials.REFLECTION_PROBE_UNIT_1));
        assertTrue(PbrMaterials.REFLECTION_PROBE_UNIT_0 > 21,
                "volume-fog samplers occupy 14-21; probes must start above them");
        assertEquals(PbrMaterials.REFLECTION_PROBE_UNIT_0 + 1,
                PbrMaterials.REFLECTION_PROBE_UNIT_1);
    }

    @Test
    void fullScanReferenceCarriesTheSameProbeHelpers() throws IOException {
        String production = resource(PBR);
        String reference = resource(REFERENCE);
        String start = "vec3 rotateEnvironmentBy(vec3 direction, float rotation) {";
        String end = "float distributionGgx(";
        assertEquals(section(production, start, end), section(reference, start, end),
                "the benchmark-only full-scan oracle drifted from the probe helpers");
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
        try (InputStream input = ReflectionProbeShaderTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("missing shader " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
