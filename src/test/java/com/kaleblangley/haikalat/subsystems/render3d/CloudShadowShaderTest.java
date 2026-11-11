package com.kaleblangley.haikalat.subsystems.render3d;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the PBR cloud-shadow contract: a default-off world-space mask that
 * cannot read screen/camera state and only multiplies the selected directional
 * light's direct radiance.
 */
class CloudShadowShaderTest {
    private static final String PBR = "/shaders/render3d/pbr/pbr-forward.frag";
    private static final String REFERENCE = "/shaders/clustered/full-scan-reference.frag";

    @Test
    void cloudShadowIsExplicitWorldSpaceAndGatedBeforeAnyNoise() throws IOException {
        String source = resource(PBR).replace("\r\n", "\n");
        for (String uniform : new String[]{"uCloudShadowEnabled", "uCloudShadowCoverage",
                "uCloudShadowSoftness", "uCloudShadowScale", "uCloudShadowSpeed",
                "uCloudShadowStrength", "uCloudShadowTime"}) {
            assertTrue(source.contains("uniform int " + uniform)
                            || source.contains("uniform float " + uniform),
                    "missing cloud-shadow uniform " + uniform);
        }
        assertTrue(source.contains("#define CLOUD_SHADOW_OCTAVES 3"));
        assertTrue(source.contains(
                "if (uCloudShadowEnabled == 0 || uCloudShadowStrength <= 0.0"));
        assertTrue(source.contains("for (int octave = 0; octave < CLOUD_SHADOW_OCTAVES; octave++)"));
        assertTrue(source.contains("float cloudShadowVisibility(vec3 n, vec3 l)"));
        assertTrue(source.contains("visibility *= cloudShadowVisibility(n, l);"));

        String function = cloudSection(source);
        assertTrue(function.contains("dot(vWorldPosition, tangent)"),
                "cloud coordinates must come from the receiver world position");
        assertTrue(function.contains("uCloudShadowTime * max(uCloudShadowSpeed, 0.0)"),
                "cloud layer must scroll by frame time");
        assertFalse(function.contains("gl_FragCoord"),
                "cloud mask must not stick to screen coordinates");
        assertFalse(function.contains("uCameraPosition"),
                "cloud mask must not depend on camera position");
        assertFalse(function.contains("uStableViewProjection"),
                "cloud mask must not use the stable screen projection");
        assertFalse(function.contains("uEmissiveMap")
                        || function.contains("uIrradianceMap")
                        || function.contains("uPrefilteredMap"),
                "cloud mask must not touch emissive or IBL inputs");
    }

    @Test
    void fullScanReferenceMirrorsTheProductionCloudShadowContract() throws IOException {
        String production = resource(PBR).replace("\r\n", "\n");
        String reference = resource(REFERENCE).replace("\r\n", "\n");
        assertEquals(cloudSection(production), cloudSection(reference),
                "production and full-scan reference must share the cloud implementation");
        assertTrue(reference.contains("uniform float uCloudShadowTime"));
        assertTrue(reference.contains("visibility *= cloudShadowVisibility(n, l);"));
    }

    private static String cloudSection(String source) {
        int start = source.indexOf("float cloudShadowHash(");
        int end = source.indexOf("int pointShadowFace(", start);
        assertTrue(start >= 0 && end > start, "missing cloud-shadow function section");
        return source.substring(start, end);
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = CloudShadowShaderTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("missing shader " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
