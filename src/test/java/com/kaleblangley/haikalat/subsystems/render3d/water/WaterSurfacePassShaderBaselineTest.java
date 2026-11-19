package com.kaleblangley.haikalat.subsystems.render3d.water;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shader variant assembly; the legacy output is checked by real-GL readback. */
class WaterSurfacePassShaderBaselineTest {
    private static final String FRAGMENT = "/shaders/render3d/water/water-surface.frag";

    @Test
    void refractionAndMultisampleDefinesAreInjectedAfterTheVersionLine() throws IOException {
        String source = read(FRAGMENT);
        String domain = read("/shaders/render3d/water/water-domain.glsl");
        String base = WaterSurfacePass.fragmentSource(false);
        assertEquals(base, WaterSurfacePass.fragmentSource(false, false));
        String multisample = WaterSurfacePass.fragmentSource(true);
        String refraction = WaterSurfacePass.fragmentSource(false, true);
        String both = WaterSurfacePass.fragmentSource(true, true);
        assertTrue(multisample.startsWith("#version 460 core\n#define WATER_MSAA\n"));
        assertTrue(refraction.startsWith("#version 460 core\n#define WATER_REFRACTION\n"));
        assertTrue(both.startsWith("#version 460 core\n#define WATER_MSAA\n#define WATER_REFRACTION\n"));
        for (String variant : List.of(base, multisample, refraction, both)) {
            assertTrue(variant.endsWith(domain + "\n" + source.substring(source.indexOf('\n') + 1)),
                    "the variants must share the finite water domain and fragment body");
        }
    }

    private static String read(String resource) throws IOException {
        try (InputStream input = WaterSurfacePassShaderBaselineTest.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("missing test resource " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
