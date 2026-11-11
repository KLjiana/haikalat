package com.kaleblangley.haikalat.subsystems.render3d;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the PBR contact-shadow contract: bounded shader cost, a default-off
 * gate and composition that cannot darken an already cascaded surface twice.
 */
class ContactShadowShaderTest {
    private static final String PBR = "/shaders/render3d/pbr/pbr-forward.frag";
    private static final String REFERENCE = "/shaders/clustered/full-scan-reference.frag";

    @Test
    void contactShadowIsExplicitBoundedAndGatedBeforeAnySample() throws IOException {
        String source = resource(PBR).replace("\r\n", "\n");
        for (String uniform : new String[]{"uContactShadowEnabled", "uContactShadowDistance",
                "uContactShadowThickness", "uContactShadowSteps", "uContactShadowStrength"}) {
            assertTrue(source.contains("uniform int " + uniform)
                            || source.contains("uniform float " + uniform),
                    "missing contact-shadow uniform " + uniform);
        }
        assertTrue(source.contains("#define CONTACT_SHADOW_MAX_STEPS 32"));
        assertTrue(source.contains("uContactShadowEnabled == 0 || uHasDirectionalShadow == 0"));
        assertTrue(source.contains("for (int step = 1; step <= CONTACT_SHADOW_MAX_STEPS; step++)"));
        assertTrue(source.contains("if (step > steps) break;"));
        assertTrue(source.contains("float contact = directionalContactShadow(n, l) * visibility;"));
        assertTrue(source.contains("visibility = max(visibility - contact, 0.0);"));
        assertTrue(source.contains("smoothstep(0.0, 0.05, edge)"));
    }

    @Test
    void fullScanReferenceMirrorsTheProductionContactShadowContract() throws IOException {
        String production = resource(PBR).replace("\r\n", "\n");
        String reference = resource(REFERENCE).replace("\r\n", "\n");
        assertTrue(reference.contains("float directionalContactShadow(vec3 n, vec3 l)"));
        assertTrue(reference.contains("uniform int uContactShadowEnabled"));
        assertTrue(reference.contains("smoothstep(0.0, 0.05, edge)"));
        assertTrue(production.contains("float directionalContactShadow(vec3 n, vec3 l)"));
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = ContactShadowShaderTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("missing shader " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
