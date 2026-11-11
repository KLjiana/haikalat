package com.kaleblangley.haikalat.subsystems.render3d.water;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM guard for the disabled water shader contract.
 *
 * <p>The base fragment variant must stay byte-for-byte identical to the
 * pre-refraction {@code water-surface.frag} fixture: removing the
 * {@code WATER_REFRACTION} conditional block from the production source has to
 * reproduce the fixture exactly.  Together with the real-GL baseline case this
 * makes "refraction disabled" and "#14 water" the same program, not merely
 * similar output.</p>
 */
class WaterSurfacePassShaderBaselineTest {
    private static final String BASELINE = "/shaders/waterrefr/water-surface-baseline.frag";
    private static final String FRAGMENT = "/shaders/render3d/water/water-surface.frag";

    @Test
    void baseVariantIsThePreRefractionShader() throws IOException {
        String source = read(FRAGMENT);
        String baseline = read(BASELINE);
        assertEquals(baseline, preprocessWithoutRefraction(source),
                "the WATER_REFRACTION-disabled source must equal the #14 fixture");
        assertEquals(source, WaterSurfacePass.fragmentSource(false));
        assertEquals(source, WaterSurfacePass.fragmentSource(false, false));
    }

    @Test
    void refractionAndMultisampleDefinesAreInjectedAfterTheVersionLine() throws IOException {
        String source = read(FRAGMENT);
        String multisample = WaterSurfacePass.fragmentSource(true);
        assertTrue(multisample.startsWith("#version 460 core\n#define WATER_MSAA\n"));
        String refraction = WaterSurfacePass.fragmentSource(false, true);
        assertTrue(refraction.startsWith("#version 460 core\n#define WATER_REFRACTION\n"));
        String both = WaterSurfacePass.fragmentSource(true, true);
        assertTrue(both.startsWith(
                "#version 460 core\n#define WATER_MSAA\n#define WATER_REFRACTION\n"));
        for (String variant : List.of(multisample, refraction, both)) {
            assertTrue(variant.endsWith(source.substring(source.indexOf('\n') + 1)),
                    "every variant must reuse the production shader body");
        }
    }

    @Test
    void refractionVariantDeclaresTheDocumentedInputsAndFallback() throws IOException {
        String source = read(FRAGMENT);
        assertTrue(source.contains("#ifdef WATER_REFRACTION"));
        assertTrue(source.contains("uniform sampler2D uSceneColor;"));
        assertTrue(source.contains("uniform mat4 uProjection;"));
        assertTrue(source.contains("uniform float uRefractionIor;"));
        assertTrue(source.contains("uniform float uRefractionStrength;"));
        assertTrue(source.contains("uniform float uRefractionMaxOffset;"));
        assertTrue(source.contains("uniform float uRefractionThicknessScale;"));
        assertTrue(source.contains("uniform float uRefractionForegroundBias;"));
        assertTrue(source.contains("uniform vec3 uRefractionFallbackColor;"));
        assertTrue(source.contains("uniform int uRefractionFallbackMode;"));
        assertTrue(source.contains("texture(uSceneColor, shiftedUv)"));
        assertTrue(source.contains("uRefractionFallbackMode == 1"));
        assertTrue(source.contains("fallback = true"));
    }

    /** Minimal GLSL preprocessor for the single WATER_REFRACTION conditional. */
    static String preprocessWithoutRefraction(String source) {
        List<String> kept = new ArrayList<>();
        boolean inside = false;
        boolean keepElse = true;
        for (String line : source.split("\n", -1)) {
            String trimmed = line.trim();
            if (!inside) {
                if (trimmed.equals("#ifdef WATER_REFRACTION")) {
                    inside = true;
                    keepElse = false;
                    continue;
                }
                kept.add(line);
            } else if (trimmed.equals("#else")) {
                keepElse = true;
            } else if (trimmed.equals("#endif")) {
                inside = false;
            } else if (keepElse) {
                kept.add(line);
            }
        }
        if (inside) throw new IllegalStateException("unterminated WATER_REFRACTION conditional");
        return String.join("\n", kept);
    }

    private static String read(String resource) throws IOException {
        try (InputStream input = WaterSurfacePassShaderBaselineTest.class
                .getResourceAsStream(resource)) {
            if (input == null) throw new IOException("missing test resource " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
