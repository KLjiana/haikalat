package com.kaleblangley.haikalat.subsystems.render3d;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents the benchmark-only full-scan oracle from silently drifting from PBR. */
class ClusterShaderSynchronizationTest {
    private static final String PRODUCTION = "/shaders/render3d/pbr/pbr-forward.frag";
    private static final String REFERENCE = "/shaders/clustered/full-scan-reference.frag";
    private static final String TRAVERSAL_START = "        uint localCount = uLightHeader.y;\n";
    private static final String TRAVERSAL_END = "    }\n\n    float nDotV";

    @Test
    void fullScanDiffIsLimitedToTheLocalLightTraversal() throws IOException {
        String production = resource(PRODUCTION).replace("\r\n", "\n");
        String reference = resource(REFERENCE).replace("\r\n", "\n");
        int productionStart = traversalStart(production);
        int referenceStart = traversalStart(reference);
        int productionEnd = production.indexOf(TRAVERSAL_END, productionStart);
        int referenceEnd = reference.indexOf(TRAVERSAL_END, referenceStart);
        assertTrue(productionEnd > productionStart, "production traversal end marker missing");
        assertTrue(referenceEnd > referenceStart, "reference traversal end marker missing");

        assertEquals(production.substring(0, productionStart),
                reference.substring(0, referenceStart),
                "BRDF/material/IBL/shadow code before traversal drifted");
        assertEquals(production.substring(productionEnd), reference.substring(referenceEnd),
                "BRDF/material/IBL/output code after traversal drifted");

        String clusteredTraversal = production.substring(productionStart, productionEnd);
        String fullScanTraversal = reference.substring(referenceStart, referenceEnd);
        assertTrue(clusteredTraversal.contains("uClusterHeaders[cluster]"));
        assertTrue(clusteredTraversal.contains("uClusterIndices[header.x + k]"));
        assertTrue(fullScanTraversal.contains("for (uint i = 0u; i < localCount; i++)"));
        assertTrue(fullScanTraversal.contains("directionalCount + i"));
        assertTrue(!fullScanTraversal.contains("uClusterHeaders[cluster]"));
    }

    private static int traversalStart(String source) {
        int direct = source.indexOf("    vec3 direct = vec3(0.0);");
        assertTrue(direct >= 0, "direct-light block missing");
        int marker = source.indexOf(TRAVERSAL_START, direct);
        assertTrue(marker >= 0, "local-light traversal marker missing");
        return marker + TRAVERSAL_START.length();
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = ClusterShaderSynchronizationTest.class
                .getResourceAsStream(path)) {
            if (input == null) throw new IOException("missing resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
