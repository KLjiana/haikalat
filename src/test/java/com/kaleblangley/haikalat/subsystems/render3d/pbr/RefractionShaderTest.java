package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.core.material.RefractionSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the production refraction shader contract: one sampled background
 * snapshot, bounded offset/taps, no history or feedback target, and an explicit
 * pass-through fallback for every rejected lookup.
 */
class RefractionShaderTest {
    private static final String REFRACTION = "/shaders/render3d/pbr/refraction-forward.frag";
    private static final String VERTEX = "/shaders/render3d/pbr/pbr-forward.vert";

    @Test
    void refractionShaderIsBoundedAndSamplesOnlyTheSnapshot() throws IOException {
        String source = resource(REFRACTION).replace("\r\n", "\n");
        for (String uniform : new String[]{"uRefractionSource", "uRefractionExtent",
                "uRefractionIor", "uRefractionThickness", "uRefractionRoughness",
                "uRefractionTransmission", "uRefractionReflection"}) {
            assertTrue(source.contains("uniform ") && source.contains(uniform),
                    "missing refraction uniform " + uniform);
        }
        assertTrue(source.contains("#define REFRACTION_MAX_ROUGH_TAPS 4"));
        assertTrue(source.contains("#define REFRACTION_MAX_SCREEN_OFFSET_UV 0.25"));
        assertTrue(source.contains("#define REFRACTION_MAX_ROUGHNESS_SPREAD_UV 0.02"));
        assertTrue(source.contains("for (int tap = 0; tap < REFRACTION_MAX_ROUGH_TAPS; tap++)"),
                "rough transmission must have a fixed bounded tap loop");
        assertTrue(source.contains("uRefractionRoughness <= 1.0e-4"),
                "zero roughness must take the single-tap path");

        // The only colour input is the pre-pass snapshot. The shader must not
        // declare the scene colour writer, history, depth or any other target.
        assertTrue(source.contains("uniform sampler2D uRefractionSource;"));
        assertFalse(source.contains("uSceneColor"), "shader must not sample the scene colour target");
        assertFalse(source.contains("uHistory"), "no temporal history in the first bounded layer");
        assertFalse(source.contains("uNoise"), "rough transmission is deterministic, not noise-history");
        assertFalse(source.contains("uDepth"), "single-layer refraction does not read scene depth");
    }

    @Test
    void offscreenLookupsFallBackToTheUntouchedPixel() throws IOException {
        String source = resource(REFRACTION).replace("\r\n", "\n");

        assertTrue(source.contains("bool insideUnitSquare(vec2 value)"));
        assertTrue(source.contains(
                "texture(uRefractionSource, insideUnitSquare(uv) ? uv : centreUv).rgb"),
                "out-of-range lookups must copy the untouched pixel rather than clamp the border");
        assertTrue(source.contains("if (insideUnitSquare(refractedUv))"),
                "the projected refraction sample must be explicitly range-tested");
        assertTrue(source.contains("if (length(offset) > REFRACTION_MAX_SCREEN_OFFSET_UV) offset = vec2(0.0);"),
                "the offset must be bounded before it is sampled");

        RefractionSettings disabled = RefractionSettings.disabled();
        assertFalse(disabled.active());
        assertTrue(source.contains("vec3 transmitted = mix(background, refractedColor * tint,"),
                "zero transmission must reproduce the untouched background exactly");
        assertTrue(source.contains("FragColor = vec4(max(transmitted + reflection, vec3(0.0)), 1.0);"),
                "glass replaces the visible layer with complete coverage");
    }

    @Test
    void glassUsesTheStandardPbrVertexContract() throws IOException {
        String vertex = resource(VERTEX).replace("\r\n", "\n");
        for (String output : new String[]{"vTexCoord", "vWorldPosition", "vNormal",
                "vTangent", "vTangentHandedness", "vVertexColor"}) {
            assertTrue(vertex.contains("out ") && vertex.contains(output),
                    "missing vertex contract " + output);
        }
        assertTrue(vertex.contains("gl_Position = uProjection * uView * world;"));
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = RefractionShaderTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("missing shader " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
