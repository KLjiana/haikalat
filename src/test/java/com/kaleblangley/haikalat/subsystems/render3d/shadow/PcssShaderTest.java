package com.kaleblangley.haikalat.subsystems.render3d.shadow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the PBR PCSS shader contract: default-off dispatch, compile-time tap
 * bounds, normalized depth units and per-tap atlas-tile clamping.
 */
class PcssShaderTest {
    private static final String PBR = "/shaders/render3d/pbr/pbr-forward.frag";
    private static final String REFERENCE = "/shaders/clustered/full-scan-reference.frag";

    @Test
    void pcssUniformsAndCompileTimeBudgetsMatchTheSettingsBounds() throws IOException {
        String source = resource(PBR);
        for (String uniform : new String[]{"uPcssEnabled", "uPcssLightSize",
                "uPcssBlockerRadiusTexels", "uPcssBlockerTaps", "uPcssFilterRadiusTexels",
                "uPcssFilterTaps", "uPcssDepthBias"}) {
            assertTrue(source.contains("uniform int " + uniform)
                            || source.contains("uniform float " + uniform),
                    "missing PCSS uniform " + uniform);
        }
        assertTrue(source.contains("uniform float uDirectionalCascadeTexelWorld[4];"));
        assertEquals("#define PCSS_MAX_BLOCKER_TAPS " + PcssSettings.MAX_BLOCKER_TAPS,
                define(source, "PCSS_MAX_BLOCKER_TAPS"));
        assertEquals("#define PCSS_MAX_FILTER_TAPS " + PcssSettings.MAX_FILTER_TAPS,
                define(source, "PCSS_MAX_FILTER_TAPS"));
        assertEquals("#define PCSS_MAX_BLOCKER_RADIUS_TEXELS "
                        + PcssSettings.MAX_BLOCKER_RADIUS_TEXELS,
                define(source, "PCSS_MAX_BLOCKER_RADIUS_TEXELS"));
        assertEquals("#define PCSS_MAX_FILTER_RADIUS_TEXELS "
                        + PcssSettings.MAX_FILTER_RADIUS_TEXELS,
                define(source, "PCSS_MAX_FILTER_RADIUS_TEXELS"));
    }

    @Test
    void disabledPathKeepsTheOriginalHardPcfSampler() throws IOException {
        String source = resource(PBR);
        assertTrue(source.contains(
                "if (uPcssEnabled == 0) return sampleDirectionalCascade(cascade, n, l);"),
                "PCSS must dispatch to the untouched hard/PCF path when disabled");
        assertTrue(source.contains("float current = sampleDirectionalShadow(cascade, n, l);"));
        assertTrue(source.contains(
                "float next = sampleDirectionalShadow(cascade + 1, n, l);"));
        assertTrue(source.contains("for (int x = -2; x <= 2; ++x) for (int y = -2; y <= 2; ++y)"),
                "the legacy 5x5 PCF loop must remain available");
        assertTrue(source.contains("if (abs(x) > radius || abs(y) > radius) continue;"));
    }

    @Test
    void everyPcssTapIsBoundedAndClampedToTheSelectedCascadeTile() throws IOException {
        String source = resource(PBR);
        String pcss = section(source, "float sampleDirectionalCascadePcss(int cascade",
                "float sampleDirectionalShadow(int cascade");
        assertEquals(2, occurrences(pcss, "texture(uShadowMap"),
                "PCSS must use exactly one blocker-search and one filter sample per tap");
        assertEquals(2, occurrences(pcss, "clamp(projected.xy"),
                "both PCSS tap types must clamp to the cascade tile");
        assertTrue(pcss.contains("tileMinimum, tileMaximum);"));
        assertTrue(pcss.contains("float searchRadius = float(max(blockerRadius, filterRadius));"));
        assertTrue(pcss.contains(
                "directionalCascadeTile(cascade, searchRadius, scale, offset, tileMinimum, tileMaximum);"),
                "the tile guard must cover the largest PCSS tap radius");
        assertTrue(pcss.contains("for (int tap = 0; tap < PCSS_MAX_BLOCKER_TAPS; ++tap)"));
        assertTrue(pcss.contains("for (int tap = 0; tap < PCSS_MAX_FILTER_TAPS; ++tap)"));
        assertTrue(pcss.contains("if (tap >= blockerTaps) break;"));
        assertTrue(pcss.contains("if (tap >= filterTaps) break;"));
        assertTrue(pcss.contains("clamp(uPcssBlockerRadiusTexels, 1, PCSS_MAX_BLOCKER_RADIUS_TEXELS)"));
        assertTrue(pcss.contains("clamp(uPcssFilterRadiusTexels, 1, PCSS_MAX_FILTER_RADIUS_TEXELS)"));
    }

    @Test
    void penumbraUsesTheLinearlyConvertedDepthRangeAndTheFilterRadiusCap() throws IOException {
        String source = resource(PBR);
        String pcss = section(source, "float sampleDirectionalCascadePcss(int cascade",
                "float sampleDirectionalShadow(int cascade");
        String penumbra = section(source, "float pcssPenumbraTexels(int cascade",
                "float sampleDirectionalCascadePcss(int cascade");
        assertTrue(penumbra.contains("float depthRange = -2.0 / lightSpace[2][2];"),
                "orthographic window depth must be converted with the cascade depth range");
        assertTrue(penumbra.contains(
                "float nearDistance = (lightSpace[3][2] + 1.0) / lightSpace[2][2];"));
        assertTrue(penumbra.contains("uDirectionalCascadeTexelWorld[cascade]"));
        assertTrue(penumbra.contains("* uPcssLightSize / occluderDistance"));
        assertTrue(pcss.contains(
                "min(pcssPenumbraTexels(cascade, projected.z, blockerDepth),"));
        assertTrue(pcss.contains("float(filterRadius));"));
    }

    @Test
    void fullScanReferenceCarriesTheSamePcssBlock() throws IOException {
        String production = resource(PBR);
        String reference = resource(REFERENCE);
        String productionBlock = section(production, "vec2 pcssDiskOffset(int index",
                "float directionalShadowFactor(vec3 n, vec3 l)");
        String referenceBlock = section(reference, "vec2 pcssDiskOffset(int index",
                "float directionalShadowFactor(vec3 n, vec3 l)");
        assertEquals(productionBlock, referenceBlock,
                "the benchmark-only full-scan reference must not drift from the PCSS block");
    }

    private static String define(String source, String name) {
        for (String line : source.split("\n")) {
            if (line.startsWith("#define " + name + " ")) return line.strip();
        }
        throw new AssertionError("missing define " + name);
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
        try (InputStream input = PcssShaderTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("missing shader " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
